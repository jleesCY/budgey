package com.jlees.budgey.ui.purchases

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.jlees.budgey.AppContainer
import com.jlees.budgey.data.db.PurchaseEntity
import com.jlees.budgey.data.db.PurchaseSource
import com.jlees.budgey.data.db.PaymentMethodEntity
import com.jlees.budgey.data.db.displayName
import com.jlees.budgey.domain.CategoryTree
import com.jlees.budgey.icons.Brand
import com.jlees.budgey.domain.Money
import com.jlees.budgey.scan.ScanDraft
import com.jlees.budgey.scan.ScanKind
import com.jlees.budgey.scan.ScanResult
import com.jlees.budgey.domain.BillingCycle
import com.jlees.budgey.ui.navigation.PurchaseEditRoute
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate

data class PurchaseForm(
    val id: String? = null,
    val merchant: String = "",
    val amountText: String = "",
    val isRefund: Boolean = false,
    val date: LocalDate = LocalDate.now(),
    val categoryId: String? = null,
    /** True once the user picked a category themselves — stop auto-suggesting. */
    val categoryTouched: Boolean = false,
    val suggestedCategoryId: String? = null,
    val note: String = "",
    /** Name snapshot (legacy free text, or the saved method's name). */
    val paymentMethod: String = "",
    val paymentMethodId: String? = null,
    /** True once the user picked a method — stop auto-suggesting. */
    val paymentTouched: Boolean = false,
    val brandKey: String? = null,
    val receiptFile: String? = null,
    val source: PurchaseSource = PurchaseSource.MANUAL,
    val subscriptionId: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    /** Alternatives found by the scanner. */
    val amountCandidates: List<Long> = emptyList(),
    val scanText: String? = null,
    val loaded: Boolean = false,
) {
    val amountCents: Long? get() = Money.parse(amountText)
    val canSave: Boolean get() = merchant.isNotBlank() && (amountCents ?: 0) > 0
}

class PurchaseEditViewModel(private val c: AppContainer, handle: SavedStateHandle) : ViewModel() {
    private val route = handle.toRoute<PurchaseEditRoute>()
    val isNew = route.id == null

    private val _form = MutableStateFlow(
        PurchaseForm(
            categoryId = route.categoryId,
            categoryTouched = route.categoryId != null,
            date = route.date?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: LocalDate.now(),
        )
    )
    val form: StateFlow<PurchaseForm> = _form.asStateFlow()

    val tree: StateFlow<CategoryTree> = c.repository.categoryTree.stateIn(viewModelScope, SharingStarted.Eagerly, CategoryTree.EMPTY)
    val merchants = c.repository.merchants.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val paymentMethods = c.repository.paymentMethods.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** The scan draft (kept so the user can switch this into a subscription). */
    var draft: ScanDraft? = null
        private set

    init {
        viewModelScope.launch {
            val id = route.id
            if (id != null) {
                loadExisting(id)
            } else if (route.fromScan) {
                val d = c.scanDrafts.take()
                draft = d
                if (d != null) {
                    val r = d.result
                    val scanned = r.rawText.isNotBlank()
                    _form.update {
                        it.copy(
                            merchant = r.merchant ?: "",
                            amountText = r.amountCents?.let(Money::toInput) ?: "",
                            date = r.date ?: LocalDate.now(),
                            brandKey = d.brandKey,
                            receiptFile = d.receiptFile,
                            source = if (scanned) PurchaseSource.SCAN else PurchaseSource.MANUAL,
                            amountCandidates = r.amountCandidates,
                            scanText = r.rawText.ifBlank { null },
                            categoryId = d.categoryId ?: it.categoryId,
                            categoryTouched = d.categoryId != null,
                            paymentMethodId = d.paymentMethodId,
                            paymentTouched = d.paymentMethodId != null,
                            note = d.note,
                            loaded = true,
                        )
                    }
                    suggest()
                } else _form.update { it.copy(loaded = true) }
            } else _form.update { it.copy(loaded = true) }
        }
    }

    private suspend fun loadExisting(id: String) {
        c.repository.purchase(id)?.let { p ->
            _form.value = PurchaseForm(
                id = p.id, merchant = p.merchant, amountText = Money.toInput(kotlin.math.abs(p.amountCents)),
                isRefund = p.amountCents < 0, date = p.date, categoryId = p.categoryId, categoryTouched = true,
                note = p.note, paymentMethod = p.paymentMethod, paymentMethodId = p.paymentMethodId, paymentTouched = true,
                brandKey = p.brandKey, receiptFile = p.receiptFile,
                source = p.source, subscriptionId = p.subscriptionId, createdAt = p.createdAt, loaded = true,
            )
        }
    }

    /** Leave edit mode without saving: put back what's stored. */
    fun reload() = viewModelScope.launch { route.id?.let { loadExisting(it) } }

    private var suggestJob: Job? = null

    fun setMerchant(v: String) {
        _form.update { it.copy(merchant = v) }
        suggestJob?.cancel()
        suggestJob = viewModelScope.launch { delay(350); suggest() }
    }

    private suspend fun suggest() {
        val f = _form.value
        val s = c.repository.suggestCategory(f.merchant, f.brandKey)
        _form.update {
            if (!it.categoryTouched) it.copy(categoryId = s, suggestedCategoryId = s) else it.copy(suggestedCategoryId = s)
        }
        if (!_form.value.paymentTouched) {
            val pm = c.repository.suggestPaymentMethod(f.merchant)
            if (pm != null) _form.update { if (!it.paymentTouched) it.copy(paymentMethodId = pm) else it }
        }
    }

    fun setAmount(v: String) = _form.update { it.copy(amountText = v) }
    fun setRefund(v: Boolean) = _form.update { it.copy(isRefund = v) }
    fun setDate(v: LocalDate) = _form.update { it.copy(date = v) }
    fun setCategory(id: String?) = _form.update { it.copy(categoryId = id, categoryTouched = true) }
    fun setNote(v: String) = _form.update { it.copy(note = v) }
    fun setPaymentMethodId(id: String?) = _form.update { it.copy(paymentMethodId = id, paymentTouched = true, paymentMethod = if (id == null) "" else it.paymentMethod) }

    suspend fun createPaymentMethod(m: PaymentMethodEntity) = c.repository.savePaymentMethod(m)
    suspend fun importPaymentIcon(uri: Uri): String = c.paymentIcons.importImage(uri)
    /** Icon picked in the icon sheet. Picking a logo also fills an empty name and the category. */
    fun setIcon(ref: String?, brand: Brand?) {
        _form.update { it.copy(brandKey = ref, merchant = if (brand != null && it.merchant.isBlank()) brand.name else it.merchant) }
        if (brand != null && !_form.value.categoryTouched) viewModelScope.launch {
            c.repository.categoryForBrand(brand)?.let { id -> _form.update { it.copy(categoryId = id, suggestedCategoryId = id) } }
        }
    }

    fun attachReceipt(uri: Uri) = viewModelScope.launch {
        val name = c.receipts.importImage(uri)
        _form.update { it.copy(receiptFile = name) }
    }

    fun removeReceipt() = _form.update { it.copy(receiptFile = null) }

    fun receiptFile(name: String) = c.receipts.file(name)

    fun save(onDone: () -> Unit) = viewModelScope.launch {
        val f = _form.value
        val cents = f.amountCents ?: return@launch
        val base = PurchaseEntity(
            merchant = f.merchant.trim(),
            amountCents = if (f.isRefund) -cents else cents,
            date = f.date,
            categoryId = f.categoryId,
            note = f.note.trim(),
            paymentMethod = f.paymentMethodId?.let { id -> paymentMethods.value.firstOrNull { it.id == id }?.displayName } ?: f.paymentMethod.trim(),
            paymentMethodId = f.paymentMethodId,
            brandKey = f.brandKey,
            receiptFile = f.receiptFile,
            source = f.source,
            subscriptionId = f.subscriptionId,
            createdAt = f.createdAt,
        )
        c.repository.savePurchase(if (f.id != null) base.copy(id = f.id) else base)
        onDone()
    }

    fun delete(onDone: () -> Unit) = viewModelScope.launch {
        val id = _form.value.id ?: return@launch
        c.repository.purchase(id)?.let { c.repository.deletePurchase(it) }
        onDone()
    }

    /**
     * Hands everything typed so far to the subscription editor (the Purchase ⇄ Subscription
     * switch at the top of a new item). Works for scans and for manually entered purchases.
     */
    fun convertToSubscription() {
        val f = _form.value
        val base = draft?.result ?: ScanResult(
            kind = ScanKind.SUBSCRIPTION, merchant = null, brand = null, amountCents = null, date = null,
            cycle = null, nextBillingDate = null, trialEndDate = null,
            amountCandidates = emptyList(), subscriptionScore = 0, rawText = "",
        )
        c.scanDrafts.put(
            ScanDraft(
                result = base.copy(
                    kind = ScanKind.SUBSCRIPTION, merchant = f.merchant, amountCents = f.amountCents, date = f.date,
                    amountCandidates = f.amountCandidates, rawText = f.scanText ?: "",
                ),
                receiptFile = f.receiptFile,
                categoryId = f.categoryId.takeIf { f.categoryTouched },
                paymentMethodId = f.paymentMethodId,
                brandKey = f.brandKey,
                note = f.note,
                switched = true,
            )
        )
    }
}
