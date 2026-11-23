package br.com.docscan.domain

import java.io.IOException

data class ShareablePdf(val uri: String, val fileName: String)

enum class ImageOrigin { GALLERY, SCANNER, PDF }

class ProtectedPdfException : IOException("PDF protegido por senha")
data class PdfImport(val pages: List<ScanPage>, val totalPages: Int)
data class ImportSource(val uri: String, val isPdf: Boolean)

interface ScanRepository<Preview> {
    suspend fun loadSession(): EditorSession
    suspend fun saveSession(session: EditorSession, cleanup: Boolean = false)
    suspend fun importImage(source: String): ScanPage
    suspend fun composeIdSheet(front: ScanPage, back: ScanPage?): ScanPage
    suspend fun importPdf(source: String, limit: Int): PdfImport = throw IOException("Importação de PDF indisponível")
    suspend fun detectFrame(page: ScanPage): DocumentQuad?
    suspend fun preview(page: ScanPage, original: Boolean): Preview
    suspend fun thumbnail(pageId: String): Preview?
    suspend fun estimateSizes(pages: List<ScanPage>, qualities: List<PdfQuality>): Map<PdfQuality, Long>
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
                ImageOrigin.GALLERY -> repository.autoFrame(image)
                ImageOrigin.SCANNER, ImageOrigin.PDF -> image.copy(look = Look(Filter.ORIGINAL))
            }
        }
        return ImportResult(pages, unreadable, sources.size - accepted.size)
    }
}

class ImportFiles<Preview>(private val repository: ScanRepository<Preview>) {
    suspend operator fun invoke(files: List<ImportSource>, available: Int): ImportResult {
        val capacity = available.coerceAtLeast(0)
        val pages = mutableListOf<ScanPage>()
        val importedPdfs = mutableListOf<String>()
        var unreadable = 0
        var aboveLimit = 0
        var protectedPdfs = 0
        for ((index, file) in files.withIndex()) {
            val remaining = capacity - pages.size
            if (remaining == 0) {
                aboveLimit += files.size - index
                break
            }
            try {
                if (file.isPdf) {
                    val pdf = repository.importPdf(file.uri, remaining)
                    pages += pdf.pages.map { it.copy(quad = null, look = Look(Filter.ORIGINAL)) }
                    aboveLimit += pdf.totalPages - pdf.pages.size
                    if (pdf.pages.isNotEmpty()) importedPdfs += file.uri
                } else {
                    pages += repository.autoFrame(repository.importImage(file.uri))
                }
            } catch (_: ProtectedPdfException) {
                protectedPdfs++
                unreadable++
            } catch (_: IOException) {
                unreadable++
            }
        }
        return ImportResult(pages, unreadable, aboveLimit, protectedPdfs, importedPdfs.singleOrNull())
    }
}

private suspend fun ScanRepository<*>.autoFrame(page: ScanPage): ScanPage {
    val quad = try {
        detectFrame(page)
    } catch (_: IOException) {
        null
    }
    return quad?.let(page::withAutoFrame) ?: page
}

data class ImportResult(
    val pages: List<ScanPage>,
    val unreadable: Int,
    val aboveLimit: Int,
    val protectedPdfs: Int = 0,
    val importedPdfUri: String? = null,
)
