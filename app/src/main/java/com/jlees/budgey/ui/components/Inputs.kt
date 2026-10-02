package com.jlees.budgey.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Autorenew
import androidx.compose.material.icons.rounded.ShoppingBag
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jlees.budgey.domain.CategoryTree
import com.jlees.budgey.icons.BrandMatcher
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

val MediumDate: DateTimeFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)

@Composable
fun AmountField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "Amount",
    isError: Boolean = false,
    large: Boolean = true,
    supportingText: String? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { v -> onValueChange(v.filter { it.isDigit() || it == '.' || it == ',' }) },
        label = { Text(label) },
        prefix = { Text("$") },
        singleLine = true,
        isError = isError,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier,
        textStyle = if (large) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.bodyLarge,
        supportingText = if (supportingText != null) {
            { Text(supportingText, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        } else null,
    )
}

/** Read-only text field that opens a Material date picker. */
@Composable
fun DateField(
    label: String,
    date: LocalDate?,
    onDateChange: (LocalDate?) -> Unit,
    modifier: Modifier = Modifier,
    allowClear: Boolean = false,
) {
    var open by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    LaunchedEffect(interaction) {
        interaction.interactions.collect { if (it is PressInteraction.Release) open = true }
    }
    OutlinedTextField(
        value = date?.format(MediumDate) ?: "",
        onValueChange = {},
        readOnly = true,
        label = { Text(label) },
        trailingIcon = { Icon(Icons.Rounded.CalendarMonth, null) },
        interactionSource = interaction,
        singleLine = true,
        modifier = modifier,
    )
    if (open) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = (date ?: LocalDate.now()).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        )
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { onDateChange(LocalDate.ofEpochDay(it / 86_400_000L)) }
                    open = false
                }) { Text("OK") }
            },
            dismissButton = {
                Row {
                    if (allowClear) TextButton(onClick = { onDateChange(null); open = false }) { Text("Clear") }
                    TextButton(onClick = { open = false }) { Text("Cancel") }
                }
            },
        ) { DatePicker(state = state) }
    }
}

/** Field that shows the chosen category and opens the picker sheet. */
@Composable
fun CategoryField(
    tree: CategoryTree,
    selectedId: String?,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "Category",
    highlightEmpty: Boolean = false,
) {
    var open by remember { mutableStateOf(false) }
    val cat = selectedId?.let { tree.byId[it] }
    ListItem(
        headlineContent = { Text(cat?.name ?: "Uncategorized") },
        overlineContent = { Text(label) },
        supportingContent = {
            val path = tree.path(selectedId).dropLast(1)
            if (path.isNotEmpty()) Text(path.joinToString(" › ") { it.name })
            else if (cat == null) Text("Tap to choose a category", color = if (highlightEmpty) MaterialTheme.colorScheme.error else Color.Unspecified)
        },
        leadingContent = { if (cat != null) CategoryBadge(cat, size = 40.dp) else UncategorizedBadge(size = 40.dp) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = modifier
            .clip28()
            .clickable { open = true },
    )
    if (open) {
        CategoryPickerSheet(tree, selectedId, onDismiss = { open = false }, onSelect = { onSelect(it); open = false })
    }
}

@Composable
fun CategoryPickerSheet(
    tree: CategoryTree,
    selectedId: String?,
    onDismiss: () -> Unit,
    onSelect: (String?) -> Unit,
    title: String = "Choose category",
    allowNone: Boolean = true,
    noneLabel: String = "Uncategorized",
    excludeSubtreeOf: String? = null,
) {
    var query by remember { mutableStateOf("") }
    val excluded = remember(excludeSubtreeOf, tree) { excludeSubtreeOf?.let { tree.subtreeIds(it) } ?: emptySet() }
    val rows = remember(tree, query, excluded) {
        val q = query.trim().lowercase()
        tree.flattened().filter { (c, _) -> c.id !in excluded && (q.isEmpty() || c.name.lowercase().contains(q)) }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = query, onValueChange = { query = it },
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                placeholder = { Text("Search categories") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
        }
        LazyColumn(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            if (allowNone && query.isBlank()) item {
                ListItem(
                    headlineContent = { Text(noneLabel) },
                    leadingContent = { UncategorizedBadge(size = 36.dp) },
                    trailingContent = { if (selectedId == null) Icon(Icons.Rounded.Check, null) },
                    modifier = Modifier.clickable { onSelect(null) },
                )
            }
            items(rows, key = { it.first.id }) { (c, depth) ->
                ListItem(
                    headlineContent = { Text(c.name) },
                    supportingContent = if (query.isNotBlank() && c.parentId != null) {
                        { Text(tree.pathLabel(c.parentId)) }
                    } else null,
                    leadingContent = { CategoryBadge(c, size = 36.dp) },
                    trailingContent = { if (selectedId == c.id) Icon(Icons.Rounded.Check, null) },
                    modifier = Modifier
                        .clickable { onSelect(c.id) }
                        .padding(start = if (query.isBlank()) (depth * 24).dp else 0.dp),
                )
            }
            item { Spacer(Modifier.height(32.dp)) }
        }
    }
}

@Composable
fun IconCircle(icon: ImageVector, modifier: Modifier = Modifier) {
    Box(
        modifier.size(36.dp).background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(20.dp)) }
}

@Composable
fun ColorPicker(selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        CategoryColors.forEach { c ->
            val argb = c.toInt()
            val isSel = argb == selected
            Box(
                Modifier
                    .size(36.dp)
                    .background(Color(c), CircleShape)
                    .then(if (isSel) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier)
                    .clickable { onSelect(argb) },
                contentAlignment = Alignment.Center,
            ) { if (isSel) Icon(Icons.Rounded.Check, null, tint = Color.White, modifier = Modifier.size(18.dp)) }
        }
    }
}

@Composable
fun IconPicker(selected: String, color: Int, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CategoryIcons.all.forEach { (key, icon) ->
            val isSel = key == selected
            Box(
                Modifier
                    .size(44.dp)
                    .background(
                        if (isSel) Color(color).copy(alpha = 0.3f) else MaterialTheme.colorScheme.surfaceContainerHigh,
                        CircleShape,
                    )
                    .clickable { onSelect(key) },
                contentAlignment = Alignment.Center,
            ) { Icon(icon, key, tint = if (isSel) Color(color) else MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, body: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Column(
        modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(96.dp).background(MaterialTheme.colorScheme.primaryContainer, softBurstShape()),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, Modifier.size(44.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer) }
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(4.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        if (action != null) {
            Spacer(Modifier.height(16.dp))
            action()
        }
    }
}

@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = { onConfirm(); onDismiss() }) {
                Text(confirmLabel, color = if (destructive) MaterialTheme.colorScheme.error else Color.Unspecified)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Suppress("unused")
@Composable
fun FullScreenLoading() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        androidx.compose.material3.LoadingIndicator()
    }
}

fun Modifier.clip28(): Modifier = this.clip(RoundedCornerShape(28.dp))

/**
 * Purchase ⇄ Subscription switch shown at the top of a *new* item. Switching keeps everything
 * typed so far, and you can flip back and forth freely.
 */
@Composable
fun ItemKindSwitch(isSubscription: Boolean, onSwitch: () -> Unit, modifier: Modifier = Modifier) {
    SingleChoiceSegmentedButtonRow(modifier.fillMaxWidth()) {
        SegmentedButton(
            selected = !isSubscription,
            onClick = { if (isSubscription) onSwitch() },
            shape = SegmentedButtonDefaults.itemShape(0, 2),
            icon = { Icon(Icons.Rounded.ShoppingBag, null, Modifier.size(18.dp)) },
        ) { Text("Purchase") }
        SegmentedButton(
            selected = isSubscription,
            onClick = { if (!isSubscription) onSwitch() },
            shape = SegmentedButtonDefaults.itemShape(1, 2),
            icon = { Icon(Icons.Rounded.Autorenew, null, Modifier.size(18.dp)) },
        ) { Text("Subscription") }
    }
}
