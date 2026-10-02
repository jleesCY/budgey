package com.jlees.budgey.ui.components

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.jlees.budgey.reminders.RenewalReminders

@Stable
class NotificationPermissionState(
    granted: Boolean,
    private val onRequest: () -> Unit,
    private val onOpenSettings: () -> Unit,
) {
    var granted by mutableStateOf(granted)
        internal set
    /** True after the user has said no once — the system dialog may not show again. */
    var denied by mutableStateOf(false)
        internal set

    /** Asks for permission, or opens system settings if Android won't show the dialog anymore. */
    fun request() = if (denied || Build.VERSION.SDK_INT < 33) onOpenSettings() else onRequest()
}

/** Tracks POST_NOTIFICATIONS (Android 13+) and whether notifications are enabled at all. */
@Composable
fun rememberNotificationPermission(onResult: (Boolean) -> Unit = {}): NotificationPermissionState {
    val context = LocalContext.current
    lateinit var state: NotificationPermissionState
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        state.granted = RenewalReminders.canNotify(context)
        if (!ok) state.denied = true
        onResult(ok)
    }
    state = remember {
        NotificationPermissionState(
            granted = RenewalReminders.canNotify(context),
            onRequest = { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) },
            onOpenSettings = {
                context.startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            },
        )
    }
    // Re-check when returning from system settings.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { state.granted = RenewalReminders.canNotify(context) }
    return state
}
