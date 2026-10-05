package com.jlees.budgey.ui.purchases

import androidx.compose.runtime.saveable.rememberSaveable
import com.jlees.budgey.ui.components.appear
import com.jlees.budgey.ui.components.disappear
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.ui.text.style.TextAlign
import com.jlees.budgey.ui.components.ChartSlice
import com.jlees.budgey.ui.components.ChartTypeButton
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jlees.budgey.data.ChartType
import com.jlees.budgey.data.db.PurchaseEntity
import com.jlees.budgey.data.db.PurchaseSource
import com.jlees.budgey.domain.DatePreset
import com.jlees.budgey.domain.Money
import com.jlees.budgey.ui.AppViewModels
import com.jlees.budgey.ui.components.AddFabMenu
import com.jlees.budgey.ui.components.CategoryPickerSheet
import com.jlees.budgey.ui.components.EmptyState
import com.jlees.budgey.ui.components.MerchantAvatar
import com.jlees.budgey.ui.components.PaymentMethodIcon
import com.jlees.budgey.data.db.displayName
import com.jlees.budgey.ui.components.SpendingChart
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter


/** Where the main Purchases tab can jump to (the shortcuts that used to live on Home). */
class PurchasesLinks(
    val openSubscription: (String) -> Unit,
    val subscriptionsTab: () -> Unit,
    val budgetsTab: () -> Unit,
    val calendarTab: () -> Unit,
)

@Composable
fun PurchasesScreen(
    onBack: (() -> Unit)?,
    onAdd: (categoryId: String?) -> Unit,
    onOpen: (String) -> Unit,
    onScan: () -> Unit,
    links: PurchasesLinks,
    /** Reopen the unfinished new purchase (null = nothing to resume). */
    onResume: (() -> Unit)? = null,
    vm: PurchasesViewModel = viewModel(factory = AppViewModels.Factory),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val selection by vm.selection.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val f = state.filter
    var showFilters by rememberSaveable { mutableStateOf(false) }
    var showSearch by rememberSaveable { mutableStateOf(query.isNotBlank()) }
    var showBulkCategory by rememberSaveable { mutableStateOf(false) }
    var showCategoryFilter by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val inSelection = selection.isNotEmpty()
    // Back clears the selection first instead of leaving the screen.
    androidx.activity.compose.BackHandler(enabled = inSelection) { vm.clearSelection() }

    val title = when {
        f.categoryIds.size == 1 && vm.isScoped -> state.tree.byId[f.categoryIds.first()]?.name ?: "Purchases"
        f.uncategorizedOnly && vm.isScoped -> "Uncategorized"
        f.paymentMethodIds.size == 1 && vm.isScoped ->
            state.paymentMethods.firstOrNull { it.id == f.paymentMethodIds.first() }?.displayName ?: "Purchases"
        else -> "Purchases"
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            if (inSelection) {
                TopAppBar(
                    title = { Text("${selection.size} selected") },
                    navigationIcon = { IconButton(onClick = vm::clearSelection) { Icon(Icons.Rounded.Close, "Clear selection") } },
                    actions = {
                        IconButton(onClick = { showBulkCategory = true }) { Icon(Icons.Rounded.DriveFileMove, "Set category") }
                        IconButton(onClick = {
                            val all = state.groups.flatMap { it.items }
                            val batch = vm.deleteSelected(all)
                            val n = batch.size
                            scope.launch {
                                var undone = false
                                try {
                                    val r = snackbar.showSnackbar("Deleted $n purchase${if (n == 1) "" else "s"}", "Undo", withDismissAction = true)
                                    if (r == SnackbarResult.ActionPerformed) { vm.undoDelete(batch); undone = true }
                                } finally {
                                    // No undo any more: free their receipt photos now.
                                    if (!undone) vm.forgetDeleted(batch)
                                }
                            }
                        }) { Icon(Icons.Rounded.Delete, "Delete") }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                )
            } else {
                TopAppBar(
                    title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = { if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                    actions = {
                        IconButton(onClick = { showSearch = !showSearch; if (!showSearch) vm.setQuery("") }) {
                            Icon(if (showSearch) Icons.Rounded.SearchOff else Icons.Rounded.Search, "Search")
                        }
                        IconButton(onClick = { showFilters = true }) {
                            BadgedBox(badge = { if (f.activeCount > 0) Badge { Text("${f.activeCount}") } }) {
                                Icon(Icons.Rounded.Tune, "Filters")
                            }
                        }
                    },
                    scrollBehavior = scrollBehavior,
                )
            }
        },
        floatingActionButton = {
            if (!inSelection) AddFabMenu(
                label = "Purchase",
                onManual = { onAdd(f.categoryIds.singleOrNull()) },
                onScan = onScan,
                onResume = onResume,
            )
        },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 120.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(key = "search") {
                AnimatedVisibility(showSearch, enter = appear(), exit = disappear()) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = vm::setQuery,
                        placeholder = { Text("Merchant, note, category, amount…") },
                        leadingIcon = { Icon(Icons.Rounded.Search, null) },
                        trailingIcon = {
                            if (query.isNotEmpty()) IconButton(onClick = { vm.setQuery("") }) { Icon(Icons.Rounded.Clear, "Clear search") }
                        },
                        singleLine = true,
                        shape = CircleShape,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
            }
            item(key = "chart", contentType = "chart") {
                HeroChart(
                    state = state,
                    onType = vm::setChartType,
                    onSlice = vm::drillInto,
                    onDrillUp = vm::drillUp,
                )
            }
            item(key = "chips") {
                QuickFilterRow(
                    state = state,
                    onPreset = { p -> if (p == DatePreset.CUSTOM) showFilters = true else vm.updateFilter { it.copy(datePreset = p) } },
                    onPickCategory = { showCategoryFilter = true },
                    onClearCategory = { id -> vm.updateFilter { it.copy(categoryIds = it.categoryIds - id) } },
                    onClearUncategorized = { vm.updateFilter { it.copy(uncategorizedOnly = false) } },
                    onClearAll = { vm.clearFilters() },
                )
            }
            if (state.settings.nudgeUncategorized && state.uncategorizedCount > 0 && !f.uncategorizedOnly) {
                item(key = "nudge") {
                    UncategorizedNudge(state.uncategorizedCount) {
                        vm.updateFilter { it.copy(uncategorizedOnly = true, categoryIds = emptySet(), datePreset = DatePreset.ALL) }
                    }
                }
            }
            val o = state.overview
            if (!vm.isScoped && o.trialsEndingSoon.isNotEmpty()) item(key = "trials") {
                Card(
                    onClick = { links.openSubscription(o.trialsEndingSoon.first().id) },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.HourglassBottom, null, tint = MaterialTheme.colorScheme.onErrorContainer)
                        Spacer(Modifier.width(12.dp))
                        // Ended trials (now being charged) come first; see the Subscriptions tab to keep or cancel.
                        val ended = o.trialsEndingSoon.filter { com.jlees.budgey.domain.Renewals.trialEnded(it, java.time.LocalDate.now()) }
                        val soon = o.trialsEndingSoon - ended.toSet()
                        Text(
                            listOfNotNull(
                                ended.takeIf { it.isNotEmpty() }?.let { l -> "Free trial ended: " + l.joinToString { it.name } },
                                soon.takeIf { it.isNotEmpty() }?.let { l -> "Free trial ending soon: " + l.joinToString { it.name } },
                            ).joinToString(" · "),
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            if (!vm.isScoped) item(key = "shortcuts", contentType = "shortcuts") {
                Shortcuts(state, links)
            }
            if (!state.loading && state.count == 0) {
                item(key = "empty") {
                    EmptyState(
                        icon = Icons.Rounded.ReceiptLong,
                        title = when {
                            state.canClear -> "Nothing matches"
                            f.datePreset != DatePreset.ALL -> "Quiet ${state.rangeLabel}"
                            else -> "Your nest is empty"
                        },
                        body = if (state.canClear) "Try loosening your filters." else "Tap + to scan a receipt or add one by hand.",
                        action = if (state.canClear || f.datePreset != DatePreset.ALL) {
                            { FilledTonalButton(onClick = { vm.clearFilters(allTime = true) }) { Text(if (state.canClear) "Clear filters" else "Show all time") } }
                        } else null,
                    )
                }
            }
            state.groups.forEach { group ->
                if (group.date != LocalDate.MIN) {
                    stickyHeader(key = "h-${group.date}", contentType = "day-header") { DayHeader(group.date, group.total) }
                }
                items(group.items, key = { it.id }, contentType = { "purchase" }) { p ->
                    PurchaseRow(
                        p = p,
                        category = p.categoryId?.let { state.tree.byId[it] },
                        method = p.paymentMethodId?.let { state.methodsById[it] },
                        selected = p.id in selection,
                        onClick = { if (inSelection) vm.toggleSelect(p.id) else onOpen(p.id) },
                        onLongClick = { vm.toggleSelect(p.id) },
                        modifier = Modifier.animateItem(),
                    )
                }
            }
        }
    }

    if (showFilters) {
        FilterSheet(
            initial = f.copy(query = query),
            base = vm.baseFilter,
            tree = state.tree,
            paymentMethods = state.paymentMethods,
            onApply = { vm.setFilter(it); showFilters = false },
            onDismiss = { showFilters = false },
        )
    }
    if (showCategoryFilter) {
        CategoryPickerSheet(
            tree = state.tree,
            selectedId = f.categoryIds.singleOrNull(),
            title = "Filter by category",
            noneLabel = "Uncategorized only",
            onDismiss = { showCategoryFilter = false },
            onSelect = { id ->
                vm.updateFilter {
                    if (id == null) it.copy(uncategorizedOnly = true, categoryIds = emptySet())
                    else it.copy(categoryIds = it.categoryIds + id, uncategorizedOnly = false)
                }
                showCategoryFilter = false
            },
        )
    }
    if (showBulkCategory) {
        CategoryPickerSheet(
            tree = state.tree,
            selectedId = null,
            title = "Move ${selection.size} to…",
            onDismiss = { showBulkCategory = false },
            onSelect = { vm.categorizeSelected(it); showBulkCategory = false },
        )
    }
}

@Composable
private fun QuickFilterRow(
    state: PurchasesUiState,
    onPreset: (DatePreset) -> Unit,
    onPickCategory: () -> Unit,
    onClearCategory: (String) -> Unit,
    onClearUncategorized: () -> Unit,
    onClearAll: () -> Unit,
) {
    val f = state.filter
    var presetMenu by rememberSaveable { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            FilterChip(
                selected = true,
                onClick = { presetMenu = true },
                label = { Text(f.datePreset.label) },
                leadingIcon = { Icon(Icons.Rounded.DateRange, null, Modifier.size(18.dp)) },
                trailingIcon = { Icon(Icons.Rounded.ArrowDropDown, null, Modifier.size(18.dp)) },
            )
            DropdownMenu(expanded = presetMenu, onDismissRequest = { presetMenu = false }) {
                DatePreset.entries.forEach { p ->
                    DropdownMenuItem(text = { Text(p.label) }, onClick = { presetMenu = false; onPreset(p) })
                }
            }
        }
        f.categoryIds.forEach { id ->
            val c = state.tree.byId[id]
            InputChip(
                selected = true,
                onClick = { onClearCategory(id) },
                label = { Text(c?.name ?: "Deleted category") },
                trailingIcon = { Icon(Icons.Rounded.Close, "Remove", Modifier.size(18.dp)) },
            )
        }
        if (f.uncategorizedOnly) InputChip(
            selected = true,
            onClick = onClearUncategorized,
            label = { Text("Uncategorized") },
            trailingIcon = { Icon(Icons.Rounded.Close, "Remove", Modifier.size(18.dp)) },
        )
        FilterChip(
            selected = false,
            onClick = onPickCategory,
            label = { Text(if (f.categoryIds.isEmpty()) "Category" else "Add category") },
            leadingIcon = { Icon(Icons.Rounded.Folder, null, Modifier.size(18.dp)) },
        )
        if (state.canClear) TextButton(onClick = onClearAll) { Text("Clear all") }
    }
}

@Composable
private fun UncategorizedNudge(count: Int, onReview: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Lightbulb, null, tint = MaterialTheme.colorScheme.onTertiaryContainer)
            Spacer(Modifier.width(12.dp))
            Text(
                "$count purchase${if (count == 1) " needs" else "s need"} a category so budgets stay accurate.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onReview) { Text("Review") }
        }
    }
}

@Composable
private fun DayHeader(date: LocalDate, total: Long) {
    val label = com.jlees.budgey.ui.components.dayLabel(date)
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
        Text(Money.format(total), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun PurchaseRow(
    p: PurchaseEntity,
    category: com.jlees.budgey.data.db.CategoryEntity?,
    method: com.jlees.budgey.data.db.PaymentMethodEntity?,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PurchaseListItem(
        p = p,
        category = category,
        method = method,
        onClick = onClick,
        onLongClick = onLongClick,
        selected = selected,
        modifier = modifier,
    )
}

/** Large, centered chart with the total spent right underneath it. */
@Composable
private fun HeroChart(
    state: PurchasesUiState,
    onType: (ChartType) -> Unit,
    onSlice: (ChartSlice) -> Unit,
    onDrillUp: () -> Unit,
) {
    val o = state.overview
    val f = state.filter
    // Month-to-date comparisons only make sense for the plain "this month, everything" view.
    val plainMonth = f.datePreset == DatePreset.THIS_MONTH && f.activeCount == 0 && state.chartParent == null
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (state.chartParent != null) {
                IconButton(onClick = onDrillUp) { Icon(Icons.Rounded.ArrowUpward, "Up one level") }
            } else Spacer(Modifier.size(48.dp))
            Text(
                state.chartParent?.let { state.tree.pathLabel(it) } ?: "",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            ChartTypeButton(state.settings.chartType, onType)
        }
        SpendingChart(
            type = state.settings.chartType,
            slices = state.slices,
            daily = state.daily,
            caption = "No spending ${state.rangeLabel}",
            onSliceClick = onSlice,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            Money.format(state.total),
            style = MaterialTheme.typography.displayMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            textAlign = TextAlign.Center,
        )
        Text(
            "spent ${state.rangeLabel} · ${state.count} purchase${if (state.count == 1) "" else "s"}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        if (plainMonth && !state.loading) {
            o.deltaPercent?.let { d ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                    Icon(
                        if (d > 0) Icons.Rounded.TrendingUp else Icons.Rounded.TrendingDown, null, Modifier.size(18.dp),
                        tint = if (d > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "${if (d > 0) "+" else ""}$d% vs. same point last month",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (o.monthlyBudget > 0) {
                Spacer(Modifier.height(12.dp))
                val fraction = (o.budgetedSpent.toFloat() / o.monthlyBudget).coerceIn(0f, 1f)
                LinearWavyProgressIndicator(
                    progress = { fraction },
                    color = if (o.budgetRemaining < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.fillMaxWidth(0.8f),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    if (o.budgetRemaining >= 0)
                        "${Money.format(o.budgetRemaining)} left of ${Money.format(o.monthlyBudget)} · about ${Money.format(o.perDayLeft)}/day"
                    else "${Money.format(-o.budgetRemaining)} over your ${Money.format(o.monthlyBudget)} monthly budgets",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

private val ShortcutWidth = 220.dp
private val ShortcutHeight = 168.dp

/**
 * Jump-off cards to the other tabs (what the Home tab used to hold). Fixed size, so they never
 * squash while scrolling sideways.
 */
@Composable
private fun Shortcuts(state: PurchasesUiState, links: PurchasesLinks) {
    val o = state.overview
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "renewals") {
            ShortcutCard("Upcoming renewals", Icons.Rounded.Autorenew, links.subscriptionsTab) {
                if (o.upcoming.isEmpty()) {
                    ShortcutEmpty("Nothing in the next 2 weeks")
                } else {
                    Text(
                        "${Money.format(o.upcomingTotal)} in 2 weeks",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(6.dp))
                    o.upcoming.take(3).forEach { u ->
                        Row(
                            Modifier.fillMaxWidth().clip(CircleShape).clickable { links.openSubscription(u.subscription.id) }.padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            MerchantAvatar(u.subscription.name, u.subscription.brandKey, u.subscription.categoryId?.let { state.tree.byId[it] }, size = 22.dp)
                            Spacer(Modifier.width(6.dp))
                            Text(u.subscription.name, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(shortDue(u.date, state.today), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        }
                    }
                }
            }
        }
        item(key = "budgets") {
            ShortcutCard("Budgets", Icons.Rounded.Savings, links.budgetsTab) {
                if (o.budgets.isEmpty()) {
                    ShortcutEmpty("Give a category a budget to track it here")
                } else {
                    Text(
                        "${o.budgetCount} budget${if (o.budgetCount == 1) "" else "s"} · closest to the limit",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(6.dp))
                    o.budgets.take(3).forEach { b ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(b.category.name, Modifier.width(76.dp), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.width(6.dp))
                            LinearProgressIndicator(
                                progress = { b.fraction.coerceIn(0f, 1f) },
                                color = if (b.over) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("${(b.fraction * 100).toInt()}%", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        item(key = "week") {
            ShortcutCard("Last 7 days", Icons.Rounded.CalendarMonth, links.calendarTab) {
                Text(
                    Money.format(o.week.sumOf { it.second }),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                WeekBars(o.week, state.today, Modifier.fillMaxWidth().weight(1f))
            }
        }
    }
}

@Composable
private fun ShortcutCard(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Card(
        onClick = onClick,
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.width(ShortcutWidth).height(ShortcutHeight),
    ) {
        Column(Modifier.fillMaxSize().padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(6.dp))
                Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(2.dp))
            content()
        }
    }
}

@Composable
private fun ShortcutEmpty(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
}

private fun shortDue(date: LocalDate, today: LocalDate): String {
    val days = java.time.temporal.ChronoUnit.DAYS.between(today, date)
    return when (days) {
        0L -> "today"
        1L -> "tmrw"
        else -> "${days}d"
    }
}

@Composable
private fun WeekBars(week: List<Pair<LocalDate, Long>>, today: LocalDate, modifier: Modifier) {
    val max = (week.maxOfOrNull { it.second } ?: 0L).coerceAtLeast(1L)
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        week.forEach { (day, cents) ->
            val isToday = day == today
            Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                androidx.compose.foundation.layout.Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
                    val frac = (cents.toFloat() / max).coerceIn(0f, 1f)
                    androidx.compose.foundation.layout.Box(
                        Modifier
                            .fillMaxWidth()
                            .fillMaxHeight(if (cents > 0) maxOf(frac, 0.06f) else 0.04f)
                            .clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = if (isToday) 1f else if (cents > 0) 0.45f else 0.15f))
                    )
                }
                Text(
                    day.dayOfWeek.getDisplayName(java.time.format.TextStyle.NARROW, java.util.Locale.getDefault()),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                    color = if (isToday) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
