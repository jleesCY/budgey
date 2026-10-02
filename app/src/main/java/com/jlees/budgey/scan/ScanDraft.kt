package com.jlees.budgey.scan

/**
 * Hand-off from the scan screen to the purchase/subscription editors. Kept in memory
 * (not a nav argument) because it can hold a lot of OCR text.
 */
data class ScanDraft(
    val result: ScanResult,
    val receiptFile: String?,
    // Extra fields carried when switching a new item between purchase ⇄ subscription.
    val categoryId: String? = null,
    val paymentMethodId: String? = null,
    val brandKey: String? = null,
    val note: String = "",
    /** True when this came from the Purchase/Subscription switch rather than a scan. */
    val switched: Boolean = false,
)

class ScanDraftHolder {
    private var draft: ScanDraft? = null

    fun put(d: ScanDraft) {
        draft = d
    }

    /** Returns and clears the pending draft. */
    fun take(): ScanDraft? = draft.also { draft = null }
}
