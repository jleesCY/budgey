package com.jlees.budgey.ui.purchases

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Button
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jlees.budgey.data.db.PurchaseSource
import com.jlees.budgey.data.db.PaymentMethodEntity
import com.jlees.budgey.data.db.displayName
import com.jlees.budgey.ui.components.PaymentMethodIcon
import com.jlees.budgey.domain.AmountKind
import com.jlees.budgey.domain.CategoryTree
import com.jlees.budgey.domain.DatePreset
import com.jlees.budgey.domain.DateRange
import com.jlees.budgey.domain.Money
import com.jlees.budgey.domain.PurchaseFilter
import com.jlees.budgey.domain.SortOrder
import com.jlees.budgey.ui.components.AmountField
import com.jlees.budgey.ui.components.CategoryPickerSheet
import com.jlees.budgey.ui.components.MediumDate
import java.time.LocalDate
import java.time.ZoneOffset

/** Advanced filters. Edits a local copy; nothing changes until "Show results". */
@Composable
fun FilterSheet(
    initial: PurchaseFilter,
    tree: CategoryTree,
    paymentMethods: List<PaymentMethodEntity>,
    onApply: (PurchaseFilter) -> Unit,
    onDismiss: () -> Unit,
) {
    var f by remember { mutableStateOf(initial) }
    var minText by remember { mutableStateOf(initial.minCents?.let(Money::toInput) ?: "") }
    var maxText by remember { mutableStateOf(initial.maxCents?.let(Money::toInput) ?: "") }
    var pickCategory by remember { mutableStateOf(false) }
    var pickRange by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Filters", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    f = PurchaseFilter(datePreset = DatePreset.THIS_MONTH); minText = ""; maxText = ""
                }) { Text("Reset") }
            }

            Section("Date") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DatePreset.entries.forEach { p ->
                        FilterChip(
                            selected = f.datePreset == p,
                            onClick = { if (p == DatePreset.CUSTOM) pickRange = true else f = f.copy(datePreset = p) },
                            label = {
                                Text(
                                    if (p == DatePreset.CUSTOM && f.customRange != null && f.datePreset == p)
                                        "${f.customRange!!.start.format(MediumDate)} – ${f.customRange!!.end.format(MediumDate)}"
                                    else p.label
                                )
                            },
                        )
                    }
                }
            }

            Section("Categories") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    f.categoryIds.forEach { id ->
                        InputChip(
                            selected = true,
                            onClick = { f = f.copy(categoryIds = f.categoryIds - id) },
                            label = { Text(tree.pathLabel(id).ifEmpty { "Deleted" }) },
                            trailingIcon = { Icon(Icons.Rounded.Close, "Remove", Modifier.size(18.dp)) },
                        )
                    }
                    FilterChip(
                        selected = false,
                        onClick = { pickCategory = true },
                        label = { Text("Add") },
                        leadingIcon = { Icon(Icons.Rounded.Add, null, Modifier.size(18.dp)) },
                    )
                }
                SwitchRow("Include sub-categories", f.includeSubcategories) { f = f.copy(includeSubcategories = it) }
                SwitchRow("Only uncategorized", f.uncategorizedOnly) {
                    f = f.copy(uncategorizedOnly = it, categoryIds = if (it) emptySet() else f.categoryIds)
                }
            }

            Section("Amount") {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    AmountField(minText, { minText = it }, Modifier.weight(1f), label = "Min", large = false)
                    AmountField(maxText, { maxText = it }, Modifier.weight(1f), label = "Max", large = false)
                }
                Spacer(Modifier.height(8.dp))
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    AmountKind.entries.forEachIndexed { i, k ->
                        SegmentedButton(
                            selected = f.amountKind == k,
                            onClick = { f = f.copy(amountKind = k) },
                            shape = SegmentedButtonDefaults.itemShape(i, AmountKind.entries.size),
                        ) { Text(k.label) }
                    }
                }
            }

            Section("Source") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PurchaseSource.entries.forEach { s ->
                        FilterChip(
                            selected = s in f.sources,
                            onClick = { f = f.copy(sources = if (s in f.sources) f.sources - s else f.sources + s) },
                            label = { Text(sourceLabel(s)) },
                        )
                    }
                }
            }

            if (paymentMethods.isNotEmpty()) Section("Payment method") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    paymentMethods.forEach { m ->
                        val on = m.id in f.paymentMethodIds
                        FilterChip(
                            selected = on,
                            onClick = { f = f.copy(paymentMethodIds = if (on) f.paymentMethodIds - m.id else f.paymentMethodIds + m.id) },
                            label = { Text(m.displayName) },
                            leadingIcon = { PaymentMethodIcon(m, size = 20.dp) },
                        )
                    }
                }
            }

            Section("Receipt") {
                val opts = listOf<Pair<Boolean?, String>>(null to "Any", true to "Attached", false to "None")
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    opts.forEachIndexed { i, (v, label) ->
                        SegmentedButton(
                            selected = f.hasReceipt == v,
                            onClick = { f = f.copy(hasReceipt = v) },
                            shape = SegmentedButtonDefaults.itemShape(i, opts.size),
                        ) { Text(label) }
                    }
                }
            }

            Section("Sort") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SortOrder.entries.forEach { s ->
                        FilterChip(selected = f.sort == s, onClick = { f = f.copy(sort = s) }, label = { Text(s.label) })
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("Cancel") }
                Button(
                    onClick = {
                        onApply(f.copy(minCents = Money.parse(minText), maxCents = Money.parse(maxText)))
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("Show results") }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (pickCategory) {
        CategoryPickerSheet(
            tree = tree,
            selectedId = null,
            allowNone = false,
            title = "Add category filter",
            onDismiss = { pickCategory = false },
            onSelect = { id -> if (id != null) f = f.copy(categoryIds = f.categoryIds + id, uncategorizedOnly = false); pickCategory = false },
        )
    }

    if (pickRange) {
        val state = rememberDateRangePickerState(
            initialSelectedStartDateMillis = f.customRange?.start?.toMillis(),
            initialSelectedEndDateMillis = f.customRange?.end?.toMillis(),
        )
        DatePickerDialog(
            onDismissRequest = { pickRange = false },
            confirmButton = {
                TextButton(
                    enabled = state.selectedStartDateMillis != null,
                    onClick = {
                        val s = state.selectedStartDateMillis!!.toLocalDate()
                        val e = state.selectedEndDateMillis?.toLocalDate() ?: s
                        f = f.copy(datePreset = DatePreset.CUSTOM, customRange = DateRange(s, e))
                        pickRange = false
                    },
                ) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { pickRange = false }) { Text("Cancel") } },
        ) {
            DateRangePicker(state = state, modifier = Modifier.height(500.dp))
        }
    }
}

private fun LocalDate.toMillis() = atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
private fun Long.toLocalDate() = LocalDate.ofEpochDay(this / 86_400_000L)

fun sourceLabel(s: PurchaseSource) = when (s) {
    PurchaseSource.MANUAL -> "Manual"
    PurchaseSource.SCAN -> "Scanned"
    PurchaseSource.SUBSCRIPTION -> "Subscription"
    PurchaseSource.IMPORT -> "Imported"
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    HorizontalDivider(Modifier.padding(vertical = 12.dp))
    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
    Spacer(Modifier.height(8.dp))
    content()
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
