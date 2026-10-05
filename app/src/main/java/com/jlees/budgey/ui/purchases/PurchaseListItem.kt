package com.jlees.budgey.ui.purchases

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Autorenew
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Receipt
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jlees.budgey.data.db.CategoryEntity
import com.jlees.budgey.data.db.PaymentMethodEntity
import com.jlees.budgey.data.db.PurchaseEntity
import com.jlees.budgey.data.db.PurchaseSource
import com.jlees.budgey.domain.Money
import com.jlees.budgey.ui.components.MerchantAvatar
import com.jlees.budgey.ui.components.PaymentMethodIcon

/**
 * One purchase in a list — the same everywhere it appears (Purchases tab, Calendar day list):
 * logo, merchant, then subscription / receipt marks, category and payment method, and the amount.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PurchaseListItem(
    p: PurchaseEntity,
    category: CategoryEntity?,
    method: PaymentMethodEntity?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    containerColor: Color = MaterialTheme.colorScheme.surface,
) {
    ListItem(
        headlineContent = { Text(p.merchant, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Payments of a subscription (auto-logged or logged by hand).
                if (p.source == PurchaseSource.SUBSCRIPTION || p.subscriptionId != null) {
                    Icon(Icons.Rounded.Autorenew, "Subscription", Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(4.dp))
                }
                if (p.receiptFile != null) {
                    Icon(Icons.Rounded.Receipt, "Has receipt", Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    category?.name ?: "Uncategorized",
                    color = if (category == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (method != null) {
                    Text(" · ", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    PaymentMethodIcon(method, size = 16.dp)
                    Spacer(Modifier.width(4.dp))
                    Text(
                        method.name, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                    )
                }
            }
        },
        leadingContent = {
            if (selected) {
                Box(
                    Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.Check, null, tint = MaterialTheme.colorScheme.onPrimary) }
            } else MerchantAvatar(p.merchant, p.brandKey, category)
        },
        trailingContent = {
            Text(
                if (p.amountCents < 0) "+" + Money.format(-p.amountCents) else Money.format(p.amountCents),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (p.amountCents < 0) com.jlees.budgey.ui.theme.AppColors.refund else MaterialTheme.colorScheme.onSurface,
            )
        },
        colors = ListItemDefaults.colors(
            containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else containerColor,
        ),
        modifier = modifier.combinedClickable(
            onClick = onClick,
            onLongClick = onLongClick,
            onLongClickLabel = if (onLongClick != null) (if (selected) "Unselect" else "Select") else null,
        ),
    )
}
