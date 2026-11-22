package br.com.docscan.data

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import br.com.docscan.data.imaging.EXPORT_DIMENSION
import br.com.docscan.data.imaging.decodeSampled
import java.io.File
import java.io.IOException
import kotlin.math.roundToInt

internal fun storeImage(resolver: ContentResolver, uri: Uri, file: File) {
    val source = try {
        if (Build.VERSION.SDK_INT >= 28) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, image, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
                val width = image.size.width
                val height = image.size.height
                val scale = minOf(1f, EXPORT_DIMENSION.toFloat() / maxOf(width, height))
                decoder.setTargetSize(
                    (width * scale).roundToInt().coerceAtLeast(1),
                    (height * scale).roundToInt().coerceAtLeast(1),
                )
            }
        } else {
            readWithExif(resolver, uri)
        }
    } catch (error: SecurityException) {
        throw IOException("Sem acesso à imagem", error)
    } catch (error: IllegalArgumentException) {
        throw IOException("Imagem inválida", error)
    }
    val opaque = if (source.hasAlpha()) {
        Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888).also {
            Canvas(it).apply {
                drawColor(Color.WHITE)
                drawBitmap(source, 0f, 0f, null)
            }
            source.recycle()
        }
    } else source
    try {
        file.outputStream().buffered().use {
            if (!opaque.compress(Bitmap.CompressFormat.JPEG, 96, it)) throw IOException("Falha ao armazenar a imagem")
        }
    } finally {
        opaque.recycle()
    }
}

private fun readWithExif(resolver: ContentResolver, uri: Uri): Bitmap {
    val orientation = resolver.openInputStream(uri)?.use { stream ->
        try {
            ExifInterface(stream).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } catch (_: IOException) {
            ExifInterface.ORIENTATION_NORMAL
        }
    } ?: ExifInterface.ORIENTATION_NORMAL
    val bitmap = decodeSampled(EXPORT_DIMENSION) { options ->
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
    }
    val matrix = Matrix()
    when (orientation) {
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
        ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
        ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.setRotate(90f); matrix.postScale(-1f, 1f) }
        ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
        ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.setRotate(270f); matrix.postScale(-1f, 1f) }
        ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(270f)
        else -> return bitmap
    }
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true).also {
        if (it !== bitmap) bitmap.recycle()
    }
}
