package br.com.docscan.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanTest {
    @Test
    fun selectingMovingAndRemovingKeepTheDocumentOrderAndSelectionConsistent() {
        val first = ScanPage("first")
        val middle = ScanPage("middle")
        val last = ScanPage("last")
        val draft = Draft().add(listOf(first, middle)).add(listOf(last))
        assertEquals(last, draft.selected)
        val reordered = draft.moveSelected(-1)
        assertEquals(listOf(first, last, middle), reordered.pages)
        assertEquals(last, reordered.selected)
        val remaining = reordered.deleteSelected()
        assertEquals(listOf(first, middle), remaining.pages)
        assertEquals(middle, remaining.selected)
    }

    @Test
    fun aFullDraftKeepsItsCurrentSelectionAndPages() {
        val draft = Draft().add(List(MAX_PAGES) { ScanPage("page-$it") })
        assertSame(draft, draft.add(listOf(ScanPage("extra"))))
    }

    @Test
    fun rotatingKeepsTheSelectionOverTheSamePartOfTheImage() {
        val crop = Crop(0.10f, 0.15f, 0.85f, 0.70f)
        val page = ScanPage("page", crop = crop)
        val rotated = page.rotateClockwise()
        assertEquals(1, rotated.quarterTurns)
        assertEquals(0.30f, rotated.crop.left, 0.00001f)
        assertEquals(crop.left, rotated.crop.top, 0.00001f)
        assertEquals(0.85f, rotated.crop.right, 0.00001f)
        assertEquals(crop.right, rotated.crop.bottom, 0.00001f)
    }

    @Test
    fun paperAspectAccountsForThePreviewDimensions() {
        val imageRatio = 4f / 3
        val crop = Crop.Full.withAspect(210f / 297, imageRatio)
        assertEquals(210f / 297, crop.width * imageRatio / crop.height, 0.00001f)
        assertEquals(0.5f, (crop.left + crop.right) / 2, 0.00001f)
    }

    @Test
    fun automaticOrientationAndMarginKeepPortraitAndLandscapePagesProportional() {
        val options = PdfOptions()
        val portrait = options.layout(600, 900)
        val landscape = options.layout(900, 600)
        assertEquals(595, portrait.pageWidth)
        assertEquals(842, portrait.pageHeight)
        assertEquals(portrait.pageWidth, landscape.pageHeight)
        assertEquals(portrait.pageHeight, landscape.pageWidth)
        assertEquals(2.0 / 3, portrait.imageWidth / portrait.imageHeight, 0.00001)
        assertTrue(portrait.x >= 28.3 && portrait.y >= 28.3)
        val fitted = PdfOptions(paper = Paper.FIT_IMAGE, marginMm = 0).layout(800, 600)
        assertEquals(600, fitted.pageWidth)
        assertEquals(450, fitted.pageHeight)
    }

    @Test
    fun usingPdfExtensionAndInvalidFileNameCharactersIsSafe() {
        assertEquals("Relatório_Q4.pdf", PdfOptions(name = " Relatório/Q4.PDF ").fileName)
        assertEquals("Documento_Digitalizado.pdf", PdfOptions(name = " .pdf ").fileName)
        assertEquals("A_B.pdf", PdfOptions(name = "A\\B").fileName)
    }
}
