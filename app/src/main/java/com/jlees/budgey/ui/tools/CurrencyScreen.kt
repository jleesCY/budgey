package com.jlees.budgey.ui.tools

import android.text.format.DateUtils
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Surface
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jlees.budgey.domain.FxTable
import com.jlees.budgey.ui.AppViewModels
import java.math.BigDecimal
import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Currency

private fun currencyName(code: String): String = runCatching { Currency.getInstance(code).displayName }.getOrDefault(code)

/** Just the number ("1,234.50") — the currency code is already on the button next to it. */
private fun formatMoney(amount: BigDecimal, code: String): String {
    val digits = runCatching { Currency.getInstance(code).defaultFractionDigits }.getOrDefault(2).let { if (it < 0) 2 else it }
    return NumberFormat.getNumberInstance().apply {
        minimumFractionDigits = digits
        maximumFractionDigits = digits
        isGroupingUsed = true
    }.format(amount)
}

/** Rates like 0.000123 need more digits than 1.13. */
private fun formatRate(r: Double): String = when {
    r >= 100 -> String.format("%,.2f", r)
    r >= 1 -> String.format("%.4f", r)
    else -> String.format("%.6f", r).trimEnd('0')
}

/** Currency converter using daily central-bank rates (saved for offline use). */
@Composable
fun CurrencyScreen(onBack: () -> Unit, vm: CurrencyViewModel = viewModel(factory = AppViewModels.Factory)) {
    val s by vm.state.collectAsStateWithLifecycle()
    // Which side the picker is choosing for: "from", "to" or null (closed).
    var picking by rememberSaveable { mutableStateOf<String?>(null) }
    // Same rules as every amount box: "1,234.50", "1.234,50" and "12,5" all work.
    val amount = com.jlees.budgey.domain.Money.parse(s.amount)?.let { BigDecimal.valueOf(it, 2) }
    val converted = amount?.let { s.table?.convert(it, s.from, s.to) }
    val rate = s.table?.rate(s.from, s.to)
    @Suppress("DEPRECATION") val clipboard = LocalClipboardManager.current
    var copied by remember(converted, s.to) { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Currency converter") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                actions = {
                    if (s.loading) CircularProgressIndicator(Modifier.size(24.dp).padding(end = 4.dp), strokeWidth = 2.dp)
                    else if (s.hasPack) IconButton(onClick = { vm.refresh() }) { Icon(Icons.Rounded.Refresh, "Update rates") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow), shape = RoundedCornerShape(28.dp)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = s.amount,
                            onValueChange = vm::setAmount,
                            singleLine = true,
                            label = { Text("Amount") },
                            textStyle = MaterialTheme.typography.headlineSmall,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        CurrencyButton(s.from) { picking = "from" }
                    }
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        FilledTonalIconButton(onClick = { vm.swap() }) { Icon(Icons.Rounded.SwapVert, "Swap currencies") }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val result = converted?.let { formatMoney(it, s.to) }
                        // The whole number is always shown (it wraps rather than being cut off) and
                        // can be selected, or copied with the button.
                        SelectionContainer(Modifier.weight(1f).padding(start = 4.dp)) {
                            Text(
                                result ?: "—",
                                style = when {
                                    (result?.length ?: 0) <= 12 -> MaterialTheme.typography.headlineMedium
                                    (result?.length ?: 0) <= 18 -> MaterialTheme.typography.headlineSmall
                                    else -> MaterialTheme.typography.titleLarge
                                },
                                fontWeight = FontWeight.SemiBold,
                                softWrap = true,
                            )
                        }
                        if (result != null) IconButton(onClick = {
                            // Plain digits with a "." (e.g. 1234.56), whatever the phone's number style.
                            val digits = runCatching { Currency.getInstance(s.to).defaultFractionDigits }.getOrDefault(2).let { if (it < 0) 2 else it }
                            clipboard.setText(AnnotatedString(converted!!.setScale(digits, java.math.RoundingMode.HALF_UP).toPlainString()))
                            copied = true
                        }) { Icon(if (copied) Icons.Rounded.Check else Icons.Rounded.ContentCopy, "Copy result") }
                        Spacer(Modifier.width(4.dp))
                        CurrencyButton(s.to) { picking = "to" }
                    }
                }
            }

            // Reminder: built-in rates age; suggest the daily update pack.
            if (!s.hasPack && s.table != null) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
                    shape = RoundedCornerShape(20.dp),
                ) {
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Schedule, null, tint = MaterialTheme.colorScheme.onTertiaryContainer)
                        Spacer(Modifier.width(12.dp))
                        Text(
                            staleText(s),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { vm.refresh() }, enabled = !s.loading) { Text("Turn on") }
                    }
                }
            }

            if (rate != null) {
                Text("1 ${s.from} = ${formatRate(rate)} ${s.to}", style = MaterialTheme.typography.bodyLarge)
                Text("1 ${s.to} = ${formatRate(1 / rate)} ${s.from}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else if (s.table != null) {
                Text("No rate for this pair.", color = MaterialTheme.colorScheme.error)
            }

            val table = s.table
            val rateDate = table?.date?.let { d -> runCatching { LocalDate.parse(d).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)) }.getOrDefault(d) }
            Text(
                when {
                    table == null -> "Loading rates…"
                    s.hasPack -> "Rates from $rateDate (updated ${DateUtils.getRelativeTimeSpanString(s.fetchedAt ?: 0L).toString().lowercase()}; " +
                        "updates daily when you're online). Reference rates from central banks via Frankfurter — your bank or card may charge a different rate."
                    else -> "Built-in rates from $rateDate. For current rates, add the daily update pack in Settings → Tools. " +
                        "Your bank or card may charge a different rate."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            s.error?.let { err ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.CloudOff, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(8.dp))
                    Text(if (table != null) "$err. Showing saved rates." else err, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    picking?.let { side ->
        CurrencyPicker(
            codes = s.table?.currencies ?: listOf("EUR", "USD", "GBP", "JPY", "CAD", "AUD"),
            selected = if (side == "from") s.from else s.to,
            onPick = { code -> if (side == "from") vm.setFrom(code) else vm.setTo(code); picking = null },
            onDismiss = { picking = null },
        )
    }
}

@Composable
private fun CurrencyButton(code: String, onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick) {
        Text(code, style = MaterialTheme.typography.titleMedium)
        Icon(Icons.Rounded.ArrowDropDown, "Change currency")
    }
}

/**
 * Full-screen currency list with search. (A plain dialog rather than a bottom sheet: flinging
 * hard to the end of a list inside a sheet made the sheet and list fight and bounce.)
 */
@Composable
private fun CurrencyPicker(codes: List<String>, selected: String, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val shown = remember(codes, query) {
        val q = query.trim().lowercase()
        codes.map { it to currencyName(it) }
            .filter { (code, name) -> q.isEmpty() || code.lowercase().contains(q) || name.lowercase().contains(q) }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
                TopAppBar(
                    title = { Text("Choose a currency") },
                    navigationIcon = { IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, "Close") } },
                )
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Rounded.Search, null) },
                    placeholder = { Text("Search currencies") },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                )
                LazyColumn(Modifier.fillMaxSize().padding(top = 8.dp)) {
                    items(shown, key = { it.first }) { (code, name) ->
                        ListItem(
                            headlineContent = { Text(name) },
                            leadingContent = {
                                Text(code, style = MaterialTheme.typography.titleSmall, color = if (code == selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                            },
                            trailingContent = { Text(runCatching { Currency.getInstance(code).symbol }.getOrDefault("")) },
                            modifier = Modifier.clickable { onPick(code) },
                        )
                    }
                }
            }
        }
    }
}

/** "Using built-in rates from Oct 2, 2026 (40 days old), so conversions may be slightly off." */
private fun staleText(s: CurrencyState): String {
    val date = s.table?.date?.let { d -> runCatching { LocalDate.parse(d) }.getOrNull() }
    val days = date?.let { java.time.temporal.ChronoUnit.DAYS.between(it, LocalDate.now()) }
    val age = when {
        days == null -> ""
        days <= 0 -> " (today)"
        days == 1L -> " (1 day old)"
        else -> " ($days days old)"
    }
    val shown = date?.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)) ?: "an earlier date"
    return "Using built-in rates from $shown$age, so conversions may be slightly off."
}
