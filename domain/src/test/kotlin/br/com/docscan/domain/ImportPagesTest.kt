package br.com.docscan.domain

import java.io.IOException
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ImportPagesTest {
    @Test
    fun scannedPagesKeepTheFramingAndColorsReturnedByTheScanner() {
        val repository = ImportRepository()
        val result = runImmediately {
            ImportPages(repository)(listOf("page-1", "page-2"), 2, ImageOrigin.SCANNER)
        }

        assertEquals(listOf("page-1", "page-2"), result.pages.map(ScanPage::id))
        assertEquals(0, repository.detections)
        for (page in result.pages) {
            assertNull(page.quad)
            assertEquals(Crop.Full, page.crop)
            assertEquals(Look(Filter.ORIGINAL), page.look)
        }
    }

    @Test
    fun galleryPagesStillGetAutomaticFramingAndReportUnreadableAndExcessFiles() {
        val repository = ImportRepository()
        val result = runImmediately {
            ImportPages(repository)(listOf("invalid", "page-1", "extra"), 2, ImageOrigin.GALLERY)
        }

        val page = result.pages.single()
        assertEquals("page-1", page.id)
        assertEquals(repository.quad, page.quad)
        assertEquals(Filter.MAGIC_COLOR, page.look.filter)
        assertEquals(1, repository.detections)
        assertEquals(1, result.unreadable)
        assertEquals(1, result.aboveLimit)
    }

    internal class ImportRepository : ScanRepository<Unit> {
        val quad = DocumentQuad(PagePoint(.1f, .1f), PagePoint(.9f, .1f), PagePoint(.9f, .9f), PagePoint(.1f, .9f))
        var detections = 0
        val pdfPageCounts = mutableMapOf<String, Int>()
        val reads = mutableListOf<String>()

        override suspend fun importImage(source: String): ScanPage {
            reads += source
            if (source == "invalid") throw IOException()
            return ScanPage(source)
        }

        override suspend fun importPdf(source: String, limit: Int): PdfImport {
            reads += source
            if (source == "protected") throw ProtectedPdfException()
            val total = pdfPageCounts[source] ?: throw IOException()
            return PdfImport(List(minOf(total, limit)) { ScanPage("$source-${it + 1}", quad = quad) }, total)
        }

        override suspend fun detectFrame(page: ScanPage): DocumentQuad {
            detections++
            return quad
        }

        override suspend fun loadSession() = error("Unused")
        override suspend fun saveSession(session: EditorSession, cleanup: Boolean) = error("Unused")
        override suspend fun composeIdSheet(front: ScanPage, back: ScanPage?) = error("Unused")
        override suspend fun preview(page: ScanPage, original: Boolean) = error("Unused")
        override suspend fun thumbnail(pageId: String) = error("Unused")
        override suspend fun estimateSizes(pages: List<ScanPage>, qualities: List<PdfQuality>) = error("Unused")
        override suspend fun export(pages: List<ScanPage>, options: PdfOptions, destination: String, onPageSaved: (Int) -> Unit) =
            error("Unused")
        override suspend fun prepareShare(pages: List<ScanPage>, options: PdfOptions, onPageSaved: (Int) -> Unit) =
            error("Unused")
    }

    private fun <T> runImmediately(block: suspend () -> T): T {
        var outcome: Result<T>? = null
        block.startCoroutine(object : Continuation<T> {
            override val context = EmptyCoroutineContext
            override fun resumeWith(result: Result<T>) { outcome = result }
        })
        return checkNotNull(outcome).getOrThrow()
    }
}
