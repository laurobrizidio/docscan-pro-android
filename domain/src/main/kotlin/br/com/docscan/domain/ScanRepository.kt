package br.com.docscan.domain

import java.io.IOException

data class ShareablePdf(val uri: String, val fileName: String)

enum class ImageOrigin { GALLERY, SCANNER }

interface ScanRepository<Preview> {
    suspend fun loadDraft(): Draft
    suspend fun saveDraft(draft: Draft)
    suspend fun importImage(source: String): ScanPage
    suspend fun detectFrame(page: ScanPage): DocumentQuad?
    suspend fun preview(page: ScanPage, original: Boolean): Preview
    suspend fun thumbnail(pageId: String): Preview?
    suspend fun export(pages: List<ScanPage>, options: PdfOptions, destination: String, onPageSaved: (Int) -> Unit)
    suspend fun prepareShare(
        pages: List<ScanPage>,
        options: PdfOptions,
        onPageSaved: (Int) -> Unit,
    ): ShareablePdf
}

/** Coordinates the default import flow and preserves successfully imported pages in mixed batches. */
class ImportPages<Preview>(private val repository: ScanRepository<Preview>) {
    suspend operator fun invoke(sources: List<String>, available: Int, origin: ImageOrigin): ImportResult {
        val accepted = sources.take(available.coerceAtLeast(0))
        val pages = mutableListOf<ScanPage>()
        var unreadable = 0
        for (source in accepted) {
            val image = try {
                repository.importImage(source)
            } catch (_: IOException) {
                unreadable++
                continue
            }
            pages += when (origin) {
                ImageOrigin.GALLERY -> autoFrame(image)
                ImageOrigin.SCANNER -> image.copy(look = Look(Filter.ORIGINAL))
            }
        }
        return ImportResult(pages, unreadable, sources.size - accepted.size)
    }

    private suspend fun autoFrame(page: ScanPage): ScanPage {
        val quad = try {
            repository.detectFrame(page)
        } catch (_: IOException) {
            null
        }
        return quad?.let(page::withAutoFrame) ?: page
    }
}

data class ImportResult(val pages: List<ScanPage>, val unreadable: Int, val aboveLimit: Int)
