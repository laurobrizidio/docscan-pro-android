package br.com.docscan.data

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal suspend fun shareOcrTextFile(context: Context, text: String): Uri = withContext(Dispatchers.IO) {
    val directory = File(context.cacheDir, "shared-text")
    if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cache indisponível")
    val file = File(directory, "texto.txt")
    val temporary = File.createTempFile("ocr-", ".tmp", directory)
    try {
        temporary.writeText(text, Charsets.UTF_8)
        if (!temporary.renameTo(file)) throw IOException("Não foi possível preparar o arquivo")
        FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    } finally {
        temporary.delete()
    }
}

internal suspend fun saveOcrTextFile(context: Context, destination: Uri, text: String) = withContext(Dispatchers.IO) {
    val stream = context.contentResolver.openOutputStream(destination, "wt")
        ?: throw IOException("Destino indisponível")
    stream.bufferedWriter(Charsets.UTF_8).use { it.write(text) }
}
