package com.jlees.budgey.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.DayOfWeek
import com.jlees.budgey.scan.ScanEngine

enum class ThemeMode(val label: String) { SYSTEM("System"), LIGHT("Light"), DARK("Dark") }

enum class AppFont(val label: String) { SYSTEM("System"), GOOGLE_SANS_FLEX("Google Sans Flex") }

/** In-app text size, applied on top of the phone's own font-size setting. */
enum class TextSize(val label: String, val scale: Float) {
    SMALL("Small", 0.9f), DEFAULT("Default", 1.0f), LARGE("Large", 1.15f), EXTRA_LARGE("Extra large", 1.3f)
}

enum class ChartType(val label: String) { DONUT("Donut"), PIE("Pie"), BARS("Bars"), TREND("Trend"), RADIAL("Rings") }

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    /** Material You wallpaper colors (Android 12+). Off by default so Budgey's own colors show. */
    val dynamicColor: Boolean = false,
    /** Used when dynamicColor is off. Default: budgie green. */
    val seedColor: Int = 0xFF3F9B3A.toInt(),
    /** Pure-black dark theme for OLED screens. */
    val amoled: Boolean = false,
    /** Last chart style picked on any chart — shared app-wide and remembered. */
    val chartType: ChartType = ChartType.DONUT,
    val firstDayOfWeek: DayOfWeek = DayOfWeek.SUNDAY,
    /** Show the "purchases need a category" nudge. */
    val nudgeUncategorized: Boolean = true,
    /** Notify before subscription renewals / trial ends. */
    val renewalReminders: Boolean = true,
    /** Days before a renewal to notify (0 = on the day). */
    val reminderDaysBefore: Int = 1,
    /** App-wide font. Falls back to the system font if Google Sans Flex isn't bundled. */
    val font: AppFont = AppFont.GOOGLE_SANS_FLEX,
    /** Use Google Sans Flex's rounded variant (ROND axis). Only applies when font = GOOGLE_SANS_FLEX. */
    val roundedFont: Boolean = false,
    val textSize: TextSize = TextSize.DEFAULT,
    /** Motion across the app (transitions, chart sweeps, expanding panels). Off = everything is instant. */
    val animations: Boolean = true,
    /** Which scanner reads pictures. Standard (built in) unless you pick an AI model. */
    val scanEngine: ScanEngine = ScanEngine.STANDARD,
    /** Only download AI models over Wi-Fi. */
    val modelsWifiOnly: Boolean = true,
    /** Set when the AI model crashed during a scan (Budgey kept running); explained in Settings. */
    val smartScanCrashed: Boolean = false,
    val defaultsSeeded: Boolean = false,
)

private val Context.dataStore by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {
    private object Keys {
        val THEME = stringPreferencesKey("theme_mode")
        val DYNAMIC = booleanPreferencesKey("dynamic_color")
        val SEED = intPreferencesKey("seed_color")
        val AMOLED = booleanPreferencesKey("amoled")
        val CHART = stringPreferencesKey("chart_type")
        val FIRST_DOW = stringPreferencesKey("first_day_of_week")
        val AUTO_LOG = booleanPreferencesKey("auto_log_subscriptions")
        val NUDGE = booleanPreferencesKey("nudge_uncategorized")
        val SEEDED = booleanPreferencesKey("defaults_seeded")
        val REMINDERS = booleanPreferencesKey("renewal_reminders")
        val REMINDER_DAYS = intPreferencesKey("reminder_days_before")
        val SENT_REMINDERS = stringSetPreferencesKey("sent_reminders")
        val FONT = stringPreferencesKey("font")
        val ROUNDED_FONT = booleanPreferencesKey("rounded_font")
        val TEXT_SIZE = stringPreferencesKey("text_size")
        val ANIMATIONS = booleanPreferencesKey("animations")
        val SCAN_ENGINE = stringPreferencesKey("scan_engine")
        val MODELS_WIFI_ONLY = booleanPreferencesKey("models_wifi_only")
        val SMART_SCAN_CRASHED = booleanPreferencesKey("smart_scan_crashed")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { it.toSettings() }

    suspend fun current(): AppSettings = settings.first()

    private fun Preferences.toSettings() = AppSettings(
        themeMode = enumOr(this[Keys.THEME], ThemeMode.SYSTEM),
        dynamicColor = this[Keys.DYNAMIC] ?: false,
        seedColor = this[Keys.SEED] ?: 0xFF3F9B3A.toInt(),
        amoled = this[Keys.AMOLED] ?: false,
        chartType = enumOr(this[Keys.CHART], ChartType.DONUT),
        firstDayOfWeek = enumOr(this[Keys.FIRST_DOW], DayOfWeek.SUNDAY),
        nudgeUncategorized = this[Keys.NUDGE] ?: true,
        defaultsSeeded = this[Keys.SEEDED] ?: false,
        renewalReminders = this[Keys.REMINDERS] ?: true,
        reminderDaysBefore = this[Keys.REMINDER_DAYS] ?: 1,
        font = enumOr(this[Keys.FONT], AppFont.GOOGLE_SANS_FLEX),
        roundedFont = this[Keys.ROUNDED_FONT] ?: false,
        textSize = enumOr(this[Keys.TEXT_SIZE], TextSize.DEFAULT),
        animations = this[Keys.ANIMATIONS] ?: true,
        scanEngine = enumOr(this[Keys.SCAN_ENGINE], ScanEngine.STANDARD),
        modelsWifiOnly = this[Keys.MODELS_WIFI_ONLY] ?: true,
        smartScanCrashed = this[Keys.SMART_SCAN_CRASHED] ?: false,
    )

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.dataStore.edit { prefs ->
            val s = transform(prefs.toSettings())
            prefs[Keys.THEME] = s.themeMode.name
            prefs[Keys.DYNAMIC] = s.dynamicColor
            prefs[Keys.SEED] = s.seedColor
            prefs[Keys.AMOLED] = s.amoled
            prefs[Keys.CHART] = s.chartType.name
            prefs[Keys.FIRST_DOW] = s.firstDayOfWeek.name
            prefs[Keys.NUDGE] = s.nudgeUncategorized
            prefs[Keys.SEEDED] = s.defaultsSeeded
            prefs[Keys.REMINDERS] = s.renewalReminders
            prefs[Keys.REMINDER_DAYS] = s.reminderDaysBefore
            prefs[Keys.FONT] = s.font.name
            prefs[Keys.ROUNDED_FONT] = s.roundedFont
            prefs[Keys.TEXT_SIZE] = s.textSize.name
            prefs[Keys.ANIMATIONS] = s.animations
            prefs[Keys.SCAN_ENGINE] = s.scanEngine.name
            prefs[Keys.MODELS_WIFI_ONLY] = s.modelsWifiOnly
            prefs[Keys.SMART_SCAN_CRASHED] = s.smartScanCrashed
        }
    }

    /**
     * Auto-logging used to be a global switch; it's now per subscription. Returns true once if
     * the old global switch was OFF (so the caller can turn it off on each subscription), then
     * forgets the old key.
     */
    suspend fun consumeLegacyAutoLogOff(): Boolean {
        var wasOff = false
        context.dataStore.edit { prefs ->
            if (prefs[Keys.AUTO_LOG] == false) wasOff = true
            prefs.remove(Keys.AUTO_LOG)
        }
        return wasOff
    }

    /** Keys of reminders already shown (so each fires once). */
    suspend fun sentReminders(): Set<String> = context.dataStore.data.first()[Keys.SENT_REMINDERS] ?: emptySet()

    suspend fun markRemindersSent(keys: Collection<String>) {
        if (keys.isEmpty()) return
        context.dataStore.edit { prefs ->
            // Keep the set small: only the most recent 300 keys matter.
            val merged = (prefs[Keys.SENT_REMINDERS].orEmpty() + keys).toList().takeLast(300).toSet()
            prefs[Keys.SENT_REMINDERS] = merged
        }
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        name?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: default
}
