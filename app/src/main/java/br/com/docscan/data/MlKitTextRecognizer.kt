package br.com.docscan.data

import android.graphics.Bitmap
import br.com.docscan.data.imaging.EXPORT_DIMENSION
import br.com.docscan.data.imaging.PageRenderer
import br.com.docscan.domain.Filter
import br.com.docscan.domain.Look
import br.com.docscan.domain.OcrPage
import br.com.docscan.domain.ScanPage
import br.com.docscan.domain.TextRecognizer
import br.com.docscan.domain.normalizeOcrText
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class MlKitTextRecognizer(
    private val renderer: PageRenderer,
    private val source: (ScanPage) -> File,
) : TextRecognizer {
    private val client = lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private val processingLock = Mutex()
    private var closed = false

    override suspend fun recognize(page: ScanPage): OcrPage = withContext(Dispatchers.Default) {
        processingLock.withLock {
            var bitmap: Bitmap? = null
            var task: Task<Text>? = null
            try {
                val original = page.copy(look = Look(Filter.ORIGINAL, useMl = false))
                val rendered = renderer.render(source(page), original, EXPORT_DIMENSION)
                bitmap = rendered
                val cropped = PageRenderer.crop(rendered, page.crop)
                bitmap = cropped
                if (cropped !== rendered) rendered.recycle()
                if (cropped.config != Bitmap.Config.ARGB_8888) {
                    bitmap = cropped.copy(Bitmap.Config.ARGB_8888, false) ?: throw IOException("Imagem inválida")
                    cropped.recycle()
                }

                ensureActive()
                val pending = process(InputImage.fromBitmap(bitmap, 0))
                task = pending
                val result = pending.awaitText()
                OcrPage(page.id, normalizeOcrText(result.text), result.confidence())
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                throw IOException("Falha ao reconhecer o texto", error)
            } finally {
                // A cancelled coroutine can leave ML Kit reading the bitmap.
                task?.takeUnless { it.isComplete }?.let { pending ->
                    withContext(NonCancellable) {
                        suspendCoroutine<Unit> { completion ->
                            pending.addOnCompleteListener { completion.resume(Unit) }
                        }
                    }
                }
                bitmap?.recycle()
            }
        }
    }

    @Synchronized
    private fun process(image: InputImage): Task<Text> {
        check(!closed)
        return client.value.process(image)
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        if (client.isInitialized()) client.value.close()
    }
}

private suspend fun Task<Text>.awaitText(): Text = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { continuation.resume(it) }
    addOnFailureListener { continuation.resumeWithException(it) }
    addOnCanceledListener { continuation.cancel() }
}

private fun Text.confidence(): Int {
    val lines = textBlocks.flatMap { it.lines }
    val length = lines.sumOf { it.text.length }
    if (length == 0) return -1
    val weighted = lines.sumOf { it.confidence.toDouble() * it.text.length }
    return (100 * weighted / length).roundToInt().coerceIn(0, 100)
}
