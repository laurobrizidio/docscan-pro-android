package br.com.docscan.domain

import br.com.docscan.domain.ImportPagesTest.ImportRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ImportFilesTest {
    @Test
    fun mixedFilesKeepSelectionOrderAndOnlyImagesGetAutomaticFraming() {
        val repository = ImportRepository().apply { pdfPageCounts["pdf"] = 2 }
        val files = listOf(ImportSource("first", false), ImportSource("pdf", true), ImportSource("last", false))

        val result = runBlocking { ImportFiles(repository)(files, 5) }

        assertEquals(listOf("first", "pdf-1", "pdf-2", "last"), result.pages.map(ScanPage::id))
        assertEquals(2, repository.detections)
        assertEquals(repository.quad, result.pages.first().quad)
        assertEquals(repository.quad, result.pages.last().quad)
        for (page in result.pages.subList(1, 3)) {
            assertNull(page.quad)
            assertEquals(Look(Filter.ORIGINAL), page.look)
        }
        assertEquals("pdf", result.importedPdfUri)
    }

    @Test
    fun largePdfReportsEveryPageBeyondTheLimit() {
        val repository = ImportRepository().apply { pdfPageCounts["pdf"] = 5 }

        val result = runBlocking { ImportFiles(repository)(listOf(ImportSource("pdf", true)), 3) }

        assertEquals(listOf("pdf-1", "pdf-2", "pdf-3"), result.pages.map(ScanPage::id))
        assertEquals(2, result.aboveLimit)
    }

    @Test
    fun failedPdfDoesNotConsumeCapacityOrPreventLaterFilesFromImporting() {
        val repository = ImportRepository().apply { pdfPageCounts["pdf"] = 1 }
        val files = listOf(ImportSource("protected", true), ImportSource("pdf", true), ImportSource("image", false))

        val result = runBlocking { ImportFiles(repository)(files, 2) }

        assertEquals(listOf("pdf-1", "image"), result.pages.map(ScanPage::id))
        assertEquals(1, result.unreadable)
        assertEquals(1, result.protectedPdfs)
        assertEquals("pdf", result.importedPdfUri)
    }

    @Test
    fun imagesBeyondCapacityAreCountedWithoutBeingOpened() {
        val repository = ImportRepository().apply { pdfPageCounts["pdf"] = 2 }
        val files = listOf(ImportSource("pdf", true), ImportSource("image-1", false), ImportSource("image-2", false))

        val result = runBlocking { ImportFiles(repository)(files, 2) }

        assertEquals(2, result.aboveLimit)
        assertEquals(listOf("pdf"), repository.reads)
    }

    @Test
    fun multipleImportedPdfsDoNotSuggestOneSourceForTheName() {
        val repository = ImportRepository().apply {
            pdfPageCounts["first"] = 1
            pdfPageCounts["second"] = 1
        }
        val files = listOf(ImportSource("first", true), ImportSource("second", true))

        val result = runBlocking { ImportFiles(repository)(files, 2) }

        assertEquals(2, result.pages.size)
        assertNull(result.importedPdfUri)
    }
}
