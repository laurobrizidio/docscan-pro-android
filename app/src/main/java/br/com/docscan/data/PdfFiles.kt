package br.com.docscan.data

import android.content.Context
import android.graphics.Bitmap
import androidx.core.content.FileProvider
import br.com.docscan.data.imaging.EXPORT_DIMENSION
import br.com.docscan.data.imaging.PageRenderer
import br.com.docscan.domain.PdfOptions
import br.com.docscan.domain.PdfQuality
import br.com.docscan.domain.ScanPage
import br.com.docscan.domain.ShareablePdf
import br.com.docscan.domain.layout
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

private const val PDF_OVERHEAD_PER_PAGE = 1024L
internal const val SHARED_PDF_DIRECTORY = "shared-pdfs"

internal class PdfFiles(private val context: Context, private val renderer: PageRenderer = PageRenderer()) {
    private val sharedPdfs = File(context.cacheDir, SHARED_PDF_DIRECTORY)

    suspend fun write(
        file: File,
        pages: List<ScanPage>,
        options: PdfOptions,
        source: (ScanPage) -> File,
        onPageSaved: (Int) -> Unit,
    ) = withContext(Dispatchers.IO) {
        file.outputStream().buffered().use { stream ->
            val pdf = JpegPdfWriter(stream, pages.size)
            for ((index, page) in pages.withIndex()) {
                val image = source(page)
                withContext(Dispatchers.Default) { addPage(pdf, image, page, options) }
                onPageSaved(index + 1)
            }
            pdf.finish()
        }
    }

    internal suspend fun estimate(
        pages: List<ScanPage>,
        source: (ScanPage) -> File,
        qualities: List<PdfQuality>,
    ): Map<PdfQuality, Long> = withContext(Dispatchers.Default) {
        if (pages.isEmpty()) return@withContext emptyMap()
        val sampleIndices = listOf(0, pages.size / 2).distinct()
        val groups = qualities.distinct().groupBy { it.renderLimit }
        val totals = mutableMapOf<PdfQuality, Long>()

        for (index in sampleIndices) {
            ensureActive()
            val page = pages[index]
            val image = source(page)
            for ((limit, group) in groups) {
                ensureActive()
                withCroppedPage(image, page, limit) { bitmap ->
                    for (quality in group) {
                        ensureActive()
                        val count = JpegByteCounter()
                        bitmap.writeJpeg(quality, count)
                        totals[quality] = (totals[quality] ?: 0L) + count.bytes
                    }
                }
            }
        }

        totals.mapValues { (_, bytes) -> (bytes / sampleIndices.size + PDF_OVERHEAD_PER_PAGE) * pages.size }
    }

    suspend fun share(
        name: String,
        documentId: String? = null,
        write: suspend (File) -> Unit,
    ): ShareablePdf = withContext(Dispatchers.IO) {
        val staleBefore = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(7)
        sharedPdfs.listFiles()?.filter { it.lastModified() < staleBefore }?.forEach { it.deleteRecursively() }
        val folder = File(sharedPdfs, "${documentId ?: "draft"}-${UUID.randomUUID()}")
        if (!folder.mkdirs()) throw IOException("Armazenamento indisponível")
        val pdf = File(folder, name)
        try {
            write(pdf)
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", pdf)
            ShareablePdf(uri.toString(), pdf.name)
        } catch (error: Exception) {
            folder.deleteRecursively()
            throw error
        }
    }

    fun deleteShares(documentIds: Set<String>) {
        sharedPdfs.listFiles()?.forEach { folder ->
            if (documentIds.any { folder.name.startsWith("$it-") }) folder.deleteRecursively()
        }
    }

    private suspend fun addPage(pdf: JpegPdfWriter, source: File, page: ScanPage, options: PdfOptions) {
        withCroppedPage(source, page, options.quality.renderLimit) { cropped ->
            val jpeg = ByteArrayOutputStream()
            cropped.writeJpeg(options.quality, jpeg)
            pdf.addPage(jpeg.toByteArray(), cropped.width, cropped.height, options.layout(cropped.width, cropped.height))
        }
    }

    private suspend inline fun withCroppedPage(
        source: File,
        page: ScanPage,
        limit: Int,
        use: (Bitmap) -> Unit,
    ) {
        val rendered = renderer.render(source, page, limit)
        try {
            val cropped = PageRenderer.crop(rendered, page.crop)
            try {
                use(cropped)
            } finally {
                if (cropped !== rendered) cropped.recycle()
            }
        } finally {
            rendered.recycle()
        }
    }
}

private val PdfQuality.renderLimit get() = minOf(EXPORT_DIMENSION, maxSide)

private fun Bitmap.writeJpeg(quality: PdfQuality, output: OutputStream) {
    if (!compress(Bitmap.CompressFormat.JPEG, quality.jpeg, output)) throw IOException("Falha ao comprimir a página")
}

private class JpegByteCounter : OutputStream() {
    var bytes = 0L
        private set

    override fun write(value: Int) {
        bytes++
    }

    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        bytes += length
    }
}
