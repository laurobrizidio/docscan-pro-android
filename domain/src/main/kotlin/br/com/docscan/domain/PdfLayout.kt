package br.com.docscan.domain

import kotlin.math.roundToInt

data class PdfLayout(
    val pageWidth: Int,
    val pageHeight: Int,
    val imageWidth: Double,
    val imageHeight: Double,
    val x: Double,
    val y: Double,
)

fun PdfOptions.layout(imageWidth: Int, imageHeight: Int): PdfLayout {
    require(imageWidth > 0 && imageHeight > 0)
    val pointsPerMm = 72 / 25.4
    val margin = marginMm.coerceIn(0, 20) * pointsPerMm
    val (width, height) = when (paper) {
        Paper.A4 -> 210 * pointsPerMm to 297 * pointsPerMm
        Paper.LETTER -> 612.0 to 792.0
        Paper.FIT_IMAGE -> imageWidth * 0.75 + 2 * margin to imageHeight * 0.75 + 2 * margin
    }
    val landscape = when (orientation) {
        Orientation.AUTO -> imageWidth > imageHeight
        Orientation.PORTRAIT -> false
        Orientation.LANDSCAPE -> true
    }
    val pageWidth = (if (landscape) maxOf(width, height) else minOf(width, height)).roundToInt().coerceAtLeast(1)
    val pageHeight = (if (landscape) minOf(width, height) else maxOf(width, height)).roundToInt().coerceAtLeast(1)
    val scale = minOf(
        (pageWidth - 2 * margin).coerceAtLeast(0.5) / imageWidth,
        (pageHeight - 2 * margin).coerceAtLeast(0.5) / imageHeight,
    )
    val w = imageWidth * scale
    val h = imageHeight * scale
    return PdfLayout(pageWidth, pageHeight, w, h, (pageWidth - w) / 2, (pageHeight - h) / 2)
}
