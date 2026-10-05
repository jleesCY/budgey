package com.jlees.budgey.ui.components

import com.jlees.budgey.ui.theme.AppColors
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.jlees.budgey.domain.BudgetStatus
import com.jlees.budgey.domain.Money

/**
 * Bar colour for budget health: on track → category colour, ahead of pace → amber, over → error.
 * For bars and dots only — use [budgetTextColor] for words and numbers.
 */
@Composable
fun budgetColor(status: BudgetStatus): Color = when {
    status.over -> MaterialTheme.colorScheme.error
    status.aheadOfPace -> AppColors.warningGraphic
    else -> Color(status.category.color)
}

/** Readable text colour for budget health (category colours can be too light to read). */
@Composable
fun budgetTextColor(status: BudgetStatus): Color = when {
    status.over -> MaterialTheme.colorScheme.error
    status.aheadOfPace -> AppColors.warning
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

/** Budget health in words, so it doesn't rely on colour alone. */
fun budgetStatusWords(status: BudgetStatus): String = when {
    status.over -> "${Money.format(-status.remaining)} over"
    status.aheadOfPace -> "${Money.format(status.remaining)} left · ahead of pace"
    else -> "${Money.format(status.remaining)} left"
}

/**
 * Expressive wavy progress bar plus "spent of budget" text.
 * (If the wavy indicator API changes in a future alpha, swap for LinearProgressIndicator.)
 */
@Composable
fun BudgetProgress(status: BudgetStatus, modifier: Modifier = Modifier, showLabels: Boolean = true) {
    val color = budgetColor(status)
    Column(modifier) {
        LinearWavyProgressIndicator(
            progress = { status.fraction.coerceIn(0f, 1f) },
            color = color,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            modifier = Modifier.fillMaxWidth(),
        )
        if (showLabels) {
            Spacer(Modifier.height(6.dp))
            Row {
                Text(
                    "${Money.format(status.spentCents)} of ${Money.format(status.budgetCents)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    budgetStatusWords(status),
                    style = MaterialTheme.typography.bodySmall,
                    color = budgetTextColor(status),
                    maxLines = 1,
                )
            }
        }
    }
}
