package com.jlees.budgey.ui.subscriptions

import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.automirrored.rounded.Notes
import androidx.compose.material.icons.rounded.Category
import androidx.compose.material.icons.rounded.CreditCard
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.HourglassBottom
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Receipt
import androidx.compose.material3.FilledTonalButton
import com.jlees.budgey.data.db.displayName
import com.jlees.budgey.ui.components.DetailCard
import com.jlees.budgey.ui.components.DetailRow
import com.jlees.budgey.ui.components.PreviewHeader
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Autorenew
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.ui.text.style.TextOverflow
import com.jlees.budgey.data.db.SubscriptionPeriodEntity
import com.jlees.budgey.data.db.cycle
import com.jlees.budgey.ui.components.clip28
import java.time.LocalDate
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DocumentScanner
import androidx.compose.material.icons.rounded.Payments
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jlees.budgey.data.db.SubscriptionStatus
import com.jlees.budgey.domain.BillingCycle
import com.jlees.budgey.domain.CycleUnit
import com.jlees.budgey.domain.Money
import com.jlees.budgey.domain.PriceField
import com.jlees.budgey.ui.AppViewModels
import com.jlees.budgey.ui.components.AmountField
import com.jlees.budgey.ui.components.ItemIconSheet
import com.jlees.budgey.ui.components.IconSheetMode
import com.jlees.budgey.ui.components.CategoryField
import com.jlees.budgey.ui.components.ConfirmDialog
import com.jlees.budgey.ui.components.DateField
import com.jlees.budgey.ui.components.MediumDate
import com.jlees.budgey.ui.components.MerchantAvatar
import com.jlees.budgey.ui.components.ReceiptSection
import com.jlees.budgey.ui.components.ItemKindSwitch
import com.jlees.budgey.ui.components.PaymentMethodField

@Composable
fun SubscriptionEditScreen(
    onBack: () -> Unit,
    onOpenPurchase: (String) -> Unit,
    onSwitchToPurchase: () -> Unit,
    vm: SubscriptionEditViewModel = viewModel(factory = AppViewModels.Factory),
) {
    val form by vm.form.collectAsStateWithLifecycle()
    val tree by vm.tree.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    var brandPicker by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var showScanText by remember { mutableStateOf(false) }
    var customCycle by remember { mutableStateOf(false) }
    var showResume by remember { mutableStateOf(false) }
    var editingPeriod by remember { mutableStateOf<SubscriptionPeriodEntity?>(null) }
    val syncPrompt by vm.syncPrompt.collectAsStateWithLifecycle()
    val allMethods by vm.paymentMethods.collectAsStateWithLifecycle()
    // Existing subscriptions open as a read-only preview; the Edit button switches to the editor.
    var editing by rememberSaveable { mutableStateOf(vm.isNew) }
    val leaveEditing: () -> Unit = { vm.reload(); editing = false }
    BackHandler(enabled = editing && !vm.isNew) { leaveEditing() }
    // After saving an existing one, go back to its (refreshed) preview.
    val afterSave: () -> Unit = {
        if (vm.isNew) onBack() else { vm.reload(); editing = false }
        Unit
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (vm.isNew) "New subscription" else if (editing) "Edit subscription" else "Subscription") },
                navigationIcon = {
                    IconButton(onClick = { if (editing && !vm.isNew) leaveEditing() else onBack() }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, if (editing && !vm.isNew) "Stop editing" else "Back")
                    }
                },
                actions = {
                    if (!vm.isNew) IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Rounded.Delete, "Delete") }
                    if (editing) TextButton(onClick = { vm.save(afterSave) }, enabled = form.canSave) { Text("Save") }
                    else IconButton(onClick = { editing = true }) { Icon(Icons.Rounded.Edit, "Edit") }
                },
            )
        },
    ) { padding ->
        if (!editing) {
            SubscriptionPreview(
                form, tree, history, allMethods,
                receipt = form.receiptFile?.let(vm::receiptFile),
                onOpenPurchase = onOpenPurchase,
                onEdit = { editing = true },
                modifier = Modifier.padding(padding),
            )
            return@Scaffold
        }
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (vm.isNew) {
                ItemKindSwitch(isSubscription = true, onSwitch = { vm.convertToPurchase(); onSwitchToPurchase() })
            }
            if (form.fromScan) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.DocumentScanner, null)
                        Spacer(Modifier.width(12.dp))
                        Text("Filled in from your scan — please double-check.", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        if (form.scanText != null) TextButton(onClick = { showScanText = true }) { Text("Text") }
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { brandPicker = true }) {
                    MerchantAvatar(form.name.ifBlank { "?" }, form.brandKey, form.categoryId?.let { tree.byId[it] }, size = 56.dp)
                    Text("Icon", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
                Spacer(Modifier.width(12.dp))
                OutlinedTextField(
                    value = form.name, onValueChange = vm::setName, label = { Text("Service") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                    modifier = Modifier.weight(1f),
                )
            }

            PriceSection(form, vm)

            Text("Billing cycle", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BillingCycle.presets.forEach { p ->
                    FilterChip(selected = form.cycle == p && !customCycle, onClick = { customCycle = false; vm.setCycle(p) }, label = { Text(p.label) })
                }
                FilterChip(
                    selected = customCycle || form.cycle !in BillingCycle.presets,
                    onClick = { customCycle = true },
                    label = { Text("Custom") },
                )
            }
            if (customCycle || form.cycle !in BillingCycle.presets) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Every")
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(
                        value = form.cycleCount.toString(),
                        onValueChange = { v -> v.filter(Char::isDigit).toIntOrNull()?.let(vm::setCycleCount) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.width(80.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    UnitMenu(form.cycleUnit, form.cycleCount, vm::setCycleUnit)
                }
            }
            form.amountCents?.let { cents ->
                if (form.cycle != BillingCycle.MONTHLY) Text(
                    "≈ ${Money.format(form.cycle.monthlyCost(cents))} per month · ${Money.format(form.cycle.yearlyCost(cents))} per year",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            DateField("Started on (first payment)", form.anchorDate, { it?.let(vm::setAnchor) }, Modifier.fillMaxWidth())
            val pastCount by produceState(0, form.anchorDate, form.cycleUnit, form.cycleCount, form.autoLog, form.status, form.trialEndDate, form.price) {
                value = vm.pastPaymentCount()
            }
            if (vm.isNew && pastCount > 0) {
                Text(
                    "$pastCount past payment${if (pastCount == 1) "" else "s"} since the start date will be added to your purchases" +
                        (form.amountCents?.let { " (${Money.format(it * pastCount)} total)" } ?: "") + ".",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Calculate next payment automatically", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        if (!form.nextDueOverridden) "Next payment: ${form.nextDueDate.format(MediumDate)} (${dueLabel(form.nextDueDate).removePrefix("renews ")})"
                        else "Pick the next payment date yourself",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (!form.nextDueOverridden) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = !form.nextDueOverridden, onCheckedChange = vm::setAutoNextDue)
            }
            if (form.nextDueOverridden) {
                DateField("Next payment", form.nextDueDate, { it?.let(vm::setNextDue) }, Modifier.fillMaxWidth())
            }

            CategoryField(tree, form.categoryId, vm::setCategory, Modifier.fillMaxWidth())
            val suggested = form.suggestedCategoryId
            if (suggested != null && suggested != form.categoryId) {
                AssistChip(
                    onClick = { vm.setCategory(suggested) },
                    label = { Text("Suggested: ${tree.byId[suggested]?.name ?: ""}") },
                    leadingIcon = { Icon(Icons.Rounded.AutoAwesome, null, Modifier.size(18.dp)) },
                )
            }

            Text("Status", style = MaterialTheme.typography.titleSmall)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SubscriptionStatus.entries.forEach { s ->
                    FilterChip(
                        selected = form.status == s,
                        onClick = {
                            val goingLive = s == SubscriptionStatus.ACTIVE || s == SubscriptionStatus.TRIAL
                            // Turning a stopped subscription back on = resume on a date (keeps the past intact).
                            if (goingLive && vm.needsResume) showResume = true else vm.setStatus(s)
                        },
                        label = { Text(s.label) },
                    )
                }
            }
            if (form.status == SubscriptionStatus.PAUSED || form.status == SubscriptionStatus.CANCELLED) {
                DateField("Last billed / stopped on", form.endDate, { it?.let(vm::setEndDate) }, Modifier.fillMaxWidth())
                Text(
                    "Billing stops after this date. Nothing before it changes.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!vm.isNew) FilledTonalButton(onClick = { showResume = true }) {
                    Icon(Icons.Rounded.PlayArrow, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Resume on a date…")
                }
            }
            if (form.status == SubscriptionStatus.TRIAL) {
                DateField("Trial ends", form.trialEndDate, vm::setTrialEnd, Modifier.fillMaxWidth(), allowClear = true)
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Auto-log payments", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Adds a purchase on each billing date so it counts toward your budgets.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = form.autoLog, onCheckedChange = vm::setAutoLog)
            }

            val defaultDays by vm.defaultReminderDays.collectAsStateWithLifecycle()
            Text("Renewal reminder", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val options = listOf<Pair<Int?, String>>(
                    null to "Default (${daysLabel(defaultDays)})",
                    0 to "On the day", 1 to "1 day before", 3 to "3 days before", 7 to "1 week before", -1 to "Off",
                )
                options.forEach { (v, label) ->
                    FilterChip(selected = form.reminderDays == v, onClick = { vm.setReminderDays(v) }, label = { Text(label) })
                }
            }

            val methods by vm.paymentMethods.collectAsStateWithLifecycle()
            PaymentMethodField(
                methods = methods,
                selectedId = form.paymentMethodId,
                legacyName = form.paymentMethod,
                onSelect = vm::setPaymentMethodId,
                onCreate = vm::createPaymentMethod,
                importIcon = vm::importPaymentIcon,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = form.note, onValueChange = vm::setNote, label = { Text("Notes (account email, plan, how to cancel…)") },
                minLines = 2, modifier = Modifier.fillMaxWidth(),
            )
            ReceiptSection(form.receiptFile?.let(vm::receiptFile), vm::attachReceipt, vm::removeReceipt)

            PriceHistorySection(form, onEdit = { editingPeriod = it }, onAdd = {
                editingPeriod = SubscriptionPeriodEntity(
                    subscriptionId = form.id ?: "",
                    startDate = form.anchorDate.minusMonths(6),
                    endDate = form.anchorDate.minusDays(1),
                    amountCents = form.amountCents ?: 0,
                    cycleUnit = form.cycleUnit,
                    cycleCount = form.cycleCount,
                )
            })

            if (!vm.isNew) {
                HorizontalDivider()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Payment history", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "${history.count} payments · ${Money.format(history.total)} total",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    OutlinedButton(onClick = { vm.logPaymentNow() }) {
                        Icon(Icons.Rounded.Payments, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Log payment")
                    }
                }
                history.recent.forEach { p ->
                    Row(Modifier.fillMaxWidth().clickable { onOpenPurchase(p.id) }.padding(vertical = 6.dp)) {
                        Text(p.date.format(MediumDate), Modifier.weight(1f))
                        Text(Money.format(p.amountCents))
                    }
                }
            }

            Button(onClick = { vm.save(afterSave) }, enabled = form.canSave, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                Text(if (vm.isNew) "Add subscription" else "Save changes")
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (brandPicker) {
        ItemIconSheet(
            mode = IconSheetMode.MERCHANT,
            name = form.name,
            current = form.brandKey,
            category = form.categoryId?.let { tree.byId[it] },
            importImage = vm::importPaymentIcon,
            onSelect = { ref, brand -> vm.setIcon(ref, brand); brandPicker = false },
            onDismiss = { brandPicker = false },
        )
    }
    if (confirmDelete) {
        ConfirmDialog(
            title = "Delete subscription?",
            text = "Payments already logged stay in your purchases. Tip: set the status to Cancelled instead to keep its history here.",
            confirmLabel = "Delete",
            destructive = true,
            onConfirm = { vm.delete(onBack) },
            onDismiss = { confirmDelete = false },
        )
    }
    if (showResume) {
        ResumeDialog(
            currentTotal = form.price.total,
            onDismiss = { showResume = false },
            onResume = { date, total -> vm.resume(date, total); showResume = false },
        )
    }
    editingPeriod?.let { p ->
        PeriodSheet(
            initial = p,
            isNew = form.periods.none { it.id == p.id },
            currentStart = form.anchorDate,
            onSave = { vm.upsertPeriod(it); editingPeriod = null },
            onDelete = { vm.removePeriod(p.id); editingPeriod = null },
            onDismiss = { editingPeriod = null },
        )
    }

    syncPrompt?.let { p ->
        AlertDialog(
            onDismissRequest = vm::cancelSync,
            title = { Text("Update past payments?") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("This subscription already has logged payments. Apply your changes to them too?")
                    Spacer(Modifier.height(4.dp))
                    p.changes.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "\"Only future\" saves the subscription but leaves every past payment exactly as it is.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = { TextButton(onClick = { vm.answerSync(applyToPast = true) }) { Text("Update past") } },
            dismissButton = { TextButton(onClick = { vm.answerSync(applyToPast = false) }) { Text("Only future") } },
        )
    }
    if (showScanText) {
        AlertDialog(
            onDismissRequest = { showScanText = false },
            title = { Text("Recognized text") },
            text = { Text(form.scanText ?: "", Modifier.verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodySmall) },
            confirmButton = { TextButton(onClick = { showScanText = false }) { Text("Close") } },
        )
    }
}

/**
 * Three linked boxes: base price + fees & taxes = total charged.
 * Base or fees edited → total recalculates. Total edited → fees recalculate. Base is never overwritten.
 */
@Composable
private fun PriceSection(form: SubscriptionForm, vm: SubscriptionEditViewModel) {
    val p = form.price
    fun hint(f: PriceField) = if (p.derived == f && p.text(f).isNotBlank()) "Calculated" else null
    Text("Price", style = MaterialTheme.typography.titleSmall)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
        AmountField(
            p.base, { vm.setPrice(PriceField.BASE, it) }, Modifier.weight(1f),
            label = "Base price", large = false, supportingText = hint(PriceField.BASE),
        )
        Text("+", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 16.dp))
        AmountField(
            p.fees, { vm.setPrice(PriceField.FEES, it) }, Modifier.weight(1f),
            label = "Fees & taxes", large = false, supportingText = hint(PriceField.FEES),
        )
    }
    AmountField(
        p.total, { vm.setPrice(PriceField.TOTAL, it) }, Modifier.fillMaxWidth(),
        label = "Total charged ${form.cycle.perLabel}",
        supportingText = hint(PriceField.TOTAL) ?: "What's actually charged (this gets logged)",
    )
}

/** Current period + earlier periods (e.g. student pricing), with "Add earlier period". */
@Composable
private fun PriceHistorySection(form: SubscriptionForm, onEdit: (SubscriptionPeriodEntity) -> Unit, onAdd: () -> Unit) {
    HorizontalDivider()
    Text("Price history", style = MaterialTheme.typography.titleSmall)
    Text(
        "Add earlier stretches that had a different price or cycle — past payments are logged at those prices.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    val stopped = form.status == SubscriptionStatus.PAUSED || form.status == SubscriptionStatus.CANCELLED
    PeriodRow(
        title = "${form.amountCents?.let(Money::format) ?: "—"}${form.cycle.shortSuffix}",
        subtitle = "Current · from ${form.anchorDate.format(MediumDate)}" +
            (if (stopped && form.endDate != null) " to ${form.endDate.format(MediumDate)}" else " on"),
        highlight = true,
        onClick = null,
    )
    form.periods.sortedByDescending { it.startDate }.forEach { p ->
        PeriodRow(
            title = "${Money.format(p.amountCents)}${p.cycle.shortSuffix}" + if (p.label.isNotBlank()) " · ${p.label}" else "",
            subtitle = "${p.startDate.format(MediumDate)} – ${p.endDate.format(MediumDate)}",
            highlight = false,
            onClick = { onEdit(p) },
        )
    }
    OutlinedButton(onClick = onAdd) {
        Icon(Icons.Rounded.History, null, Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text("Add earlier period")
    }
}

@Composable
private fun PeriodRow(title: String, subtitle: String, highlight: Boolean, onClick: (() -> Unit)?) {
    ListItem(
        headlineContent = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingContent = {
            Icon(
                if (highlight) Icons.Rounded.Autorenew else Icons.Rounded.History, null,
                tint = if (highlight) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailingContent = if (onClick != null) { { Icon(Icons.Rounded.Edit, "Edit") } } else null,
        colors = ListItemDefaults.colors(
            containerColor = if (highlight) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        modifier = Modifier.clip28().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    )
}

/** Resume a paused/cancelled subscription on a date, optionally at a new price. */
@Composable
private fun ResumeDialog(currentTotal: String, onDismiss: () -> Unit, onResume: (LocalDate, String?) -> Unit) {
    var date by remember { mutableStateOf<LocalDate?>(LocalDate.now()) }
    var total by remember { mutableStateOf(currentTotal) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Resume subscription") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "The earlier run is kept as price history and its payments stay exactly as they are. Billing restarts on this date.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                DateField("Resumes on", date, { date = it }, Modifier.fillMaxWidth())
                AmountField(total, { total = it }, Modifier.fillMaxWidth(), label = "Total charged going forward", large = false)
            }
        },
        confirmButton = {
            TextButton(enabled = date != null, onClick = { date?.let { onResume(it, total.ifBlank { null }) } }) { Text("Resume") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Add / edit an earlier period of the subscription (its own dates, price and cycle). */
@Composable
private fun PeriodSheet(
    initial: SubscriptionPeriodEntity,
    isNew: Boolean,
    currentStart: LocalDate,
    onSave: (SubscriptionPeriodEntity) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    var label by remember { mutableStateOf(initial.label) }
    var start by remember { mutableStateOf(initial.startDate) }
    var end by remember { mutableStateOf(initial.endDate) }
    var total by remember { mutableStateOf(Money.toInput(initial.amountCents)) }
    var cycle by remember { mutableStateOf(initial.cycle) }
    val cents = Money.parse(total)
    val valid = !end.isBefore(start) && (cents ?: 0) > 0
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (isNew) "Add earlier period" else "Edit period", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                if (!isNew) IconButton(onClick = onDelete) { Icon(Icons.Rounded.Delete, "Delete period") }
            }
            OutlinedTextField(
                value = label, onValueChange = { label = it }, label = { Text("Label (optional)") },
                placeholder = { Text("e.g. Student plan") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                DateField("From", start, { it?.let { d -> start = d } }, Modifier.weight(1f))
                DateField("To", end, { it?.let { d -> end = d } }, Modifier.weight(1f))
            }
            if (end.isBefore(start)) Text("\"To\" must be on or after \"From\".", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            if (!end.isBefore(currentStart)) Text(
                "This overlaps the current period (from ${currentStart.format(MediumDate)}); the current price wins on overlapping dates.",
                color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.bodySmall,
            )
            AmountField(total, { total = it }, Modifier.fillMaxWidth(), label = "Total charged per billing", large = false)
            Text("Billing cycle", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BillingCycle.presets.forEach { p ->
                    FilterChip(selected = cycle == p, onClick = { cycle = p }, label = { Text(p.label) })
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("Cancel") }
                Button(
                    enabled = valid,
                    onClick = {
                        onSave(
                            initial.copy(
                                label = label.trim(), startDate = start, endDate = end, amountCents = cents ?: 0,
                                cycleUnit = cycle.unit, cycleCount = cycle.count,
                            )
                        )
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("Save") }
            }
            Text(
                "Changes are applied when you save the subscription.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
        }
    }
}

fun daysLabel(days: Int): String = when {
    days < 0 -> "off"
    days == 0 -> "on the day"
    days == 1 -> "1 day before"
    days == 7 -> "1 week before"
    else -> "$days days before"
}

@Composable
private fun UnitMenu(unit: CycleUnit, count: Int, onSelect: (CycleUnit) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column {
        OutlinedButton(onClick = { open = true }) { Text(if (count == 1) unit.singular else unit.plural) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            CycleUnit.entries.forEach { u ->
                DropdownMenuItem(text = { Text(u.plural) }, onClick = { onSelect(u); open = false })
            }
        }
    }
}

private val FullDate: java.time.format.DateTimeFormatter =
    java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.LONG)

/** Read-only view of a saved subscription. */
@Composable
private fun SubscriptionPreview(
    form: SubscriptionForm,
    tree: com.jlees.budgey.domain.CategoryTree,
    history: PaymentHistory,
    methods: List<com.jlees.budgey.data.db.PaymentMethodEntity>,
    receipt: java.io.File?,
    onOpenPurchase: (String) -> Unit,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (form.id == null) return // still loading
    val live = form.status == SubscriptionStatus.ACTIVE || form.status == SubscriptionStatus.TRIAL
    val method = form.paymentMethodId?.let { id -> methods.firstOrNull { it.id == id } }
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PreviewHeader(
            avatar = { MerchantAvatar(form.name.ifBlank { "?" }, form.brandKey, form.categoryId?.let { tree.byId[it] }, size = 72.dp) },
            title = form.name,
            amount = Money.format(form.amountCents ?: 0) + form.cycle.shortSuffix,
            caption = if (live) "Next payment ${form.nextDueDate.format(FullDate)}" else form.endDate?.let { "Ended ${it.format(FullDate)}" },
            chip = form.status.label,
        )
        DetailCard {
            DetailRow(Icons.Rounded.Autorenew, "Billing", form.cycle.label)
            if (form.price.base.isNotBlank() && form.price.fees.isNotBlank()) {
                DetailRow(Icons.Rounded.Payments, "Price", "${form.price.base} + ${form.price.fees} fees & taxes")
            }
            DetailRow(Icons.Rounded.Event, "Started", form.anchorDate.format(FullDate))
            form.trialEndDate?.let { DetailRow(Icons.Rounded.HourglassBottom, "Free trial ends", it.format(FullDate)) }
            DetailRow(
                Icons.Rounded.Category, "Category",
                form.categoryId?.let { tree.pathLabel(it) }?.ifBlank { null } ?: "Uncategorized",
            )
            val methodName = method?.displayName ?: form.paymentMethod.ifBlank { null }
            if (methodName != null) DetailRow(
                Icons.Rounded.CreditCard, "Paid with", methodName,
                leading = if (method != null) { { com.jlees.budgey.ui.components.PaymentMethodIcon(method, size = 24.dp) } } else null,
            )
            DetailRow(
                Icons.Rounded.Notifications, "Reminder",
                form.reminderDays.let { d ->
                    when {
                        d == null -> "App default"
                        d < 0 -> "Off"
                        d == 0 -> "On the day"
                        d == 1 -> "1 day before"
                        else -> "$d days before"
                    }
                },
            )
            DetailRow(Icons.Rounded.Payments, "Log payments automatically", if (form.autoLog) "On" else "Off")
            if (form.note.isNotBlank()) DetailRow(Icons.AutoMirrored.Rounded.Notes, "Note", form.note)
        }
        if (form.periods.isNotEmpty()) {
            Text("Earlier prices", style = MaterialTheme.typography.titleSmall)
            DetailCard {
                form.periods.sortedByDescending { it.startDate }.forEach { p ->
                    DetailRow(
                        Icons.Rounded.History,
                        "${p.startDate.format(MediumDate)} – ${p.endDate.format(MediumDate)}",
                        "${Money.format(p.amountCents)}${p.cycle.shortSuffix}" + if (p.label.isNotBlank()) " · ${p.label}" else "",
                    )
                }
            }
        }
        Text(
            "Payment history · ${history.count} payment${if (history.count == 1) "" else "s"} · ${Money.format(history.total)}",
            style = MaterialTheme.typography.titleSmall,
        )
        if (history.recent.isNotEmpty()) DetailCard {
            history.recent.forEach { p ->
                DetailRow(Icons.Rounded.Receipt, p.date.format(MediumDate), Money.format(p.amountCents), onClick = { onOpenPurchase(p.id) })
            }
        }
        receipt?.let { com.jlees.budgey.ui.components.ReceiptPreview(it) }
        FilledTonalButton(onClick = onEdit, modifier = Modifier.fillMaxWidth().height(56.dp)) {
            Icon(Icons.Rounded.Edit, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Edit subscription")
        }
        Spacer(Modifier.height(24.dp))
    }
}
