package com.jlees.budgey.ui.tools

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.jlees.budgey.domain.Money
import com.jlees.budgey.domain.TipMath
import com.jlees.budgey.ui.components.AmountField
import kotlin.math.roundToInt

private val Shortcuts = listOf(10, 15, 18, 20, 22, 25)

/**
 * Tip calculator (just the tip — splitting is the check splitter's job). The tip can be typed
 * as a percentage or a dollar amount; whichever you type, the other follows. Rounds up to the cent.
 */
@Composable
fun TipCalculatorScreen(onBack: () -> Unit) {
    var bill by rememberSaveable { mutableStateOf("") }
    var tax by rememberSaveable { mutableStateOf("") }
    var afterTax by rememberSaveable { mutableStateOf(false) }
    var percentText by rememberSaveable { mutableStateOf("20") }
    var tipText by rememberSaveable { mutableStateOf("") }
    // Which tip box you typed in last; the other one is calculated from it.
    var percentIsSource by rememberSaveable { mutableStateOf(true) }

    val billCents = Money.parse(bill) ?: 0L
    val taxCents = if (afterTax) Money.parse(tax) ?: 0L else 0L
    val base = TipMath.base(billCents, taxCents, afterTax)

    fun percentValue(): Double = percentText.replace(',', '.').toDoubleOrNull()?.coerceIn(0.0, 1000.0) ?: 0.0

    /** Recalculate the box you're not typing in. */
    fun sync(newBase: Long = base) {
        if (percentIsSource) {
            tipText = if (newBase > 0) Money.toInput(TipMath.tipFromPercent(newBase, percentValue())) else ""
        } else {
            val tip = Money.parse(tipText) ?: 0L
            percentText = if (newBase > 0) TipMath.formatPercent(TipMath.percentFromTip(newBase, tip)) else percentText
        }
    }

    fun setPercent(p: String) {
        percentText = p
        percentIsSource = true
        sync()
    }

    val tipCents = if (percentIsSource) TipMath.tipFromPercent(base, percentValue()) else Money.parse(tipText) ?: 0L
    val total = billCents + taxCents + tipCents

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Tip calculator") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Result first: it updates as you type.
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), shape = RoundedCornerShape(28.dp)) {
                Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Tip", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text(
                        Money.format(tipCents),
                        style = MaterialTheme.typography.displayMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "${TipMath.formatPercent(if (percentIsSource) percentValue() else TipMath.percentFromTip(base, tipCents))}% · " +
                            "Total ${if (afterTax) "with tax" else "before tax"} ${Money.format(total)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }

            AmountField(
                bill, { bill = it; sync(TipMath.base(Money.parse(it) ?: 0L, taxCents, afterTax)) }, Modifier.fillMaxWidth(),
                label = "Bill total (without tax)",
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Tip after tax", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        if (afterTax) "The tip is worked out on the bill plus tax" else "The tip is worked out on the bill before tax",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(afterTax, { on ->
                    afterTax = on
                    sync(TipMath.base(billCents, if (on) Money.parse(tax) ?: 0L else 0L, on))
                })
            }
            if (afterTax) AmountField(
                tax, { tax = it; sync(TipMath.base(billCents, Money.parse(it) ?: 0L, true)) }, Modifier.fillMaxWidth(),
                label = "Tax", large = false,
            )

            HorizontalDivider()
            Text("Tip", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = percentText,
                    onValueChange = { v -> setPercent(v.filter { it.isDigit() || it == '.' || it == ',' }.take(6)) },
                    label = { Text("Percent") },
                    suffix = { Text("%") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f),
                )
                AmountField(
                    tipText,
                    { v -> tipText = v; percentIsSource = false; sync() },
                    Modifier.weight(1f),
                    label = "Amount",
                    large = false,
                )
            }
            Slider(
                value = percentValue().toFloat().coerceIn(1f, 50f),
                onValueChange = { setPercent(it.roundToInt().toString()) },
                valueRange = 1f..50f,
                steps = 48,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Shortcuts.forEach { p ->
                    FilterChip(
                        selected = percentIsSource && percentValue() == p.toDouble(),
                        onClick = { setPercent(p.toString()) },
                        label = { Text("$p%") },
                    )
                }
            }
            Text(
                "The tip is rounded up to the cent. Splitting with friends? Use the check splitter.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}
