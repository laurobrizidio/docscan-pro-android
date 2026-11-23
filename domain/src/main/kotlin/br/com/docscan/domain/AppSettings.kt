package br.com.docscan.domain

import kotlinx.coroutines.flow.StateFlow

enum class AppTheme { SYSTEM, LIGHT, DARK }

data class AppSettings(
    val pdf: PdfOptions = PdfOptions(),
    val documentNamePrefix: String = DEFAULT_DOCUMENT_NAME_PREFIX,
    val theme: AppTheme = AppTheme.SYSTEM,
)

data class StorageUsage(val documentBytes: Long, val temporaryBytes: Long)

interface SettingsRepository {
    val settings: StateFlow<AppSettings>
    suspend fun load(): AppSettings
    suspend fun update(change: (AppSettings) -> AppSettings)
    suspend fun storageUsage(): StorageUsage
    suspend fun clearTemporaryFiles()
}
