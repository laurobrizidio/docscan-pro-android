package br.com.docscan.domain

import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DocumentTest {
    @Test
    fun defaultNameUsesTheLocalDateAndMinute() {
        val zone = TimeZone.getTimeZone("GMT-04:00")
        assertEquals("DocScan 31-12-1969 20.00", defaultDocumentName(0, zone))
    }

    @Test
    fun documentsAreOrderedByUpdateAndThenCreationDate() {
        val old = document("old", 1, 2)
        val edited = document("edited", 2, 8)
        val newest = document("newest", 3, 8)
        assertEquals(listOf(newest, edited, old), listOf(old, edited, newest).sortedWith(documentOrder))
    }

    @Test
    fun blankAndDuplicateNamesAreRejectedAndExtensionIsOptional() {
        assertEquals(DocumentNameError.EMPTY,
            assertThrows(InvalidDocumentName::class.java) { validatedDocumentName(" .pdf ", emptyList()) }.reason)
        assertEquals(DocumentNameError.DUPLICATE,
            assertThrows(InvalidDocumentName::class.java) { validatedDocumentName(" relatorio ", listOf("Relatório")) }.reason)
        assertEquals("Comprovante", validatedDocumentName(" Comprovante.PDF ", listOf("Outro")))
    }

    @Test
    fun automaticNamesGetTheFirstFreeSuffix() {
        val name = "DocScan 22-11-2026 19.05"
        assertEquals("$name (3)", availableDocumentName(name, listOf(name, "$name (2)", "$name (4)")))
    }

    @Test
    fun longAutomaticNamesHaveRoomForASuffixAndCanBeSaved() {
        val base = "a".repeat(110)
        val used = listOf(base.take(100), "${base.take(96)} (2)")
        val name = availableDocumentName(base, used)
        assertEquals("${base.take(96)} (3)", name)
        assertEquals(name, validatedDocumentName(name, used))
    }

    @Test
    fun searchKeysIgnoreCaseAndAccentsAndKeepPunctuationLiteral() {
        assertEquals("cartao 100%", documentNameKey("  CARTÃO 100%  "))
    }

    private fun document(id: String, created: Long, updated: Long) =
        Document(id, id, created, updated, 1, null)
}
