package com.jlees.budgey.scan

/**
 * The scanners you can choose from (Settings → Scanner): the built-in Standard reader, or a
 * vision AI that looks at the picture itself — Gemini Nano (part of some phones) or
 * Gemma 4 E2B (an optional download).
 *
 * Sizes are exact file sizes from Hugging Face (litert-community), shown before downloading.
 */
enum class ScanEngine(
    val title: String,
    val summary: String,
    /** Download size in bytes (0 = nothing to download). */
    val bytes: Long,
    /** Hugging Face repo + file for downloadable models. */
    val repo: String? = null,
    val file: String? = null,
    /** Reads the picture itself (vision) vs. only the text the standard reader found. */
    val vision: Boolean = false,
    /** Rough RAM needed, in GB (phones report a little under their nominal size). */
    val minRamGb: Int = 0,
    val speed: String,
    /**
     * The model squashes every picture to a fixed square. Pad it to a square first so tall
     * receipts and wide screenshots aren't distorted.
     */
    val squareInput: Boolean = false,
) {
    STANDARD(
        title = "Standard",
        summary = "Google's text reader plus Budgey's rules. Built in, works on every phone.",
        bytes = 0,
        speed = "about 1 s",
    ),
    GEMINI_NANO(
        title = "Gemini Nano",
        summary = "Google's on-device AI, built into Android on supported phones. Looks at the picture. Budgey checks automatically whether this phone has it; Android manages the model, so nothing is stored in Budgey.",
        bytes = 0,
        vision = true,
        speed = "about 2–4 s",
    ),
    VISION_AI(
        title = "Vision AI (Gemma 4 E2B)",
        summary = "Looks at the whole picture — the strongest all-rounder for pumps, kiosks, screenshots and messy receipts.",
        bytes = 2_588_147_712,
        repo = "litert-community/gemma-4-E2B-it-litert-lm",
        file = "gemma-4-E2B-it.litertlm",
        vision = true,
        minRamGb = 8,
        speed = "about 3–8 s on recent phones",
    );

    val downloadable: Boolean get() = repo != null && file != null
    val url: String? get() = if (downloadable) "https://huggingface.co/$repo/resolve/main/$file" else null

    companion object {
        fun forFile(name: String): ScanEngine? = entries.firstOrNull { it.file.equals(name, ignoreCase = true) }
    }
}
