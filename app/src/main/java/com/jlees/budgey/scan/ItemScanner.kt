package com.jlees.budgey.scan

import android.net.Uri
import com.jlees.budgey.data.SettingsRepository
import java.time.LocalDate

/**
 * Reads the line items of a receipt for the check splitter. Uses the AI scanner you picked in
 * Settings when it's ready (much better at messy receipts), otherwise Google's text reader plus
 * [ReceiptItems.fromRows]. If the AI model fails, the text reader's result is used.
 */
class ItemScanner(
    private val ocr: OcrEngine,
    private val smart: SmartScanner,
    private val nano: NanoScanner,
    private val settings: SettingsRepository,
) {
    data class Outcome(
        val receipt: ItemizedReceipt,
        /** Which reader produced it, for the "Read with …" note. */
        val readWith: String,
        /** Something worth telling the user (e.g. the AI model stopped). */
        val note: String? = null,
        /** What the text reader found (rows, top to bottom). */
        val ocrText: String = "",
        /** The AI model's raw answer, if one was used. */
        val aiReply: String? = null,
    )

    /** [engine] = read with this scanner (rescans); null = the one picked in Settings. */
    suspend fun scan(uri: Uri, engine: ScanEngine? = null): Outcome {
        val engine = engine ?: settings.current().scanEngine
        val today = LocalDate.now()
        var note: String? = null
        var aiReply: String? = null

        val useVision = engine.downloadable && smart.fits(engine) && smart.modelFile(engine) != null
        val useNano = engine == ScanEngine.GEMINI_NANO && nano.status() == NanoStatus.READY
        if (useVision || useNano) {
            val image = ocr.prepareModelImage(uri)
            if (image != null) try {
                val reply = if (useVision) smart.read(engine, image, today, ReceiptItems.AI_PROMPT)
                else nano.read(image, today, ReceiptItems.AI_PROMPT, maxTokens = 768) // room for long checks
                aiReply = reply
                ReceiptItems.fromAiReply(reply.orEmpty())?.takeIf { it.items.isNotEmpty() }?.let {
                    return Outcome(it, engine.title, aiReply = reply)
                }
                // Say so instead of quietly showing a weaker result.
                note = if (reply.isNullOrBlank()) "${engine.title} didn't answer, so the Standard reader was used."
                else "${engine.title}'s answer couldn't be used, so the Standard reader was used."
            } catch (e: ModelCrashedException) {
                note = (e.message ?: "The AI model stopped.") + " Used the Standard reader instead."
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // Fall through to the text reader.
            } finally {
                image.delete()
            }
        }

        // Google's reader reads the photo a few ways; keep the reading that found the most.
        val pages = ocr.scan(uri).pages
        val best = pages.map { p -> p to ReceiptItems.fromRows(p.lines.map { it.text }) }
            .maxByOrNull { (_, r) -> r.items.size * 2 + listOfNotNull(r.subtotalCents, r.totalCents, r.taxCents).size }
        return Outcome(
            best?.second ?: ItemizedReceipt(),
            ScanEngine.STANDARD.title,
            note,
            ocrText = best?.first?.lines.orEmpty().joinToString("\n") { it.text },
            aiReply = aiReply,
        )
    }
}
