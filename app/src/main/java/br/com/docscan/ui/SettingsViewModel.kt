package br.com.docscan.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import br.com.docscan.domain.AppSettings
import br.com.docscan.domain.SettingsRepository
import br.com.docscan.domain.StorageUsage
import java.io.IOException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal data class StorageState(val usage: StorageUsage? = null, val busy: Boolean = false)

internal class SettingsViewModel(private val repository: SettingsRepository) : ViewModel() {
    val settings = repository.settings
    private val storage = MutableStateFlow(StorageState())
    val storageState = storage.asStateFlow()
    private val feedback = Channel<String>(Channel.BUFFERED)
    val messages = feedback.receiveAsFlow()

    init {
        viewModelScope.launch { repository.load() }
    }

    fun update(change: (AppSettings) -> AppSettings) {
        viewModelScope.launch {
            try {
                repository.update(change)
            } catch (_: IOException) {
                feedback.trySend("Não foi possível salvar os ajustes.")
            }
        }
    }

    fun refreshStorage() = runStorageOperation("Não foi possível consultar o armazenamento.") {
        repository.storageUsage()
    }

    fun clearTemporaryFiles() = runStorageOperation("Não foi possível limpar todos os arquivos temporários.") {
        repository.clearTemporaryFiles()
        val usage = repository.storageUsage()
        feedback.trySend("Arquivos temporários removidos.")
        usage
    }

    private fun runStorageOperation(error: String, operation: suspend () -> StorageUsage) {
        if (storage.value.busy) return
        storage.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                val usage = operation()
                storage.update { it.copy(usage = usage) }
            } catch (_: IOException) {
                feedback.trySend(error)
                storage.update { it.copy(usage = null) }
            } finally {
                storage.update { it.copy(busy = false) }
            }
        }
    }
}
