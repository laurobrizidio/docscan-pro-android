package br.com.docscan.data

import android.content.Context
import android.graphics.Bitmap
import br.com.docscan.data.imaging.PageRenderer
import br.com.docscan.domain.Document
import br.com.docscan.domain.DocumentRepository
import br.com.docscan.domain.Draft
import br.com.docscan.domain.EditorSession
import br.com.docscan.domain.PdfOptions
import br.com.docscan.domain.ScanPage
import br.com.docscan.domain.ShareablePdf
import br.com.docscan.domain.availableDocumentName
import br.com.docscan.domain.defaultDocumentName
import br.com.docscan.domain.documentNameKey
import br.com.docscan.domain.documentOrder
import br.com.docscan.domain.validatedDocumentName
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class LocalDocumentRepository(
    context: Context,
    private val dao: DocumentDao,
    private val scans: LocalScanRepository,
    private val pdfFiles: PdfFiles,
) : DocumentRepository {
    private val documents = File(context.filesDir, "documents")
    private val changes = File(context.filesDir, "document-changes")
    private val renderer = PageRenderer()
    private val mutex = Mutex()
    private var ready = false

    override fun observeAll(): Flow<List<Document>> = search("")

    override fun search(query: String): Flow<List<Document>> = flow {
        inLibrary { Unit }
        emitAll(dao.observe(documentNameKey(query)).map { rows -> rows.map(DocumentRow::toDocument).sortedWith(documentOrder) })
    }

    override suspend fun get(id: String): Document? = inLibrary { dao.get(id)?.toDocument() }

    override suspend fun open(id: String): EditorSession = inLibrary {
        val row = requireDocument(id)
        val folder = documentFolder(id)
        val session = DraftStore(folder).read { true }
        val originals = session.draft.pages.indices.map { pageFile(folder, it) }
        if (originals.size != row.pageCount || originals.any { !it.isFile }) {
            throw IOException("Páginas indisponíveis")
        }
        scans.openSession(session.copy(documentId = id, options = session.options.copy(name = row.name), dirty = false), originals)
    }

    override suspend fun save(
        scan: Draft,
        existingId: String?,
        options: PdfOptions,
        onPageSaved: (Int) -> Unit,
    ): Document = inLibrary {
        val existing = existingId?.let { requireDocument(it) }
        saveVersion(
            scan, existing?.id ?: UUID.randomUUID().toString(), existing, options,
            System.currentTimeMillis(), { scans.imageFile(it.id) }, onPageSaved,
        )
    }

    override suspend fun rename(id: String, newName: String) = inLibrary {
        val row = requireDocument(id)
        val name = validatedDocumentName(newName, otherNames(id))
        if (row.name != name) dao.rename(id, name, documentNameKey(name), nextUpdate(row))
    }

    override suspend fun delete(ids: Set<String>) = inLibrary {
        val rows = dao.all().filter { it.id in ids }
        if (rows.isEmpty()) return@inLibrary
        withContext(NonCancellable) {
            val moved = mutableListOf<DocumentRow>()
            try {
                for (row in rows) {
                    move(documentFolder(row.id), previousVersion(row.id))
                    moved += row
                }
                dao.delete(rows.map(DocumentRow::id))
            } catch (error: Exception) {
                for (row in moved) move(previousVersion(row.id), documentFolder(row.id))
                throw error
            }
            for (row in rows) previousVersion(row.id).deleteRecursively()
            pdfFiles.deleteShares(rows.mapTo(mutableSetOf(), DocumentRow::id))
        }
    }

    override suspend fun prepareShare(ids: Set<String>): List<ShareablePdf> = inLibrary {
        val rows = dao.all().filter { it.id in ids }
        if (rows.size != ids.size) throw IOException("Documento indisponível")
        rows.sortedWith(compareByDescending<DocumentRow> { it.updatedAt }.thenBy { it.id }).map { row ->
            val source = File(documentFolder(row.id), "document.pdf")
            pdfFiles.share(PdfOptions(name = row.name).fileName, row.id) { target -> source.copyTo(target) }
        }
    }

    suspend fun thumbnail(document: Document): Bitmap? = withContext(Dispatchers.IO) {
        val path = document.thumbnailPath ?: return@withContext null
        try {
            PageRenderer.decode(File(documents, path), 250)
        } catch (_: IOException) {
            null
        }
    }

    private suspend fun saveVersion(
        draft: Draft,
        id: String,
        existing: DocumentRow?,
        options: PdfOptions,
        now: Long,
        source: (ScanPage) -> File,
        onPageSaved: (Int) -> Unit,
    ): Document {
        if (draft.pages.isEmpty()) throw IOException("Documento sem páginas")
        val names = otherNames(id)
        val name = if (options.name.isBlank()) {
            existing?.name ?: availableDocumentName(defaultDocumentName(now), names)
        } else {
            validatedDocumentName(options.name, names)
        }
        val revision = UUID.randomUUID().toString()
        val row = DocumentRow(
            id, name, documentNameKey(name), existing?.createdAt ?: now,
            if (existing == null) now else maxOf(now, existing.updatedAt + 1),
            draft.pages.size, revision,
        )
        val prepared = File(changes, "stage-$revision")
        if (!prepared.mkdir()) throw IOException("Armazenamento indisponível")
        try {
            val copies = draft.pages.mapIndexed { index, page ->
                page.id to source(page).copyTo(pageFile(prepared, index))
            }.toMap()
            val stored = EditorSession(draft, id, options.copy(name = name))
            DraftStore(prepared).write(stored)
            writeThumbnail(File(prepared, "thumb.jpg"), copies.getValue(draft.pages.first().id), draft.pages.first())
            pdfFiles.write(File(prepared, "document.pdf"), draft.pages, options, { copies.getValue(it.id) }, onPageSaved)
            File(prepared, "revision").writeText(revision)
            publish(prepared, row, existing != null)
            return row.toDocument()
        } finally {
            prepared.deleteRecursively()
        }
    }

    private suspend fun publish(prepared: File, row: DocumentRow, replacing: Boolean) = withContext(NonCancellable) {
        val active = documentFolder(row.id)
        val previous = previousVersion(row.id)
        if (replacing) move(active, previous)
        var installed = false
        try {
            move(prepared, active)
            installed = true
            dao.upsert(row)
        } catch (error: Exception) {
            if (installed) move(active, prepared)
            if (replacing) move(previous, active)
            throw error
        }
        previous.deleteRecursively()
        Unit
    }

    private suspend fun writeThumbnail(file: File, source: File, page: ScanPage) = withContext(Dispatchers.Default) {
        val image = renderer.render(source, page, 340)
        val cropped = PageRenderer.crop(image, page.crop)
        val thumbnail = if (cropped.width > 200) {
            Bitmap.createScaledBitmap(cropped, 200, (cropped.height * 200 / cropped.width).coerceAtLeast(1), true)
        } else cropped
        try {
            file.outputStream().buffered().use {
                if (!thumbnail.compress(Bitmap.CompressFormat.JPEG, 85, it)) throw IOException("Erro ao criar miniatura")
            }
        } finally {
            if (thumbnail !== cropped) thumbnail.recycle()
            if (cropped !== image) cropped.recycle()
            image.recycle()
        }
    }

    private suspend fun <T> inLibrary(work: suspend () -> T): T = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!ready) {
                ensureDirectory(documents)
                ensureDirectory(changes)
                recoverChanges()
                ready = true
            }
            work()
        }
    }

    /** The database revision decides which directory survives an interrupted publish or delete. */
    private suspend fun recoverChanges() {
        val rows = dao.all().associateBy(DocumentRow::id)
        changes.listFiles().orEmpty().filter { it.name.startsWith("previous-") }.forEach { previous ->
            val id = previous.name.removePrefix("previous-")
            val row = rows[id]
            val active = documentFolder(id)
            if (row != null && revision(active) != row.revision && revision(previous) == row.revision) {
                if (active.exists() && !active.deleteRecursively()) throw IOException("Erro ao recuperar documento")
                move(previous, active)
            } else {
                previous.deleteRecursively()
            }
        }
        documents.listFiles().orEmpty().filter { it.name !in rows }.forEach { it.deleteRecursively() }
        changes.listFiles().orEmpty().filter { it.name.startsWith("stage-") }.forEach { it.deleteRecursively() }
    }

    private fun revision(directory: File): String? =
        File(directory, "revision").takeIf(File::isFile)?.readText()

    private suspend fun otherNames(id: String) = dao.all().filter { it.id != id }.map(DocumentRow::name)
    private suspend fun requireDocument(id: String) = dao.get(id) ?: throw IOException("Documento indisponível")
    private fun documentFolder(id: String) = File(documents, id)
    private fun previousVersion(id: String) = File(changes, "previous-$id")
    private fun pageFile(folder: File, index: Int) = File(folder, String.format(Locale.ROOT, "page_%03d.jpg", index + 1))
    private fun nextUpdate(row: DocumentRow) = maxOf(System.currentTimeMillis(), row.updatedAt + 1)

    private fun ensureDirectory(directory: File) {
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Armazenamento indisponível")
    }

    private fun move(source: File, destination: File) {
        if (destination.exists() || !source.renameTo(destination)) throw IOException("Armazenamento indisponível")
    }
}
