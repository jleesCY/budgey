package com.jlees.budgey.reminders

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.jlees.budgey.BudgeyApp
import com.jlees.budgey.MainActivity
import com.jlees.budgey.R
import com.jlees.budgey.domain.Money
import com.jlees.budgey.domain.Reminder
import com.jlees.budgey.data.db.cycle
import com.jlees.budgey.domain.ReminderKind
import com.jlees.budgey.domain.Reminders
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.concurrent.TimeUnit

/**
 * Renewal reminders run fully on-device: a WorkManager job wakes once a day (around 9 AM),
 * auto-logs any subscription payments that came due, and posts notifications for upcoming
 * renewals and free trials that are about to end. No server, no push.
 */
object RenewalReminders {
    const val CHANNEL_ID = "renewals"
    const val EXTRA_SUBSCRIPTION_ID = "open_subscription_id"
    private const val DAILY_WORK = "renewal-reminders-daily"
    private const val NOW_WORK = "renewal-reminders-now"
    private val REMIND_AT: LocalTime = LocalTime.of(9, 0)

    /** Call on every app start; KEEP means an existing schedule is left alone. */
    fun schedule(context: Context) {
        val now = LocalDateTime.now()
        var next = now.toLocalDate().atTime(REMIND_AT)
        if (!next.isAfter(now)) next = next.plusDays(1)
        val delay = Duration.between(now, next).toMinutes()
        val request = PeriodicWorkRequestBuilder<RenewalReminderWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(delay, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(DAILY_WORK, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /** Runs the check immediately (e.g. after editing a subscription or from Settings). */
    fun checkNow(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            NOW_WORK, ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<RenewalReminderWorker>().build(),
        )
    }

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Subscription renewals", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Reminders before subscriptions renew and free trials end"
            }
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    fun canNotify(context: Context): Boolean {
        val granted = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        return granted && NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    fun text(r: Reminder): Pair<String, String> {
        val s = r.subscription
        val price = Money.format(s.amountCents)
        val whenText = when (r.daysUntil) {
            0L -> "today"
            1L -> "tomorrow"
            else -> "in ${r.daysUntil} days"
        }
        return when (r.kind) {
            ReminderKind.RENEWAL -> "${s.name} renews $whenText" to "$price will be charged${if (s.paymentMethod.isNotBlank()) " to ${s.paymentMethod}" else ""}."
            ReminderKind.TRIAL_END -> "${s.name} free trial ends $whenText" to "After that it's $price. Cancel before then if you don't want it."
            ReminderKind.TRIAL_ENDED -> (if (r.daysUntil == 0L) "${s.name} free trial ends today" else "${s.name} free trial has ended") to
                "You're now being charged $price${s.cycle.shortSuffix}. Open Budgey to keep it or cancel it."
        }
    }

    fun post(context: Context, r: Reminder) {
        if (!canNotify(context)) return
        ensureChannel(context)
        val (title, body) = text(r)
        val open = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_SUBSCRIPTION_ID, r.subscription.id)
        }
        val pi = PendingIntent.getActivity(
            context, r.key.hashCode(), open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_renewal)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(r.key.hashCode(), n)
        } catch (_: SecurityException) {
            // Permission revoked between the check and the post — nothing to do.
        }
    }
}

class RenewalReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as BudgeyApp
        val c = app.container
        val settings = c.settings.current()
        val today = LocalDate.now()

        // Reminders first, so "renews today" is seen before auto-log moves the date forward.
        if (settings.renewalReminders && RenewalReminders.canNotify(applicationContext)) {
            val subs = c.repository.allSubscriptions()
            val due = Reminders.due(subs, today, settings.reminderDaysBefore, c.settings.sentReminders())
            due.forEach { RenewalReminders.post(applicationContext, it) }
            c.settings.markRemindersSent(due.map { it.key })
        }
        c.repository.processDueSubscriptions(today)
        return Result.success()
    }
}
