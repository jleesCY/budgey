package com.jlees.budgey.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jlees.budgey.data.backup.DuplicateStrategy
import com.jlees.budgey.domain.Money
import com.jlees.budgey.ui.AppViewModels
import com.jlees.budgey.ui.components.CategoryBadge
import com.jlees.budgey.ui.components.CategoryPickerSheet
import com.jlees.budgey.ui.components.EmptyState
import com.jlees.budgey.ui.components.UncategorizedBadge

@Composable
fun ImportScreen(onBack: () -> Unit, vm: ImportViewModel = viewModel(factory = AppViewModels.Factory)) {
    val state by vm.state.collectAsStateWithLifecycle()
    val localTree by vm.localTree.collectAsStateWithLifecycle()
    var pickDestination by remember { mutableStateOf(false) }
    val opener = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.load(uri) }
    val openFile = { opener.launch(arrayOf("application/zip", "application/json", "application/octet-stream", "*/*")) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Import") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                actions = { if (state.backup != null) TextButton(onClick = openFile) { Text("Other file") } },
            )
        },
        bottomBar = {
            val b = state.backup
            if (b != null) Surface(tonalElevation = 3.dp) {
                Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${state.plan.categoryIds.size} of ${b.file.categories.size} categories",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(onClick = vm::runImport, enabled = !state.importing) { Text(if (state.importing) "Importing…" else "Import") }
                }
            }
        },
    ) { padding ->
        val b = state.backup
        when {
            state.loading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { LoadingIndicator() }
            b == null -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                EmptyState(
                    Icons.Rounded.FileOpen,
                    "Import a backup",
                    "Pick a .zip exported from this app on any device. You'll choose what to bring in before anything changes.",
                ) { Button(onClick = openFile) { Text("Choose file") } }
            }
            else -> {
                val tree = b.categoryTree
                LazyColumn(contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 16.dp)) {
                    item {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                        ) {
                            Column(Modifier.padding(16.dp)) {
                                Text("Backup from ${b.file.device.ifBlank { "unknown device" }}", style = MaterialTheme.typography.titleMedium)
                                Text(b.file.exportedAt.take(10) + " · app " + b.file.appVersion, style = MaterialTheme.typography.bodySmall)
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "${b.file.categories.size} categories · ${b.file.purchases.size} purchases · ${b.file.subscriptions.size} subscriptions",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                    }
                    item {
                        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Categories & budgets", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            TextButton(onClick = { vm.selectAll(true) }) { Text("All") }
                            TextButton(onClick = { vm.selectAll(false) }) { Text("None") }
                        }
                        Text(
                            "Purchases and subscriptions come along with their category.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }
                    items(tree.flattened(), key = { it.first.id }) { (cat, depth) ->
                        val sub = tree.subtreeIds(cat.id)
                        val selectedInSub = sub.count { it in state.plan.categoryIds }
                        val toggle = when {
                            selectedInSub == 0 -> ToggleableState.Off
                            cat.id in state.plan.categoryIds && selectedInSub == sub.size -> ToggleableState.On
                            else -> ToggleableState.Indeterminate
                        }
                        val counts = state.counts[cat.id]
                        ListItem(
                            headlineContent = { Text(cat.name) },
                            supportingContent = {
                                Text(
                                    buildString {
                                        append("${counts?.purchases ?: 0} purchases")
                                        if ((counts?.subscriptions ?: 0) > 0) append(" · ${counts?.subscriptions} subs")
                                        cat.budgetCents?.let { append(" · budget ${Money.format(it)}/${cat.budgetPeriod.label.lowercase()}") }
                                    }
                                )
                            },
                            leadingContent = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    TriStateCheckbox(state = toggle, onClick = { vm.toggleCategory(cat.id) })
                                    CategoryBadge(cat, size = 36.dp)
                                }
                            },
                            modifier = Modifier.clickable { vm.toggleCategory(cat.id) }.padding(start = (depth * 20).dp),
                        )
                    }
                    item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
                    if (state.uncategorizedPurchases > 0) item {
                        CheckRow("Uncategorized purchases (${state.uncategorizedPurchases})", state.plan.includeUncategorizedPurchases) { v ->
                            vm.updatePlan { it.copy(includeUncategorizedPurchases = v) }
                        }
                    }
                    if (state.uncategorizedSubscriptions > 0) item {
                        CheckRow("Uncategorized subscriptions (${state.uncategorizedSubscriptions})", state.plan.includeUncategorizedSubscriptions) { v ->
                            vm.updatePlan { it.copy(includeUncategorizedSubscriptions = v) }
                        }
                    }
                    if (b.file.settings != null) item {
                        CheckRow("App settings (theme, chart, preferences)", state.plan.includeSettings) { v -> vm.updatePlan { it.copy(includeSettings = v) } }
                    }

                    item {
                        Text("Options", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp))
                        val dest = state.plan.destinationParentId?.let { localTree.byId[it] }
                        ListItem(
                            overlineContent = { Text("Put imported top-level categories in") },
                            headlineContent = { Text(dest?.let { localTree.pathLabel(it.id) } ?: "None — on their own") },
                            leadingContent = { if (dest != null) CategoryBadge(dest, size = 36.dp) else UncategorizedBadge(size = 36.dp) },
                            modifier = Modifier.clickable { pickDestination = true },
                        )
                        SwitchRow("Merge with my categories of the same name", state.plan.mergeCategoriesByName) { v ->
                            vm.updatePlan { it.copy(mergeCategoriesByName = v) }
                        }
                        SwitchRow("Skip purchases that look like duplicates", state.plan.skipLookalikePurchases) { v ->
                            vm.updatePlan { it.copy(skipLookalikePurchases = v) }
                        }
                        Text("If an item already exists here", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 16.dp, top = 8.dp))
                        DuplicateStrategy.entries.forEach { d ->
                            Row(
                                Modifier.fillMaxWidth().clickable { vm.setDuplicates(d) }.padding(horizontal = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(selected = state.plan.duplicates == d, onClick = { vm.setDuplicates(d) })
                                Column {
                                    Text(d.label)
                                    Text(d.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (pickDestination) {
        CategoryPickerSheet(
            tree = localTree,
            selectedId = state.plan.destinationParentId,
            title = "Import into…",
            noneLabel = "None",
            noneSupporting = "Keep them on their own, not inside another category",
            onDismiss = { pickDestination = false },
            onSelect = { id -> vm.updatePlan { it.copy(destinationParentId = id) }; pickDestination = false },
        )
    }
    state.result?.let { r ->
        AlertDialog(
            onDismissRequest = onBack,
            icon = { Icon(Icons.Rounded.CheckCircle, null) },
            title = { Text("Import complete") },
            text = {
                Text(
                    buildString {
                        appendLine("Categories: ${r.categoriesAdded} added, ${r.categoriesMerged} merged")
                        appendLine("Purchases: ${r.purchasesAdded} added, ${r.purchasesSkipped} skipped")
                        appendLine("Subscriptions: ${r.subscriptionsAdded} added, ${r.subscriptionsSkipped} skipped")
                        if (r.settingsApplied) append("Settings applied")
                    }
                )
            },
            confirmButton = { TextButton(onClick = onBack) { Text("Done") } },
        )
    }
    state.error?.let { e ->
        AlertDialog(
            onDismissRequest = vm::clearError,
            title = { Text("Import problem") },
            text = { Text(e) },
            confirmButton = { TextButton(onClick = vm::clearError) { Text("OK") } },
        )
    }
}

@Composable
private fun CheckRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Spacer(Modifier.width(8.dp))
        Text(label)
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
