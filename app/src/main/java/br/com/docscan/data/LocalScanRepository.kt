package br.com.docscan.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import br.com.docscan.data.imaging.EXPORT_DIMENSION
import br.com.docscan.data.imaging.PageRenderer
import br.com.docscan.domain.Draft
import br.com.docscan.domain.EditorSession
import br.com.docscan.domain.Filter
import br.com.docscan.domain.ID_CARD_RATIO
import br.com.docscan.domain.Look
import br.com.docscan.domain.PdfImport
import br.com.docscan.domain.PdfOptions
import br.com.docscan.domain.PdfQuality
import br.com.docscan.domain.PixelRect
import br.com.docscan.domain.ProtectedPdfException
import br.com.docscan.domain.ScanPage
import br.com.docscan.domain.ScanRepository
import br.com.docscan.domain.ShareablePdf
import br.com.docscan.domain.idSheetLayout
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
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

    override suspend fun composeIdSheet(front: ScanPage, back: ScanPage?): ScanPage {
        val layout = idSheetLayout(back != null)
        val page = ScanPage(UUID.randomUUID().toString(), look = Look(Filter.ORIGINAL, useMl = false))
        val file = imageFile(page.id)
        try {
            withContext(Dispatchers.Default) {
                val sheet = Bitmap.createBitmap(layout.sheetW, layout.sheetH, Bitmap.Config.ARGB_8888)
                try {
                    sheet.eraseColor(Color.WHITE)
                    val canvas = Canvas(sheet)
                    canvas.drawIdCard(front, layout.front, page.look)
                    if (back != null) canvas.drawIdCard(back, requireNotNull(layout.back), page.look)
                    withContext(Dispatchers.IO) {
                        requireDirectory()
                        file.outputStream().buffered().use { output ->
                            if (!sheet.compress(Bitmap.CompressFormat.JPEG, 95, output)) {
                                throw IOException("Falha ao gravar a folha do cartão")
                            }
                        }
                    }
                } finally {
                    sheet.recycle()
                }
            }
        } catch (error: Exception) {
            withContext(NonCancellable + Dispatchers.IO) { file.delete() }
            throw error
        }
        return page
    }

    private suspend fun Canvas.drawIdCard(page: ScanPage, rect: PixelRect, look: Look) {
        val rendered = renderer.render(imageFile(page.id), page.copy(look = look), 1400)
        var cropped = rendered
        var landscape = rendered
        try {
            cropped = PageRenderer.crop(rendered, page.crop)
            landscape = if (cropped.height > cropped.width) {
                val rotate = Matrix().apply { postRotate(90f) }
                Bitmap.createBitmap(cropped, 0, 0, cropped.width, cropped.height, rotate, true)
            } else cropped

            val frame = RectF(rect.x.toFloat(), rect.y.toFloat(), (rect.x + rect.w).toFloat(), (rect.y + rect.h).toFloat())
            val ratio = landscape.width.toFloat() / landscape.height
            val destination = if (ratio in ID_CARD_RATIO * 0.92f..ID_CARD_RATIO * 1.08f) {
                frame
            } else {
                val scale = minOf(rect.w.toFloat() / landscape.width, rect.h.toFloat() / landscape.height)
                val halfWidth = landscape.width * scale / 2
                val halfHeight = landscape.height * scale / 2
                RectF(
                    frame.centerX() - halfWidth, frame.centerY() - halfHeight,
                    frame.centerX() + halfWidth, frame.centerY() + halfHeight,
                )
            }
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            drawBitmap(landscape, null, destination, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2f
            paint.color = 0xFFBDBDBD.toInt()
            drawRect(frame, paint)
        } finally {
            if (landscape !== rendered && landscape !== cropped) landscape.recycle()
            if (cropped !== rendered) cropped.recycle()
            rendered.recycle()
        }
    }

    override suspend fun importPdf(source: String, limit: Int): PdfImport {
        val created = mutableListOf<File>()
        try {
            return withContext(Dispatchers.IO) {
                val descriptor = try {
                    resolver.openFileDescriptor(Uri.parse(source), "r")
                } catch (error: SecurityException) {
                    throw IOException("Sem acesso ao arquivo", error)
                } ?: throw IOException("Sem acesso ao arquivo")
                descriptor.use {
                    val pdf = try {
                        PdfRenderer(descriptor)
                    } catch (_: SecurityException) {
                        throw ProtectedPdfException()
                    } catch (error: IOException) {
                        throw IOException("PDF inválido", error)
                    } catch (error: IllegalArgumentException) {
                        throw IOException("PDF inválido", error)
                    }
                    pdf.use {
                        requireDirectory()
                        val total = pdf.pageCount
                        val pages = (0 until minOf(total, limit)).map { index ->
                            ensureActive()
                            val id = UUID.randomUUID().toString()
                            val file = imageFile(id)
                            created += file
                            try {
                                pdf.openPage(index).use { page -> storePdfPage(page, file) }
                            } catch (error: IllegalArgumentException) {
                                throw IOException("PDF inválido", error)
                            } catch (error: IllegalStateException) {
                                throw IOException("PDF inválido", error)
                            }
                            ScanPage(id)
                        }
                        PdfImport(pages, total)
                    }
                }
            }
        } catch (error: Throwable) {
            withContext(NonCancellable + Dispatchers.IO) { created.forEach { it.delete() } }
            throw error
        }
    }

    private fun storePdfPage(page: PdfRenderer.Page, file: File) {
        val scale = (EXPORT_DIMENSION / maxOf(page.width, page.height).toFloat()).coerceAtMost(6f)
        val width = (page.width * scale).roundToInt().coerceAtLeast(1)
        val height = (page.height * scale).roundToInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            file.outputStream().buffered().use { output ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 92, output)) {
                    throw IOException("Falha ao armazenar a página do PDF")
                }
            }
        } finally {
            bitmap.recycle()
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

    override suspend fun estimateSizes(pages: List<ScanPage>, qualities: List<PdfQuality>): Map<PdfQuality, Long> =
        pdfFiles.estimate(pages, { imageFile(it.id) }, qualities)

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
