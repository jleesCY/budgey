package com.jlees.budgey.ui.purchases

import androidx.compose.foundation.clickable
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.automirrored.rounded.Notes
import androidx.compose.material.icons.rounded.CalendarToday
import androidx.compose.material.icons.rounded.Category
import androidx.compose.material.icons.rounded.CreditCard
import androidx.compose.material3.FilledTonalButton
import com.jlees.budgey.data.db.PaymentMethodEntity
import com.jlees.budgey.data.db.PurchaseSource
import com.jlees.budgey.data.db.displayName
import com.jlees.budgey.domain.CategoryTree
import com.jlees.budgey.ui.components.DetailCard
import com.jlees.budgey.ui.components.DetailRow
import com.jlees.budgey.ui.components.PaymentMethodIcon
import com.jlees.budgey.ui.components.PreviewHeader
import com.jlees.budgey.ui.components.ReceiptPreview
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Autorenew
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DocumentScanner
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jlees.budgey.domain.Money
import com.jlees.budgey.ui.AppViewModels
import com.jlees.budgey.ui.components.AmountField
import com.jlees.budgey.ui.components.ItemIconSheet
import com.jlees.budgey.ui.components.IconSheetMode
import com.jlees.budgey.ui.components.CategoryField
import com.jlees.budgey.ui.components.ConfirmDialog
import com.jlees.budgey.ui.components.DateField
import com.jlees.budgey.ui.components.ItemKindSwitch
import com.jlees.budgey.ui.components.MerchantAvatar
import com.jlees.budgey.ui.components.ReceiptSection
import com.jlees.budgey.ui.components.PaymentMethodField

@Composable
fun PurchaseEditScreen(
    onBack: () -> Unit,
    onSwitchToSubscription: () -> Unit,
    vm: PurchaseEditViewModel = viewModel(factory = AppViewModels.Factory),
) {
    val form by vm.form.collectAsStateWithLifecycle()
    val tree by vm.tree.collectAsStateWithLifecycle()
    val merchants by vm.merchants.collectAsStateWithLifecycle()
    val methods by vm.paymentMethods.collectAsStateWithLifecycle()
    var brandPicker by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var askCategory by remember { mutableStateOf(false) }
    var showScanText by remember { mutableStateOf(false) }
    // Existing purchases open as a read-only preview; the Edit button switches to the editor.
    var editing by rememberSaveable { mutableStateOf(vm.isNew) }
    val leaveEditing: () -> Unit = { vm.reload(); editing = false }
    BackHandler(enabled = editing && !vm.isNew) { leaveEditing() }
    val afterSave: () -> Unit = {
        if (vm.isNew) onBack() else editing = false
        Unit
    }

    fun save() {
        if (form.categoryId == null) askCategory = true else vm.save(afterSave)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (vm.isNew) "New purchase" else if (editing) "Edit purchase" else "Purchase") },
                navigationIcon = {
                    IconButton(onClick = { if (editing && !vm.isNew) leaveEditing() else onBack() }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, if (editing && !vm.isNew) "Stop editing" else "Back")
                    }
                },
                actions = {
                    if (!vm.isNew) IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Rounded.Delete, "Delete") }
                    if (editing) TextButton(onClick = { save() }, enabled = form.canSave) { Text("Save") }
                    else IconButton(onClick = { editing = true }) { Icon(Icons.Rounded.Edit, "Edit") }
                },
            )
        },
    ) { padding ->
        if (!editing) {
            PurchasePreview(form, tree, methods, vm, onEdit = { editing = true }, modifier = Modifier.padding(padding))
            return@Scaffold
        }
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (vm.isNew) {
                ItemKindSwitch(isSubscription = false, onSwitch = { vm.convertToSubscription(); onSwitchToSubscription() })
            }
            if (form.scanText != null) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.DocumentScanner, null)
                        Spacer(Modifier.width(12.dp))
                        Text("Filled in from your scan — please double-check.", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = { showScanText = true }) { Text("Text") }
                    }
                }
            }

            // Merchant + live auto-icon
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { brandPicker = true }) {
                    MerchantAvatar(form.merchant.ifBlank { "?" }, form.brandKey, form.categoryId?.let { tree.byId[it] }, size = 56.dp)
                    Text("Icon", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
                Spacer(Modifier.width(12.dp))
                OutlinedTextField(
                    value = form.merchant,
                    onValueChange = vm::setMerchant,
                    label = { Text("Merchant / description") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                    modifier = Modifier.weight(1f),
                )
            }
            val suggestions = remember(form.merchant, merchants) {
                val q = form.merchant.trim().lowercase()
                if (q.length < 2) emptyList()
                else merchants.filter { it.lowercase().startsWith(q) && !it.equals(form.merchant.trim(), true) }.take(6)
            }
            if (suggestions.isNotEmpty()) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    suggestions.forEach { s -> SuggestionChip(onClick = { vm.setMerchant(s) }, label = { Text(s) }) }
                }
            }

            AmountField(form.amountText, vm::setAmount, Modifier.fillMaxWidth(), isError = form.amountText.isNotEmpty() && form.amountCents == null)
            if (form.amountCandidates.size > 1) {
                Text("Other amounts found", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    form.amountCandidates.forEach { c ->
                        FilterChip(
                            selected = form.amountCents == c,
                            onClick = { vm.setAmount(Money.toInput(c)) },
                            label = { Text(Money.format(c)) },
                        )
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Refund / return", Modifier.weight(1f))
                Switch(checked = form.isRefund, onCheckedChange = vm::setRefund)
            }

            DateField("Date", form.date, { it?.let(vm::setDate) }, Modifier.fillMaxWidth())

            CategoryField(tree, form.categoryId, vm::setCategory, Modifier.fillMaxWidth(), highlightEmpty = true)
            val suggested = form.suggestedCategoryId
            if (suggested != null && suggested != form.categoryId) {
                AssistChip(
                    onClick = { vm.setCategory(suggested) },
                    label = { Text("Suggested: ${tree.byId[suggested]?.name ?: ""}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingIcon = { Icon(Icons.Rounded.AutoAwesome, null, Modifier.size(18.dp)) },
                )
            }

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
                value = form.note,
                onValueChange = vm::setNote,
                label = { Text("Note (optional)") },
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )

            ReceiptSection(form.receiptFile?.let(vm::receiptFile), vm::attachReceipt, vm::removeReceipt)

            Button(onClick = { save() }, enabled = form.canSave, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                Text(if (vm.isNew) "Add purchase" else "Save changes")
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (brandPicker) {
        ItemIconSheet(
            mode = IconSheetMode.MERCHANT,
            name = form.merchant,
            current = form.brandKey,
            category = form.categoryId?.let { tree.byId[it] },
            importImage = vm::importPaymentIcon,
            onSelect = { ref, brand -> vm.setIcon(ref, brand); brandPicker = false },
            onDismiss = { brandPicker = false },
        )
    }
    if (confirmDelete) {
        ConfirmDialog(
            title = "Delete purchase?",
            text = "This can't be undone.",
            confirmLabel = "Delete",
            destructive = true,
            onConfirm = { vm.delete(onBack) },
            onDismiss = { confirmDelete = false },
        )
    }
    if (askCategory) {
        // Gentle nudge: budgets only count categorized purchases.
        AlertDialog(
            onDismissRequest = { askCategory = false },
            icon = { Icon(Icons.Rounded.Edit, null) },
            title = { Text("Add a category?") },
            text = { Text("Uncategorized purchases don't count toward any budget. You can always categorize it later from the Purchases list.") },
            confirmButton = { TextButton(onClick = { askCategory = false }) { Text("Choose category") } },
            dismissButton = { TextButton(onClick = { askCategory = false; vm.save(afterSave) }) { Text("Save anyway") } },
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

private val FullDate: DateTimeFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL)

/** Read-only view of a saved purchase. */
@Composable
private fun PurchasePreview(
    form: PurchaseForm,
    tree: CategoryTree,
    methods: List<PaymentMethodEntity>,
    vm: PurchaseEditViewModel,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!form.loaded) return
    val method = form.paymentMethodId?.let { id -> methods.firstOrNull { it.id == id } }
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PreviewHeader(
            avatar = { MerchantAvatar(form.merchant.ifBlank { "?" }, form.brandKey, form.categoryId?.let { tree.byId[it] }, size = 72.dp) },
            title = form.merchant,
            amount = (if (form.isRefund) "+" else "") + Money.format(form.amountCents ?: 0),
            amountColor = if (form.isRefund) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurface,
            caption = form.date.format(FullDate),
            chip = if (form.isRefund) "Refund" else null,
        )
        DetailCard {
            DetailRow(Icons.Rounded.CalendarToday, "Date", form.date.format(FullDate))
            DetailRow(
                Icons.Rounded.Category, "Category",
                form.categoryId?.let { tree.pathLabel(it) }?.ifBlank { null } ?: "Uncategorized",
            )
            val methodName = method?.displayName ?: form.paymentMethod.ifBlank { null }
            if (methodName != null) DetailRow(
                Icons.Rounded.CreditCard, "Paid with", methodName,
                leading = if (method != null) { { PaymentMethodIcon(method, size = 24.dp) } } else null,
            )
            when {
                form.subscriptionId != null -> DetailRow(Icons.Rounded.Autorenew, "Added by", "A subscription payment")
                form.source == PurchaseSource.SCAN -> DetailRow(Icons.Rounded.DocumentScanner, "Added by", "Scanning a picture")
                else -> Unit
            }
            if (form.note.isNotBlank()) DetailRow(Icons.AutoMirrored.Rounded.Notes, "Note", form.note)
        }
        form.receiptFile?.let { ReceiptPreview(vm.receiptFile(it)) }
        FilledTonalButton(onClick = onEdit, modifier = Modifier.fillMaxWidth().height(56.dp)) {
            Icon(Icons.Rounded.Edit, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Edit purchase")
        }
        Spacer(Modifier.height(24.dp))
    }
}
