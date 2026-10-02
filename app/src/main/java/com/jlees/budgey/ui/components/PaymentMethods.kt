package com.jlees.budgey.ui.components

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountBalance
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.CardGiftcard
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CreditCard
import androidx.compose.material.icons.rounded.CurrencyBitcoin
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Payment
import androidx.compose.material.icons.rounded.Payments
import androidx.compose.material.icons.rounded.PhoneIphone
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.jlees.budgey.data.db.PaymentMethodEntity
import com.jlees.budgey.data.db.displayName
import com.jlees.budgey.icons.IconRef
import kotlinx.coroutines.launch
import java.io.File

/** Generic payment icons for things that aren't a brand (cash, a gift card, …). Keys are stored — only add. */
object PaymentGenericIcons {
    val all: List<Triple<String, String, ImageVector>> = listOf(
        Triple("card", "Credit card", Icons.Rounded.CreditCard),
        Triple("debit", "Debit card", Icons.Rounded.Payment),
        Triple("cash", "Cash", Icons.Rounded.Payments),
        Triple("bank", "Bank account", Icons.Rounded.AccountBalance),
        Triple("wallet", "Wallet", Icons.Rounded.AccountBalanceWallet),
        Triple("phone", "Phone pay", Icons.Rounded.PhoneIphone),
        Triple("gift", "Gift card", Icons.Rounded.CardGiftcard),
        Triple("crypto", "Crypto", Icons.Rounded.CurrencyBitcoin),
    )

    fun get(key: String): ImageVector = all.firstOrNull { it.first == key }?.third ?: Icons.Rounded.CreditCard
}

/** Renders a payment method's icon: brand logo, generic symbol, or the user's own picture. */
@Composable
fun PaymentMethodIcon(method: PaymentMethodEntity?, size: Dp = 40.dp, modifier: Modifier = Modifier) {
    PaymentIcon(method?.icon ?: "none", size, modifier, contentDescription = method?.name)
}

@Composable
fun PaymentIcon(icon: String, size: Dp = 40.dp, modifier: Modifier = Modifier, contentDescription: String? = null) {
    when (val ref = IconRef.parse(icon)) {
        // Payment methods never auto-detect from a merchant name; "none" shows a neutral card.
        IconRef.Auto, IconRef.None -> SymbolBadge(Icons.Rounded.CreditCard, modifier, size, contentDescription)
        is IconRef.BrandRef -> {
            val catalog = LocalBrandCatalog.current
            if (catalog.byId(ref.id)?.takeIf { catalog.hasIcon(it) } == null) SymbolBadge(Icons.Rounded.CreditCard, modifier, size, contentDescription)
            else ItemIcon(icon, "", null, modifier, size, contentDescription)
        }
        else -> ItemIcon(icon, "", null, modifier, size, contentDescription)
    }
}

/**
 * Field used in the purchase & subscription editors. Picks from your saved payment methods so
 * names are always consistent, and can add a new one on the spot.
 */
@Composable
fun PaymentMethodField(
    methods: List<PaymentMethodEntity>,
    selectedId: String?,
    legacyName: String,
    onSelect: (String?) -> Unit,
    onCreate: suspend (PaymentMethodEntity) -> Unit,
    importIcon: suspend (Uri) -> String,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    val selected = methods.firstOrNull { it.id == selectedId }
    ListItem(
        overlineContent = { Text("Payment method") },
        headlineContent = {
            Text(
                selected?.displayName ?: legacyName.ifBlank { "None" },
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = if (selected == null && legacyName.isNotBlank()) {
            { Text("Not a saved method — tap to pick one") }
        } else null,
        leadingContent = { PaymentMethodIcon(selected, size = 40.dp) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = modifier.clip28().clickable { open = true },
    )
    if (open) {
        ModalBottomSheet(onDismissRequest = { open = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Text("Paid with", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 16.dp))
            LazyColumn(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                item {
                    ListItem(
                        headlineContent = { Text("None") },
                        leadingContent = { PaymentIcon("none", 36.dp) },
                        trailingContent = { if (selectedId == null) Icon(Icons.Rounded.Check, null) },
                        modifier = Modifier.clickable { onSelect(null); open = false },
                    )
                }
                items(methods.filter { !it.archived || it.id == selectedId }, key = { it.id }) { m ->
                    ListItem(
                        headlineContent = { Text(m.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        leadingContent = { PaymentMethodIcon(m, 36.dp) },
                        trailingContent = { if (selectedId == m.id) Icon(Icons.Rounded.Check, null) },
                        modifier = Modifier.clickable { onSelect(m.id); open = false },
                    )
                }
                item {
                    HorizontalDivider(Modifier.padding(vertical = 4.dp))
                    ListItem(
                        headlineContent = { Text("Add payment method") },
                        leadingContent = { IconCircle(Icons.Rounded.Add) },
                        modifier = Modifier.clickable { creating = true },
                    )
                    Spacer(Modifier.height(32.dp))
                }
            }
        }
    }
    if (creating) {
        val scope = rememberCoroutineScope()
        PaymentMethodEditSheet(
            initial = PaymentMethodEntity(name = "", sortOrder = methods.size),
            isNew = true,
            importIcon = importIcon,
            onSave = { m ->
                scope.launch {
                    onCreate(m)
                    onSelect(m.id)
                    creating = false
                    open = false
                }
            },
            onDelete = null,
            onDismiss = { creating = false },
        )
    }
}

/** Create / edit a payment method: name, optional last 4 digits, and an icon. */
@Composable
fun PaymentMethodEditSheet(
    initial: PaymentMethodEntity,
    isNew: Boolean,
    importIcon: suspend (Uri) -> String,
    onSave: (PaymentMethodEntity) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val catalog = LocalBrandCatalog.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(initial.name) }
    var last4 by remember { mutableStateOf(initial.last4 ?: "") }
    var icon by remember { mutableStateOf(initial.icon) }
    // Until the user picks an icon themselves, follow the name ("Chase Sapphire" → Chase logo).
    var iconTouched by remember { mutableStateOf(!isNew) }
    var archived by remember { mutableStateOf(initial.archived) }
    var confirmDelete by remember { mutableStateOf(false) }
    var pickIcon by remember { mutableStateOf(false) }

    fun onNameChange(v: String) {
        name = v
        if (!iconTouched) icon = catalog.matchPayment(v)?.takeIf { catalog.hasIcon(it) }?.let { "brand:${it.id}" } ?: "generic:card"
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clip(MaterialTheme.shapes.medium).clickable { pickIcon = true }) {
                    PaymentIcon(icon, 52.dp)
                    Text("Icon", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
                Spacer(Modifier.width(12.dp))
                Text(if (isNew) "New payment method" else "Edit payment method", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                if (onDelete != null) IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Rounded.Delete, "Delete") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name, onValueChange = ::onNameChange, label = { Text("Name") },
                    placeholder = { Text("e.g. Chase Sapphire") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = last4, onValueChange = { v -> last4 = v.filter(Char::isDigit).take(4) },
                    label = { Text("Last 4") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.width(96.dp),
                )
            }

            OutlinedButton(onClick = { pickIcon = true }) {
                Icon(Icons.Rounded.AddPhotoAlternate, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Change icon")
            }

            if (!isNew) Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Hide from pickers", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "For closed cards — past purchases keep showing it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = archived, onCheckedChange = { archived = it })
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("Cancel") }
                Button(
                    enabled = name.isNotBlank(),
                    onClick = {
                        onSave(initial.copy(name = name.trim(), last4 = last4.ifBlank { null }, icon = icon, archived = archived))
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("Save") }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
    if (pickIcon) {
        ItemIconSheet(
            mode = IconSheetMode.PAYMENT,
            name = name,
            current = icon,
            category = null,
            importImage = importIcon,
            onSelect = { ref, _ -> icon = ref ?: "none"; iconTouched = true; pickIcon = false },
            onDismiss = { pickIcon = false },
        )
    }
    if (confirmDelete && onDelete != null) {
        ConfirmDialog(
            title = "Delete \"${initial.name}\"?",
            text = "Purchases and subscriptions that used it keep the name as plain text. Tip: \"Hide from pickers\" keeps the link instead.",
            confirmLabel = "Delete",
            destructive = true,
            onConfirm = onDelete,
            onDismiss = { confirmDelete = false },
        )
    }
}
