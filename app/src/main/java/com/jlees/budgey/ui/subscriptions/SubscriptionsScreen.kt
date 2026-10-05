package com.jlees.budgey.ui.subscriptions

import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Autorenew
import androidx.compose.material.icons.rounded.HourglassBottom
import androidx.compose.material.icons.rounded.Sort
import androidx.compose.material.icons.rounded.Subscriptions
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.rounded.NotificationsActive
import com.jlees.budgey.ui.components.rememberNotificationPermission
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jlees.budgey.data.db.SubscriptionEntity
import com.jlees.budgey.data.db.SubscriptionStatus
import com.jlees.budgey.data.db.cycle
import com.jlees.budgey.data.db.monthlyCents
import com.jlees.budgey.domain.Money
import com.jlees.budgey.ui.AppViewModels
import com.jlees.budgey.ui.components.AddFabMenu
import com.jlees.budgey.ui.components.EmptyState
import com.jlees.budgey.ui.components.MediumDate
import com.jlees.budgey.ui.components.MerchantAvatar
import java.time.LocalDate
import androidx.compose.material.icons.rounded.Warning
import com.jlees.budgey.domain.Renewals
import java.time.temporal.ChronoUnit

fun dueLabel(date: LocalDate, today: LocalDate = LocalDate.now()): String {
    val days = ChronoUnit.DAYS.between(today, date)
    return when {
        days < 0 -> "overdue since ${date.format(MediumDate)}"
        days == 0L -> "renews today"
        days == 1L -> "renews tomorrow"
        days < 14 -> "renews in $days days"
        else -> "renews ${date.format(MediumDate)}"
    }
}

@Composable
fun SubscriptionsScreen(
    onAdd: () -> Unit,
    onOpen: (String) -> Unit,
    onScan: () -> Unit,
    /** Reopen the unfinished new subscription (null = nothing to resume). */
    onResume: (() -> Unit)? = null,
    vm: SubscriptionsViewModel = viewModel(factory = AppViewModels.Factory),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var sortMenu by rememberSaveable { mutableStateOf(false) }
    val permission = rememberNotificationPermission()
    // Ask the first time reminders matter: you've got a subscription and reminders are on.
    com.jlees.budgey.ui.components.AskForNotificationsOnce(state.remindersOn && state.liveCount > 0, permission)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Subscriptions") },
                actions = {
                    IconButton(onClick = { sortMenu = true }) { Icon(Icons.Rounded.Sort, "Sort") }
                    DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                        SubSort.entries.forEach { s ->
                            DropdownMenuItem(text = { Text(s.label) }, onClick = { vm.sort.value = s; sortMenu = false })
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            AddFabMenu(label = "Subscription", onManual = onAdd, onScan = onScan, onResume = onResume)
        },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 120.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(key = "summary") { SummaryCard(state) }

            if (state.remindersOn && !permission.granted && state.liveCount > 0) item(key = "notif") {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                ) {
                    Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.NotificationsActive, null)
                        Spacer(Modifier.width(12.dp))
                        Text("Allow notifications to get renewal reminders.", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = permission::request) { Text("Allow") }
                    }
                }
            }

            // Free trials that are over: they're being charged now. One card each, until you decide.
            items(state.trialsEnded, key = { "trial-ended-" + it.id }) { s ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                ) {
                    Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 16.dp, bottom = 4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Warning, null, tint = MaterialTheme.colorScheme.onErrorContainer)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f).padding(end = 8.dp)) {
                                val ended = s.trialEndDate
                                Text(
                                    if (ended == LocalDate.now()) "${s.name}'s free trial ends today" else "${s.name}'s free trial has ended",
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                Text(
                                    (if (ended != null && ended != LocalDate.now()) "Ended ${ended.format(MediumDate)}. " else "") +
                                        "You're now being charged ${Money.format(s.amountCents)}${s.cycle.shortSuffix}.",
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            // Cancelling needs a stop date and maybe a refund note: the editor handles that.
                            TextButton(onClick = { onOpen(s.id) }) { Text("Cancel it…", color = MaterialTheme.colorScheme.onErrorContainer) }
                            TextButton(onClick = { vm.keepAfterTrial(s.id) }) { Text("Keep it", color = MaterialTheme.colorScheme.onErrorContainer) }
                        }
                    }
                }
            }

            if (state.trialsEndingSoon.isNotEmpty()) item(key = "trials") {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.HourglassBottom, null, tint = MaterialTheme.colorScheme.onErrorContainer)
                        Spacer(Modifier.width(12.dp))
                        Text(
                            "Free trial ending soon: " + state.trialsEndingSoon.joinToString { it.name + " (" + (it.trialEndDate?.format(MediumDate) ?: "") + ")" },
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }

            if (state.upcoming.isNotEmpty()) {
                item(key = "upcoming-title") {
                    Text("Coming up", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 8.dp))
                }
                item(key = "upcoming") { UpcomingRow(state.upcoming, state, onOpen) }
            }

            item(key = "tabs") {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SubTab.entries.forEach { t ->
                        FilterChip(
                            selected = state.tab == t,
                            onClick = { vm.tab.value = t },
                            label = { Text("${t.label} ${state.counts[t] ?: 0}") },
                        )
                    }
                }
            }

            if (!state.loading && state.list.isEmpty()) item(key = "empty") {
                EmptyState(
                    Icons.Rounded.Subscriptions,
                    if (state.tab == SubTab.ACTIVE) "No subscriptions yet" else "Nothing here",
                    "Add one manually, or scan a confirmation email / app-store screenshot.",
                )
            }
            items(state.list, key = { it.id }) { s ->
                SubscriptionRow(s, state, onClick = { onOpen(s.id) }, modifier = Modifier.animateItem())
            }
        }
    }
}

@Composable
private fun SummaryCard(state: SubscriptionsUiState) {
    Card(
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(Modifier.padding(24.dp)) {
            Text("Per month", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
            Text(Money.format(state.monthlyTotal), style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
            Spacer(Modifier.height(4.dp))
            Text(
                "${Money.format(state.yearlyTotal)} per year · ${state.liveCount} active",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

/**
 * Fixed-size cards in a plain horizontal list: every card is always shown whole (no squeezing
 * like the M3 carousel does while scrolling), and each carries the full set of details.
 */
@Composable
private fun UpcomingRow(items: List<SubscriptionEntity>, state: SubscriptionsUiState, onOpen: (String) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        items(items, key = { it.id }) { s ->
            val cat = s.categoryId?.let { state.tree.byId[it] }
            Card(
                onClick = { onOpen(s.id) },
                shape = MaterialTheme.shapes.extraLarge,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                modifier = Modifier.width(176.dp).height(184.dp),
            ) {
                Column(Modifier.fillMaxSize().padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        MerchantAvatar(s.name, s.brandKey, cat, size = 40.dp)
                        Spacer(Modifier.weight(1f))
                        if (s.status == SubscriptionStatus.TRIAL) {
                            Text(
                                "Trial",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                                modifier = Modifier
                                    .background(MaterialTheme.colorScheme.tertiaryContainer, CircleShape)
                                    .padding(horizontal = 8.dp, vertical = 2.dp),
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(s.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        Money.format(s.amountCents) + s.cycle.shortSuffix,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        dueLabel(s.nextDueDate).replaceFirstChar { it.uppercase() },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        s.nextDueDate.format(MediumDate) + (cat?.let { " · " + it.name } ?: ""),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun SubscriptionRow(s: SubscriptionEntity, state: SubscriptionsUiState, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val cat = s.categoryId?.let { state.tree.byId[it] }
    val inactive = s.status == SubscriptionStatus.PAUSED || s.status == SubscriptionStatus.CANCELLED
    ListItem(
        headlineContent = { Text(s.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Column {
                Text(
                    when (s.status) {
                        SubscriptionStatus.PAUSED -> "Paused"
                        SubscriptionStatus.CANCELLED -> "Cancelled"
                        SubscriptionStatus.TRIAL -> s.trialEndDate.let { end ->
                            when {
                                end == null -> "Free trial"
                                !end.isAfter(LocalDate.now()) -> "Trial ended ${end.format(MediumDate)} · now paid"
                                else -> "Trial until ${end.format(MediumDate)} · then ${Money.format(s.amountCents)}${s.cycle.shortSuffix}"
                            }
                        }
                        SubscriptionStatus.ACTIVE -> dueLabel(s.nextDueDate)
                    },
                    color = when {
                        Renewals.trialEnded(s, LocalDate.now()) -> MaterialTheme.colorScheme.error
                        s.status == SubscriptionStatus.TRIAL -> MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (cat != null) Text(cat.name, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        leadingContent = { MerchantAvatar(s.name, s.brandKey, cat) },
        trailingContent = {
            Column(horizontalAlignment = Alignment.End) {
                Text(Money.format(s.amountCents) + s.cycle.shortSuffix, style = MaterialTheme.typography.titleSmall)
                if (s.cycle.unit != com.jlees.budgey.domain.CycleUnit.MONTH || s.cycle.count != 1) {
                    Text("≈ ${Money.format(s.monthlyCents)}/mo", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (s.autoLog && !inactive) Icon(Icons.Rounded.Autorenew, "Auto-logged", Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
            }
        },
        colors = ListItemDefaults.colors(containerColor = if (inactive) MaterialTheme.colorScheme.surfaceContainerLowest else Color.Transparent),
        modifier = modifier.clickable(onClick = onClick),
    )
}
