package br.com.docscan.data.imaging

import android.graphics.Bitmap
import kotlin.math.roundToInt

/** Estimates the illumination of the paper from a coarse, color-aware grid. */
internal fun paperBackground(bitmap: Bitmap): Bitmap {
    val scale = 48f / maxOf(bitmap.width, bitmap.height)
    val width = (bitmap.width * scale).roundToInt().coerceAtLeast(4)
    val height = (bitmap.height * scale).roundToInt().coerceAtLeast(4)
    val small = Bitmap.createScaledBitmap(bitmap, width, height, true)
    val size = width * height
    val pixels = IntArray(size)
    small.getPixels(pixels, 0, width, 0, 0, width, height)
    if (small !== bitmap) small.recycle()

    val rgb = FloatArray(size * 3)
    val luminance = FloatArray(size)
    val saturation = FloatArray(size)
    for (y in 0 until height) {
        for (x in 0 until width) {
            val i = y * width + x
            var r = 0f
            var g = 0f
            var b = 0f
            for (ny in maxOf(0, y - 2)..minOf(height - 1, y + 2)) {
                for (nx in maxOf(0, x - 2)..minOf(width - 1, x + 2)) {
                    val pixel = pixels[ny * width + nx]
                    r = maxOf(r, red(pixel))
                    g = maxOf(g, green(pixel))
                    b = maxOf(b, blue(pixel))
                }
            }
            rgb[i * 3] = r
            rgb[i * 3 + 1] = g
            rgb[i * 3 + 2] = b
            luminance[i] = luma(r, g, b)
            saturation[i] = saturation(r, g, b)
        }
    }

    val bright = luminance.indices.sortedByDescending { luminance[it] }
        .take((size * 0.3f).roundToInt().coerceAtLeast(1)).map { saturation[it] }.sorted()
    val saturationLimit = (bright[bright.size / 2] + 0.2f).coerceIn(0.3f, 0.6f)
    val paperLuminance = luminance.filterIndexed { i, _ -> saturation[i] < saturationLimit }.sorted()
    val reference = paperLuminance.getOrNull((paperLuminance.size * 0.9f).toInt()) ?: 255f
    val filled = BooleanArray(size) { saturation[it] < saturationLimit && luminance[it] >= 0.3f * reference }
    var missing = filled.count { !it }
    while (missing in 1 until size) {
        val updated = mutableListOf<Int>()
        for (i in 0 until size) {
            if (filled[i]) continue
            val x = i % width
            val y = i / width
            var count = 0
            var r = 0f
            var g = 0f
            var b = 0f
            for (ny in maxOf(0, y - 1)..minOf(height - 1, y + 1)) {
                for (nx in maxOf(0, x - 1)..minOf(width - 1, x + 1)) {
                    val j = ny * width + nx
                    if (!filled[j]) continue
                    r += rgb[j * 3]
                    g += rgb[j * 3 + 1]
                    b += rgb[j * 3 + 2]
                    count++
                }
            }
            if (count == 0) continue
            rgb[i * 3] = r / count
            rgb[i * 3 + 1] = g / count
            rgb[i * 3 + 2] = b / count
            updated += i
        }
        updated.forEach { filled[it] = true }
        missing -= updated.size
    }

    for (i in pixels.indices) pixels[i] = rgb(rgb[i * 3], rgb[i * 3 + 1], rgb[i * 3 + 2])
    val grid = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    return Bitmap.createScaledBitmap(grid, bitmap.width, bitmap.height, true).also {
        if (it !== grid) grid.recycle()
    }
}

internal fun red(pixel: Int) = ((pixel ushr 16) and 255).toFloat()
internal fun green(pixel: Int) = ((pixel ushr 8) and 255).toFloat()
internal fun blue(pixel: Int) = (pixel and 255).toFloat()

internal fun rgb(r: Float, g: Float, b: Float): Int =
    (0xff shl 24) or (channel(r) shl 16) or (channel(g) shl 8) or channel(b)

private fun channel(value: Float) = value.coerceIn(0f, 255f).roundToInt()

internal fun saturation(r: Float, g: Float, b: Float): Float {
    val high = maxOf(r, g, b)
    return if (high <= 0.02f) 0f else (high - minOf(r, g, b)) / high
}
