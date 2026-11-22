package br.com.docscan.data.imaging

import android.graphics.Bitmap
import br.com.docscan.domain.Filter
import br.com.docscan.domain.Look
import kotlin.coroutines.coroutineContext
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlinx.coroutines.ensureActive

internal suspend fun applyFilter(bitmap: Bitmap, look: Look) {
    val filter = look.filter
    if (filter == Filter.ORIGINAL && look.brightness == 100 && look.contrast == 100) return

    val width = bitmap.width
    val height = bitmap.height
    val scale = maxOf(width, height) / 1400f
    val useInk = filter != Filter.ORIGINAL && look.useMl
    val background = if (filter != Filter.ORIGINAL && (look.strength > 0 || useInk)) paperBackground(bitmap) else null
    try {
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val strength = if (background == null) 0f else look.strength / 100f
        val brightness = look.brightness / 100f
        val contrast = (look.contrast / 100f).pow(1.35f)
        val row = IntArray(width)
        val features = FloatArray(10)
        val bandHeight = 96
        val halo = if (useInk) (12 * scale).roundToInt().coerceAtLeast(3) else 0
        for (firstRow in 0 until height step bandHeight) {
            coroutineContext.ensureActive()
            val endRow = minOf(height, firstRow + bandHeight)
            val start = maxOf(0, firstRow - halo)
            val end = minOf(height, endRow + halo)
            val count = width * (end - start)
            val base = IntArray(count)
            background?.getPixels(base, 0, width, 0, start, width, end - start)
            val luminance = if (useInk) FloatArray(count) { index ->
                val pixel = pixels[start * width + index]
                val paper = base[index]
                luma(normalized(red(pixel), red(paper)), normalized(green(pixel), green(paper)), normalized(blue(pixel), blue(paper)))
            } else null
            val local = luminance?.let { InkModel.features(it, width, end - start, scale) }
            for (y in firstRow until endRow) {
                for (x in 0 until width) {
                    val i = (y - start) * width + x
                    val pixel = pixels[y * width + x]
                    val paper = base[i]
                    val r = red(pixel)
                    val g = green(pixel)
                    val b = blue(pixel)
                    val paperR = red(paper).coerceAtLeast(45f)
                    val paperG = green(paper).coerceAtLeast(45f)
                    val paperB = blue(paper).coerceAtLeast(45f)
                    val ink = if (local != null) {
                        val sat = saturation(normalized(r, paperR), normalized(g, paperG), normalized(b, paperB))
                        InkModel.probability(i, luminance[i], sat, luma(paperR, paperG, paperB) / 255, local, features)
                    } else Float.NaN
                    row[x] = stylePixel(
                        adjust(r, paperR, strength, brightness, contrast),
                        adjust(g, paperG, strength, brightness, contrast),
                        adjust(b, paperB, strength, brightness, contrast),
                        filter,
                        ink,
                    )
                }
                bitmap.setPixels(row, 0, width, 0, y, width, 1)
            }
        }
    } finally {
        background?.recycle()
    }
}

private fun normalized(channel: Float, paper: Float) = (channel / paper.coerceAtLeast(45f)).coerceAtMost(1.25f)

private fun adjust(value: Float, paper: Float, strength: Float, brightness: Float, contrast: Float): Float {
    val corrected = value + ((value / paper * 255).coerceAtMost(255f) - value) * strength
    return ((corrected * brightness - 128) * contrast + 128).coerceIn(0f, 255f)
}

private fun stylePixel(r: Float, g: Float, b: Float, filter: Filter, ink: Float): Int {
    if (filter == Filter.ORIGINAL) return rgb(r, g, b)
    val light = luma(r, g, b)
    val sat = saturation(r, g, b)
    val colorR = light + (r - light) * 1.38f
    val colorG = light + (g - light) * 1.38f
    val colorB = light + (b - light) * 1.38f
    if (!ink.isNaN()) {
        val opacity = if (filter == Filter.SHARP_BW) smooth((ink - 0.3f) / 0.4f) else smooth((ink - 0.02f) / 0.28f)
        val keep = maxOf(opacity, ((sat - 0.3f) / 0.2f).coerceIn(0f, 1f))
        return when (filter) {
            Filter.MAGIC_COLOR -> rgb(white(colorR, keep), white(colorG, keep), white(colorB, keep))
            Filter.LIGHTEN -> {
                val blend = 0.15f + 0.85f * keep
                rgb(white(r, blend), white(g, blend), white(b, blend))
            }
            Filter.SHARP_BW -> gray(255 * (1 - keep))
            Filter.GRAYSCALE -> gray(white(light, keep))
            Filter.ORIGINAL -> rgb(r, g, b)
        }
    }
    return when (filter) {
        Filter.MAGIC_COLOR -> {
            if (light > 205 && sat < 0.22f) {
                val keep = 1 - ((light - 205) / 35).coerceIn(0f, 1f)
                rgb(white(r, keep), white(g, keep), white(b, keep))
            } else rgb(colorR, colorG, colorB)
        }
        Filter.LIGHTEN -> {
            val keep = 1 - ((light - 195) / 45).coerceIn(0f, 1f)
            rgb(white(r, keep), white(g, keep), white(b, keep))
        }
        Filter.SHARP_BW -> gray(255 * smooth((light - 145) / 43))
        Filter.GRAYSCALE -> gray(white(light, 1 - ((light - 205) / 38).coerceIn(0f, 1f)))
        Filter.ORIGINAL -> rgb(r, g, b)
    }
}

private fun gray(value: Float) = rgb(value, value, value)
private fun white(value: Float, keep: Float) = 255 + (value - 255) * keep

private fun smooth(value: Float): Float {
    val t = value.coerceIn(0f, 1f)
    return t * t * (3 - 2 * t)
}
