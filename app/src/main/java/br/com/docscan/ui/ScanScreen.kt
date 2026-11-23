package br.com.docscan.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import br.com.docscan.domain.Filter
import br.com.docscan.domain.MAX_PAGES

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ScanScreen(
    state: ScanUiState,
    viewModel: ScanViewModel,
    snackbars: SnackbarHostState,
    dark: Boolean,
    toggleTheme: () -> Unit,
    startScanner: () -> Unit,
    pickImages: () -> Unit,
    savePdf: () -> Unit,
    exportPdf: () -> Unit,
    goHome: () -> Unit,
    sharePdf: () -> Unit,
    openPdf: (String) -> Unit,
) {
    var showAdd by rememberSaveable { mutableStateOf(false) }
    var showOptions by rememberSaveable { mutableStateOf(false) }
    var showAdjustments by rememberSaveable { mutableStateOf(false) }
    var showDelete by rememberSaveable { mutableStateOf(false) }
    var showExportMenu by rememberSaveable { mutableStateOf(false) }
    val page = state.draft.selected
    val ready = state.operation == null
    val canAdd = ready && state.draft.pages.size < MAX_PAGES
    val colors = MaterialTheme.colorScheme
    LaunchedEffect(showOptions, state.draft.pages) {
        if (showOptions) viewModel.estimateSizes() else viewModel.clearEstimates()
    }
    DisposableEffect(viewModel) {
        onDispose { viewModel.clearEstimates() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.options.name.ifBlank { "Novo documento" }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = goHome, enabled = ready) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Voltar à biblioteca")
                    }
                },
                actions = {
                    IconButton(onClick = toggleTheme) {
                        ScanGlyph(if (dark) Glyph.SUN else Glyph.MOON, description = if (dark) "Tema claro" else "Tema escuro")
                    }
                    if (page != null) {
                        Box {
                            IconButton(onClick = { showExportMenu = true }, enabled = ready) {
                                Icon(Icons.Default.MoreVert, "Opções do documento")
                            }
                            DropdownMenu(expanded = showExportMenu, onDismissRequest = { showExportMenu = false }) {
                                DropdownMenuItem(text = { Text("Opções do PDF") }, onClick = {
                                    showExportMenu = false
                                    showOptions = true
                                })
                                DropdownMenuItem(text = { Text("Exportar cópia em PDF") }, onClick = {
                                    showExportMenu = false
                                    exportPdf()
                                })
                                state.lastExport?.let { uri ->
                                    DropdownMenuItem(text = { Text("Abrir último PDF") }, onClick = {
                                        showExportMenu = false
                                        openPdf(uri)
                                    })
                                }
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.background),
            )
        },
        snackbarHost = { SnackbarHost(snackbars) },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (page != null) {
                Surface(color = colors.surfaceContainer, tonalElevation = 2.dp) {
                    Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(horizontal = 16.dp)) {
                        HorizontalDivider(color = colors.outlineVariant)
                        Row(
                            modifier = Modifier.widthIn(max = 650.dp).fillMaxWidth().align(Alignment.CenterHorizontally)
                                .padding(vertical = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Box {
                                OutlinedIconButton(onClick = { showAdd = true }, enabled = canAdd, modifier = Modifier.size(50.dp)) {
                                    Icon(Icons.Default.Add, "Adicionar páginas")
                                }
                                DropdownMenu(expanded = showAdd, onDismissRequest = { showAdd = false }) {
                                    DropdownMenuItem(
                                        text = { Text("Digitalizar com a câmera") },
                                        leadingIcon = { ScanGlyph(Glyph.SCAN) },
                                        onClick = { showAdd = false; startScanner() },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Importar imagens") },
                                        leadingIcon = { ScanGlyph(Glyph.IMAGE) },
                                        onClick = { showAdd = false; pickImages() },
                                    )
                                }
                            }
                            OutlinedButton(
                                onClick = sharePdf,
                                enabled = ready,
                                modifier = Modifier.weight(1.15f).height(50.dp),
                                contentPadding = PaddingValues(horizontal = 6.dp),
                            ) {
                                Icon(Icons.Default.Share, null, Modifier.size(16.dp))
                                Spacer(Modifier.width(5.dp))
                                Text("Compartilhar")
                            }
                            Button(
                                onClick = savePdf,
                                enabled = ready,
                                modifier = Modifier.weight(1f).height(50.dp),
                                contentPadding = PaddingValues(horizontal = 6.dp),
                            ) {
                                Text("Salvar")
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                    }
                }
            }
        },
    ) { scaffoldPadding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(scaffoldPadding)) {
            val wide = maxWidth >= 760.dp
            val previewHeight = (maxHeight * if (wide) 0.69f else 0.45f).coerceIn(230.dp, if (wide) 540.dp else 370.dp)
            Column(Modifier.fillMaxSize()) {
                state.operation?.let { CurrentOperation(it, state.pagesExported, state.draft.pages.size) }
                when {
                    state.draftError -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Rascunho indisponível", style = MaterialTheme.typography.headlineSmall)
                            TextButton(onClick = viewModel::restore) { Text("Tentar novamente") }
                        }
                    }
                    page == null && state.operation != Operation.LOADING ->
                        EmptyScanner(ready, startScanner, pickImages)
                    page != null -> Editor(
                        state, page, wide, previewHeight, viewModel,
                        adjustments = { showAdjustments = true },
                        requestDelete = { showDelete = true },
                    )
                }
            }
        }
    }
    if (showOptions) {
        PdfOptionsSheet(state.options, state.sizeEstimates, viewModel::setPdfOptions, onDismiss = { showOptions = false })
    }
    if (showAdjustments && page != null) {
        AdjustmentsSheet(page, state.draft.pages.size, viewModel, onDismiss = { showAdjustments = false })
    }
    if (showDelete && page != null) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text("Remover esta página?") },
            text = { Text("A página será retirada do rascunho do documento.") },
            confirmButton = { TextButton(onClick = { showDelete = false; viewModel.delete() }) { Text("Remover") } },
            dismissButton = { TextButton(onClick = { showDelete = false }) { Text("Cancelar") } },
        )
    }
}

@Composable
internal fun BrandMark(modifier: Modifier = Modifier) {
    Surface(modifier, shape = RoundedCornerShape(11.dp), color = MaterialTheme.colorScheme.primaryContainer) {
        ScanGlyph(Glyph.SCAN, Modifier.padding(6.dp).size(22.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}

@Composable
internal fun CurrentOperation(operation: Operation, done: Int, total: Int) {
    val progress = if (total == 0) 0f else done.toFloat() / total
    val label = when (operation) {
        Operation.LOADING -> "Carregando rascunho"
        Operation.OPENING -> "Abrindo documento"
        Operation.IMPORTING -> "Adicionando e enquadrando imagens"
        Operation.ADDING_SCANS -> "Adicionando páginas digitalizadas"
        Operation.COMPOSING -> "Montando página…"
        Operation.DISCARDING -> "Descartando rascunho"
        Operation.DETECTING -> "Procurando bordas"
        Operation.SAVING -> "Salvando na biblioteca · $done de $total"
        Operation.EXPORTING -> "Gerando PDF · $done de $total"
        Operation.SHARING -> "Preparando PDF · $done de $total"
    }
    Column(Modifier.fillMaxWidth()) {
        if (operation == Operation.EXPORTING || operation == Operation.SHARING || operation == Operation.SAVING) {
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        Text(label, Modifier.padding(horizontal = 16.dp, vertical = 7.dp), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun EmptyScanner(enabled: Boolean, startScanner: () -> Unit, pickImages: () -> Unit) {
    Column(
        Modifier.fillMaxSize().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            modifier = Modifier.widthIn(max = 470.dp).fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            color = Color(0xFF132E26),
        ) {
            DocumentIllustration()
        }
        Spacer(Modifier.height(28.dp))
        Text(
            "Digitalize seus\ndocumentos",
            color = MaterialTheme.colorScheme.onBackground,
            style = MaterialTheme.typography.headlineLarge,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "Fotografe ou importe páginas para criar um PDF.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(26.dp))
        Button(
            onClick = startScanner, enabled = enabled,
            modifier = Modifier.widthIn(max = 430.dp).fillMaxWidth().height(54.dp),
        ) {
            ScanGlyph(Glyph.SCAN)
            Spacer(Modifier.width(9.dp))
            Text("Digitalizar com a câmera")
        }
        Spacer(Modifier.height(9.dp))
        OutlinedButton(
            onClick = pickImages, enabled = enabled,
            modifier = Modifier.widthIn(max = 430.dp).fillMaxWidth().height(54.dp),
        ) {
            ScanGlyph(Glyph.IMAGE)
            Spacer(Modifier.width(9.dp))
            Text("Importar imagens")
        }
        Spacer(Modifier.height(22.dp))
        Text("Processamento no aparelho", color = MaterialTheme.colorScheme.onSurfaceVariant, style = specStyle)
    }
}

internal fun Filter.label() = when (this) {
    Filter.ORIGINAL -> "Original"
    Filter.MAGIC_COLOR -> "Cor mágica"
    Filter.LIGHTEN -> "Documento claro"
    Filter.SHARP_BW -> "P&B nítido"
    Filter.GRAYSCALE -> "Escala de cinza"
}
