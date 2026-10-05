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
import com.jlees.budgey.data.toScanResult
import com.jlees.budgey.scan.ScanKind
import com.jlees.budgey.scan.ScanResult
import com.jlees.budgey.domain.BillingCycle
import com.jlees.budgey.ui.navigation.PurchaseEditRoute
import kotlinx.coroutines.Job
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
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
    /** $0 is allowed (a freebie, or a fully discounted receipt). */
    val canSave: Boolean get() = merchant.isNotBlank() && (amountCents ?: -1) >= 0
}

@OptIn(FlowPreview::class)
class PurchaseEditViewModel(private val c: AppContainer, handle: SavedStateHandle) : ViewModel() {
    private val route = handle.toRoute<PurchaseEditRoute>()
    val isNew = route.id == null

    /** Saved or discarded: stop offering to resume it. */
    private var finished = false

    /** Which receipt photos this visit created / the saved purchase owns (see [com.jlees.budgey.data.ReceiptSession]). */
    private val photos = c.receiptSession()

    /** This editor has saved the current "resume" entry (so clearing the form may remove it). */
    private var ownsPending = false

    /** Set once the editor has loaded and started keeping an unfinished copy for "Resume". */
    private var startForm: PurchaseForm? = null

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
            } else if (route.resume) {
                val p = c.pendingAdds.get(ScanKind.PURCHASE)?.purchase
                _form.value = p?.toForm() ?: _form.value.copy(loaded = true)
            } else if (route.fromScan) {
                // The scan screen's hand-off is in memory; if Android closed Budgey meanwhile, use the
                // scan saved for "resume" instead.
                val d = c.scanDrafts.take() ?: c.pendingAdds.get(ScanKind.PURCHASE)?.scan?.let {
                    ScanDraft(it.toScanResult(c.brands::byId), it.receiptFile)
                }
                draft = d
                if (d != null) {
                    val r = d.result
                    val scanned = r.rawText.isNotBlank()
                    _form.update {
                        it.copy(
                            merchant = r.merchant ?: "",
                            amountText = r.amountCents?.let(Money::toInput) ?: "",
                            isRefund = r.isRefund,
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
            if (isNew) keepForResume()
        }
    }

    // ---------------------------------------------------------------- resume




    /**
     * While adding a new purchase, keep the form so leaving it can be undone with "Resume".
     * A blank editor you just glanced at doesn't replace an earlier unfinished one: saving starts
     * once you change something (or right away when it came from a scan or a resume).
     */
    private suspend fun keepForResume() {
        startForm = _form.value
        _form.debounce(300).collect { f -> if (!finished) keepNow(f) }
    }

    /** Saves [f] as the unfinished purchase if it's worth resuming. Returns whether it did. */
    private fun keepNow(f: PurchaseForm): Boolean {
        if (!f.loaded) return false
        // Anything you've entered counts — a name, price, date, category, payment method, icon,
        // note, photo or the refund switch — not just a price.
        val meaningful = f.userData() != blankForm().userData()
        if (!meaningful) {
            // Emptied out (e.g. a resumed one cleared and its photo removed): nothing left to resume.
            if (ownsPending || route.resume) c.pendingAdds.clear(ScanKind.PURCHASE, deleteReceipt = true)
            ownsPending = false
            return false
        }
        c.pendingAdds.put(ScanKind.PURCHASE, com.jlees.budgey.data.PendingAdd(purchase = f.toPending()))
        ownsPending = true
        return true
    }

    /** A new purchase as it opens, before you've touched anything. */
    private fun blankForm() = PurchaseForm(
        categoryId = route.categoryId,
        date = route.date?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: LocalDate.now(),
    )

    /** The fields you fill in (not bookkeeping like suggestions or timestamps). */
    private fun PurchaseForm.userData() = listOf(
        merchant.trim(), amountText.trim(), isRefund, date, categoryId, note.trim(),
        paymentMethodId, paymentMethod.trim(), brandKey, receiptFile,
    )

    /** Throw the unfinished purchase away (photo included). */
    fun discard(onDone: () -> Unit) {
        finished = true
        c.pendingAdds.clear(ScanKind.PURCHASE, deleteReceipt = true)
        photos.drop(_form.value.receiptFile)
        photos.abandon(keep = null)
        onDone()
    }

    override fun onCleared() {
        val f = _form.value
        when {
            // Editing a saved purchase and leaving without saving: drop photos attached meanwhile.
            !isNew -> photos.abandon(keep = null)
            finished -> photos.abandon(keep = null)
            // A new purchase you left: the "resume" entry keeps it (photo included) — written now,
            // since the debounced save may not have run yet.
            else -> photos.abandon(keep = if (startForm != null && keepNow(f)) f.receiptFile else null)
        }
    }

    private fun PurchaseForm.toPending() = com.jlees.budgey.data.PendingPurchase(
        merchant = merchant, amountText = amountText, isRefund = isRefund, date = date.toString(),
        categoryId = categoryId, categoryTouched = categoryTouched, note = note,
        paymentMethod = paymentMethod, paymentMethodId = paymentMethodId, paymentTouched = paymentTouched,
        brandKey = brandKey, receiptFile = receiptFile, source = source.name,
        amountCandidates = amountCandidates, scanText = scanText,
    )

    private fun com.jlees.budgey.data.PendingPurchase.toForm() = PurchaseForm(
        merchant = merchant, amountText = amountText, isRefund = isRefund,
        date = runCatching { LocalDate.parse(date) }.getOrDefault(LocalDate.now()),
        categoryId = categoryId, categoryTouched = categoryTouched, note = note,
        paymentMethod = paymentMethod, paymentMethodId = paymentMethodId, paymentTouched = paymentTouched,
        brandKey = brandKey, receiptFile = receiptFile,
        source = PurchaseSource.entries.firstOrNull { it.name == source } ?: PurchaseSource.MANUAL,
        amountCandidates = amountCandidates, scanText = scanText, loaded = true,
    )

    /** The item this editor was opened for doesn't exist (any more). */
    val notFound = kotlinx.coroutines.flow.MutableStateFlow(false)

    private suspend fun loadExisting(id: String) {
        val found = c.repository.purchase(id)
        notFound.value = found == null
        found?.let { p ->
            photos.stored = p.receiptFile
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
    fun reload() = viewModelScope.launch {
        photos.abandon(keep = null)
        route.id?.let { loadExisting(it) }
    }

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
        runCatching { photos.attach(uri, current = _form.value.receiptFile) }
            .onSuccess { name -> _form.update { it.copy(receiptFile = name) } }
    }

    fun removeReceipt() {
        photos.drop(_form.value.receiptFile)
        _form.update { it.copy(receiptFile = null) }
    }

    fun receiptFile(name: String) = c.receipts.file(name)

    private var saveJob: Job? = null

    /**
     * Saves once: extra taps while a save is running (or after a new item was already added) are
     * ignored. A quick double tap used to add the same item twice.
     */
    fun save(onDone: () -> Unit) {
        if (saveJob?.isActive == true || (isNew && finished)) return
        saveJob = saveNow(onDone)
    }

    private fun saveNow(onDone: () -> Unit) = viewModelScope.launch {
        val f = _form.value
        // Deleted somewhere else while this was open: saving would bring it back.
        route.id?.let { id -> if (c.repository.purchase(id) == null) { notFound.value = true; return@launch } }
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
        photos.saved(f.receiptFile) // frees a replaced photo
        if (isNew) {
            finished = true
            c.pendingAdds.clear(ScanKind.PURCHASE, deleteReceipt = false) // the purchase owns the photo now
        }
        onDone()
    }

    fun delete(onDone: () -> Unit) = viewModelScope.launch {
        val id = _form.value.id ?: return@launch
        c.repository.purchase(id)?.let { c.repository.deletePurchase(it) }
        finished = true
        photos.deleted()
        onDone()
    }
}
