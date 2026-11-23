package br.com.docscan.data

import android.content.Context
import br.com.docscan.domain.AppSettings
import br.com.docscan.domain.PdfOptions
import br.com.docscan.domain.SettingsRepository
import br.com.docscan.domain.StorageUsage
import br.com.docscan.domain.documentNamePrefix
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class LocalSettingsRepository(context: Context) : SettingsRepository {
    private val preferences by lazy { context.getSharedPreferences("docscan_settings", Context.MODE_PRIVATE) }
    private val documents = File(context.filesDir, DOCUMENTS_DIRECTORY)
    private val cache = context.cacheDir
    private val temporaryDirectories = listOf(
        File(cache, SHARED_PDF_DIRECTORY),
        File(cache, SHARED_TEXT_DIRECTORY),
    )
    private val mutex = Mutex()
    private var loaded = false
    private val model = MutableStateFlow(AppSettings())
    override val settings = model.asStateFlow()

    override suspend fun load(): AppSettings = withContext(Dispatchers.IO) {
        mutex.withLock { readSettings() }
    }

    override suspend fun update(change: (AppSettings) -> AppSettings) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val current = readSettings()
            val next = change(current)
            if (next == current) return@withLock
            val saved = preferences.edit()
                .putString("paper", next.pdf.paper.name)
                .putString("orientation", next.pdf.orientation.name)
                .putInt("marginMm", next.pdf.marginMm)
                .putString("quality", next.pdf.quality.name)
                .putString("namePrefix", next.documentNamePrefix)
                .putString("theme", next.theme.name)
                .commit()
            if (!saved) throw IOException("Não foi possível salvar os ajustes")
            model.value = next
        }
    }

    override suspend fun storageUsage(): StorageUsage = withContext(Dispatchers.IO) {
        StorageUsage(documents.sizeOnDisk(), temporaryFiles().sumOf { it.sizeOnDisk() })
    }

    override suspend fun clearTemporaryFiles() = withContext(Dispatchers.IO) {
        for (file in temporaryFiles()) {
            if (!file.deleteRecursively()) throw IOException("Não foi possível limpar os arquivos temporários")
        }
    }

    private fun readSettings(): AppSettings {
        if (!loaded) {
            val defaults = AppSettings()
            model.value = AppSettings(
                pdf = PdfOptions(
                    paper = preferences.readEnum("paper", defaults.pdf.paper),
                    orientation = preferences.readEnum("orientation", defaults.pdf.orientation),
                    marginMm = preferences.getInt("marginMm", defaults.pdf.marginMm).coerceIn(0, 20),
                    quality = preferences.readEnum("quality", defaults.pdf.quality),
                ),
                documentNamePrefix = documentNamePrefix(preferences.getString("namePrefix", null).orEmpty()),
                theme = preferences.readEnum("theme", defaults.theme),
            )
            loaded = true
        }
        return model.value
    }

    private fun temporaryFiles(): List<File> = temporaryDirectories + cache.listFiles { file ->
        file.isFile && file.name.startsWith("scan-") && file.extension == "pdf"
    }.orEmpty()
}

private inline fun <reified T : Enum<T>> android.content.SharedPreferences.readEnum(key: String, fallback: T): T {
    val name = getString(key, null)
    return enumValues<T>().firstOrNull { it.name == name } ?: fallback
}

private fun File.sizeOnDisk(): Long = walkTopDown().filter(File::isFile).sumOf(File::length)
