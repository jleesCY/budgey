package com.jlees.budgey.ui.tools

import android.content.Intent
import androidx.compose.material.icons.automirrored.rounded.Notes
import com.jlees.budgey.ui.components.ScanTextDialog
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Refresh
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.jlees.budgey.scan.ModelState
import com.jlees.budgey.scan.ScanEngine
import com.jlees.budgey.ui.components.ReceiptPreview
import kotlinx.coroutines.delay
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DocumentScanner
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jlees.budgey.domain.Check
import com.jlees.budgey.domain.CheckSplitter
import com.jlees.budgey.domain.CustomKind
import com.jlees.budgey.domain.Money
import com.jlees.budgey.domain.PersonShare
import com.jlees.budgey.domain.SplitItem
import com.jlees.budgey.domain.SplitMode
import com.jlees.budgey.domain.SplitPerson
import com.jlees.budgey.domain.SplitResult
import com.jlees.budgey.ui.AppViewModels
import com.jlees.budgey.ui.components.AmountField
import com.jlees.budgey.ui.components.rememberImageSource

private val TipPresets = listOf(0.0, 15.0, 18.0, 20.0, 22.0, 25.0)

/**
 * Check splitter: scan a receipt (or type it in), add the people, then split evenly, by custom
 * amounts / percentages, or item by item. Each person's total is rounded up to the cent.
 */
@Composable
fun CheckSplitScreen(
    onBack: () -> Unit,
    onOpenScannerSettings: () -> Unit,
    vm: CheckSplitViewModel = viewModel(factory = AppViewModels.Factory),
) {
    val check by vm.check.collectAsStateWithLifecycle()
    val inputs by vm.inputs.collectAsStateWithLifecycle()
    val result by vm.result.collectAsStateWithLifecycle()
    val scanning by vm.scanning.collectAsStateWithLifecycle()
    val scanSummary by vm.scanSummary.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }
    val context = LocalContext.current
    val pickImage = rememberImageSource("Scan") { uri -> if (uri != null) vm.scan(uri) }
    // null = closed, "" = new item, else the id being edited.
    var editingItem by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmReset by remember { mutableStateOf(false) }
    val image by vm.image.collectAsStateWithLifecycle()
    val scanText by vm.scanText.collectAsStateWithLifecycle()
    var showScanText by rememberSaveable { mutableStateOf(false) }
    val rescan by vm.rescanState.collectAsStateWithLifecycle()
    val resumeOffer by vm.resumeOffer.collectAsStateWithLifecycle()
    var askDownload by rememberSaveable { mutableStateOf(false) }
    // "std" / "adv": a rescan waiting for "replace the items?" confirmation.
    var confirmRescan by rememberSaveable { mutableStateOf<String?>(null) }
    // Re-check the rescan options whenever this screen is in view (e.g. back from downloading Vision AI).
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                vm.refreshRescan()
                delay(if (vm.rescanState.value.vision is ModelState.Downloading) 1_500L else 5_000L)
            }
        }
    }
    val assigned = check.items.any { it.people.isNotEmpty() }
    fun runRescan(kind: String) {
        if (kind == "std") vm.rescan()
        else when (val m = rescan.vision) {
            is ModelState.Ready -> vm.advancedRescan()
            is ModelState.Downloading -> vm.messages.tryEmit("Vision AI is still downloading — ${(m.fraction * 100).toInt()}%")
            else -> askDownload = true
        }
    }
    fun requestRescan(kind: String) {
        if (assigned) confirmRescan = kind else runRescan(kind)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Check splitter") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                actions = {
                    IconButton(onClick = { confirmReset = true }) { Icon(Icons.Rounded.RestartAlt, "Start over") }
                    IconButton(onClick = {
                        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, vm.summary())
                        context.startActivity(Intent.createChooser(send, "Share the split"))
                    }) { Icon(Icons.Rounded.Share, "Share the split") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).imePadding(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "scan") {
                ScanCard(
                    scanning, scanSummary, image, rescan,
                    onScan = pickImage,
                    onRescan = { requestRescan("std") },
                    onAdvancedRescan = { requestRescan("adv") },
                    onViewText = if (scanText.isNotEmpty()) ({ showScanText = true }) else null,
                )
            }

            item(key = "mode") {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SplitMode.entries.forEachIndexed { i, m ->
                        SegmentedButton(
                            selected = check.mode == m,
                            onClick = { vm.setMode(m) },
                            shape = SegmentedButtonDefaults.itemShape(i, SplitMode.entries.size),
                            icon = {},
                        ) { Text(m.label, maxLines = 1) }
                    }
                }
            }

            item(key = "people") { PeopleSection(check, vm) }

            item(key = "items-header") {
                SectionHeader(
                    if (check.mode == SplitMode.ITEMS) "Items — tap a name to say who had it" else "Items",
                    trailing = { TextButton(onClick = { editingItem = "" }) { Icon(Icons.Rounded.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Item") } },
                )
            }
            if (check.items.isEmpty()) item(key = "items-empty") {
                Text(
                    if (check.mode == SplitMode.ITEMS) "Scan, or add items yourself, to split them by who had what."
                    else "Optional for an even or custom split — just fill in the bill below.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(check.items, key = { it.id }) { item ->
                ItemRow(item, check, onEdit = { editingItem = item.id }, onToggle = { vm.toggleAssignee(item.id, it) }, onEveryone = { vm.toggleEveryone(item.id) })
            }
            val typed = check.subtotalOverride
            if (check.items.isNotEmpty() && typed != null && typed != check.itemsCents) item(key = "items-mismatch") {
                Warning("Items add up to ${Money.format(check.itemsCents)}, but the subtotal says ${Money.format(typed)}. " +
                    if (check.mode == SplitMode.ITEMS) "Splitting by item uses the items." else "The subtotal below is used.")
            }

            item(key = "bill") { BillSection(check, inputs, vm) }
            item(key = "results") { ResultsSection(check, result) }
        }
    }

    editingItem?.let { id ->
        ItemDialog(
            item = check.items.firstOrNull { it.id == id },
            onSave = { name, cents, qty -> vm.saveItem(id.ifEmpty { null }, name, cents, qty); editingItem = null },
            onDelete = { vm.removeItem(id); editingItem = null },
            onDismiss = { editingItem = null },
        )
    }
    resumeOffer?.let { saved ->
        val people = saved.people.size
        val items = saved.items.size
        AlertDialog(
            onDismissRequest = {},
            icon = { Icon(Icons.Rounded.History, null) },
            title = { Text("Pick up where you left off?") },
            text = {
                Text(
                    "You were splitting a check " +
                        android.text.format.DateUtils.getRelativeTimeSpanString(saved.savedAt).toString().lowercase() +
                        " — $people ${if (people == 1) "person" else "people"}" +
                        (if (items > 0) ", $items item${if (items == 1) "" else "s"}" else "") + "."
                )
            },
            confirmButton = { TextButton(onClick = { vm.resume() }) { Text("Continue") } },
            dismissButton = { TextButton(onClick = { vm.startFresh() }) { Text("Start fresh") } },
        )
    }
    if (askDownload) AlertDialog(
        onDismissRequest = { askDownload = false },
        icon = { Icon(Icons.Rounded.AutoAwesome, null) },
        title = { Text("Download Vision AI?") },
        text = {
            Text(
                "Advanced Rescan uses Vision AI, which isn't on this phone yet " +
                    "(%.1f GB download). ".format(ScanEngine.VISION_AI.bytes / 1e9) +
                    "Open Scanner & AI models to download it, then press Back to return to your check."
            )
        },
        confirmButton = { TextButton(onClick = { askDownload = false; onOpenScannerSettings() }) { Text("Open") } },
        dismissButton = { TextButton(onClick = { askDownload = false }) { Text("Not now") } },
    )
    confirmRescan?.let { kind ->
        AlertDialog(
            onDismissRequest = { confirmRescan = null },
            title = { Text("Replace the items?") },
            text = { Text("Rescanning replaces the item list, so who-had-what will need to be picked again. People and the bill settings stay.") },
            confirmButton = { TextButton(onClick = { confirmRescan = null; runRescan(kind) }) { Text("Rescan") } },
            dismissButton = { TextButton(onClick = { confirmRescan = null }) { Text("Cancel") } },
        )
    }
    if (showScanText) ScanTextDialog(scanText, onDismiss = { showScanText = false })
    if (confirmReset) AlertDialog(
        onDismissRequest = { confirmReset = false },
        title = { Text("Start over?") },
        text = { Text("Clears the items, people, bill and photo.") },
        confirmButton = { TextButton(onClick = { vm.reset(); confirmReset = false }) { Text("Start over") } },
        dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } },
    )
}

@Composable
private fun SectionHeader(title: String, trailing: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        trailing?.invoke()
    }
}

@Composable
private fun Warning(text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(Icons.Rounded.WarningAmber, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun ScanCard(
    scanning: Boolean,
    summary: String?,
    image: java.io.File?,
    rescan: CheckSplitViewModel.RescanState,
    onScan: () -> Unit,
    onRescan: () -> Unit,
    onAdvancedRescan: () -> Unit,
    /** Shows what the scan read; null until something has been scanned. */
    onViewText: (() -> Unit)?,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), shape = RoundedCornerShape(28.dp)) {
        Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (scanning) {
                ContainedLoadingIndicator(Modifier.size(64.dp))
                Spacer(Modifier.height(8.dp))
                Text("Reading…", style = MaterialTheme.typography.bodyLarge)
                return@Column
            }
            // The photo: tap to see it full screen.
            if (image != null) {
                ReceiptPreview(image)
                Spacer(Modifier.height(12.dp))
            }
            Text(
                summary ?: "Scan to pull in every item, or type the bill in below.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Spacer(Modifier.height(12.dp))
            FilledTonalButton(onClick = onScan, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.DocumentScanner, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (image != null) "Scan another" else "Scan")
            }
            if (image != null) {
                Spacer(Modifier.height(8.dp))
                FilledTonalButton(onClick = onRescan, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.Refresh, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Rescan")
                }
                if (rescan.visionFits) {
                    Spacer(Modifier.height(8.dp))
                    FilledTonalButton(onClick = onAdvancedRescan, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Rounded.AutoAwesome, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Advanced Rescan")
                    }
                }
                if (onViewText != null) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = onViewText, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.AutoMirrored.Rounded.Notes, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("View text")
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    buildString {
                        append(if (rescan.nanoReady) "Rescan uses Gemini Nano" else "Rescan reads the photo again")
                        if (rescan.visionFits) {
                            append(" · Advanced Rescan uses Vision AI")
                            when (val m = rescan.vision) {
                                is ModelState.Downloading -> append(" (downloading ${(m.fraction * 100).toInt()}%)")
                                is ModelState.Ready -> Unit
                                else -> append(" (needs a download)")
                            }
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}

@Composable
private fun PeopleSection(check: Check, vm: CheckSplitViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader("People") {
            // Quick count stepper (handy for "split evenly between 6").
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilledTonalIconButton(onClick = { vm.setPeopleCount(check.people.size - 1) }, enabled = check.people.size > 1) { Icon(Icons.Rounded.Remove, "One fewer person") }
                Text("${check.people.size}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.widthIn(min = 32.dp).padding(horizontal = 4.dp), maxLines = 1)
                FilledTonalIconButton(onClick = { vm.addPerson() }) { Icon(Icons.Rounded.Add, "Add a person") }
            }
        }
        if (check.mode == SplitMode.CUSTOM) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Each share (before tip) as", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                FilterChip(check.customKind == CustomKind.AMOUNT, { vm.setCustomKind(CustomKind.AMOUNT) }, label = { Text("$ amount") })
                Spacer(Modifier.width(8.dp))
                FilterChip(check.customKind == CustomKind.PERCENT, { vm.setCustomKind(CustomKind.PERCENT) }, label = { Text("% of bill") })
            }
        }
        check.people.forEachIndexed { i, p -> PersonRow(p, i, check, vm) }
        Text(
            "The tip is split between the people with \"Tip\" switched on.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (check.mode == SplitMode.CUSTOM) {
            OutlinedButton(onClick = { vm.splitRemainingEvenly() }) { Text("Split what's left between the empty ones") }
        }
    }
}

@Composable
private fun PersonRow(p: SplitPerson, index: Int, check: Check, vm: CheckSplitViewModel) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = p.name,
                onValueChange = { vm.renamePerson(p.id, it) },
                singleLine = true,
                placeholder = { Text("Name") },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                modifier = Modifier.weight(1f),
            )
            if (check.mode == SplitMode.CUSTOM) {
                Spacer(Modifier.width(8.dp))
                OutlinedTextField(
                    value = p.custom,
                    onValueChange = { vm.setCustom(p.id, it) },
                    singleLine = true,
                    prefix = { if (check.customKind == CustomKind.AMOUNT) Text("$") },
                    suffix = { if (check.customKind == CustomKind.PERCENT) Text("%") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.width(96.dp),
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(start = 8.dp)) {
                Text("Tip", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Switch(checked = p.sharesTip, onCheckedChange = { vm.setSharesTip(p.id, it) })
            }
            IconButton(onClick = { vm.removePerson(p.id) }, enabled = check.people.size > 1) { Icon(Icons.Rounded.Close, "Remove ${displayName(index, p)}") }
        }
    }
}

@Composable
private fun ItemRow(item: SplitItem, check: Check, onEdit: () -> Unit, onToggle: (String) -> Unit, onEveryone: () -> Unit) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(Modifier.fillMaxWidth().clickable(onClick = onEdit).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (item.qty > 1) Text("${item.qty}× ", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(item.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    Money.format(item.priceCents),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = if (item.priceCents < 0) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurface,
                )
            }
            if (check.mode == SplitMode.ITEMS) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val everyone = check.people.isNotEmpty() && item.people.containsAll(check.people.map { it.id })
                    FilterChip(everyone, onEveryone, label = { Text("Everyone") }, leadingIcon = { Icon(Icons.Rounded.Groups, null, Modifier.size(16.dp)) })
                    check.people.forEachIndexed { i, p ->
                        InputChip(
                            selected = p.id in item.people,
                            onClick = { onToggle(p.id) },
                            label = { Text(displayName(i, p), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        )
                    }
                }
                val n = item.people.count { id -> check.people.any { it.id == id } }
                if (n > 1) Text(
                    "${CheckSplitter.format(item.priceCents.toBigDecimal().divide(n.toBigDecimal(), java.math.MathContext.DECIMAL64))} each",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun BillSection(check: Check, inputs: BillInputs, vm: CheckSplitViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader("Bill")
        if (check.mode == SplitMode.ITEMS) {
            Text("Subtotal (from items): ${Money.format(check.itemsCents)}", style = MaterialTheme.typography.bodyLarge)
        } else {
            AmountField(
                inputs.subtotal, vm::setSubtotal, Modifier.fillMaxWidth(), label = "Subtotal (before tax)", large = false,
                supportingText = if (inputs.subtotal.isBlank() && check.items.isNotEmpty()) "Using the items: ${Money.format(check.itemsCents)}" else null,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AmountField(inputs.tax, vm::setTax, Modifier.weight(1f), label = "Tax", large = false)
            AmountField(inputs.fees, vm::setFees, Modifier.weight(1f), label = "Fees", large = false)
        }
        Text("Tip", style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TipPresets.forEach { pct ->
                FilterChip(
                    selected = check.tip.amountCents == null && check.tip.percent == pct,
                    onClick = { vm.setTipPercent(pct) },
                    label = { Text(if (pct == 0.0) "None" else "${pct.toInt()}%") },
                )
            }
        }
        AmountField(
            inputs.tipAmount, vm::setTipAmount, Modifier.fillMaxWidth(), label = "Or a tip amount", large = false,
            supportingText = "Tip: ${Money.format(check.tipCents)}" + (check.tip.percent?.let { " (${it.toInt()}% of the subtotal)" } ?: ""),
        )
        if (check.mode == SplitMode.ITEMS) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Tip by what each person ordered", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        if (check.tipByOrder) "Bigger orders tip more" else "Tippers split it evenly",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(check.tipByOrder, vm::setTipByOrder)
            }
        }
        HorizontalDivider()
        Row {
            Text("Total", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text(Money.format(check.totalCents), style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun ResultsSection(check: Check, result: SplitResult) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader("Who pays what")
        result.notes.forEach { Warning(it) }
        result.shares.forEachIndexed { i, s -> ShareCard(s, i, check) }
        if (result.roundingExtraCents > 0) Text(
            "Everyone's share is rounded up to the cent, so the group pays ${Money.format(result.roundingExtraCents)} extra in total.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ShareCard(s: PersonShare, index: Int, check: Check) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        shape = RoundedCornerShape(24.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(displayName(index, s.person), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(Money.format(s.totalCents), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            }
            val parts = buildList {
                add("${if (check.mode == SplitMode.ITEMS) "Items" else "Bill"} ${CheckSplitter.format(s.items)}")
                if (s.taxAndFees.signum() != 0) add("tax & fees ${CheckSplitter.format(s.taxAndFees)}")
                add(if (s.tip.signum() != 0) "tip ${CheckSplitter.format(s.tip)}" else "no tip")
            }
            Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
            if (check.mode == SplitMode.ITEMS && s.lines.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                s.lines.forEach { (item, part) ->
                    Row {
                        val shared = part.compareTo(item.priceCents.toBigDecimal()) != 0
                        Text(item.name + if (shared) " (shared)" else "", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(CheckSplitter.format(part), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun ItemDialog(item: SplitItem?, onSave: (String, Long, Int) -> Unit, onDelete: () -> Unit, onDismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf(item?.name ?: "") }
    var price by rememberSaveable { mutableStateOf(item?.priceCents?.let { Money.toInput(kotlin.math.abs(it)) } ?: "") }
    var qty by rememberSaveable { mutableStateOf(item?.qty ?: 1) }
    var discount by rememberSaveable { mutableStateOf((item?.priceCents ?: 0) < 0) }
    val cents = Money.parse(price)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (item == null) "Add item" else "Edit item") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    name, { name = it.take(60) }, label = { Text("Name") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth(),
                )
                AmountField(price, { price = it }, Modifier.fillMaxWidth(), label = "Price (for all of them)", large = false)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Quantity", modifier = Modifier.weight(1f))
                    FilledTonalIconButton(onClick = { qty = (qty - 1).coerceAtLeast(1) }) { Icon(Icons.Rounded.Remove, "Fewer") }
                    Text("$qty", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 8.dp))
                    FilledTonalIconButton(onClick = { qty += 1 }) { Icon(Icons.Rounded.Add, "More") }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Discount / coupon", modifier = Modifier.weight(1f))
                    Switch(discount, { discount = it })
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(name, (cents ?: 0) * if (discount) -1 else 1, qty) }, enabled = cents != null) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (item != null) TextButton(onClick = onDelete) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}
