package br.com.docscan.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.CreateDocument
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import br.com.docscan.data.saveOcrTextFile
import br.com.docscan.data.shareOcrTextFile
import br.com.docscan.domain.joinOcrPages
import java.io.IOException
import kotlinx.coroutines.launch

private const val TEXT_MIME = "text/plain"
private const val MAX_INLINE_SHARE_LENGTH = 100_000

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun OcrSheet(
    ocr: OcrState,
    documentName: String,
    visible: Boolean,
    viewModel: ScanViewModel,
    snackbars: SnackbarHostState,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val initialText = remember(ocr) { joinOcrPages(ocr.pages) }
    var text by remember(ocr) { mutableStateOf(initialText) }
    var sharing by remember { mutableStateOf(false) }
    val confidence = remember(ocr) { ocr.pages.map { it.confidence }.filter { it >= 0 }.average() }
    val hasText = text.isNotBlank() && (text != initialText || ocr.pages.any { it.text.isNotBlank() })

    val createText = rememberLauncherForActivityResult(CreateDocument(TEXT_MIME)) { destination ->
        val snapshot = viewModel.pendingOcrExport
        viewModel.pendingOcrExport = null
        if (destination != null && snapshot != null) {
            scope.launch {
                val message = try {
                    saveOcrTextFile(context, destination, snapshot)
                    "Arquivo de texto salvo."
                } catch (_: IOException) {
                    "Não foi possível salvar o arquivo de texto."
                } catch (_: SecurityException) {
                    "Não foi possível salvar o arquivo de texto."
                }
                viewModel.tell(message)
            }
        }
    }

    if (!visible) return

    EditorSheet(onDismiss = viewModel::dismissOcr, snackbars = snackbars) {
        Text("Texto extraído", style = MaterialTheme.typography.headlineSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(!ocr.allPages, { if (ocr.allPages) viewModel.extractText(false) }, { Text("Esta página") })
            FilterChip(ocr.allPages, { if (!ocr.allPages) viewModel.extractText(true) }, { Text("Todas as páginas") })
        }

        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text("Texto") },
            modifier = Modifier.fillMaxWidth().heightIn(min = 240.dp),
            maxLines = 9,
        )
        if (!hasText) {
            Text("Nenhum texto encontrado.", style = MaterialTheme.typography.bodySmall)
        } else if (confidence < 60) {
            Text("Qualidade baixa. Melhore a iluminação ou o recorte e tente de novo.", style = MaterialTheme.typography.bodySmall)
        }

        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TextButton(enabled = hasText, onClick = {
                val clipboard = context.getSystemService(ClipboardManager::class.java)
                clipboard?.setPrimaryClip(ClipData.newPlainText("Texto extraído", text))
                if (clipboard != null && Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                    viewModel.tell("Texto copiado.")
                }
            }) { Text("Copiar") }
            TextButton(enabled = hasText && !sharing, onClick = {
                val snapshot = text
                sharing = true
                scope.launch {
                    try {
                        context.startActivity(Intent.createChooser(shareTextIntent(context, snapshot), null))
                    } catch (_: IOException) {
                        viewModel.tell("Não foi possível preparar o texto para compartilhar.")
                    } catch (_: ActivityNotFoundException) {
                        viewModel.tell("Nenhum aplicativo disponível para compartilhar o texto.")
                    } catch (_: SecurityException) {
                        viewModel.tell("Não foi possível compartilhar o texto.")
                    } finally {
                        sharing = false
                    }
                }
            }) { Text("Compartilhar") }
            TextButton(enabled = hasText, onClick = {
                val name = documentName.trim().ifBlank { "Documento" }
                try {
                    viewModel.pendingOcrExport = text
                    createText.launch("$name.txt")
                } catch (_: ActivityNotFoundException) {
                    viewModel.pendingOcrExport = null
                    viewModel.tell("O seletor de destino está indisponível.")
                }
            }) { Text("Salvar .txt") }
            TextButton(onClick = viewModel::dismissOcr) { Text("Fechar") }
        }
    }
}

private suspend fun shareTextIntent(context: Context, text: String): Intent {
    val intent = Intent(Intent.ACTION_SEND).setType(TEXT_MIME)
    if (text.length <= MAX_INLINE_SHARE_LENGTH) return intent.putExtra(Intent.EXTRA_TEXT, text)

    val uri = shareOcrTextFile(context, text)
    return intent.putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        .apply { clipData = ClipData.newUri(context.contentResolver, "Texto extraído", uri) }
}
