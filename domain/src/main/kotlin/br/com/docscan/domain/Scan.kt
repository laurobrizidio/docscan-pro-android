package br.com.docscan.domain

import kotlin.math.min

const val MAX_PAGES = 60
const val MIN_CROP = 0.08f

data class PagePoint(val x: Float, val y: Float)

data class DocumentQuad(
    val topLeft: PagePoint,
    val topRight: PagePoint,
    val bottomRight: PagePoint,
    val bottomLeft: PagePoint,
) {
    val corners get() = listOf(topLeft, topRight, bottomRight, bottomLeft)
}

data class Crop(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    init {
        require(left >= 0f && top >= 0f && right <= 1f && bottom <= 1f)
        require(width > 0f && height > 0f)
    }

    val width get() = right - left
    val height get() = bottom - top

    fun rotateClockwise() = Crop(1f - bottom, left, 1f - top, right)

    fun withAspect(ratio: Float, imageRatio: Float): Crop {
        require(ratio > 0f && imageRatio > 0f)
        val normalizedRatio = ratio / imageRatio
        val fittedWidth = min(width, height * normalizedRatio)
        val fittedHeight = fittedWidth / normalizedRatio
        val x = left + (width - fittedWidth) / 2
        val y = top + (height - fittedHeight) / 2
        return Crop(x, y, min(1f, x + fittedWidth), min(1f, y + fittedHeight))
    }

    companion object {
        val Full = Crop(0f, 0f, 1f, 1f)
    }
}

enum class Filter(val strength: Int, val brightness: Int, val contrast: Int) {
    ORIGINAL(0, 100, 100),
    MAGIC_COLOR(85, 105, 115),
    LIGHTEN(75, 112, 110),
    SHARP_BW(90, 108, 135),
    GRAYSCALE(80, 106, 120),
}

data class Look(
    val filter: Filter = Filter.MAGIC_COLOR,
    val strength: Int = filter.strength,
    val brightness: Int = filter.brightness,
    val contrast: Int = filter.contrast,
    val useMl: Boolean = true,
)

data class ScanPage(
    val id: String,
    val quad: DocumentQuad? = null,
    val crop: Crop = Crop.Full,
    val quarterTurns: Int = 0,
    val fineRotation: Float = 0f,
    val look: Look = Look(),
) {
    fun rotateClockwise() = copy(quarterTurns = (quarterTurns + 1) % 4, crop = crop.rotateClockwise())

    fun withAutoFrame(detected: DocumentQuad) = copy(quad = detected, crop = Crop.Full, fineRotation = 0f)
}

data class Draft(val pages: List<ScanPage> = emptyList(), val selectedId: String? = null) {
    val selected get() = pages.firstOrNull { it.id == selectedId }

    fun add(newPages: List<ScanPage>): Draft {
        val accepted = newPages.take((MAX_PAGES - pages.size).coerceAtLeast(0))
        val first = accepted.firstOrNull() ?: return this
        return Draft(pages + accepted, first.id)
    }

    fun edit(page: ScanPage) = copy(pages = pages.map { if (it.id == page.id) page else it })

    fun moveSelected(direction: Int): Draft {
        val index = pages.indexOfFirst { it.id == selectedId }
        if (index < 0 || index + direction !in pages.indices) return this
        val reordered = pages.toMutableList()
        reordered.add(index + direction, reordered.removeAt(index))
        return copy(pages = reordered)
    }

    fun deleteSelected(): Draft {
        val index = pages.indexOfFirst { it.id == selectedId }
        if (index < 0) return this
        val remaining = pages.toMutableList().apply { removeAt(index) }
        return Draft(remaining, remaining.getOrNull(index)?.id ?: remaining.lastOrNull()?.id)
    }
}

enum class Paper { A4, LETTER, FIT_IMAGE }
enum class Orientation { AUTO, PORTRAIT, LANDSCAPE }
enum class PdfQuality(val jpeg: Int) { COMPACT(60), BALANCED(82), HIGH(95) }

data class PdfOptions(
    val name: String = "",
    val paper: Paper = Paper.A4,
    val orientation: Orientation = Orientation.AUTO,
    val marginMm: Int = 10,
    val quality: PdfQuality = PdfQuality.BALANCED,
) {
    val fileName: String
        get() {
            val safe = name.trim().replace(Regex("""[\\/:*?"<>|\p{Cntrl}]"""), "_")
                .replace(Regex("(?i)\\.pdf$"), "").trim().trim('.').take(100)
            return "${safe.ifBlank { "Documento_Digitalizado" }}.pdf"
        }
}
