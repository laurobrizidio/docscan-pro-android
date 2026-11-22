package br.com.docscan.ui

import android.database.sqlite.SQLiteException
import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import br.com.docscan.domain.Crop
import br.com.docscan.domain.Draft
import br.com.docscan.domain.DocumentRepository
import br.com.docscan.domain.EditorSession
import br.com.docscan.domain.Filter
import br.com.docscan.domain.ImageOrigin
import br.com.docscan.domain.ImportPages
import br.com.docscan.domain.InvalidDocumentName
import br.com.docscan.domain.Look
import br.com.docscan.domain.MAX_PAGES
import br.com.docscan.domain.PdfOptions
import br.com.docscan.domain.ScanPage
import br.com.docscan.domain.ScanRepository
import br.com.docscan.domain.ShareablePdf
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal enum class Operation { LOADING, OPENING, IMPORTING, ADDING_SCANS, DETECTING, SAVING, EXPORTING, SHARING }

internal data class ScanUiState(
    val session: EditorSession = EditorSession(),
    val operation: Operation? = Operation.LOADING,
    val preview: Bitmap? = null,
    val previewLoading: Boolean = false,
    val original: Boolean = false,
    val pagesExported: Int = 0,
    val lastExport: String? = null,
    val sharePdf: ShareablePdf? = null,
    val draftError: Boolean = false,
) {
    val draft get() = session.draft
    val options get() = session.options
}

internal class ScanViewModel(
    private val repository: ScanRepository<Bitmap>,
    private val documents: DocumentRepository,
) : ViewModel() {
    private data class PreviewRequest(val page: ScanPage, val original: Boolean)

    private val model = MutableStateFlow(ScanUiState())
    val state = model.asStateFlow()
    private val previewRequests = MutableStateFlow<PreviewRequest?>(null)
    private val draftWrites = Channel<EditorSession>(Channel.CONFLATED)
    private val workspaceLock = Mutex()
    private val feedback = Channel<String>(Channel.BUFFERED)
    val messages = feedback.receiveAsFlow()

    init {
        restore()
        viewModelScope.launch {
            for (session in draftWrites) {
                workspaceLock.withLock {
                    if (state.value.session == session) {
                        safely("Não foi possível guardar o rascunho.") { repository.saveSession(session) }
                    }
                }
            }
        }
        viewModelScope.launch {
            previewRequests.collectLatest { request ->
                if (request == null) return@collectLatest
                delay(100)
                try {
                    val image = repository.preview(request.page, request.original)
                    model.update { it.copy(preview = image, previewLoading = false) }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: IOException) {
                    model.update { it.copy(preview = null, previewLoading = false) }
                    tell("Não foi possível abrir a prévia.")
                }
            }
        }
    }

    fun restore() {
        model.update { it.copy(operation = Operation.LOADING, draftError = false) }
        viewModelScope.launch {
            try {
                var session = repository.loadSession()
                val id = session.documentId
                if (id != null && documents.get(id) == null) {
                    session = EditorSession()
                    repository.saveSession(session, cleanup = true)
                }
                updateSession(session)
                refreshPreview()
            } catch (error: Exception) {
                if (error !is IOException && error !is SQLiteException) throw error
                model.update { it.copy(draftError = true) }
                tell("Não foi possível ler o rascunho salvo.")
            } finally {
                model.update { it.copy(operation = null) }
            }
        }
    }

    fun import(sources: List<String>, origin: ImageOrigin, newDocument: Boolean, onImported: () -> Unit) {
        val operation = if (origin == ImageOrigin.SCANNER) Operation.ADDING_SCANS else Operation.IMPORTING
        if (sources.isEmpty() || !begin(operation)) return
        viewModelScope.launch {
            try {
                safely("Não foi possível adicionar as imagens.") {
                    val base = if (newDocument) EditorSession() else state.value.session
                    val available = MAX_PAGES - base.draft.pages.size
                    val result = ImportPages(repository)(sources, available, origin)
                    if (result.pages.isNotEmpty()) {
                        val added = base.copy(draft = base.draft.add(result.pages), dirty = true)
                        workspaceLock.withLock {
                            repository.saveSession(added, cleanup = newDocument)
                            updateSession(added)
                        }
                        onImported()
                    }
                    if (result.unreadable > 0) tell("${result.unreadable} imagem(ns) não puderam ser abertas.")
                    if (result.aboveLimit > 0) tell("O documento aceita até $MAX_PAGES páginas.")
                }
            } finally {
                model.update { it.copy(operation = null) }
            }
        }
    }

    fun select(id: String) {
        if (state.value.operation != null || state.value.draft.selectedId == id) return
        model.update { it.copy(original = false) }
        editDraft(state.value.draft.copy(selectedId = id), modifies = false)
    }

    suspend fun thumbnail(id: String) = repository.thumbnail(id)

    fun rotate() = editPage(ScanPage::rotateClockwise)
    fun adjustAngle(angle: Float) = editPage { it.copy(fineRotation = angle) }
    fun adjustCrop(crop: Crop) = editPage { it.copy(crop = crop) }
    fun resetCrop() = editPage { it.copy(quad = null, crop = Crop.Full) }
    fun resetPage() = editPage { ScanPage(it.id) }

    fun setFilter(filter: Filter) = editPage { it.copy(look = Look(filter, useMl = it.look.useMl)) }
    fun updateLook(transform: (Look) -> Look) = editPage { it.copy(look = transform(it.look)) }

    fun applyLookToAll() {
        val current = state.value
        if (current.operation != null) return
        val look = current.draft.selected?.look ?: return
        editDraft(current.draft.copy(pages = current.draft.pages.map { it.copy(look = look) }))
        tell("Filtro aplicado a todas as páginas.")
    }

    fun move(direction: Int) {
        if (state.value.operation == null) editDraft(state.value.draft.moveSelected(direction))
    }

    fun delete() {
        if (state.value.operation == null) {
            model.update { it.copy(original = false) }
            editDraft(state.value.draft.deleteSelected())
        }
    }

    fun detectFrame() {
        val page = state.value.draft.selected ?: return
        if (!begin(Operation.DETECTING)) return
        viewModelScope.launch {
            try {
                safely("Não foi possível detectar as bordas.") {
                    val quad = repository.detectFrame(page)
                    if (quad == null) tell("Bordas não encontradas. Você pode ajustar o recorte.")
                    else editDraft(state.value.draft.edit(page.withAutoFrame(quad)))
                }
            } finally {
                model.update { it.copy(operation = null) }
            }
        }
    }

    fun toggleOriginal() {
        if (state.value.operation != null) return
        model.update { it.copy(original = !it.original) }
        refreshPreview()
    }

    fun setPdfOptions(options: PdfOptions) {
        val session = state.value.session
        if (options != session.options && state.value.operation == null) {
            changeSession(session.copy(options = options, dirty = true))
        }
    }

    fun openDocument(id: String, onOpened: () -> Unit) {
        if (!begin(Operation.OPENING)) return
        viewModelScope.launch {
            try {
                safely("Não foi possível abrir o documento.") {
                    workspaceLock.withLock {
                        updateSession(documents.open(id))
                    }
                    onOpened()
                }
            } finally {
                model.update { it.copy(operation = null) }
            }
        }
    }

    fun saveToLibrary(onSaved: () -> Unit) {
        val current = state.value
        if (current.draft.pages.isEmpty() || !begin(Operation.SAVING)) return
        model.update { it.copy(pagesExported = 0) }
        viewModelScope.launch {
            try {
                safely("Não foi possível salvar o documento na biblioteca.") {
                    val session = current.session
                    val pendingId = session.pendingDocumentId ?: if (session.documentId == null) UUID.randomUUID().toString() else null
                    if (pendingId != session.pendingDocumentId) {
                        val pending = session.copy(pendingDocumentId = pendingId)
                        workspaceLock.withLock {
                            repository.saveSession(pending)
                            updateSession(pending)
                        }
                    }
                    val saved = documents.save(session.draft, session.documentId, session.options, pendingId) { done ->
                        model.update { it.copy(pagesExported = done) }
                    }
                    workspaceLock.withLock {
                        val empty = EditorSession()
                        try {
                            repository.saveSession(empty, cleanup = true)
                            updateSession(empty)
                        } catch (_: IOException) {
                            val finished = session.copy(
                                documentId = saved.id, options = session.options.copy(name = saved.name),
                                dirty = false, pendingDocumentId = null,
                            )
                            updateSession(finished)
                            draftWrites.trySend(finished)
                        }
                    }
                    model.update { it.copy(lastExport = null) }
                    tell("Documento salvo na biblioteca.")
                    onSaved()
                }
            } catch (error: InvalidDocumentName) {
                tell(error.reason.message())
            } finally {
                model.update { it.copy(operation = null) }
            }
        }
    }

    suspend fun discardDeleted(ids: Set<String>) = workspaceLock.withLock {
        val session = state.value.session
        if (session.documentId in ids || session.pendingDocumentId in ids) {
            val empty = EditorSession()
            repository.saveSession(empty, cleanup = true)
            updateSession(empty)
        }
    }

    fun syncRenamed(id: String, name: String) {
        val session = state.value.session
        if (session.documentId == id) changeSession(session.copy(options = session.options.copy(name = name)))
    }

    fun export(destination: String, options: PdfOptions) {
        val draft = state.value.draft
        if (draft.pages.isEmpty() || !begin(Operation.EXPORTING)) return
        model.update { it.copy(pagesExported = 0, lastExport = null) }
        viewModelScope.launch {
            try {
                safely("Não foi possível salvar o PDF. Confira o destino e tente novamente.") {
                    repository.export(draft.pages, options, destination) { done ->
                        model.update { it.copy(pagesExported = done) }
                    }
                    model.update { it.copy(lastExport = destination) }
                    tell("PDF salvo.")
                }
            } finally {
                model.update { it.copy(operation = null) }
            }
        }
    }

    fun prepareShare() {
        val current = state.value
        if (current.draft.pages.isEmpty() || !begin(Operation.SHARING)) return
        model.update { it.copy(pagesExported = 0, sharePdf = null) }
        viewModelScope.launch {
            try {
                safely("Não foi possível preparar o PDF para compartilhar.") {
                    val pdf = repository.prepareShare(current.draft.pages, current.options) { done ->
                        model.update { it.copy(pagesExported = done) }
                    }
                    model.update { it.copy(sharePdf = pdf) }
                }
            } finally {
                model.update { it.copy(operation = null) }
            }
        }
    }

    fun dismissShare() = model.update { it.copy(sharePdf = null) }

    fun tell(message: String) {
        feedback.trySend(message)
    }

    private fun editPage(change: (ScanPage) -> ScanPage) {
        val current = state.value
        if (current.operation != null) return
        val page = current.draft.selected ?: return
        editDraft(current.draft.edit(change(page)))
    }

    private fun editDraft(draft: Draft, modifies: Boolean = true) {
        val session = state.value.session
        if (draft != session.draft) changeSession(session.copy(draft = draft, dirty = session.dirty || modifies))
    }

    private fun changeSession(session: EditorSession) {
        updateSession(session)
        draftWrites.trySend(session)
    }

    private fun updateSession(session: EditorSession) {
        model.update {
            it.copy(
                session = session,
                original = it.original && session.draft.selectedId == it.draft.selectedId,
            )
        }
        refreshPreview()
    }

    private fun refreshPreview() {
        val current = state.value
        val page = current.draft.selected?.copy(crop = Crop.Full)
        val request = page?.let { PreviewRequest(if (current.original) it.copy(look = Look(Filter.ORIGINAL)) else it, current.original) }
        val previous = previewRequests.value
        if (request == previous) return
        val samePage = previous?.page?.id == request?.page?.id && request != null
        model.update { it.copy(preview = it.preview.takeIf { samePage }, previewLoading = request != null) }
        previewRequests.value = request
    }

    private fun begin(operation: Operation): Boolean {
        if (state.value.operation != null || state.value.draftError) return false
        model.update { it.copy(operation = operation) }
        return true
    }

    private suspend fun safely(message: String, action: suspend () -> Unit) {
        try {
            action()
        } catch (_: IOException) {
            tell(message)
        } catch (_: SecurityException) {
            tell(message)
        } catch (_: SQLiteException) {
            tell(message)
        }
    }
}
