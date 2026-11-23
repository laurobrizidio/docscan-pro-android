package br.com.docscan.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class OcrTextTest {
    @Test fun normalizesWindowsLineEndings() {
        assertEquals("Linha 1\nLinha 2", normalizeOcrText("Linha 1\r\nLinha 2\r\n"))
    }

    @Test fun trimsTrailingWhitespacePerLine() {
        assertEquals("Linha 1\n  Linha 2", normalizeOcrText("  Linha 1 \t\n  Linha 2  "))
    }

    @Test fun collapsesConsecutiveBlankLines() {
        assertEquals("Linha 1\n\nLinha 2", normalizeOcrText("Linha 1\n\n \n\nLinha 2"))
    }

    @Test fun preservesPortugueseAccents() {
        assertEquals("Atenção, você é ótima", normalizeOcrText("Atenção, você é ótima"))
    }

    @Test fun keepsEmptyTextEmpty() {
        assertEquals("", normalizeOcrText(""))
        assertEquals("", normalizeOcrText(" \r\n\t"))
        assertEquals("", joinOcrPages(emptyList()))
    }

    @Test fun leavesSinglePageWithoutHeader() {
        assertEquals("Texto", joinOcrPages(listOf(OcrPage("page", "Texto", 100))))
    }

    @Test fun joinsMultiplePagesInOrderWithHeadersForBlankPages() {
        val pages = listOf(OcrPage("b", "Primeira", 80), OcrPage("a", "", -1), OcrPage("c", "Última", 90))
        assertEquals("Página 1\n\nPrimeira\n\nPágina 2\n\nPágina 3\n\nÚltima", joinOcrPages(pages))
    }
}
