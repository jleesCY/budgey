package com.jlees.budgey.ui.tools

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jlees.budgey.AppContainer
import com.jlees.budgey.domain.Check
import com.jlees.budgey.domain.CheckSplitter
import com.jlees.budgey.domain.CustomKind
import com.jlees.budgey.domain.Money
import com.jlees.budgey.domain.SplitItem
import com.jlees.budgey.domain.SplitMode
import com.jlees.budgey.domain.SplitPerson
import com.jlees.budgey.domain.SplitResult
import com.jlees.budgey.domain.TipSpec
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import java.io.File
import com.jlees.budgey.scan.ModelState
import com.jlees.budgey.scan.NanoStatus
import com.jlees.budgey.scan.ScanEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.withContext

/** The text boxes for the bill's summary lines, kept as typed. */
data class BillInputs(
    val subtotal: String = "",
    val tax: String = "",
    val fees: String = "",
    /** Custom tip amount (used when the tip is a fixed amount rather than a percentage). */
    val tipAmount: String = "",
)

@OptIn(FlowPreview::class)
class CheckSplitViewModel(private val c: AppContainer) : ViewModel() {
    private fun id() = UUID.randomUUID().toString()

    private val _inputs = MutableStateFlow(BillInputs())
    val inputs: StateFlow<BillInputs> = _inputs.asStateFlow()

    private val _check = MutableStateFlow(
        Check(people = listOf(SplitPerson(id(), ""), SplitPerson(id(), "")))
    )
    val check: StateFlow<Check> = _check.asStateFlow()

    val result: StateFlow<SplitResult> = _check.combine(_inputs) { check, _ -> CheckSplitter.split(check) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, CheckSplitter.split(_check.value))

    val scanning = MutableStateFlow(false)
    /** e.g. "Found 7 items with Vision AI". */
    val scanSummary = MutableStateFlow<String?>(null)
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 4)

    // ---------------------------------------------------------------- scanning

    /** The receipt photo (a private copy, so rescans and "pick up where you left off" work). */
    val image = MutableStateFlow<File?>(null)

    /** A new photo: keep a copy, then read it with the scanner chosen in Settings. */
    fun scan(uri: Uri) = viewModelScope.launch {
        scanning.value = true
        val copy = runCatching { store.keepImage(uri) }.getOrNull()
        if (copy != null) image.value = copy
        read(if (copy != null) Uri.fromFile(copy) else uri, engine = null)
    }

    /** Read the same photo again: Gemini Nano if it's ready on this phone, otherwise Standard. */
    fun rescan() = rescanWith(if (rescanState.value.nanoReady) ScanEngine.GEMINI_NANO else ScanEngine.STANDARD)

    /** Read the same photo again with Vision AI (must be downloaded). */
    fun advancedRescan() = rescanWith(ScanEngine.VISION_AI)

    private fun rescanWith(engine: ScanEngine) = viewModelScope.launch {
        val file = image.value?.takeIf { it.isFile } ?: run {
            messages.tryEmit("The photo isn't available any more — scan it again")
            return@launch
        }
        scanning.value = true
        read(Uri.fromFile(file), engine)
    }

    private suspend fun read(uri: Uri, engine: ScanEngine?) {
        scanning.value = true
        runCatching { c.itemScanner.scan(uri, engine) }
            .onSuccess { outcome -> apply(outcome) }
            .onFailure { messages.tryEmit("Couldn't read that picture: ${it.message ?: "unknown error"}") }
        scanning.value = false
    }

    private fun apply(outcome: com.jlees.budgey.scan.ItemScanner.Outcome) {
        val r = outcome.receipt
        if (r.isEmpty) {
            messages.tryEmit("Couldn't find any items — try a straighter, closer photo, or add them yourself")
        } else {
            _check.update { ch ->
                ch.copy(
                    items = r.items.map { SplitItem(id(), it.name, it.priceCents, it.qty) },
                    subtotalOverride = r.subtotalCents,
                    taxCents = r.taxCents ?: 0,
                    feesCents = r.feesCents ?: 0,
                    tip = if (r.tipCents != null && r.tipCents > 0) TipSpec(percent = null, amountCents = r.tipCents) else ch.tip,
                )
            }
            _inputs.value = BillInputs(
                subtotal = r.subtotalCents?.let(Money::toInput) ?: "",
                tax = r.taxCents?.let(Money::toInput) ?: "",
                fees = r.feesCents?.let(Money::toInput) ?: "",
                tipAmount = r.tipCents?.takeIf { it > 0 }?.let(Money::toInput) ?: "",
            )
            val n = r.items.size
            scanSummary.value = "Found $n item${if (n == 1) "" else "s"} with ${outcome.readWith} — check them below."
        }
        outcome.note?.let { messages.tryEmit(it) }
    }

    // ---------------------------------------------------------------- rescan options

    data class RescanState(
        val nanoReady: Boolean = false,
        /** Enough memory for Vision AI; without it there's no advanced rescan. */
        val visionFits: Boolean = false,
        val vision: ModelState = ModelState.NotDownloaded,
    )

    val rescanState = MutableStateFlow(RescanState())

    /** Re-checked whenever the screen is in view (e.g. back from downloading Vision AI). */
    suspend fun refreshRescan() {
        val v = ScanEngine.VISION_AI
        rescanState.value = RescanState(
            nanoReady = c.nanoScanner.status() == NanoStatus.READY,
            visionFits = c.smartScanner.fits(v),
            vision = withContext(Dispatchers.IO) { c.smartScanner.state(v) },
        )
    }

    // ---------------------------------------------------------------- saving progress

    private val store = SplitStore(c.context)

    /** A split saved from last time, offered as "pick up where you left off". */
    val resumeOffer = MutableStateFlow<SavedSplit?>(null)
    /** Saving starts once any "resume?" question is answered, so it can't overwrite the saved split. */
    private var saving = false

    init {
        viewModelScope.launch {
            val saved = store.load()
            if (saved != null && saved.isMeaningful) resumeOffer.value = saved else saving = true
            // Save as you go (debounced), so leaving the screen never loses your work.
            combine(_check, _inputs, image, scanSummary) { ch, inp, img, sum -> SavedSplit.of(ch, inp, img, sum) }
                .debounce(400)
                .collect { if (saving) store.save(it) }
        }
    }

    fun resume() {
        val saved = resumeOffer.value ?: return
        _check.value = saved.toCheck()
        _inputs.value = saved.toInputs()
        image.value = saved.image?.let(::File)?.takeIf { it.isFile }
        scanSummary.value = saved.summary
        resumeOffer.value = null
        saving = true
    }

    fun startFresh() {
        resumeOffer.value = null
        store.clear()
        saving = true
    }

    override fun onCleared() {
        // Last save on the way out (the debounced one may not have run yet).
        if (saving) store.saveNow(SavedSplit.of(_check.value, _inputs.value, image.value, scanSummary.value))
    }

    // ---------------------------------------------------------------- people

    fun addPerson() = _check.update { it.copy(people = it.people + SplitPerson(id(), "")) }

    fun removePerson(personId: String) = _check.update { ch ->
        if (ch.people.size <= 1) ch
        else ch.copy(
            people = ch.people.filterNot { it.id == personId },
            items = ch.items.map { it.copy(people = it.people - personId) },
        )
    }

    /** Sets how many people are splitting (adds blank people or removes from the end). */
    fun setPeopleCount(n: Int) = _check.update { ch ->
        val target = n.coerceIn(1, 50)
        when {
            target > ch.people.size -> ch.copy(people = ch.people + (ch.people.size + 1..target).map { SplitPerson(id(), "") })
            target < ch.people.size -> {
                val keep = ch.people.take(target)
                val ids = keep.map { it.id }.toSet()
                ch.copy(people = keep, items = ch.items.map { it.copy(people = it.people.filter { p -> p in ids }.toSet()) })
            }
            else -> ch
        }
    }

    private fun updatePerson(personId: String, f: (SplitPerson) -> SplitPerson) =
        _check.update { ch -> ch.copy(people = ch.people.map { if (it.id == personId) f(it) else it }) }

    fun renamePerson(personId: String, name: String) = updatePerson(personId) { it.copy(name = name.take(30)) }
    fun setSharesTip(personId: String, on: Boolean) = updatePerson(personId) { it.copy(sharesTip = on) }
    fun setCustom(personId: String, text: String) =
        updatePerson(personId) { it.copy(custom = text.filter { ch -> ch.isDigit() || ch == '.' || ch == ',' }.take(10)) }

    /** Custom split: give whatever's left to everyone who hasn't been given an amount yet. */
    fun splitRemainingEvenly() = _check.update { ch ->
        val empty = ch.people.filter { CheckSplitter.parseNumber(it.custom) == null }
        if (empty.isEmpty()) return@update ch
        val res = CheckSplitter.split(ch)
        val remaining = res.unassignedCents.coerceAtLeast(0)
        val each = if (ch.customKind == CustomKind.PERCENT) {
            val used = ch.people.sumOf { (CheckSplitter.parseNumber(it.custom)?.toDouble() ?: 0.0) }
            String.format(java.util.Locale.US, "%.2f", ((100.0 - used) / empty.size).coerceAtLeast(0.0))
        } else Money.toInput((remaining + empty.size - 1) / empty.size)
        ch.copy(people = ch.people.map { p -> if (p in empty) p.copy(custom = each) else p })
    }

    // ---------------------------------------------------------------- items

    fun saveItem(itemId: String?, name: String, priceCents: Long, qty: Int) = _check.update { ch ->
        val clean = name.trim().ifEmpty { "Item" }
        if (itemId == null) ch.copy(items = ch.items + SplitItem(id(), clean, priceCents, qty.coerceAtLeast(1)))
        else ch.copy(items = ch.items.map { if (it.id == itemId) it.copy(name = clean, priceCents = priceCents, qty = qty.coerceAtLeast(1)) else it })
    }

    fun removeItem(itemId: String) = _check.update { ch -> ch.copy(items = ch.items.filterNot { it.id == itemId }) }

    fun toggleAssignee(itemId: String, personId: String) = _check.update { ch ->
        ch.copy(items = ch.items.map { item ->
            if (item.id != itemId) item
            else item.copy(people = if (personId in item.people) item.people - personId else item.people + personId)
        })
    }

    /** Everyone shared it (or, if everyone already has it, clear it). */
    fun toggleEveryone(itemId: String) = _check.update { ch ->
        val all = ch.people.map { it.id }.toSet()
        ch.copy(items = ch.items.map { item ->
            if (item.id != itemId) item else item.copy(people = if (item.people.containsAll(all)) emptySet() else all)
        })
    }

    fun clearItems() = _check.update { it.copy(items = emptyList()) }.also { scanSummary.value = null }

    // ---------------------------------------------------------------- bill

    fun setSubtotal(text: String) {
        _inputs.update { it.copy(subtotal = text) }
        _check.update { it.copy(subtotalOverride = Money.parse(text)) }
    }

    fun setTax(text: String) {
        _inputs.update { it.copy(tax = text) }
        _check.update { it.copy(taxCents = Money.parse(text) ?: 0) }
    }

    fun setFees(text: String) {
        _inputs.update { it.copy(fees = text) }
        _check.update { it.copy(feesCents = Money.parse(text) ?: 0) }
    }

    fun setTipPercent(percent: Double) = _check.update { it.copy(tip = TipSpec(percent = percent)) }

    fun setTipAmount(text: String) {
        _inputs.update { it.copy(tipAmount = text) }
        _check.update { it.copy(tip = TipSpec(percent = null, amountCents = Money.parse(text) ?: 0)) }
    }

    fun setMode(mode: SplitMode) = _check.update { it.copy(mode = mode) }
    fun setCustomKind(kind: CustomKind) = _check.update { ch -> ch.copy(customKind = kind, people = ch.people.map { it.copy(custom = "") }) }
    fun setTipByOrder(on: Boolean) = _check.update { it.copy(tipByOrder = on) }

    /** Start over with two people and an empty bill. */
    fun reset() {
        _inputs.value = BillInputs()
        _check.value = Check(people = listOf(SplitPerson(id(), ""), SplitPerson(id(), "")))
        scanSummary.value = null
        image.value?.delete()
        image.value = null
        store.clear()
    }

    /** Plain-text summary for sharing to a group chat. */
    fun summary(): String {
        val ch = _check.value
        val r = result.value
        return buildString {
            appendLine("Bill: ${Money.format(ch.totalCents)} (tip ${Money.format(ch.tipCents)})")
            r.shares.forEach { s ->
                append("• ${displayName(r.shares.indexOf(s), s.person)}: ${Money.format(s.totalCents)}")
                if (ch.mode == SplitMode.ITEMS && s.lines.isNotEmpty()) {
                    append(" — ")
                    append(s.lines.joinToString(", ") { (item, part) ->
                        val shared = item.people.size > 1 || item.people.isEmpty()
                        item.name + if (shared) " (shared ${CheckSplitter.format(part)})" else ""
                    })
                }
                appendLine()
            }
            if (r.roundingExtraCents > 0) appendLine("(Rounded up to the cent: +${Money.format(r.roundingExtraCents)})")
            append("Split with Budgey")
        }
    }
}

/** What to call someone in results: their name, or "Person 2" if they didn't type one. */
fun displayName(index: Int, p: SplitPerson): String = p.name.trim().ifEmpty { "Person ${index + 1}" }
