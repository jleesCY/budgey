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
    /** The old 24-hour repeating job (it drifted away from 9 AM); cancelled when the new one is set up. */
    private const val OLD_DAILY_WORK = "renewal-reminders-daily"
    /** A one-off job for the next 9 AM that books the following one when it's done. */
    private const val DAILY_WORK = "renewal-reminders-9am"
    private const val KEY_DAILY = "daily"
    private const val NOW_WORK = "renewal-reminders-now"
    private val REMIND_AT: LocalTime = LocalTime.of(9, 0)

    /**
     * Call on every app start: makes sure the next 9 AM check is booked (an existing booking is kept).
     * Each run books the next one for 9 AM the following day, so the time never drifts — a 24-hour
     * repeating job slowly slid later (Doze, reboots) and never came back.
     */
    fun schedule(context: Context) {
        val wm = WorkManager.getInstance(context)
        wm.cancelUniqueWork(OLD_DAILY_WORK)
        wm.enqueueUniqueWork(DAILY_WORK, ExistingWorkPolicy.KEEP, nextDailyRequest())
    }

    /** Books the check for the next 9 AM, after the current run finishes (called by the daily run). */
    internal fun scheduleNextDaily(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(DAILY_WORK, ExistingWorkPolicy.APPEND_OR_REPLACE, nextDailyRequest())
    }

    private fun nextDailyRequest(): androidx.work.OneTimeWorkRequest {
        val now = LocalDateTime.now()
        var next = now.toLocalDate().atTime(REMIND_AT)
        if (!next.isAfter(now.plusMinutes(5))) next = next.plusDays(1)
        val delay = Duration.between(now, next).toMinutes()
        return OneTimeWorkRequestBuilder<RenewalReminderWorker>()
            .setInitialDelay(delay, TimeUnit.MINUTES)
            .setInputData(androidx.work.workDataOf(KEY_DAILY to true))
            .build()
    }

    /**
     * Sends any due reminders, then auto-logs payments that came due. Reminders go FIRST: auto-logging
     * moves "next payment" forward, and a "renews today" reminder checked after that would be lost.
     * Used by the daily job and at app start.
     */
    suspend fun runCheck(context: Context) {
        val c = (context.applicationContext as BudgeyApp).container
        val settings = c.settings.current()
        val today = LocalDate.now()
        if (settings.renewalReminders && canNotify(context)) {
            val subs = c.repository.allSubscriptions()
            val due = Reminders.due(subs, today, settings.reminderDaysBefore, c.settings.sentReminders())
            due.forEach { post(context, it) }
            c.settings.markRemindersSent(due.map { it.key })
        }
        c.repository.processDueSubscriptions(today)
    }

    /** Runs the check immediately (e.g. after editing a subscription or from Settings). */
    fun checkNow(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            NOW_WORK, ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<RenewalReminderWorker>().build(),
        )
    }

    fun ensureChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID, context.getString(R.string.channel_renewals_name), NotificationManager.IMPORTANCE_DEFAULT,
        ).apply { description = context.getString(R.string.channel_renewals_description) }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    /** POST_NOTIFICATIONS is granted (always true before Android 13). */
    fun hasPermission(context: Context): Boolean = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** Reminders can actually be seen: permission, app notifications on, and the reminders channel not off. */
    fun canNotify(context: Context): Boolean {
        val nm = NotificationManagerCompat.from(context)
        if (!hasPermission(context) || !nm.areNotificationsEnabled()) return false
        val channel = nm.getNotificationChannel(CHANNEL_ID) ?: return true // created on first use
        return channel.importance != NotificationManager.IMPORTANCE_NONE
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
        try {
            // A failure today mustn't stop tomorrow's run from being booked.
            runCatching { RenewalReminders.runCheck(applicationContext) }
        } finally {
            // The daily run books tomorrow's (one-off "check now" runs don't).
            if (inputData.getBoolean("daily", false)) RenewalReminders.scheduleNextDaily(applicationContext)
        }
        return Result.success()
    }
}
