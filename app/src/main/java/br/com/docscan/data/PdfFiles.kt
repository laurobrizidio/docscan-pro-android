package br.com.docscan.data

import android.content.Context
import android.graphics.Bitmap
import androidx.core.content.FileProvider
import br.com.docscan.data.imaging.EXPORT_DIMENSION
import br.com.docscan.data.imaging.PageRenderer
import br.com.docscan.domain.PdfOptions
import br.com.docscan.domain.ScanPage
import br.com.docscan.domain.ShareablePdf
import br.com.docscan.domain.layout
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class PdfFiles(private val context: Context, private val renderer: PageRenderer = PageRenderer()) {
    private val sharedPdfs = File(context.cacheDir, "shared-pdfs")

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
        val rendered = renderer.render(source, page, EXPORT_DIMENSION)
        try {
            val cropped = PageRenderer.crop(rendered, page.crop)
            try {
                val jpeg = ByteArrayOutputStream()
                if (!cropped.compress(Bitmap.CompressFormat.JPEG, options.quality.jpeg, jpeg)) {
                    throw IOException("Falha ao comprimir a página")
                }
                pdf.addPage(jpeg.toByteArray(), cropped.width, cropped.height, options.layout(cropped.width, cropped.height))
            } finally {
                if (cropped !== rendered) cropped.recycle()
            }
        } finally {
            rendered.recycle()
        }
    }
}
