package br.com.docscan.domain

/** Confidence is a percentage from 0 to 100, or -1 when unknown. */
data class OcrPage(val pageId: String, val text: String, val confidence: Int)

interface TextRecognizer {
    suspend fun recognize(page: ScanPage): OcrPage
    fun close()
}

private val excessNewlines = Regex("\n{3,}")

fun normalizeOcrText(raw: String): String = raw.lineSequence()
    .joinToString("\n") { it.trimEnd() }
    .replace(excessNewlines, "\n\n")
    .trim()

fun joinOcrPages(pages: List<OcrPage>): String = when (pages.size) {
    0 -> ""
    1 -> pages.single().text
    else -> pages.mapIndexed { index, page ->
        "Página ${index + 1}\n\n${page.text}".trimEnd()
    }.joinToString("\n\n")
}
