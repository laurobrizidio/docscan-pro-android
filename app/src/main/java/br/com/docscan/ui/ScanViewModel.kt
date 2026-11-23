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
import br.com.docscan.domain.ImportFiles
import br.com.docscan.domain.ImportPages
import br.com.docscan.domain.ImportResult
import br.com.docscan.domain.ImportSource
import br.com.docscan.domain.InvalidDocumentName
import br.com.docscan.domain.Look
import br.com.docscan.domain.MAX_PAGES
import br.com.docscan.domain.OcrPage
import br.com.docscan.domain.Orientation
import br.com.docscan.domain.Paper
import br.com.docscan.domain.PdfOptions
import br.com.docscan.domain.PdfQuality
import br.com.docscan.domain.ScanPage
import br.com.docscan.domain.ScanRepository
import br.com.docscan.domain.SettingsRepository
import br.com.docscan.domain.ShareablePdf
import br.com.docscan.domain.TextRecognizer
import br.com.docscan.domain.availableDocumentName
import br.com.docscan.domain.defaultDocumentName
import br.com.docscan.domain.qualityLadder
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal enum class Operation {
    LOADING, OPENING, IMPORTING, ADDING_SCANS, COMPOSING, DISCARDING, DETECTING, SAVING, EXPORTING, SHARING, RECOGNIZING,
}

internal enum class IdCardResult { FRONT_IMPORTED, COMPOSED, DISCARDED }

internal data class OcrState(val allPages: Boolean, val pages: List<OcrPage> = emptyList())

internal data class ScanUiState(
    val session: EditorSession = EditorSession(),
    val operation: Operation? = Operation.LOADING,
    val preview: Bitmap? = null,
    val previewLoading: Boolean = false,
    val original: Boolean = false,
    val pagesExported: Int = 0,
    val lastExport: String? = null,
    val sharePdf: ShareablePdf? = null,
    val sizeEstimates: Map<PdfQuality, Long>? = null,
    val draftError: Boolean = false,
    val ocr: OcrState? = null,
    val ocrDone: Int = 0,
    val ocrTotal: Int = 0,
) {
    val draft get() = session.draft
    val options get() = session.options
}

internal class ScanViewModel(
    private val repository: ScanRepository<Bitmap>,
    private val documents: DocumentRepository,
    private val recognizer: TextRecognizer,
    private val settings: SettingsRepository,
) : ViewModel() {
    private data class PreviewRequest(val page: ScanPage, val original: Boolean)

    private val model = MutableStateFlow(ScanUiState())
    val state = model.asStateFlow()
    private val previewRequests = MutableStateFlow<PreviewRequest?>(null)
    private val draftWrites = Channel<EditorSession>(Channel.CONFLATED)
    private val workspaceLock = Mutex()
    private var estimatesJob: Job? = null
    private var ocrJob: Job? = null
    var pendingOcrExport: String? = null
    private val feedback = Channel<String>(Channel.BUFFERED)
    val messages = feedback.receiveAsFlow()
    private val idCardResults = Channel<IdCardResult>(Channel.BUFFERED)
    val idCardTransitions = idCardResults.receiveAsFlow()
    private val fileImportNavigation = Channel<Unit>(Channel.CONFLATED)
    val openImportedFiles = fileImportNavigation.receiveAsFlow()

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
        if (sources.isEmpty()) return
        val operation = if (origin == ImageOrigin.SCANNER) Operation.ADDING_SCANS else Operation.IMPORTING
        importIntoDraft(
            operation = operation,
            newDocument = newDocument,
            failureMessage = "Não foi possível adicionar as imagens.",
            unreadableMessage = { "$it imagem(ns) não puderam ser abertas." },
            onImported = onImported,
        ) { available -> ImportPages(repository)(sources, available, origin) }
    }

    fun importFiles(
        files: List<ImportSource>,
        newDocument: Boolean,
        suggestedNames: Map<String, String> = emptyMap(),
        onImported: () -> Unit = { fileImportNavigation.trySend(Unit) },
    ) {
        if (files.isEmpty()) return
        importIntoDraft(
            operation = Operation.IMPORTING,
            newDocument = newDocument,
            failureMessage = "Não foi possível importar os arquivos.",
            unreadableMessage = { "$it arquivo(s) não puderam ser abertos." },
            suggestedNames = suggestedNames,
            onImported = onImported,
        ) { available -> ImportFiles(repository)(files, available) }
    }

    private fun importIntoDraft(
        operation: Operation,
        newDocument: Boolean,
        failureMessage: String,
        unreadableMessage: (Int) -> String,
        suggestedNames: Map<String, String> = emptyMap(),
        onImported: () -> Unit,
        readFiles: suspend (Int) -> ImportResult,
    ) {
        if (!begin(operation)) return
        viewModelScope.launch {
            var imported = false
            try {
                safely(failureMessage) {
                    val result = workspaceLock.withLock {
                        val base = if (newDocument) EditorSession(options = settings.load().pdf) else state.value.session
                        try {
                            val batch = readFiles(MAX_PAGES - base.draft.pages.size)
                            if (batch.pages.isNotEmpty()) {
                                val suggestion = batch.importedPdfUri?.let(suggestedNames::get)
                                val options = if (newDocument && base.options.name.isBlank() && !suggestion.isNullOrBlank()) {
                                    val used = documents.observeAll().first().map { it.name }
                                    base.options.copy(name = availableDocumentName(suggestion, used))
                                } else base.options
                                val added = base.copy(draft = base.draft.add(batch.pages), options = options, dirty = true)
                                repository.saveSession(added, cleanup = newDocument)
                                updateSession(added)
                            }
                            batch
                        } catch (error: Throwable) {
                            withContext(NonCancellable) {
                                // Loading the saved draft prunes JPEGs that the import did not commit.
                                runCatching { repository.loadSession() }
                                    .exceptionOrNull()?.let(error::addSuppressed)
                            }
                            throw error
                        }
                    }
                    imported = result.pages.isNotEmpty()
                    if (result.protectedPdfs > 0) tell("PDF protegido por senha. Remova a senha e tente novamente.")
                    val unreadable = result.unreadable - result.protectedPdfs
                    if (unreadable > 0) tell(unreadableMessage(unreadable))
                    if (result.aboveLimit > 0) tell("O documento aceita até $MAX_PAGES páginas.")
                }
            } finally {
                model.update { it.copy(operation = null) }
            }
            if (imported) onImported()
        }
    }

    fun composeIdCard(onDone: () -> Unit) {
        if (state.value.draft.pages.size !in 1..2 || !begin(Operation.COMPOSING)) return
        viewModelScope.launch {
            try {
                safely("Não foi possível montar o cartão de ID.") {
                    workspaceLock.withLock {
                        val pages = state.value.draft.pages
                        val suggested = availableDocumentName(
                            defaultDocumentName(System.currentTimeMillis(), prefix = "Cartão de ID"),
                            documents.observeAll().first().map { it.name },
                        )
                        val sheet = repository.composeIdSheet(pages.first(), pages.getOrNull(1))
                        val composed = EditorSession(
                            draft = Draft(listOf(sheet), sheet.id),
                            options = PdfOptions(suggested, Paper.A4, Orientation.PORTRAIT, 0, PdfQuality.HIGH),
                            dirty = true,
                        )
                        withContext(NonCancellable) {
                            try {
                                repository.saveSession(composed, cleanup = true)
                            } catch (error: IOException) {
                                // Reloading the stored draft also prunes an unreferenced sheet.
                                try {
                                    repository.loadSession()
                                } catch (cleanupError: IOException) {
                                    error.addSuppressed(cleanupError)
                                }
                                throw error
                            }
                            updateSession(composed)
                        }
                    }
                    onDone()
                }
            } finally {
                model.update { it.copy(operation = null) }
            }
        }
    }

    fun discardSession(onDiscarded: () -> Unit = {}) {
        if (!begin(Operation.DISCARDING)) return
        viewModelScope.launch {
            try {
                safely("Não foi possível descartar o rascunho.") {
                    workspaceLock.withLock {
                        val empty = EditorSession()
                        repository.saveSession(empty, cleanup = true)
                        updateSession(empty)
                    }
                    onDiscarded()
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

    fun extractText(allPages: Boolean = false) {
        val draft = state.value.draft
        val pages = if (allPages) draft.pages else listOfNotNull(draft.selected)
        if (pages.isEmpty() || !begin(Operation.RECOGNIZING)) return
        model.update { it.copy(ocrDone = 0, ocrTotal = pages.size) }
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            val activeJob = currentCoroutineContext()[Job]
            try {
                safely("Não foi possível extrair o texto.") {
                    val results = pages.mapIndexed { index, page ->
                        ensureActive()
                        val result = recognizer.recognize(page)
                        ensureActive()
                        model.update { it.copy(ocrDone = index + 1) }
                        result
                    }
                    model.update { it.copy(ocr = OcrState(allPages, results)) }
                }
            } finally {
                if (ocrJob === activeJob) {
                    ocrJob = null
                    model.update { it.copy(operation = null) }
                }
            }
        }
        ocrJob = job
        job.start()
    }

    fun cancelOcr() {
        val job = ocrJob ?: return
        ocrJob = null
        job.cancel()
        model.update { it.copy(operation = null) }
    }

    fun dismissOcr() = model.update { it.copy(ocr = null) }

    fun setPdfOptions(options: PdfOptions) {
        val session = state.value.session
        if (options != session.options && state.value.operation == null) {
            changeSession(session.copy(options = options, dirty = true))
        }
    }

    fun estimateSizes() {
        clearEstimates()
        val pages = state.value.draft.pages
        estimatesJob = viewModelScope.launch {
            delay(300)
            val estimates = try {
                repository.estimateSizes(pages, qualityLadder)
            } catch (_: IOException) {
                emptyMap()
            }
            ensureActive()
            model.update { it.copy(sizeEstimates = estimates) }
        }
    }

    fun clearEstimates() {
        estimatesJob?.cancel()
        estimatesJob = null
        model.update { it.copy(sizeEstimates = null) }
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

    fun finishIdCardStep(result: IdCardResult) {
        idCardResults.trySend(result)
    }

    override fun onCleared() {
        recognizer.close()
        super.onCleared()
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
                ocr = if (session.draft == it.draft) it.ocr else null,
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
