package br.com.docscan.data

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import br.com.docscan.data.imaging.PageRenderer
import br.com.docscan.domain.Draft
import br.com.docscan.domain.EditorSession
import br.com.docscan.domain.PdfOptions
import br.com.docscan.domain.ScanPage
import br.com.docscan.domain.ScanRepository
import br.com.docscan.domain.ShareablePdf
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class LocalScanRepository(
    context: Context,
    private val pdfFiles: PdfFiles = PdfFiles(context),
) : ScanRepository<Bitmap> {
    private val resolver = context.contentResolver
    private val cache = context.cacheDir
    private val directory = File(context.filesDir, "editor")
    private val store = DraftStore(directory)
    private val renderer = PageRenderer()

    override suspend fun loadSession(): EditorSession = withContext(Dispatchers.IO) {
        store.read { imageFile(it).isFile }.also { pruneImages(it.draft) }
    }

    override suspend fun saveSession(session: EditorSession, cleanup: Boolean) = withContext(Dispatchers.IO) {
        requireDirectory()
        store.write(session)
        if (cleanup) pruneImages(session.draft)
    }

    fun openSession(session: EditorSession, source: List<File>): EditorSession {
        requireDirectory()
        val copies = mutableListOf<File>()
        val copied = try {
            val pages = session.draft.pages.zip(source).map { (page, image) ->
                val id = UUID.randomUUID().toString()
                val target = imageFile(id)
                copies += target
                image.copyTo(target)
                page.copy(id = id)
            }
            val selected = session.draft.pages.indexOfFirst { it.id == session.draft.selectedId }
            session.copy(draft = Draft(pages, pages.getOrNull(selected)?.id ?: pages.firstOrNull()?.id))
                .also(store::write)
        } catch (error: Exception) {
            copies.forEach { it.delete() }
            throw error
        }
        pruneImages(copied.draft)
        return copied
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
    ): Unit = withContext(Dispatchers.IO) {
        val temporary = File.createTempFile("scan-", ".pdf", cache)
        try {
            pdfFiles.write(temporary, pages, options, { imageFile(it.id) }, onPageSaved)
            val target = resolver.openOutputStream(Uri.parse(destination), "wt")
                ?: throw IOException("Destino indisponível")
            target.use { output -> temporary.inputStream().use { it.copyTo(output) } }
        } finally {
            temporary.delete()
        }
    }

    override suspend fun prepareShare(
        pages: List<ScanPage>,
        options: PdfOptions,
        onPageSaved: (Int) -> Unit,
    ): ShareablePdf = pdfFiles.share(options.fileName) { pdf ->
        pdfFiles.write(pdf, pages, options, { imageFile(it.id) }, onPageSaved)
    }

    fun imageFile(id: String) = File(directory, "$id.jpg")

    private fun pruneImages(draft: Draft) {
        val active = draft.pages.mapTo(mutableSetOf()) { it.id }
        directory.listFiles()?.filter { it.extension == "jpg" && it.nameWithoutExtension !in active }
            ?.forEach { it.delete() }
    }

    private fun requireDirectory() {
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Armazenamento indisponível")
    }
}
