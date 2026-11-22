package br.com.docscan.data

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.content.FileProvider
import br.com.docscan.data.imaging.EXPORT_DIMENSION
import br.com.docscan.data.imaging.PageRenderer
import br.com.docscan.domain.Draft
import br.com.docscan.domain.PdfOptions
import br.com.docscan.domain.ScanPage
import br.com.docscan.domain.ScanRepository
import br.com.docscan.domain.ShareablePdf
import br.com.docscan.domain.layout
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class LocalScanRepository(private val context: Context) : ScanRepository<Bitmap> {
    private val resolver = context.contentResolver
    private val cache = context.cacheDir
    private val directory = File(context.filesDir, "scans")
    private val sharedPdfs = File(cache, "shared-pdfs")
    private val store = DraftStore(directory)
    private val renderer = PageRenderer()

    override suspend fun loadDraft(): Draft = withContext(Dispatchers.IO) {
        val draft = store.read { imageFile(it).isFile }
        val active = draft.pages.mapTo(mutableSetOf()) { it.id }
        directory.listFiles()?.filter { it.extension == "jpg" && it.nameWithoutExtension !in active }
            ?.forEach { it.delete() }
        draft
    }

    override suspend fun saveDraft(draft: Draft) = withContext(Dispatchers.IO) {
        requireDirectory()
        store.write(draft)
    }

    override suspend fun importImage(source: String): ScanPage = withContext(Dispatchers.IO) {
        requireDirectory()
        val id = UUID.randomUUID().toString()
        val file = imageFile(id)
        try {
            storeImage(resolver, Uri.parse(source), file)
            ScanPage(id)
        } catch (error: Exception) {
            file.delete()
            throw error
        }
    }

    override suspend fun detectFrame(page: ScanPage) = withContext(Dispatchers.Default) {
        renderer.detect(imageFile(page.id))
    }

    override suspend fun preview(page: ScanPage, original: Boolean): Bitmap = withContext(Dispatchers.Default) {
        renderer.render(imageFile(page.id), page, 1100, original)
    }

    override suspend fun thumbnail(pageId: String): Bitmap? = withContext(Dispatchers.IO) {
        try {
            PageRenderer.decode(imageFile(pageId), 152)
        } catch (_: IOException) {
            null
        }
    }

    override suspend fun export(
        pages: List<ScanPage>,
        options: PdfOptions,
        destination: String,
        onPageSaved: (Int) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val temporary = File.createTempFile("scan-", ".pdf", cache)
        try {
            writePdf(temporary, pages, options, onPageSaved)
            val target = resolver.openOutputStream(Uri.parse(destination), "wt")
                ?: throw IOException("Destino indisponível")
            target.use { output -> temporary.inputStream().use { it.copyTo(output) } }
            Unit
        } finally {
            temporary.delete()
        }
    }

    override suspend fun prepareShare(
        pages: List<ScanPage>,
        options: PdfOptions,
        onPageSaved: (Int) -> Unit,
    ): ShareablePdf = withContext(Dispatchers.IO) {
        val staleBefore = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(7)
        sharedPdfs.listFiles()?.filter { it.lastModified() < staleBefore }?.forEach { it.deleteRecursively() }
        val folder = File(sharedPdfs, UUID.randomUUID().toString())
        if (!folder.mkdirs()) throw IOException("Armazenamento indisponível")
        val pdf = File(folder, options.fileName)
        try {
            writePdf(pdf, pages, options, onPageSaved)
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", pdf)
            ShareablePdf(uri.toString(), pdf.name)
        } catch (error: Exception) {
            folder.deleteRecursively()
            throw error
        }
    }

    private suspend fun writePdf(
        file: File,
        pages: List<ScanPage>,
        options: PdfOptions,
        onPageSaved: (Int) -> Unit,
    ) {
        file.outputStream().buffered().use { stream ->
            val pdf = JpegPdfWriter(stream, pages.size)
            for ((index, page) in pages.withIndex()) {
                withContext(Dispatchers.Default) { addPage(pdf, page, options) }
                onPageSaved(index + 1)
            }
            pdf.finish()
        }
    }

    private suspend fun addPage(pdf: JpegPdfWriter, page: ScanPage, options: PdfOptions) {
        val rendered = renderer.render(imageFile(page.id), page, EXPORT_DIMENSION)
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

    private fun imageFile(id: String) = File(directory, "$id.jpg")

    private fun requireDirectory() {
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Armazenamento indisponível")
    }
}
