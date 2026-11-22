package br.com.docscan.domain

import kotlinx.coroutines.flow.Flow

interface DocumentRepository {
    fun observeAll(): Flow<List<Document>>
    fun search(query: String): Flow<List<Document>>
    suspend fun get(id: String): Document?
    suspend fun open(id: String): EditorSession
    suspend fun save(
        scan: Draft,
        existingId: String? = null,
        options: PdfOptions = PdfOptions(),
        pendingDocumentId: String? = null,
        onPageSaved: (Int) -> Unit = {},
    ): Document
    suspend fun rename(id: String, newName: String)
    suspend fun delete(ids: Set<String>)
    suspend fun prepareShare(ids: Set<String>): List<ShareablePdf>
}
