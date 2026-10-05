package com.jlees.budgey.ui.categories

import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ReceiptLong
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import com.jlees.budgey.ui.components.AddFab
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jlees.budgey.data.db.CategoryEntity
import com.jlees.budgey.data.db.cycle
import com.jlees.budgey.domain.BudgetStatus
import com.jlees.budgey.domain.Money
import com.jlees.budgey.ui.AppViewModels
import com.jlees.budgey.ui.components.BudgetProgress
import com.jlees.budgey.ui.components.CategoryBadge
import com.jlees.budgey.ui.components.CategoryPickerSheet
import com.jlees.budgey.ui.components.EmptyState
import com.jlees.budgey.ui.components.MerchantAvatar
import com.jlees.budgey.ui.components.UncategorizedBadge

@Composable
fun CategoriesScreen(
    onBack: (() -> Unit)?,
    onOpenFolder: (String?) -> Unit,
    onJumpToFolder: (String?) -> Unit,
    onViewPurchases: (categoryId: String?, uncategorized: Boolean) -> Unit,
    onOpenPurchase: (String) -> Unit,
    vm: CategoriesViewModel = viewModel(factory = AppViewModels.Factory),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val view by vm.view.collectAsStateWithLifecycle()
    var editing by rememberSaveable { mutableStateOf<Pair<CategoryEntity, Boolean>?>(null) } // entity, isNew
    var budgetEditing by rememberSaveable { mutableStateOf<CategoryEntity?>(null) }
    var pickForBudget by rememberSaveable { mutableStateOf(false) }
    var showTemplates by rememberSaveable { mutableStateOf(false) }
    var menu by rememberSaveable { mutableStateOf(false) }
    val folder = state.folder
    val atRoot = vm.folderId == null

    fun newCategory() {
        editing = CategoryEntity(name = "", parentId = folder?.id, icon = folder?.icon ?: "folder", color = folder?.color ?: 0xFF5C6BC0.toInt()) to true
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(folder?.name ?: "Budgets & Categories", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                actions = {
                    if (folder != null) IconButton(onClick = { editing = folder to false }) { Icon(Icons.Rounded.Edit, "Edit folder") }
                    IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "More") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("Add from defaults") },
                            leadingIcon = { Icon(Icons.Rounded.LibraryAdd, null) },
                            enabled = state.missingTemplates.isNotEmpty(),
                            onClick = { menu = false; showTemplates = true },
                        )
                        if (folder != null) DropdownMenuItem(
                            text = { Text("All purchases in folder") },
                            leadingIcon = { Icon(Icons.AutoMirrored.Rounded.ReceiptLong, null) },
                            onClick = { menu = false; onViewPurchases(folder.id, false) },
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            // One round + button, matching the view: Budgets → add a budget, Categories → add a category.
            if (view == CategoriesView.BUDGETS && atRoot) AddFab("Budget", onClick = { pickForBudget = true })
            else AddFab(if (folder == null) "Category" else "Sub-category", onClick = { newCategory() })
        },
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(160.dp),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 96.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            if (atRoot) item(span = { GridItemSpan(maxLineSpan) }) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    CategoriesView.entries.forEachIndexed { i, v ->
                        SegmentedButton(
                            selected = view == v,
                            onClick = { vm.view.value = v },
                            shape = SegmentedButtonDefaults.itemShape(i, CategoriesView.entries.size),
                            icon = {},
                        ) { Text(if (v == CategoriesView.FOLDERS) "Categories" else "Budgets") }
                    }
                }
            } else item(span = { GridItemSpan(maxLineSpan) }) {
                Breadcrumbs(state.path, onJump = onJumpToFolder)
            }

            if (view == CategoriesView.BUDGETS && atRoot) {
                item(span = { GridItemSpan(maxLineSpan) }) { BudgetSummary(state) }
                if (state.budgets.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                    EmptyState(Icons.Rounded.Savings, "No budgets yet", "Give any category a weekly, monthly, quarterly or yearly budget.")
                }
                items(state.budgets, key = { "b-" + it.category.id }, span = { GridItemSpan(maxLineSpan) }) { s ->
                    BudgetRow(s, state.tree.pathLabel(s.category.parentId), onClick = { budgetEditing = s.category })
                }
                return@LazyVerticalGrid
            }

            // ----- Folder view -----
            if (folder != null) item(span = { GridItemSpan(maxLineSpan) }) {
                FolderHeader(state, onEditBudget = { budgetEditing = folder }, onViewAll = { onViewPurchases(folder.id, false) })
            }
            if (state.items.isEmpty() && folder != null) item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    "No sub-categories. Tap \"New sub-category\" to nest one here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
            if (state.items.isEmpty() && folder == null && !state.loading) item(span = { GridItemSpan(maxLineSpan) }) {
                EmptyState(Icons.Rounded.Folder, "No categories", "Create your own or add the built-in defaults.") {
                    FilledTonalButton(onClick = { showTemplates = true }) { Text("Add defaults") }
                }
            }
            items(state.items, key = { it.category.id }) { item ->
                FolderCard(item, onClick = { onOpenFolder(item.category.id) }, onLongClick = { editing = item.category to false })
            }
            if (atRoot && state.uncategorizedCount > 0) item(key = "uncat") {
                UncategorizedCard(state.uncategorizedCount) { onViewPurchases(null, true) }
            }

            if (folder != null && state.subscriptionsHere.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) { SectionTitle("Subscriptions here") }
                items(state.subscriptionsHere, key = { "s-" + it.id }, span = { GridItemSpan(maxLineSpan) }) { s ->
                    ListItem(
                        headlineContent = { Text(s.name) },
                        supportingContent = { Text(s.cycle.label) },
                        leadingContent = { MerchantAvatar(s.name, s.brandKey, state.tree.byId[s.categoryId ?: ""]) },
                        trailingContent = { Text(Money.format(s.amountCents) + s.cycle.shortSuffix) },
                        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
                    )
                }
            }
            if (folder != null && state.recent.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SectionTitle("Recent purchases", Modifier.weight(1f))
                        TextButton(onClick = { onViewPurchases(folder.id, false) }) { Text("See all ${state.folderPurchaseCount}") }
                    }
                }
                items(state.recent, key = { "p-" + it.id }, span = { GridItemSpan(maxLineSpan) }) { p ->
                    val cat = p.categoryId?.let { state.tree.byId[it] }
                    ListItem(
                        headlineContent = { Text(p.merchant, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Text(p.date.toString() + (cat?.takeIf { it.id != folder.id }?.let { " · ${it.name}" } ?: "")) },
                        leadingContent = { MerchantAvatar(p.merchant, p.brandKey, cat) },
                        trailingContent = { Text(Money.format(p.amountCents), style = MaterialTheme.typography.titleSmall) },
                        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
                        modifier = Modifier.clickable { onOpenPurchase(p.id) },
                    )
                }
            }
        }
    }

    editing?.let { (cat, isNew) ->
        val deleteAction: () -> Unit = {
            vm.delete(cat.id)
            editing = null
            if (cat.id == folder?.id) onBack?.invoke()
        }
        CategoryEditSheet(
            initial = cat,
            isNew = isNew,
            tree = state.tree,
            onSave = { vm.save(it); editing = null },
            onDelete = if (isNew) null else deleteAction,
            onDismiss = { editing = null },
        )
    }
    budgetEditing?.let { cat ->
        CategoryEditSheet(
            initial = cat,
            isNew = false,
            tree = state.tree,
            budgetOnly = true,
            onSave = { vm.setBudget(cat, it.budgetCents, it.budgetPeriod); budgetEditing = null },
            onDelete = null,
            onDismiss = { budgetEditing = null },
        )
    }
    if (pickForBudget) {
        CategoryPickerSheet(
            tree = state.tree,
            selectedId = null,
            allowNone = false,
            title = "Budget which category?",
            onDismiss = { pickForBudget = false },
            onSelect = { id -> pickForBudget = false; budgetEditing = id?.let { state.tree.byId[it] } },
        )
    }
    if (showTemplates) {
        TemplatePicker(state, onDismiss = { showTemplates = false }, onAdd = { vm.addTemplates(it); showTemplates = false })
    }
}

@Composable
private fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = modifier.padding(top = 8.dp))
}

@Composable
private fun Breadcrumbs(path: List<CategoryEntity>, onJump: (String?) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { onJump(null) }) { Text("All") }
        path.forEachIndexed { i, c ->
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            if (i == path.lastIndex) Text(c.name, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 12.dp))
            else TextButton(onClick = { onJump(c.id) }) { Text(c.name) }
        }
    }
}

@Composable
private fun FolderHeader(state: CategoriesUiState, onEditBudget: () -> Unit, onViewAll: () -> Unit) {
    val folder = state.folder ?: return
    Card(
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CategoryBadge(folder, size = 56.dp)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(Money.format(state.folderSpentThisMonth), style = MaterialTheme.typography.headlineMedium)
                    Text("spent this month", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(16.dp))
            val status = state.folderStatus
            if (status != null) {
                Text("${folder.budgetPeriod.label} budget", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(6.dp))
                BudgetProgress(status)
            }
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(
                    onClick = onEditBudget,
                    label = { Text(if (status == null) "Set budget" else "Edit budget") },
                    leadingIcon = { Icon(Icons.Rounded.Savings, null, Modifier.size(18.dp)) },
                )
                AssistChip(
                    onClick = onViewAll,
                    label = { Text("${state.folderPurchaseCount} purchases") },
                    leadingIcon = { Icon(Icons.AutoMirrored.Rounded.ReceiptLong, null, Modifier.size(18.dp)) },
                )
            }
        }
    }
}

@Composable
private fun FolderCard(item: FolderItem, onClick: () -> Unit, onLongClick: () -> Unit) {
    val c = item.category
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier
            .fillMaxWidth()
            .clip28Combined(onClick, onLongClick),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                CategoryBadge(c, size = 48.dp)
                Spacer(Modifier.weight(1f))
                if (item.childCount > 0) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Folder, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(2.dp))
                        Text("${item.childCount}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(c.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                Money.format(item.spent) + if (item.status == null) " this month" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            item.status?.let { s ->
                Spacer(Modifier.height(8.dp))
                BudgetProgress(s, showLabels = false)
                Spacer(Modifier.height(4.dp))
                Text(
                    com.jlees.budgey.ui.components.budgetStatusWords(s),
                    style = MaterialTheme.typography.labelSmall,
                    color = com.jlees.budgey.ui.components.budgetTextColor(s),
                )
            }
        }
    }
}

@Composable
private fun UncategorizedCard(count: Int, onClick: () -> Unit) {
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
        modifier = Modifier.fillMaxWidth().clip28Combined(onClick, onClick),
    ) {
        Column(Modifier.padding(16.dp)) {
            UncategorizedBadge(size = 48.dp)
            Spacer(Modifier.height(12.dp))
            Text("Uncategorized", style = MaterialTheme.typography.titleMedium)
            Text("$count to sort", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onTertiaryContainer)
        }
    }
}

@Composable
private fun BudgetSummary(state: CategoriesUiState) {
    Card(
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp)) {
            Text("This month", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
            Text(
                "${Money.format(state.monthlySpentTotal)} of ${Money.format(state.monthlyBudgetTotal)}",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            val over = state.budgets.count { it.over }
            val ahead = state.budgets.count { it.aheadOfPace }
            Text(
                buildString {
                    append("${state.budgets.size} budgets")
                    if (over > 0) append(" · $over over")
                    if (ahead > 0) append(" · $ahead ahead of pace")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

@Composable
private fun BudgetRow(s: BudgetStatus, parentPath: String, onClick: () -> Unit) {
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth().clip28Combined(onClick, onClick),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CategoryBadge(s.category, size = 40.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(s.category.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        listOfNotNull(parentPath.ifEmpty { null }, s.category.budgetPeriod.label).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text("${(s.fraction * 100).toInt()}%", style = MaterialTheme.typography.titleMedium, color = com.jlees.budgey.ui.components.budgetTextColor(s))
            }
            Spacer(Modifier.height(12.dp))
            BudgetProgress(s)
        }
    }
}

@Composable
private fun TemplatePicker(state: CategoriesUiState, onDismiss: () -> Unit, onAdd: (Set<String>) -> Unit) {
    var selected by rememberSaveable { mutableStateOf(state.missingTemplates.map { it.key }.toSet()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add default categories") },
        text = {
            Column(Modifier.height(400.dp).verticalScroll(rememberScrollState())) {
                state.missingTemplates.forEach { t ->
                    Row(
                        Modifier.fillMaxWidth().clickable { selected = if (t.key in selected) selected - t.key else selected + t.key },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = t.key in selected, onCheckedChange = null)
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(t.name)
                            if (t.children.isNotEmpty()) Text(
                                t.children.joinToString { it.name },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(enabled = selected.isNotEmpty(), onClick = { onAdd(selected) }) { Text("Add ${selected.size}") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun Modifier.clip28Combined(onClick: () -> Unit, onLongClick: () -> Unit): Modifier =
    this.clip(RoundedCornerShape(28.dp)).combinedClickable(
        onClick = onClick, onClickLabel = "Open", onLongClick = onLongClick, onLongClickLabel = "Edit category",
    )
