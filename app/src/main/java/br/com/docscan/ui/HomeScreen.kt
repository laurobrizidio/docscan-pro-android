package br.com.docscan.ui

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import br.com.docscan.domain.Document
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val RECENT_LIMIT = 8

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun HomeScreen(
    state: HomeUiState,
    viewModel: HomeViewModel,
    snackbars: SnackbarHostState,
    dark: Boolean,
    toggleTheme: () -> Unit,
    canOpenEditor: Boolean,
    canResume: Boolean,
    startScanner: () -> Unit,
    pickImages: () -> Unit,
    startIdCard: () -> Unit,
    resumeEditor: () -> Unit,
    openDocument: (Document) -> Unit,
    deleteDocuments: (Set<String>) -> Unit,
    onRenamed: (String, String) -> Unit,
) {
    var pendingDelete by remember { mutableStateOf<Set<String>>(emptySet()) }
    val focus = LocalFocusManager.current
    val ready = !state.loading && !state.loadError && !state.busy && canOpenEditor
    val selecting = state.selectedIds.isNotEmpty()
    val list = rememberLazyListState()
    val searching = state.query.isNotBlank()
    val visible = when {
        state.loading || state.loadError -> emptyList()
        state.showAll || searching || selecting -> state.documents
        else -> state.documents.take(RECENT_LIMIT)
    }
    BackHandler(selecting || state.showAll) {
        if (selecting) viewModel.clearSelection() else viewModel.showAll(false)
    }
    LaunchedEffect(state.query, state.showAll) { list.scrollToItem(0) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (selecting) "${state.selectedIds.size} selecionados" else "DocScan Pro") },
                navigationIcon = {
                    if (selecting) {
                        IconButton(onClick = viewModel::clearSelection) { Icon(Icons.Default.Close, "Limpar seleção") }
                    } else {
                        BrandMark(Modifier.padding(start = 16.dp))
                        Spacer(Modifier.width(10.dp))
                    }
                },
                actions = {
                    if (selecting) {
                        IconButton(onClick = { viewModel.share(state.selectedIds) }, enabled = ready) {
                            Icon(Icons.Default.Share, "Compartilhar selecionados")
                        }
                        IconButton(onClick = { pendingDelete = state.selectedIds }, enabled = ready) {
                            Icon(Icons.Default.Delete, "Excluir selecionados")
                        }
                    } else {
                        IconButton(onClick = toggleTheme) {
                            ScanGlyph(if (dark) Glyph.SUN else Glyph.MOON, description = if (dark) "Tema claro" else "Tema escuro")
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            if (ready && !selecting) {
                ExtendedFloatingActionButton(onClick = startScanner, icon = { ScanGlyph(Glyph.CAMERA) }, text = { Text("Digitalizar") })
            }
        },
        snackbarHost = { SnackbarHost(snackbars) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                state = list,
                modifier = Modifier.widthIn(max = 740.dp).fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 104.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (state.loading || state.busy) item("progress") { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                item("search") {
                    OutlinedTextField(
                        value = state.query,
                        onValueChange = viewModel::setQuery,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Buscar documentos") },
                        leadingIcon = { Icon(Icons.Default.Search, null) },
                        trailingIcon = {
                            if (state.query.isNotEmpty()) {
                                IconButton(onClick = { viewModel.setQuery("") }) { Icon(Icons.Default.Close, "Limpar busca") }
                            }
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
                        shape = MaterialTheme.shapes.medium,
                    )
                }
                if (!state.showAll && !searching && !selecting) item("entry") {
                    Column(Modifier.padding(top = 9.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        FlowRow(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            maxItemsInEachRow = 2,
                        ) {
                            LibraryShortcut("Usar câmera", Glyph.CAMERA, ready, startScanner, Modifier.weight(1f))
                            LibraryShortcut("Importar imagens", Glyph.IMAGE, ready, pickImages, Modifier.weight(1f))
                            LibraryShortcut("Cartão de ID", Glyph.ID, ready, startIdCard, Modifier.weight(1f))
                            Spacer(Modifier.weight(1f))
                        }
                        if (canResume) {
                            OutlinedButton(onClick = resumeEditor, enabled = ready) { Text("Continuar edição") }
                        }
                    }
                }
                item("heading") {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            when {
                                searching -> "Resultados"
                                state.showAll -> "Todos os documentos"
                                else -> "Recentes"
                            },
                            style = MaterialTheme.typography.headlineSmall,
                        )
                        if (!searching && !selecting && state.documents.isNotEmpty()) {
                            TextButton(onClick = { viewModel.showAll(!state.showAll) }) {
                                Text(if (state.showAll) "Ver recentes" else "Exibir tudo")
                            }
                        }
                    }
                }
                if (state.loadError) {
                    item("error") {
                        Text("Não foi possível carregar a biblioteca.")
                        TextButton(onClick = viewModel::retry) { Text("Tentar novamente") }
                    }
                } else if (visible.isEmpty() && !state.loading) {
                    item("empty") { LibraryEmpty(searching) }
                }
                items(visible, key = Document::id) { document ->
                    val thumbnail by produceState<Bitmap?>(null, document.id, document.updatedAt) {
                        value = viewModel.thumbnail(document)
                    }
                    LibraryItem(
                        document, thumbnail, document.id == state.expandedId, document.id in state.selectedIds, ready,
                        showMenu = !selecting,
                        tap = { viewModel.tap(document) },
                        select = { viewModel.toggleSelected(document.id) },
                        open = { openDocument(document) },
                        share = { viewModel.share(setOf(document.id)) },
                        rename = { viewModel.startRename(document) },
                        delete = { pendingDelete = setOf(document.id) },
                    )
                }
            }
        }
    }

    state.rename?.let { rename ->
        AlertDialog(
            onDismissRequest = viewModel::cancelRename,
            title = { Text("Renomear documento") },
            text = {
                OutlinedTextField(
                    value = rename.name,
                    onValueChange = viewModel::updateRename,
                    label = { Text("Nome do documento") },
                    singleLine = true,
                    isError = rename.error != null,
                    supportingText = rename.error?.let { { Text(it) } },
                    enabled = !state.busy,
                )
            },
            dismissButton = { TextButton(onClick = viewModel::cancelRename, enabled = !state.busy) { Text("Cancelar") } },
            confirmButton = {
                Button(onClick = { viewModel.confirmRename(onRenamed) }, enabled = !state.busy) { Text("Renomear") }
            },
        )
    }
    if (pendingDelete.isNotEmpty()) {
        val ids = pendingDelete
        val singular = ids.size == 1
        AlertDialog(
            onDismissRequest = { pendingDelete = emptySet() },
            title = { Text(if (singular) "Excluir documento?" else "Excluir ${ids.size} documentos?") },
            text = { Text(if (singular) "As páginas e o PDF serão removidos da biblioteca." else "As páginas e os PDFs serão removidos da biblioteca.") },
            dismissButton = { TextButton(onClick = { pendingDelete = emptySet() }) { Text("Cancelar") } },
            confirmButton = { Button(onClick = { pendingDelete = emptySet(); deleteDocuments(ids) }) { Text("Excluir") } },
        )
    }
}

@Composable
private fun LibraryShortcut(label: String, glyph: Glyph, enabled: Boolean, onClick: () -> Unit, modifier: Modifier) {
    Surface(onClick = onClick, enabled = enabled, modifier = modifier, shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.primaryContainer) {
        Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ScanGlyph(glyph, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
            Text(label, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
}

@Composable
private fun LibraryEmpty(searching: Boolean) {
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ScanGlyph(Glyph.PDF, Modifier.size(30.dp), tint = MaterialTheme.colorScheme.primary)
            Text(if (searching) "Nenhum documento encontrado" else "Sua biblioteca começa aqui",
                style = MaterialTheme.typography.titleMedium)
            Text(if (searching) "Experimente outro nome." else "Digitalize ou importe seu primeiro documento.",
                style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun LibraryItem(
    document: Document,
    thumbnail: Bitmap?,
    expanded: Boolean,
    selected: Boolean,
    enabled: Boolean,
    showMenu: Boolean,
    tap: () -> Unit,
    select: () -> Unit,
    open: () -> Unit,
    share: () -> Unit,
    rename: () -> Unit,
    delete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    val date = remember(document.updatedAt) {
        SimpleDateFormat("dd/MM/yyyy 'às' HH:mm", Locale.forLanguageTag("pt-BR")).format(Date(document.updatedAt))
    }
    val count = if (document.pageCount == 1) "1 página" else "${document.pageCount} páginas"
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium,
        color = if (selected) colors.primaryContainer else colors.surfaceContainer) {
        Column {
            Row(
                Modifier.fillMaxWidth().combinedClickable(enabled = enabled, role = Role.Button, onLongClick = select, onClick = tap)
                    .padding(start = 12.dp, top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(width = 54.dp, height = 66.dp).clip(MaterialTheme.shapes.small).background(colors.surfaceContainerHigh),
                    contentAlignment = Alignment.Center,
                ) {
                    if (thumbnail != null) {
                        Image(thumbnail.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                    } else ScanGlyph(Glyph.PDF, tint = colors.primary)
                }
                Column(Modifier.weight(1f).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(document.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(date, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    Text(count, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
                Checkbox(selected, onCheckedChange = { select() }, enabled = enabled,
                    modifier = Modifier.semantics { contentDescription = "Selecionar ${document.name}" })
                if (showMenu) Box {
                    IconButton(onClick = { menu = true }, enabled = enabled) {
                        Icon(Icons.Default.MoreVert, "Opções de ${document.name}")
                    }
                    DropdownMenu(menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Renomear") }, onClick = { menu = false; rename() })
                        DropdownMenuItem(text = { Text("Excluir") }, onClick = { menu = false; delete() })
                    }
                }
            }
            if (expanded) {
                Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = open, enabled = enabled) { Text("Ver") }
                    OutlinedButton(onClick = share, enabled = enabled) { Text("Compartilhar") }
                }
            }
        }
    }
}
