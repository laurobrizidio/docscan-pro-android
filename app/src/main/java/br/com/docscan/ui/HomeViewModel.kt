package br.com.docscan.ui

import android.graphics.Bitmap
import android.database.sqlite.SQLiteException
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import br.com.docscan.domain.Document
import br.com.docscan.domain.DocumentNameError
import br.com.docscan.domain.DocumentRepository
import br.com.docscan.domain.InvalidDocumentName
import br.com.docscan.domain.ShareablePdf
import java.io.IOException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal data class HomeUiState(
    val documents: List<Document> = emptyList(),
    val query: String = "",
    val loading: Boolean = true,
    val loadError: Boolean = false,
    val showAll: Boolean = false,
    val expandedId: String? = null,
    val selectedIds: Set<String> = emptySet(),
    val rename: RenameDocument? = null,
    val busy: Boolean = false,
    val sharedPdfs: List<ShareablePdf> = emptyList(),
)

internal data class RenameDocument(val id: String, val name: String, val error: String? = null)

internal class HomeViewModel(
    private val repository: DocumentRepository,
    private val loadThumbnail: suspend (Document) -> Bitmap?,
) : ViewModel() {
    private data class Subscription(val query: String = "", val attempt: Int = 0)

    private val model = MutableStateFlow(HomeUiState())
    val state = model.asStateFlow()
    private val subscription = MutableStateFlow(Subscription())
    private val feedback = Channel<String>(Channel.BUFFERED)
    val messages = feedback.receiveAsFlow()

    init {
        viewModelScope.launch {
            subscription.collectLatest { (query, _) ->
                val documents = if (query.isBlank()) repository.observeAll() else repository.search(query)
                documents.catch { error ->
                    if (error !is IOException && error !is SQLiteException) throw error
                    model.update { it.copy(loadError = true, loading = false) }
                }.collect { entries ->
                    val visibleIds = entries.mapTo(mutableSetOf(), Document::id)
                    model.update {
                        it.copy(
                            documents = entries,
                            loading = false,
                            loadError = false,
                            selectedIds = it.selectedIds.intersect(visibleIds),
                            expandedId = it.expandedId?.takeIf(visibleIds::contains),
                        )
                    }
                }
            }
        }
    }

    fun retry() {
        model.update { it.copy(loadError = false, loading = true) }
        subscription.update { it.copy(attempt = it.attempt + 1) }
    }

    fun setQuery(query: String) {
        model.update { it.copy(query = query, selectedIds = emptySet(), expandedId = null) }
        subscription.update { it.copy(query = query) }
    }

    fun showAll(show: Boolean) = model.update { it.copy(showAll = show, expandedId = null) }

    fun tap(document: Document) {
        if (state.value.selectedIds.isNotEmpty()) {
            toggleSelected(document.id)
        } else {
            model.update { it.copy(expandedId = document.id.takeUnless { id -> it.expandedId == id }) }
        }
    }

    fun toggleSelected(id: String) = model.update {
        val selected = if (id in it.selectedIds) it.selectedIds - id else it.selectedIds + id
        it.copy(selectedIds = selected, expandedId = null)
    }

    fun clearSelection() = model.update { it.copy(selectedIds = emptySet()) }

    suspend fun thumbnail(document: Document) = loadThumbnail(document)

    fun startRename(document: Document) = model.update { it.copy(rename = RenameDocument(document.id, document.name)) }

    fun updateRename(name: String) = model.update { state ->
        state.copy(rename = state.rename?.copy(name = name, error = null))
    }

    fun cancelRename() {
        if (!state.value.busy) model.update { it.copy(rename = null) }
    }

    fun confirmRename(onRenamed: (String, String) -> Unit) {
        val rename = state.value.rename ?: return
        runOperation("Não foi possível renomear o documento.") {
            try {
                repository.rename(rename.id, rename.name)
                repository.get(rename.id)?.let { onRenamed(it.id, it.name) }
                model.update { it.copy(rename = null) }
            } catch (error: InvalidDocumentName) {
                model.update { it.copy(rename = rename.copy(error = error.reason.message())) }
            }
        }
    }

    fun delete(ids: Set<String>, onDeleted: suspend (Set<String>) -> Unit) {
        if (ids.isEmpty()) return
        runOperation("Não foi possível excluir os documentos.") {
            repository.delete(ids)
            onDeleted(ids)
            model.update { it.copy(selectedIds = it.selectedIds - ids) }
            feedback.trySend(if (ids.size == 1) "Documento excluído." else "${ids.size} documentos excluídos.")
        }
    }

    fun share(ids: Set<String>) {
        if (ids.isEmpty()) return
        runOperation("Não foi possível preparar os PDFs para compartilhar.") {
            val pdfs = repository.prepareShare(ids)
            model.update { it.copy(sharedPdfs = pdfs) }
        }
    }

    fun closeShare() = model.update { it.copy(sharedPdfs = emptyList()) }

    private fun runOperation(message: String, work: suspend () -> Unit) {
        if (state.value.busy) return
        model.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                work()
            } catch (_: IOException) {
                feedback.trySend(message)
            } catch (_: SQLiteException) {
                feedback.trySend(message)
            } finally {
                model.update { it.copy(busy = false) }
            }
        }
    }
}

internal fun DocumentNameError.message() = when (this) {
    DocumentNameError.EMPTY -> "Informe um nome."
    DocumentNameError.DUPLICATE -> "Já existe um documento com esse nome."
    DocumentNameError.TOO_LONG -> "Use até 100 caracteres."
}
