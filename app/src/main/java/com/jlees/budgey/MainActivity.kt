package com.jlees.budgey

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.runtime.PausableMonotonicFrameClock
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.platform.AndroidUiDispatcher
import androidx.compose.ui.platform.compositionContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jlees.budgey.data.AppSettings
import com.jlees.budgey.ui.components.LocalBrandCatalog
import com.jlees.budgey.ui.components.LocalAnimations
import com.jlees.budgey.ui.navigation.AppNav
import com.jlees.budgey.ui.theme.BudgeyTheme
import com.jlees.budgey.reminders.RenewalReminders

/** App-wide animation speed (see [MainActivity.installMotionControl]). */
private object AppMotion : MotionDurationScale {
    @Volatile var enabled = true
    @Volatile var systemScale = 1f
    override val scaleFactor: Float get() = if (enabled) systemScale else 0f
}

class MainActivity : ComponentActivity() {
    private var sharedImage by mutableStateOf<String?>(null)
    private var openSubscriptionId by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = (application as BudgeyApp).container
        if (savedInstanceState == null) handleIntent(intent)
        val recomposer = installMotionControl()

        // Run the UI on our own Recomposer (passed explicitly, so it's always the one used).
        setContent(parent = recomposer) {
            val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
            SideEffect { AppMotion.enabled = settings.animations }
            CompositionLocalProvider(LocalBrandCatalog provides container.brands, LocalAnimations provides settings.animations) {
                BudgeyTheme(settings) {
                    AppNav(
                        sharedImage = sharedImage,
                        onSharedImageConsumed = { sharedImage = null },
                        openSubscriptionId = openSubscriptionId,
                        onOpenSubscriptionConsumed = { openSubscriptionId = null },
                    )
                }
            }
        }
    }

    /**
     * Settings → Appearance → Animations. Every Compose animation (transitions, springs, ripples,
     * charts…) is timed by the [MotionDurationScale] in its coroutine context, so the whole app
     * is given a Recomposer whose scale we control: 0 = animations jump straight to the end.
     * Otherwise it follows the phone's own "Animation duration scale" setting, as Compose does
     * by default. The frame clock pauses while the app isn't visible, like the default one.
     */
    private fun installMotionControl(): Recomposer {
        AppMotion.systemScale = runCatching {
            android.provider.Settings.Global.getFloat(contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        }.getOrDefault(1f)
        val ui = AndroidUiDispatcher.CurrentThread
        val clock = PausableMonotonicFrameClock(ui[MonotonicFrameClock]!!)
        val context = ui + clock + AppMotion
        val recomposer = Recomposer(context)
        lifecycleScope.launch(context, start = CoroutineStart.UNDISPATCHED) { recomposer.runRecomposeAndApplyChanges() }
        lifecycle.addObserver(LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> clock.resume()
                Lifecycle.Event.ON_STOP -> clock.pause()
                Lifecycle.Event.ON_DESTROY -> recomposer.cancel()
                else -> Unit
            }
        })
        // setContent's ComposeView looks up the view tree for a parent composition context.
        window.decorView.compositionContext = recomposer
        return recomposer
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent?.getStringExtra(RenewalReminders.EXTRA_SUBSCRIPTION_ID)?.let {
            openSubscriptionId = it
            intent?.removeExtra(RenewalReminders.EXTRA_SUBSCRIPTION_ID)
            return
        }
        if (intent?.action != Intent.ACTION_SEND || intent.type?.startsWith("image/") != true) return
        val uri: Uri? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }
        sharedImage = uri?.toString()
    }
}
