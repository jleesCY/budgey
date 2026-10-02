package com.jlees.budgey.ui.scan

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Autorenew
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.ShoppingBag
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Refresh
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import com.jlees.budgey.scan.ModelState
import com.jlees.budgey.scan.ScanEngine
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.FilterChip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.jlees.budgey.scan.NumberBox
import com.jlees.budgey.domain.Money
import com.jlees.budgey.scan.ScanKind
import com.jlees.budgey.ui.AppViewModels
import com.jlees.budgey.ui.components.MerchantAvatar
import com.jlees.budgey.ui.components.rememberImageSource
import androidx.compose.material.icons.rounded.DocumentScanner
import com.jlees.budgey.ui.components.MediumDate
import java.io.File
import java.util.UUID

/**
 * Camera / screenshot → on-device OCR → review → hand off to the purchase or subscription editor.
 */
@Composable
fun ScanScreen(
    onBack: () -> Unit,
    onContinue: (ScanKind) -> Unit,
    onOpenScannerSettings: () -> Unit,
    vm: ScanViewModel = viewModel(factory = AppViewModels.Factory),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val errorDialog by vm.errorDialog.collectAsStateWithLifecycle()
    errorDialog?.let { msg ->
        AlertDialog(
            onDismissRequest = { vm.errorDialog.value = null },
            icon = { Icon(Icons.Rounded.ErrorOutline, null) },
            title = { Text("The AI model stopped") },
            text = { Text(msg) },
            confirmButton = { TextButton(onClick = { vm.errorDialog.value = null }) { Text("OK") } },
        )
    }
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }
    // Re-check what the rescan buttons can do whenever this screen is in view — e.g. after coming
    // back from downloading Vision AI. Polls faster while that download is running.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                vm.refreshRescan()
                delay(if (vm.rescan.value.vision is ModelState.Downloading) 1_500 else 5_000)
            }
        }
    }
    var launched by rememberSaveable { mutableStateOf(false) }

    // Android's chooser: take a photo, or pick a screenshot / image from any gallery app.
    val pickImage = rememberImageSource { uri ->
        if (uri != null) vm.process(uri) else if (vm.state.value is ScanState.Waiting) onBack()
    }

    LaunchedEffect(Unit) {
        if (!launched) {
            launched = true
            when (vm.route.mode) {
                "shared" -> vm.route.sharedUri?.let { vm.process(Uri.parse(it)) }
                else -> pickImage()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Scan") },
                navigationIcon = { IconButton(onClick = { vm.reset(); onBack() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
            when (val s = state) {
                ScanState.Waiting -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = pickImage) { Icon(Icons.Rounded.DocumentScanner, null); Spacer(Modifier.size(8.dp)); Text("Scan / Import") }
                    Text(
                        "Take a photo of a receipt, gas pump or kiosk screen — or pick a screenshot.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 32.dp),
                    )
                }
                ScanState.Working -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    ContainedLoadingIndicator(Modifier.size(96.dp))
                    Spacer(Modifier.height(16.dp))
                    val status by vm.status.collectAsStateWithLifecycle()
                    Text(status, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 32.dp))
                }
                is ScanState.Failed -> Column(Modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Rounded.ErrorOutline, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(12.dp))
                    if (s.modelCrashed) {
                        Text("The AI model stopped", style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(8.dp))
                    }
                    Text(s.message, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(16.dp))
                    if (s.modelCrashed) {
                        Button(onClick = { vm.retryWithStandard() }) { Text("Read it with Standard") }
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = { vm.reset() }) { Text("Start over") }
                    } else {
                        Button(onClick = { vm.reset() }) { Text("Try again") }
                    }
                }
                is ScanState.Done -> Review(
                    s, vm,
                    onContinue = { vm.commit()?.let(onContinue) },
                    onRetake = {
                        vm.reset()
                        pickImage()
                    },
                    onOpenScannerSettings = onOpenScannerSettings,
                    showMessage = { msg -> vm.messages.tryEmit(msg) },
                )
            }
        }
    }
}

@Composable
private fun Review(
    s: ScanState.Done,
    vm: ScanViewModel,
    onContinue: () -> Unit,
    onRetake: () -> Unit,
    onOpenScannerSettings: () -> Unit,
    showMessage: (String) -> Unit,
) {
    val r = s.result
    val rescan by vm.rescan.collectAsStateWithLifecycle()
    var askDownload by rememberSaveable { mutableStateOf(false) }
    if (askDownload) {
        AlertDialog(
            onDismissRequest = { askDownload = false },
            icon = { Icon(Icons.Rounded.AutoAwesome, null) },
            title = { Text("Download Vision AI?") },
            text = {
                Text(
                    "Advanced Rescan uses Vision AI, which isn't on this phone yet " +
                        "(%.1f GB download). ".format(ScanEngine.VISION_AI.bytes / 1e9) +
                        "Open Scanner & AI models to download it, then press Back to return to this scan."
                )
            },
            confirmButton = { TextButton(onClick = { askDownload = false; onOpenScannerSettings() }) { Text("Open") } },
            dismissButton = { TextButton(onClick = { askDownload = false }) { Text("Not now") } },
        )
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        AsyncImage(
            model = vm.receiptFile(s.receiptFile),
            contentDescription = "Scanned image",
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(28.dp)),
        )
        Text("What is this?", style = MaterialTheme.typography.titleMedium)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = s.kind == ScanKind.PURCHASE,
                onClick = { vm.setKind(ScanKind.PURCHASE) },
                shape = SegmentedButtonDefaults.itemShape(0, 2),
                icon = { Icon(Icons.Rounded.ShoppingBag, null, Modifier.size(18.dp)) },
            ) { Text("Purchase") }
            SegmentedButton(
                selected = s.kind == ScanKind.SUBSCRIPTION,
                onClick = { vm.setKind(ScanKind.SUBSCRIPTION) },
                shape = SegmentedButtonDefaults.itemShape(1, 2),
                icon = { Icon(Icons.Rounded.Autorenew, null, Modifier.size(18.dp)) },
            ) { Text("Subscription") }
        }
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
            val itemColors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
            ListItem(
                overlineContent = { Text("Merchant") },
                headlineContent = { Text(r.merchant ?: "Not found") },
                trailingContent = { if (r.brand != null) MerchantAvatar(r.merchant ?: "", r.brand.id, null, size = 36.dp) },
                colors = itemColors,
            )
            ListItem(overlineContent = { Text("Amount") }, headlineContent = { Text(r.amountCents?.let(Money::format) ?: "Not found") }, colors = itemColors)
            if (r.amountCandidates.size > 1) Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                r.amountCandidates.forEach { c ->
                    FilterChip(selected = c == r.amountCents, onClick = { vm.pickAmount(c) }, label = { Text(Money.format(c)) })
                }
            }
            ListItem(overlineContent = { Text("Date") }, headlineContent = { Text(r.date?.format(MediumDate) ?: "Not found (today)") }, colors = itemColors)
            if (s.kind == ScanKind.SUBSCRIPTION) {
                ListItem(overlineContent = { Text("Billing") }, headlineContent = { Text(r.cycle?.label ?: "Monthly (assumed)") }, colors = itemColors)
                r.nextBillingDate?.let { ListItem(overlineContent = { Text("Next payment") }, headlineContent = { Text(it.format(MediumDate)) }, colors = itemColors) }
                r.trialEndDate?.let { ListItem(overlineContent = { Text("Trial ends") }, headlineContent = { Text(it.format(MediumDate)) }, colors = itemColors) }
            }
        }
        Text(
            "You can fix anything on the next screen.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // Rescans: only after a Standard scan (with an AI scanner selected, the first read already used it).
        if (s.firstEngine == ScanEngine.STANDARD) {
            // Stacked full-width so both labels always fit, even with large system fonts.
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { vm.rescan() }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.Refresh, null, Modifier.size(18.dp)); Spacer(Modifier.size(6.dp)); Text("Rescan")
                }
                if (rescan.visionFits) FilledTonalButton(
                    onClick = {
                        when (val m = rescan.vision) {
                            is ModelState.Ready -> vm.advancedRescan()
                            is ModelState.Downloading -> showMessage(
                                (if (m.paused) "Vision AI download is waiting for Wi-Fi" else "Vision AI is still downloading") +
                                    " — ${(m.fraction * 100).toInt()}%"
                            )
                            else -> askDownload = true
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Rounded.AutoAwesome, null, Modifier.size(18.dp)); Spacer(Modifier.size(6.dp)); Text("Advanced Rescan")
                }
            }
            Text(
                buildString {
                    append(if (rescan.nanoReady) "Rescan uses Gemini Nano" else "Rescan reads the photo again")
                    if (rescan.visionFits) append(" · Advanced Rescan uses Vision AI")
                    when (val m = rescan.vision) {
                        is ModelState.Downloading -> append(" (downloading ${(m.fraction * 100).toInt()}%)")
                        is ModelState.Ready -> Unit
                        else -> if (rescan.visionFits) append(" (needs a download)")
                    }
                    if (s.readWith != ScanEngine.STANDARD) append("\nShowing ${s.readWith.title}'s reading.")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onRetake, modifier = Modifier.weight(1f)) { Text("Retake") }
            Button(onClick = onContinue, modifier = Modifier.weight(1f)) { Text("Continue") }
        }
    }
}
