package com.jlees.budgey.scan

/** One OCR pass over one version of the picture (rows of text, top to bottom). */
data class OcrPage(val lines: List<OcrLine>, val variant: String)

/** All passes over one picture. */
data class OcrScan(val pages: List<OcrPage>)
