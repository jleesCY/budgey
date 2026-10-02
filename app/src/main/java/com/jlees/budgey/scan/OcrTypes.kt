package com.jlees.budgey.scan

/** A recognized word/number and where it sits, as fractions (0–1) of the upright image. */
data class TextBox(val text: String, val left: Float, val top: Float, val right: Float, val bottom: Float)

/** One OCR pass over one version of the picture. */
data class OcrPage(val lines: List<OcrLine>, val boxes: List<TextBox>, val variant: String)

/** All passes over one picture, plus its upright width / height ratio. */
data class OcrScan(val pages: List<OcrPage>, val aspect: Float)
