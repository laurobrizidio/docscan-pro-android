package br.com.docscan.ui

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import br.com.docscan.domain.AppSettings
import br.com.docscan.domain.AppTheme
import br.com.docscan.domain.MAX_DOCUMENT_NAME_PREFIX_LENGTH
import br.com.docscan.domain.defaultDocumentName
import br.com.docscan.domain.documentNamePrefix
import br.com.docscan.domain.qualityLadder

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsScreen(
    settings: AppSettings,
    viewModel: SettingsViewModel,
    snackbars: SnackbarHostState,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val storage by viewModel.storageState.collectAsStateWithLifecycle()
    var prefix by rememberSaveable(settings.documentNamePrefix) { mutableStateOf(settings.documentNamePrefix) }
    var showAbout by rememberSaveable { mutableStateOf(false) }
    var confirmCleanup by rememberSaveable { mutableStateOf(false) }
    val previewName = remember(prefix) { defaultDocumentName(System.currentTimeMillis(), prefix = prefix) }

    LaunchedEffect(viewModel) { viewModel.refreshStorage() }
    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = {
            TopAppBar(
                title = { Text("Ajustes") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Voltar à biblioteca") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbars) },
    ) { insets ->
        Box(Modifier.fillMaxSize().padding(insets), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = 740.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("PDF padrão", style = MaterialTheme.typography.titleLarge)
                PdfPageChoices(settings.pdf) { change -> viewModel.update { it.copy(pdf = change(it.pdf)) } }
                ChoiceGroup("Qualidade", qualityLadder.asReversed(), settings.pdf.quality, { it.label() }) { quality ->
                    viewModel.update { it.copy(pdf = it.pdf.copy(quality = quality)) }
                }

                HorizontalDivider()
                Text("Nome dos documentos", style = MaterialTheme.typography.titleLarge)
                OutlinedTextField(
                    value = prefix,
                    onValueChange = { prefix = it.take(MAX_DOCUMENT_NAME_PREFIX_LENGTH) },
                    label = { Text("Prefixo") },
                    supportingText = { Text(previewName) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedButton(
                    onClick = {
                        val selected = documentNamePrefix(prefix)
                        viewModel.update { it.copy(documentNamePrefix = selected) }
                        focus.clearFocus()
                    },
                    enabled = prefix.isNotBlank() && documentNamePrefix(prefix) != settings.documentNamePrefix,
                ) { Text("Salvar prefixo") }

                HorizontalDivider()
                ChoiceGroup("Tema", AppTheme.entries, settings.theme, { theme ->
                    when (theme) {
                        AppTheme.SYSTEM -> "Sistema"
                        AppTheme.LIGHT -> "Claro"
                        AppTheme.DARK -> "Escuro"
                    }
                }) { theme -> viewModel.update { it.copy(theme = theme) } }

                HorizontalDivider()
                Text("Armazenamento", style = MaterialTheme.typography.titleLarge)
                if (storage.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                val usage = storage.usage
                if (usage != null) {
                    val documents = Formatter.formatFileSize(context, usage.documentBytes)
                    val temporary = Formatter.formatFileSize(context, usage.temporaryBytes)
                    Text("Documentos da biblioteca · $documents")
                    Text("Arquivos temporários · $temporary")
                } else if (!storage.busy) {
                    TextButton(onClick = viewModel::refreshStorage) { Text("Consultar espaço usado") }
                }
                OutlinedButton(
                    onClick = { confirmCleanup = true },
                    enabled = !storage.busy && (usage?.temporaryBytes ?: 0) > 0,
                ) { Text("Limpar arquivos temporários") }

                HorizontalDivider()
                Text("Privacidade", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Seus documentos são armazenados e processados no aparelho. Ao exportar ou compartilhar, você escolhe o destino.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                TextButton(onClick = { showAbout = true }) { Text("Sobre o DocScan Pro") }
            }
        }
    }

    if (showAbout) AboutDialog(onDismiss = { showAbout = false })
    if (confirmCleanup) {
        AlertDialog(
            onDismissRequest = { confirmCleanup = false },
            title = { Text("Limpar arquivos temporários?") },
            text = { Text("A limpeza remove as cópias temporárias geradas para compartilhamento e exportação.") },
            dismissButton = { TextButton(onClick = { confirmCleanup = false }) { Text("Cancelar") } },
            confirmButton = {
                Button(onClick = { confirmCleanup = false; viewModel.clearTemporaryFiles() }) { Text("Limpar") }
            },
        )
    }
}
