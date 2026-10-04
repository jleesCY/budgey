package com.jlees.budgey.ui.settings

import com.jlees.budgey.ui.components.appear
import com.jlees.budgey.ui.components.disappear
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.ui.Alignment
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.material.icons.outlined.Info as InfoOutlined
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.FilterChip
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.key
import androidx.compose.runtime.saveable.rememberSaveable
import com.jlees.budgey.ui.components.rememberNotificationPermission
import com.jlees.budgey.ui.subscriptions.daysLabel
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jlees.budgey.BuildConfig
import com.jlees.budgey.data.ChartType
import com.jlees.budgey.data.ThemeMode
import com.jlees.budgey.data.AppFont
import com.jlees.budgey.data.TextSize
import com.jlees.budgey.ui.theme.AppFonts
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import com.jlees.budgey.ui.AppViewModels
import com.jlees.budgey.scan.ModelState
import com.jlees.budgey.scan.NanoStatus
import com.jlees.budgey.scan.ScanEngine
import androidx.compose.material3.RadioButton
import com.jlees.budgey.ui.components.ConfirmDialog
import com.jlees.budgey.ui.theme.SeedPresets
import java.time.DayOfWeek

@Composable
fun SettingsScreen(
    onImport: () -> Unit,
    onPaymentMethods: () -> Unit,
    /** Open straight onto one page (e.g. Scanner & AI models from a scan)… */
    initialPage: SettingsPage? = null,
    /** …and leave Settings entirely when that page is closed. */
    onExit: (() -> Unit)? = null,
    vm: SettingsViewModel = viewModel(factory = AppViewModels.Factory),
) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val counts by vm.counts.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var confirmErase by remember { mutableStateOf(false) }
    val safety by vm.safetyBackup.collectAsStateWithLifecycle()
    val storage by vm.storage.collectAsStateWithLifecycle()
    var confirmRestore by remember { mutableStateOf(false) }
    val engines by vm.engines.collectAsStateWithLifecycle()
    val nanoMessage by vm.nanoMessage.collectAsStateWithLifecycle()
    val nanoSupported by vm.nanoSupported.collectAsStateWithLifecycle()
    var confirmDeleteModel by remember { mutableStateOf<ScanEngine?>(null) }
    var confirmDeleteAll by remember { mutableStateOf(false) }
    val modelPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importModel(uri)
    }
    val permission = rememberNotificationPermission { ok -> if (ok) vm.checkRemindersNow() }
    val context = LocalContext.current
    val flexAvailable = remember { AppFonts.isGoogleSansFlexAvailable(context) }
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }

    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) vm.export(uri)
    }

    // Settings is a short menu of categories; each opens its own page.
    var page by rememberSaveable { mutableStateOf(initialPage) }
    val closePage: () -> Unit = {
        if (onExit != null) onExit() else page = null
        Unit
    }
    BackHandler(enabled = page != null) { closePage() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(page?.title ?: "Settings") },
                navigationIcon = {
                    if (page != null) IconButton(onClick = closePage) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        key(page) {
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (page == null) {
                val downloaded = engines.count { it.model is ModelState.Ready }
                SettingsMenu(
                    items = listOf(
                        MenuEntry(Icons.Rounded.Palette, "Appearance", "${s.themeMode.label} theme · ${s.font.label} · ${s.textSize.label} text") { page = SettingsPage.APPEARANCE },
                        MenuEntry(Icons.Rounded.Savings, "Budgeting", "Week starts ${s.firstDayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() }} · uncategorized reminder ${if (s.nudgeUncategorized) "on" else "off"}") { page = SettingsPage.BUDGETING },
                        MenuEntry(Icons.Rounded.CreditCard, "Payment methods", "Cards, bank accounts and wallets", onPaymentMethods),
                        MenuEntry(Icons.Rounded.DocumentScanner, "Scanner & AI models", "Using ${s.scanEngine.title}" + if (downloaded > 0) " · $downloaded model${if (downloaded == 1) "" else "s"} downloaded" else "") { page = SettingsPage.SCANNER },
                        MenuEntry(Icons.Rounded.Handyman, "Tools", "Currency converter rates and updates") { page = SettingsPage.TOOLS },
                        MenuEntry(Icons.Rounded.Notifications, "Notifications", if (s.renewalReminders) "Renewal reminders on · ${daysLabel(s.reminderDaysBefore)}" else "Renewal reminders off") { page = SettingsPage.NOTIFICATIONS },
                        MenuEntry(Icons.Rounded.Storage, "Your data", "Export, import, storage, erase") { page = SettingsPage.DATA },
                        MenuEntry(Icons.Rounded.Info, "About", "Budgey ${BuildConfig.VERSION_NAME} · privacy · credits") { page = SettingsPage.ABOUT },
                    )
                )
            }
            if (page == SettingsPage.APPEARANCE) Group("") {
                Text("Theme", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    ThemeMode.entries.forEachIndexed { i, m ->
                        SegmentedButton(
                            selected = s.themeMode == m,
                            onClick = { vm.update { it.copy(themeMode = m) } },
                            shape = SegmentedButtonDefaults.itemShape(i, ThemeMode.entries.size),
                        ) { Text(m.label) }
                    }
                }
                Text("Font", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    AppFont.entries.forEachIndexed { i, f ->
                        SegmentedButton(
                            selected = s.font == f,
                            onClick = { vm.update { it.copy(font = f) } },
                            enabled = f == AppFont.SYSTEM || flexAvailable,
                            shape = SegmentedButtonDefaults.itemShape(i, AppFont.entries.size),
                            icon = {},
                        ) { Text(f.label, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                }
                if (s.font == AppFont.GOOGLE_SANS_FLEX && flexAvailable) {
                    SwitchItem(
                        Icons.Rounded.RoundedCorner,
                        "Rounded",
                        "Use the rounded variant of Google Sans Flex",
                        s.roundedFont,
                    ) { v -> vm.update { it.copy(roundedFont = v) } }
                }
                if (!flexAvailable) Text(
                    if (com.jlees.budgey.BuildConfig.DEBUG) "Google Sans Flex isn't bundled in this build yet — run ./gradlew :app:fetchFonts on your computer and rebuild. Using the system font until then."
                    else "Google Sans Flex isn't included in this version, so the system font is used.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
                Text("Text size", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextSize.entries.forEach { t ->
                        FilterChip(
                            selected = s.textSize == t,
                            onClick = { vm.update { it.copy(textSize = t) } },
                            label = { Text(t.label) },
                        )
                    }
                }
                Text(
                    "Applied on top of your phone's font size setting.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
                Spacer(Modifier.height(8.dp))
                SwitchItem(
                    Icons.Rounded.Animation,
                    "Animations",
                    "Transitions, chart sweeps and expanding panels. Turn off to make everything instant",
                    s.animations,
                ) { v -> vm.update { it.copy(animations = v) } }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    SwitchItem(Icons.Rounded.Wallpaper, "Material You colors", "Match your wallpaper", s.dynamicColor) { v -> vm.update { it.copy(dynamicColor = v) } }
                }
                if (!s.dynamicColor || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                    Text("Accent color", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        SeedPresets.forEach { color ->
                            val selected = color.toArgb() == s.seedColor
                            Box(
                                Modifier
                                    .size(40.dp)
                                    .background(color, CircleShape)
                                    .then(if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier)
                                    .clickable { vm.update { it.copy(seedColor = color.toArgb()) } },
                            )
                        }
                    }
                }
                SwitchItem(Icons.Rounded.DarkMode, "Pure black dark theme", "Saves battery on OLED screens", s.amoled) { v -> vm.update { it.copy(amoled = v) } }
            }

            if (page == SettingsPage.BUDGETING) Group("") {
                Text("Week starts on", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                val days = listOf(DayOfWeek.SUNDAY, DayOfWeek.MONDAY)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    days.forEachIndexed { i, d ->
                        SegmentedButton(
                            selected = s.firstDayOfWeek == d,
                            onClick = { vm.update { it.copy(firstDayOfWeek = d) } },
                            shape = SegmentedButtonDefaults.itemShape(i, days.size),
                        ) { Text(d.name.lowercase().replaceFirstChar { it.uppercase() }) }
                    }
                }
                Spacer(Modifier.height(8.dp))
                SwitchItem(Icons.Rounded.Lightbulb, "Remind me about uncategorized purchases", "Shows a banner on the Purchases page", s.nudgeUncategorized) { v ->
                    vm.update { it.copy(nudgeUncategorized = v) }
                }
            }

            if (page == SettingsPage.SCANNER) Group("") {
                Text(
                    "Standard is built in. For tricky pictures (gas pumps, kiosks, app screenshots) you can add an AI model. " +
                        "AI models run entirely on this phone — your photos and purchases never leave it; the internet is only used to download the model you pick.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
                engines.forEach { row ->
                    EngineItem(
                        row = row,
                        selected = s.scanEngine == row.engine,
                        ramGb = vm.ramGb,
                        nanoMessage = if (row.engine == ScanEngine.GEMINI_NANO) nanoMessage else null,
                        onSelect = { vm.selectEngine(row.engine) },
                        onDownload = { if (row.engine == ScanEngine.GEMINI_NANO) vm.downloadNano() else vm.download(row.engine) },
                        onCancel = { vm.cancelDownload(row.engine) },
                        onDelete = { confirmDeleteModel = row.engine },
                    )
                }
                if (nanoSupported == false) {
                    Text(
                        "Gemini Nano isn't available on this phone, so it isn't listed.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                SwitchItem(Icons.Rounded.Wifi, "Download models on Wi-Fi only", "Vision AI is about 2.6 GB", s.modelsWifiOnly) { v ->
                    vm.update { it.copy(modelsWifiOnly = v) }
                }
                ClickItem(Icons.Rounded.FileOpen, "Import a model file…", "Already have the model as a .litertlm file? Pick it here instead of downloading.") {
                    modelPicker.launch(arrayOf("*/*"))
                }
                if (s.smartScanCrashed) {
                    ListItem(
                        headlineContent = { Text("Vision AI stopped during a scan", color = MaterialTheme.colorScheme.error) },
                        supportingContent = {
                            Text(
                                "Budgey kept running and unloaded the model. This usually means the phone ran low on memory — " +
                                    "close other apps and try again. Select a scanner above to dismiss this."
                            )
                        },
                        leadingContent = { Icon(Icons.Rounded.ErrorOutline, null, tint = MaterialTheme.colorScheme.error) },
                        colors = itemColors(),
                    )
                }
            }

            if (page == SettingsPage.SCANNER) {
                val ready = engines.filter { it.model is ModelState.Ready }
                Group("Downloaded models") {
                    if (ready.isEmpty()) {
                        ListItem(
                            headlineContent = { Text("No AI models on this phone") },
                            supportingContent = { Text("Download one above to use a smarter scanner.") },
                            leadingContent = { Icon(Icons.Rounded.CloudOff, null) },
                            colors = itemColors(),
                        )
                    }
                    ready.forEach { row ->
                        val size = (row.model as ModelState.Ready).file.length()
                        ListItem(
                            headlineContent = { Text(row.engine.title) },
                            supportingContent = { Text(formatBytes(size) + if (s.scanEngine == row.engine) " · in use" else "") },
                            leadingContent = { Icon(Icons.Rounded.Memory, null) },
                            trailingContent = {
                                TextButton(onClick = { confirmDeleteModel = row.engine }) {
                                    Text("Delete", color = MaterialTheme.colorScheme.error)
                                }
                            },
                            colors = itemColors(),
                        )
                    }
                    if (ready.size > 1) ClickItem(
                        Icons.Rounded.DeleteSweep,
                        "Delete all models",
                        "Frees ${formatBytes(ready.sumOf { (it.model as ModelState.Ready).file.length() })}",
                        danger = true,
                    ) { confirmDeleteAll = true }
                }
            }

            if (page == SettingsPage.NOTIFICATIONS) Group("") {
                SwitchItem(
                    Icons.Rounded.NotificationsActive,
                    "Renewal reminders",
                    "Get notified before subscriptions renew and free trials end",
                    s.renewalReminders,
                ) { v ->
                    vm.update { it.copy(renewalReminders = v) }
                    if (v && !permission.granted) permission.request()
                }
                if (s.renewalReminders) {
                    if (!permission.granted) {
                        ClickItem(
                            Icons.Rounded.NotificationsOff,
                            "Notifications are blocked",
                            "Tap to allow notifications for this app",
                            danger = true,
                            onClick = permission::request,
                        )
                    }
                    Text("Remind me", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                    FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(0, 1, 2, 3, 7).forEach { d ->
                            FilterChip(
                                selected = s.reminderDaysBefore == d,
                                onClick = { vm.update { it.copy(reminderDaysBefore = d) }; vm.checkRemindersNow() },
                                label = { Text(daysLabel(d).replaceFirstChar { it.uppercase() }) },
                            )
                        }
                    }
                    Text(
                        "Checked once a day around 9 AM, on your phone. Each subscription can override this.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                    ClickItem(Icons.Rounded.Notifications, "Send a test reminder", "See what a renewal notification looks like") {
                        if (permission.granted) vm.sendTestReminder() else permission.request()
                    }
                }
            }

            if (page == SettingsPage.DATA) Group("") {
                ListItem(
                    headlineContent = { Text("Stored only on this phone") },
                    supportingContent = { Text("${counts.categories} categories · ${counts.purchases} purchases · ${counts.subscriptions} subscriptions") },
                    leadingContent = { Icon(Icons.Rounded.PhoneAndroid, null) },
                    colors = itemColors(),
                )
                ClickItem(Icons.Rounded.Upload, "Export everything", "Save a .zip backup (data + receipt images) you can import on another device") {
                    exporter.launch(vm.exportFileName())
                }
                ClickItem(Icons.Rounded.Download, "Import…", "Choose exactly which categories, budgets and items to bring in", onClick = onImport)
                ClickItem(
                    Icons.Rounded.Compress,
                    "Shrink saved pictures",
                    "Receipts & icons use ${formatBytes(storage)}. Re-compress pictures saved by older versions.",
                ) { vm.recompressImages() }
                safety?.let { info ->
                    ClickItem(
                        Icons.Rounded.Restore,
                        "Restore erased data",
                        "A safety copy from ${info.label} is kept for ${info.daysLeft} more day${if (info.daysLeft == 1) "" else "s"}",
                    ) { confirmRestore = true }
                }
                ClickItem(Icons.Rounded.DeleteForever, "Erase all data", "Start over. Export first if you might want it back.", danger = true) { confirmErase = true }
            }

            if (page == SettingsPage.TOOLS) {
                val fx by vm.fxInfo.collectAsStateWithLifecycle()
                val fxBusy by vm.fxBusy.collectAsStateWithLifecycle()
                var confirmDeleteFx by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) { vm.loadFxInfo() }
                val f = fx
                val hasPack = f?.source == com.jlees.budgey.data.FxRepository.Source.PACK
                Group("Currency converter") {
                    Text(
                        "Budgey comes with exchange rates for about 160 currencies built in, and uses only those unless you " +
                            "add the update pack below — the converter never goes online on its own.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                    // Built-in rates (always there).
                    ListItem(
                        headlineContent = { Text("Built-in rates") },
                        supportingContent = {
                            Text(if (f == null) "Loading…" else if (hasPack) "Used if the update pack is deleted" else "In use · rates from ${fxDate(f.table.date)} · ${f.table.currencies.size} currencies")
                        },
                        leadingContent = { Icon(Icons.Rounded.CurrencyExchange, null) },
                        colors = itemColors(),
                    )
                    // Optional update pack, laid out like the AI model rows: download, then delete.
                    ListItem(
                        headlineContent = { Text("Daily rate updates") },
                        supportingContent = {
                            Text(
                                when {
                                    fxBusy -> "Downloading…"
                                    hasPack && f != null ->
                                        "Downloaded · rates from ${fxDate(f.table.date)} · ${f.table.currencies.size} currencies · " +
                                            "updated ${android.text.format.DateUtils.getRelativeTimeSpanString(f.fetchedAt ?: 0L).toString().lowercase()}" +
                                            " · updates daily when online"
                                    else -> "About 10 KB download · keeps rates current, updating once a day when you're online"
                                },
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        },
                        leadingContent = { Icon(Icons.Rounded.Update, null) },
                        trailingContent = {
                            when {
                                fxBusy -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                                hasPack -> IconButton(onClick = { confirmDeleteFx = true }) { Icon(Icons.Rounded.DeleteOutline, "Delete update pack") }
                                else -> IconButton(onClick = { vm.downloadFxPack() }) { Icon(Icons.Rounded.Download, "Download update pack") }
                            }
                        },
                        colors = itemColors(),
                    )
                    if (hasPack) ClickItem(Icons.Rounded.Refresh, "Update now", "Get today's rates") { vm.downloadFxPack() }
                }
                if (confirmDeleteFx) ConfirmDialog(
                    title = "Delete the update pack?",
                    text = "The converter goes back to the built-in rates, and daily updates stop.",
                    confirmLabel = "Delete",
                    destructive = true,
                    onConfirm = { confirmDeleteFx = false; vm.deleteFxPack() },
                    onDismiss = { confirmDeleteFx = false },
                )
            }

            if (page == SettingsPage.ABOUT) Group("") {
                val (brands, withLogos) = vm.brandStats
                ListItem(
                    headlineContent = { Text("Budgey ${BuildConfig.VERSION_NAME}") },
                    supportingContent = { Text("A private, offline-first budget tracker for purchases, budgets and subscriptions.") },
                    leadingContent = { Icon(Icons.Rounded.Info, null) },
                    colors = itemColors(),
                )
                ListItem(
                    headlineContent = { Text("Privacy") },
                    supportingContent = {
                        Text(
                            "Your purchases, photos and backups stay on this phone. No accounts, ads or analytics. " +
                                "The internet is used only to download an AI scanner model you choose and to update currency rates if you add the optional daily update pack (Frankfurter, no personal data sent). ML Kit's usage reporting is turned off."
                        )
                    },
                    leadingContent = { Icon(Icons.Rounded.Lock, null) },
                    colors = itemColors(),
                )
                ListItem(
                    headlineContent = { Text("Scanner: ${s.scanEngine.title}") },
                    supportingContent = {
                        Text(
                            "Scans run on this phone with Google ML Kit text recognition" +
                                (if (s.scanEngine == ScanEngine.STANDARD) "." else " plus ${s.scanEngine.title}.") +
                                " Change it under Scanner above."
                        )
                    },
                    leadingContent = { Icon(Icons.Rounded.DocumentScanner, null) },
                    colors = itemColors(),
                )
                ListItem(
                    headlineContent = { Text("Brand icons") },
                    supportingContent = {
                        Text(
                            "$brands brands recognized, $withLogos with logos. Brands without a logo use their category icon." +
                                if (withLogos < 50 && com.jlees.budgey.BuildConfig.DEBUG) " Run ./gradlew :app:fetchBrandIcons when building to download the logos." else ""
                        )
                    },
                    leadingContent = { Icon(Icons.Rounded.Storefront, null) },
                    colors = itemColors(),
                )
                ListItem(
                    headlineContent = { Text("Credits") },
                    supportingContent = {
                        Text(
                            "Logos: Simple Icons (CC0) and Iconify open icon sets — trademarks belong to their owners. " +
                                "Font: Google Sans Flex (SIL OFL). Text recognition: Google ML Kit. " +
                                "AI models: Gemma 4 (Apache 2.0) via LiteRT-LM, and Gemini Nano on phones that support it. " +
                                "Built with Kotlin, Jetpack Compose and Material 3 Expressive."
                        )
                    },
                    leadingContent = { Icon(Icons.Rounded.Favorite, null) },
                    colors = itemColors(),
                )
            }
            Spacer(Modifier.height(24.dp))
        }
        }
    }

    if (confirmErase) {
        EraseAllFlow(
            counts = counts,
            onExportFirst = { confirmErase = false; exporter.launch(vm.exportFileName()) },
            onErase = { confirmErase = false; vm.eraseAll() },
            onDismiss = { confirmErase = false },
        )
    }
    if (confirmDeleteAll) {
        ConfirmDialog(
            title = "Delete all AI models?",
            text = "Scans will use Standard. You can download models again any time.",
            confirmLabel = "Delete all",
            destructive = true,
            onConfirm = {
                confirmDeleteAll = false
                engines.filter { it.model is ModelState.Ready }.forEach { vm.deleteModel(it.engine) }
            },
            onDismiss = { confirmDeleteAll = false },
        )
    }
    confirmDeleteModel?.let { e ->
        ConfirmDialog(
            title = "Delete ${e.title}?",
            text = "Frees ${formatBytes(e.bytes)}. You can download it again any time." +
                if (s.scanEngine == e) " Scans will switch back to Standard." else "",
            confirmLabel = "Delete",
            destructive = true,
            onConfirm = { confirmDeleteModel = null; vm.deleteModel(e) },
            onDismiss = { confirmDeleteModel = null },
        )
    }
    if (confirmRestore) {
        ConfirmDialog(
            title = "Restore erased data?",
            text = "Brings back everything from the safety copy. Anything you've added since stays, and nothing is duplicated.",
            confirmLabel = "Restore",
            onConfirm = { confirmRestore = false; vm.restoreSafetyBackup() },
            onDismiss = { confirmRestore = false },
        )
    }
}

@Composable
private fun itemColors() = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)

@Composable
private fun Group(title: String, content: @Composable () -> Unit) {
    Column {
        if (title.isNotEmpty()) Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 16.dp, bottom = 8.dp))
        Card(
            shape = MaterialTheme.shapes.extraLarge,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        ) {
            Column(Modifier.padding(vertical = 8.dp)) { content() }
        }
    }
}

@Composable
private fun SwitchItem(icon: ImageVector, title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        leadingContent = { Icon(icon, null) },
        trailingContent = { Switch(checked = checked, onCheckedChange = onChange) },
        colors = itemColors(),
        modifier = Modifier.clickable { onChange(!checked) },
    )
}

@Composable
private fun ClickItem(icon: ImageVector, title: String, subtitle: String, danger: Boolean = false, onClick: () -> Unit) {
    val tint = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    ListItem(
        headlineContent = { Text(title, color = tint) },
        supportingContent = { Text(subtitle) },
        leadingContent = { Icon(icon, null, tint = tint) },
        colors = itemColors(),
        modifier = Modifier.clickable(onClick = onClick),
    )
}

private fun formatBytes(b: Long): String = when {
    b >= 1_000_000_000 -> "%.1f GB".format(b / 1e9)
    b >= 1_000_000 -> "%.1f MB".format(b / 1e6)
    b >= 1_000 -> "%d KB".format(b / 1000)
    else -> "$b B"
}

private const val ERASE_WORD = "ERASE"

/**
 * Erasing is deliberately slow to do by accident:
 *  1. See exactly what will be lost, with a one-tap "Export first".
 *  2. Tick "I understand".
 *  3. Type ERASE, then wait out a short countdown before the button unlocks.
 * Even then, a safety copy is kept on the phone for 7 days (Settings → Restore erased data).
 */
@Composable
private fun EraseAllFlow(counts: DataCounts, onExportFirst: () -> Unit, onErase: () -> Unit, onDismiss: () -> Unit) {
    var step by remember { mutableStateOf(1) }
    var understood by remember { mutableStateOf(false) }
    var typed by remember { mutableStateOf("") }
    var secondsLeft by remember { mutableStateOf(5) }
    LaunchedEffect(step) {
        if (step == 2) {
            secondsLeft = 5
            while (secondsLeft > 0) { kotlinx.coroutines.delay(1000); secondsLeft-- }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Warning, null, tint = MaterialTheme.colorScheme.error) },
        title = { Text(if (step == 1) "Erase all data?" else "Final check") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (step == 1) {
                    Text("This permanently deletes, from this phone:")
                    Text(
                        "• ${counts.purchases} purchases\n• ${counts.subscriptions} subscriptions\n• ${counts.categories} categories & budgets\n• every receipt picture and payment method",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedButton(onClick = onExportFirst, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Rounded.Upload, null, Modifier.size(18.dp))
                        Spacer(Modifier.size(8.dp))
                        Text("Export a backup first")
                    }
                    Row(
                        Modifier.fillMaxWidth().clickable { understood = !understood },
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = understood, onCheckedChange = { understood = it })
                        Text("I understand this can't be undone from inside the app", style = MaterialTheme.typography.bodyMedium)
                    }
                } else {
                    Text("Type $ERASE_WORD to confirm.")
                    OutlinedTextField(
                        value = typed,
                        onValueChange = { typed = it },
                        singleLine = true,
                        placeholder = { Text(ERASE_WORD) },
                        isError = typed.isNotEmpty() && !ERASE_WORD.startsWith(typed.trim().uppercase()),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "A safety copy stays on this phone for 7 days in case you change your mind.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            if (step == 1) {
                TextButton(onClick = { step = 2 }, enabled = understood) { Text("Continue") }
            } else {
                val ready = typed.trim().equals(ERASE_WORD, ignoreCase = true) && secondsLeft == 0
                Button(
                    onClick = onErase,
                    enabled = ready,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
                ) { Text(if (secondsLeft > 0) "Erase ($secondsLeft)" else "Erase everything") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** One scanner choice: radio button, what it is, its size, and download / cancel / delete. */
@Composable
private fun EngineItem(
    row: EngineRow,
    selected: Boolean,
    ramGb: Float,
    nanoMessage: String?,
    onSelect: () -> Unit,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
) {
    val e = row.engine
    val status: String = when {
        e == ScanEngine.STANDARD -> "Built in · ${e.speed}"
        e == ScanEngine.GEMINI_NANO -> when (row.nano) {
            NanoStatus.READY -> "Available on this phone · ${e.speed}"
            NanoStatus.NEEDS_DOWNLOAD -> nanoMessage?.takeIf { it.startsWith("failed") || it.startsWith("Couldn't") }
                ?.let { "Setup didn't finish ($it) · select it to try again" }
                ?: "Supported · Android sets it up when you select it"
            NanoStatus.DOWNLOADING -> "Android is setting it up… " + (nanoMessage?.takeIf { it != "done" } ?: "")
            else -> "Not available on this phone"
        }
        !row.fits -> "Needs about ${e.minRamGb} GB of RAM — this phone has %.1f GB".format(ramGb)
        row.importProgress != null -> "Importing… ${(row.importProgress * 100).toInt()}%"
        else -> when (val m = row.model) {
            is ModelState.Ready -> "Downloaded · ${formatBytes(m.file.length())} · ${e.speed}"
            is ModelState.Downloading -> (if (m.paused) "Waiting for Wi-Fi / connection… " else "Downloading… ") +
                "${(m.fraction * 100).toInt()}% of ${formatBytes(e.bytes)}"
            is ModelState.Failed -> "${m.reason} · ${formatBytes(e.bytes)} download"
            ModelState.NotDownloaded -> "${formatBytes(e.bytes)} download · ${e.speed}"
        }
    }
    var showInfo by rememberSaveable(e.name) { mutableStateOf(false) }
    Column {
        ListItem(
            headlineContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(e.title, modifier = Modifier.weight(1f, fill = false))
                    IconButton(onClick = { showInfo = !showInfo }, modifier = Modifier.size(32.dp)) {
                        Icon(
                            if (showInfo) Icons.Rounded.Info else Icons.Outlined.InfoOutlined,
                            contentDescription = if (showInfo) "Hide details" else "Details",
                            modifier = Modifier.size(18.dp),
                            tint = if (showInfo) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            supportingContent = {
                Column {
                    // Short line by default; the (i) button reveals the full description.
                    Text(status, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, maxLines = 2)
                    AnimatedVisibility(visible = showInfo, enter = appear(), exit = disappear()) {
                        Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(e.summary, style = MaterialTheme.typography.bodySmall)
                            val facts = buildList {
                                add("Speed: ${e.speed}")
                                add(if (e.bytes > 0) "Download: ${formatBytes(e.bytes)}" else "Download: none")
                                if (e.minRamGb > 0) add("Needs: ${e.minRamGb} GB RAM (this phone: %.1f GB)".format(ramGb))
                                add(if (e == ScanEngine.STANDARD) "Reads: text, from 3 cleaned-up copies of the photo" else "Reads: the original photo")
                            }
                            facts.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    }
                }
            },
            leadingContent = { RadioButton(selected = selected, onClick = onSelect, enabled = row.ready) },
            trailingContent = {
                val m = row.model
                when {
                    e.downloadable && row.fits && m is ModelState.Downloading ->
                        IconButton(onClick = onCancel) { Icon(Icons.Rounded.Close, "Cancel download") }
                    e.downloadable && m is ModelState.Ready ->
                        IconButton(onClick = onDelete) { Icon(Icons.Rounded.DeleteOutline, "Delete model") }
                    e.downloadable && row.fits && row.importProgress == null ->
                        IconButton(onClick = onDownload) { Icon(Icons.Rounded.Download, "Download ${formatBytes(e.bytes)}") }
                }
            },
            colors = itemColors(),
            modifier = Modifier.clickable(enabled = row.ready, onClick = onSelect),
        )
        val m = row.model
        if (m is ModelState.Downloading) {
            androidx.compose.material3.LinearProgressIndicator(
                progress = { m.fraction.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().padding(start = 72.dp, end = 16.dp, bottom = 8.dp),
            )
        }
    }
}

/** The pages Settings opens into. */
enum class SettingsPage(val title: String) {
    APPEARANCE("Appearance"),
    BUDGETING("Budgeting"),
    SCANNER("Scanner & AI models"),
    TOOLS("Tools"),
    NOTIFICATIONS("Notifications"),
    DATA("Your data"),
    ABOUT("About"),
}

private class MenuEntry(val icon: ImageVector, val title: String, val summary: String, val onClick: () -> Unit)

/** The top-level Settings list: one row per category, each opening its own page. */
@Composable
private fun SettingsMenu(items: List<MenuEntry>) {
    Card(
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(vertical = 8.dp)) {
            items.forEach { e ->
                ListItem(
                    headlineContent = { Text(e.title) },
                    supportingContent = { Text(e.summary, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    leadingContent = {
                        Box(
                            Modifier.size(40.dp).background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
                            contentAlignment = androidx.compose.ui.Alignment.Center,
                        ) { Icon(e.icon, null, tint = MaterialTheme.colorScheme.onSecondaryContainer) }
                    },
                    trailingContent = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null) },
                    colors = itemColors(),
                    modifier = Modifier.clickable(onClick = e.onClick),
                )
            }
        }
    }
}

/** "2026-10-02" → "Oct 2, 2026" (or as-is if it isn't a date). */
private fun fxDate(iso: String): String = runCatching {
    java.time.LocalDate.parse(iso).format(java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM))
}.getOrDefault(iso)
