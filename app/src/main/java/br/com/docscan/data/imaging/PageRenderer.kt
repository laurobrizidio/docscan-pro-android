package br.com.docscan.data.imaging

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import br.com.docscan.domain.Crop
import br.com.docscan.domain.Look
import br.com.docscan.domain.ScanPage
import java.io.File
import java.io.IOException
import kotlin.math.hypot
import kotlin.math.roundToInt

internal const val EXPORT_DIMENSION = 2400

internal class PageRenderer {
    suspend fun render(file: File, page: ScanPage, maxDimension: Int, original: Boolean = false): Bitmap {
        val source = decode(file, maxDimension)
        val framed = try {
            frame(source, page, maxDimension)
        } finally {
            source.recycle()
        }
        try {
            applyFilter(framed, if (original) Look(br.com.docscan.domain.Filter.ORIGINAL) else page.look)
            return framed
        } catch (error: Exception) {
            framed.recycle()
            throw error
        }
    }

    fun detect(file: File) = decode(file, 420).let { bitmap ->
        try {
            DocumentEdges(bitmap.width, bitmap.height).detect(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    private fun frame(source: Bitmap, page: ScanPage, maxDimension: Int): Bitmap {
        val quad = page.quad?.corners
        val w = source.width.toFloat()
        val h = source.height.toFloat()
        val points = quad?.flatMap { listOf(it.x * w, it.y * h) }?.toFloatArray()
        fun distance(first: Int, second: Int): Float {
            requireNotNull(points)
            return hypot(points[first * 2] - points[second * 2], points[first * 2 + 1] - points[second * 2 + 1])
        }

        val rectWidth = if (points == null) w else (distance(0, 1) + distance(2, 3)) / 2
        val rectHeight = if (points == null) h else (distance(1, 2) + distance(3, 0)) / 2
        val scale = minOf(1f, maxDimension / maxOf(rectWidth, rectHeight))
        val width = (rectWidth * scale).roundToInt().coerceAtLeast(1)
        val height = (rectHeight * scale).roundToInt().coerceAtLeast(1)
        val matrix = Matrix()
        if (points != null) {
            val right = width.toFloat()
            val bottom = height.toFloat()
            val mapped = matrix.setPolyToPoly(points, 0, floatArrayOf(0f, 0f, right, 0f, right, bottom, 0f, bottom), 0, 4)
            if (!mapped) return frame(source, page.copy(quad = null), maxDimension)
        } else {
            matrix.setScale(width / w, height / h)
        }

        val outputWidth = if (page.quarterTurns % 2 == 0) width else height
        val outputHeight = if (page.quarterTurns % 2 == 0) height else width
        matrix.postTranslate(-width / 2f, -height / 2f)
        matrix.postRotate(page.quarterTurns * 90f + page.fineRotation)
        matrix.postTranslate(outputWidth / 2f, outputHeight / 2f)
        return Bitmap.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888).also {
            Canvas(it).apply {
                drawColor(Color.WHITE)
                drawBitmap(source, matrix, Paint(Paint.FILTER_BITMAP_FLAG))
            }
        }
    }

    companion object {
        fun decode(file: File, maxDimension: Int): Bitmap = decodeSampled(maxDimension) { options ->
            BitmapFactory.decodeFile(file.path, options)
        }

        fun crop(source: Bitmap, crop: Crop): Bitmap {
            if (crop == Crop.Full) return source
            val left = (crop.left * source.width).roundToInt().coerceIn(0, source.width - 1)
            val top = (crop.top * source.height).roundToInt().coerceIn(0, source.height - 1)
            val right = (crop.right * source.width).roundToInt().coerceIn(left + 1, source.width)
            val bottom = (crop.bottom * source.height).roundToInt().coerceIn(top + 1, source.height)
            return Bitmap.createBitmap(source, left, top, right - left, bottom - top)
        }
    }
}

internal fun decodeSampled(maxDimension: Int, decode: (BitmapFactory.Options) -> Bitmap?): Bitmap {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    decode(bounds)
    val dimension = maxOf(bounds.outWidth, bounds.outHeight)
    if (dimension <= 0) throw IOException("Imagem inválida")
    var sample = 1
    while (dimension / (2L * sample) >= maxDimension) sample *= 2
    val options = BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    val bitmap = decode(options) ?: throw IOException("Imagem inválida")
    val size = maxOf(bitmap.width, bitmap.height)
    if (size <= maxDimension) return bitmap
    val scale = maxDimension.toFloat() / size
    return Bitmap.createScaledBitmap(
        bitmap,
        (bitmap.width * scale).roundToInt().coerceAtLeast(1),
        (bitmap.height * scale).roundToInt().coerceAtLeast(1),
        true,
    ).also { if (it !== bitmap) bitmap.recycle() }
}
