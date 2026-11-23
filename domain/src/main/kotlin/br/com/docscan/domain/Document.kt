package br.com.docscan.domain

import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class Document(
    val id: String,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
    val pageCount: Int,
    val thumbnailPath: String?,
)

data class EditorSession(
    val draft: Draft = Draft(),
    val documentId: String? = null,
    val options: PdfOptions = PdfOptions(),
    val dirty: Boolean = false,
    val pendingDocumentId: String? = null,
)

val documentOrder: Comparator<Document> =
    compareByDescending<Document> { it.updatedAt }.thenByDescending { it.createdAt }.thenBy { it.id }

enum class DocumentNameError { EMPTY, DUPLICATE, TOO_LONG }

class InvalidDocumentName(val reason: DocumentNameError) : IllegalArgumentException(reason.name)

private const val MAX_DOCUMENT_NAME_LENGTH = 100

fun documentNameKey(name: String): String =
    Normalizer.normalize(name.trim().lowercase(Locale.ROOT), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")

fun defaultDocumentName(now: Long, timeZone: TimeZone = TimeZone.getDefault(), prefix: String = "DocScan"): String {
    val format = SimpleDateFormat("dd-MM-yyyy HH.mm", Locale.ROOT).apply { this.timeZone = timeZone }
    return "$prefix ${format.format(Date(now))}"
}

fun availableDocumentName(base: String, used: Iterable<String>): String {
    val keys = used.mapTo(mutableSetOf(), ::documentNameKey)
    val preferred = base.take(MAX_DOCUMENT_NAME_LENGTH).trimEnd()
    if (documentNameKey(preferred) !in keys) return preferred
    var number = 2
    while (true) {
        val suffix = " ($number)"
        val name = preferred.take(MAX_DOCUMENT_NAME_LENGTH - suffix.length).trimEnd() + suffix
        if (documentNameKey(name) !in keys) return name
        number++
    }
}

fun validatedDocumentName(requested: String, used: Iterable<String>): String {
    val name = requested.trim().replace(Regex("(?i)\\.pdf$"), "").trim()
    if (name.isEmpty()) throw InvalidDocumentName(DocumentNameError.EMPTY)
    if (name.length > MAX_DOCUMENT_NAME_LENGTH) throw InvalidDocumentName(DocumentNameError.TOO_LONG)
    if (used.any { documentNameKey(it) == documentNameKey(name) }) throw InvalidDocumentName(DocumentNameError.DUPLICATE)
    return name
}
