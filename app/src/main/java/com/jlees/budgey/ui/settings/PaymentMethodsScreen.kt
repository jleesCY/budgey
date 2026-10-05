package com.jlees.budgey.ui.settings

import androidx.compose.runtime.saveable.rememberSaveable
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CreditCard
import androidx.compose.material.icons.rounded.ReceiptLong
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jlees.budgey.AppContainer
import com.jlees.budgey.data.db.PaymentMethodEntity
import com.jlees.budgey.data.db.displayName
import com.jlees.budgey.domain.BudgetPeriod
import com.jlees.budgey.domain.Money
import com.jlees.budgey.ui.AppViewModels
import com.jlees.budgey.ui.components.EmptyState
import com.jlees.budgey.ui.components.PaymentMethodEditSheet
import com.jlees.budgey.ui.components.PaymentMethodIcon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

data class MethodRow(val method: PaymentMethodEntity, val purchaseCount: Int, val monthTotal: Long, val subscriptionCount: Int)

class PaymentMethodsViewModel(private val c: AppContainer) : ViewModel() {
    val rows: StateFlow<List<MethodRow>?> = combine(
        c.repository.paymentMethods, c.repository.purchases, c.repository.subscriptions,
    ) { methods, purchases, subs ->
        val month = BudgetPeriod.MONTHLY.rangeContaining(LocalDate.now())
        val byMethod = purchases.groupBy { it.paymentMethodId }
        methods.map { m ->
            val ps = byMethod[m.id].orEmpty()
            MethodRow(m, ps.size, ps.filter { it.date in month }.sumOf { it.amountCents }, subs.count { it.paymentMethodId == m.id })
        }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun save(m: PaymentMethodEntity) = viewModelScope.launch { c.repository.savePaymentMethod(m) }
    fun delete(id: String) = viewModelScope.launch { c.repository.deletePaymentMethod(id) }
    suspend fun importIcon(uri: Uri): String = c.paymentIcons.importImage(uri)
}

/** Settings → Payment methods: the cards, accounts and wallets you pick from everywhere else. */
@Composable
fun PaymentMethodsScreen(
    onBack: () -> Unit,
    onViewPurchases: (String) -> Unit,
    vm: PaymentMethodsViewModel = viewModel(factory = AppViewModels.Factory),
) {
    val rows by vm.rows.collectAsStateWithLifecycle()
    var editing by rememberSaveable { mutableStateOf<Pair<PaymentMethodEntity, Boolean>?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Payment methods") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
            )
        },
        floatingActionButton = {
            com.jlees.budgey.ui.components.AddFab(
                "Payment method",
                onClick = { editing = PaymentMethodEntity(name = "", sortOrder = rows?.size ?: 0) to true },
            )
        },
    ) { padding ->
        val list = rows
        LazyColumn(
            contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 96.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            if (list != null && list.isEmpty()) item {
                EmptyState(
                    Icons.Rounded.CreditCard,
                    "No payment methods yet",
                    "Add your cards, bank accounts and wallets once, then pick them on purchases and subscriptions — no more typos.",
                )
            }
            items(list.orEmpty(), key = { it.method.id }) { r ->
                val m = r.method
                ListItem(
                    headlineContent = { Text(m.displayName + if (m.archived) " (hidden)" else "", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = {
                        Text(
                            buildString {
                                append("${r.purchaseCount} purchase${if (r.purchaseCount == 1) "" else "s"}")
                                if (r.monthTotal != 0L) append(" · ${Money.format(r.monthTotal)} this month")
                                if (r.subscriptionCount > 0) append(" · ${r.subscriptionCount} subscription${if (r.subscriptionCount == 1) "" else "s"}")
                            },
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    leadingContent = { PaymentMethodIcon(m, size = 44.dp) },
                    trailingContent = {
                        IconButton(onClick = { onViewPurchases(m.id) }) { Icon(Icons.Rounded.ReceiptLong, "View purchases") }
                    },
                    modifier = Modifier.clickable { editing = m to false },
                )
            }
        }
    }

    editing?.let { (m, isNew) ->
        PaymentMethodEditSheet(
            initial = m,
            isNew = isNew,
            importIcon = vm::importIcon,
            onSave = { vm.save(it); editing = null },
            onDelete = if (isNew) null else ({ vm.delete(m.id); editing = null }),
            onDismiss = { editing = null },
        )
    }
}
