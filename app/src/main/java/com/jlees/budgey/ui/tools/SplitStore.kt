package com.jlees.budgey.ui.tools

import android.content.Context
import android.net.Uri
import com.jlees.budgey.domain.Check
import com.jlees.budgey.domain.CustomKind
import com.jlees.budgey.domain.SplitItem
import com.jlees.budgey.domain.SplitMode
import com.jlees.budgey.domain.SplitPerson
import com.jlees.budgey.domain.TipSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** A check split in progress, as saved on the phone. */
@Serializable
data class SavedSplit(
    val items: List<Item> = emptyList(),
    val subtotalOverride: Long? = null,
    val taxCents: Long = 0,
    val feesCents: Long = 0,
    val tipPercent: Double? = 18.0,
    val tipAmount: Long? = null,
    val people: List<Person> = emptyList(),
    val mode: String = SplitMode.EVEN.name,
    val customKind: String = CustomKind.AMOUNT.name,
    val tipByOrder: Boolean = true,
    val subtotalText: String = "",
    val taxText: String = "",
    val feesText: String = "",
    val tipAmountText: String = "",
    /** Path of the saved receipt photo. */
    val image: String? = null,
    val summary: String? = null,
    val savedAt: Long = System.currentTimeMillis(),
) {
    @Serializable
    data class Item(val id: String, val name: String, val priceCents: Long, val qty: Int = 1, val people: List<String> = emptyList())

    @Serializable
    data class Person(val id: String, val name: String, val sharesTip: Boolean = true, val custom: String = "")

    /** Worth offering to resume (not just two blank people and an empty bill). */
    val isMeaningful: Boolean
        get() = items.isNotEmpty() || image != null || subtotalOverride != null || taxCents != 0L || feesCents != 0L ||
            people.any { it.name.isNotBlank() || it.custom.isNotBlank() } || people.size > 2

    fun toCheck() = Check(
        items = items.map { SplitItem(it.id, it.name, it.priceCents, it.qty, it.people.toSet()) },
        subtotalOverride = subtotalOverride,
        taxCents = taxCents,
        feesCents = feesCents,
        tip = TipSpec(percent = tipPercent, amountCents = tipAmount),
        people = people.map { SplitPerson(it.id, it.name, it.sharesTip, it.custom) }.ifEmpty { listOf(SplitPerson("p1", ""), SplitPerson("p2", "")) },
        mode = SplitMode.entries.firstOrNull { it.name == mode } ?: SplitMode.EVEN,
        customKind = CustomKind.entries.firstOrNull { it.name == customKind } ?: CustomKind.AMOUNT,
        tipByOrder = tipByOrder,
    )

    fun toInputs() = BillInputs(subtotalText, taxText, feesText, tipAmountText)

    companion object {
        fun of(ch: Check, inputs: BillInputs, image: File?, summary: String?) = SavedSplit(
            items = ch.items.map { Item(it.id, it.name, it.priceCents, it.qty, it.people.toList()) },
            subtotalOverride = ch.subtotalOverride,
            taxCents = ch.taxCents,
            feesCents = ch.feesCents,
            tipPercent = ch.tip.percent,
            tipAmount = ch.tip.amountCents,
            people = ch.people.map { Person(it.id, it.name, it.sharesTip, it.custom) },
            mode = ch.mode.name,
            customKind = ch.customKind.name,
            tipByOrder = ch.tipByOrder,
            subtotalText = inputs.subtotal,
            taxText = inputs.tax,
            feesText = inputs.fees,
            tipAmountText = inputs.tipAmount,
            image = image?.path,
            summary = summary,
        )
    }
}

/** Keeps one check split (and its receipt photo) in the app's private storage. */
class SplitStore(private val context: Context) {
    private val dir get() = File(context.filesDir, "tools").apply { mkdirs() }
    private val file get() = File(dir, "check_split.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    suspend fun load(): SavedSplit? = withContext(Dispatchers.IO) {
        runCatching { json.decodeFromString(SavedSplit.serializer(), file.readText()) }.getOrNull()
    }

    suspend fun save(split: SavedSplit) = withContext(Dispatchers.IO) { saveNow(split) }

    /** Writes right away (used when the screen closes). Empty splits remove the save instead. */
    fun saveNow(split: SavedSplit) {
        runCatching {
            if (!split.isMeaningful) {
                file.delete()
                return
            }
            val tmp = File(dir, "check_split.json.tmp")
            tmp.writeText(json.encodeToString(SavedSplit.serializer(), split))
            tmp.renameTo(file)
        }
    }

    /** Copies the photo you picked, so rescans work and it survives leaving the screen. */
    suspend fun keepImage(uri: Uri): File = withContext(Dispatchers.IO) {
        val out = File(dir, "check_split_receipt_${System.currentTimeMillis()}.jpg")
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Couldn't open that picture" }
            out.outputStream().use { input.copyTo(it) }
        }
        // Only one photo is kept.
        dir.listFiles()?.filter { it.name.startsWith("check_split_receipt_") && it != out }?.forEach { it.delete() }
        out
    }

    /** Forget the saved split and its photo. */
    fun clear() {
        runCatching {
            file.delete()
            dir.listFiles()?.filter { it.name.startsWith("check_split_receipt_") }?.forEach { it.delete() }
        }
    }
}
