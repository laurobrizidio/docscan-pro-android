package br.com.docscan.ui

import android.content.ActivityNotFoundException
import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.CreateDocument
import androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments
import androidx.activity.result.contract.ActivityResultContracts.PickMultipleVisualMedia
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import br.com.docscan.domain.AppTheme
import br.com.docscan.domain.ImageOrigin
import br.com.docscan.domain.ImportSource
import br.com.docscan.domain.MAX_PAGES
import br.com.docscan.domain.Orientation
import br.com.docscan.domain.Paper
import br.com.docscan.domain.PdfOptions
import br.com.docscan.domain.PdfQuality
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val PDF_MIME_TYPE = "application/pdf"
private val pdfNameExtension = Regex("""(?i)(?:\.pdf\s*)+$""")

@Composable
internal fun ScanRoute(
    activity: ComponentActivity,
    viewModel: ScanViewModel,
    home: HomeViewModel,
    preferences: SettingsViewModel,
    onThemeChanged: (Boolean) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val library by home.state.collectAsStateWithLifecycle()
    val settings by preferences.settings.collectAsStateWithLifecycle()
    val systemDark = isSystemInDarkTheme()
    val dark = settings.theme.isDark(systemDark)
    SideEffect { onThemeChanged(dark) }
    val snackbars = remember { SnackbarHostState() }
    var editing by rememberSaveable { mutableStateOf(false) }
    var showingSettings by rememberSaveable { mutableStateOf(false) }
    var idStep by rememberSaveable { mutableStateOf<IdStep?>(null) }
    var confirmDiscardId by rememberSaveable { mutableStateOf(false) }
    var newDocument by rememberSaveable { mutableStateOf(false) }
    var confirmSwitch by remember { mutableStateOf<(() -> Unit)?>(null) }
    var pendingImages by rememberSaveable { mutableStateOf<List<String>>(arrayListOf()) }
    var pendingFiles by rememberSaveable(stateSaver = importSourcesSaver) { mutableStateOf(emptyList<ImportSource>()) }
    var imageOrigin by rememberSaveable { mutableStateOf(ImageOrigin.GALLERY) }
    var destination by rememberSaveable { mutableStateOf<String?>(null) }
    var exportOptions by rememberSaveable(stateSaver = pdfOptionsSaver) { mutableStateOf(PdfOptions()) }
    val capture = rememberDocumentCapture(
        activity,
        onPages = { pages ->
            imageOrigin = ImageOrigin.SCANNER
            pendingImages = ArrayList(pages)
        },
        onError = viewModel::tell,
    )
    val gallery = rememberLauncherForActivityResult(PickMultipleVisualMedia(MAX_PAGES)) { uris ->
        if (uris.isNotEmpty()) {
            imageOrigin = ImageOrigin.GALLERY
            pendingImages = ArrayList(uris.map(Uri::toString))
        }
    }
    val idGallery = rememberLauncherForActivityResult(PickVisualMedia()) { uri ->
        if (uri != null) {
            imageOrigin = ImageOrigin.GALLERY
            pendingImages = arrayListOf(uri.toString())
        }
    }
    val resolver = activity.contentResolver
    val files = rememberLauncherForActivityResult(OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            pendingFiles = uris.map { uri ->
                val type = try {
                    resolver.getType(uri)
                } catch (_: SecurityException) {
                    null
                }
                ImportSource(uri.toString(), type == PDF_MIME_TYPE)
            }
        }
    }
    val createPdf = rememberLauncherForActivityResult(CreateDocument(PDF_MIME_TYPE)) { uri ->
        if (uri != null) destination = uri.toString()
    }

    fun composeIdCard() {
        viewModel.composeIdCard { viewModel.finishIdCardStep(IdCardResult.COMPOSED) }
    }

    fun discardIdCard() {
        pendingImages = arrayListOf()
        viewModel.discardSession { viewModel.finishIdCardStep(IdCardResult.DISCARDED) }
    }

    fun cancelIdCard() {
        if (capture.preparing || state.operation != null) return
        if (idStep == IdStep.BACK) confirmDiscardId = true else discardIdCard()
    }

    LaunchedEffect(viewModel) { viewModel.messages.collect { snackbars.showSnackbar(it) } }
    LaunchedEffect(home) { home.messages.collect { snackbars.showSnackbar(it) } }
    LaunchedEffect(preferences) { preferences.messages.collect { snackbars.showSnackbar(it) } }
    LaunchedEffect(viewModel) {
        viewModel.idCardTransitions.collect { result ->
            when (result) {
                IdCardResult.FRONT_IMPORTED -> idStep = IdStep.BACK
                IdCardResult.COMPOSED -> {
                    idStep = null
                    editing = true
                }
                IdCardResult.DISCARDED -> idStep = null
            }
        }
    }
    LaunchedEffect(viewModel) { viewModel.openImportedFiles.collect { editing = true } }
    LaunchedEffect(pendingImages, state.operation, state.draftError, idStep) {
        if (pendingImages.isNotEmpty() && state.operation == null && !state.draftError) {
            val sources = pendingImages.toList()
            pendingImages = arrayListOf()
            when (idStep) {
                IdStep.FRONT -> viewModel.import(sources.take(1), imageOrigin, newDocument = true) {
                    viewModel.finishIdCardStep(IdCardResult.FRONT_IMPORTED)
                }
                IdStep.BACK -> if (state.draft.pages.size == 1) {
                    viewModel.import(sources.take(1), imageOrigin, newDocument = false, onImported = ::composeIdCard)
                }
                null -> viewModel.import(sources, imageOrigin, newDocument) { editing = true }
            }
        }
    }
    LaunchedEffect(pendingFiles, state.operation, state.draftError) {
        if (pendingFiles.isNotEmpty() && state.operation == null && !state.draftError) {
            val sources = pendingFiles
            val createNew = newDocument
            val names = if (createNew) {
                withContext(Dispatchers.IO) {
                    sources.filter(ImportSource::isPdf).mapNotNull { file ->
                        resolver.pdfDisplayName(Uri.parse(file.uri))?.let { file.uri to it }
                    }.toMap()
                }
            } else emptyMap()
            pendingFiles = emptyList()
            viewModel.importFiles(sources, createNew, names)
        }
    }
    LaunchedEffect(destination, state.operation, state.draftError) {
        val target = destination
        if (target != null && state.operation == null && !state.draftError) {
            destination = null
            viewModel.export(target, exportOptions)
        }
    }

    fun replaceDraft(action: () -> Unit) {
        if (state.session.dirty && state.draft.pages.isNotEmpty()) confirmSwitch = action else action()
    }

    fun capturePages(new: Boolean) {
        newDocument = new
        capture.start(if (new) MAX_PAGES else MAX_PAGES - state.draft.pages.size)
    }

    fun pickImages(new: Boolean) {
        newDocument = new
        try {
            gallery.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly))
        } catch (_: ActivityNotFoundException) {
            viewModel.tell("O seletor de imagens está indisponível.")
        }
    }

    fun pickFiles(new: Boolean) {
        newDocument = new
        try {
            files.launch(arrayOf(PDF_MIME_TYPE, "image/*"))
        } catch (_: ActivityNotFoundException) {
            viewModel.tell("O seletor de arquivos está indisponível.")
        }
    }

    fun exportPdf() {
        exportOptions = state.options
        try {
            createPdf.launch(exportOptions.fileName)
        } catch (_: ActivityNotFoundException) {
            viewModel.tell("O seletor de destino está indisponível.")
        }
    }

    fun open(uri: String) {
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(uri), PDF_MIME_TYPE)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            activity.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            viewModel.tell("Nenhum aplicativo disponível para abrir o PDF.")
        } catch (_: SecurityException) {
            viewModel.tell("O PDF não está mais acessível.")
        }
    }

    fun toggleTheme() {
        preferences.update { current ->
            val theme = if (current.theme.isDark(systemDark)) AppTheme.LIGHT else AppTheme.DARK
            current.copy(theme = theme)
        }
    }

    ScanTheme(dark) {
        val step = idStep
        BackHandler(editing || showingSettings || step != null) {
            when {
                showingSettings -> showingSettings = false
                step != null -> cancelIdCard()
                state.operation == null -> editing = false
            }
        }
        if (showingSettings) {
            SettingsScreen(settings, preferences, snackbars, onBack = { showingSettings = false })
        } else if (step != null) {
            IdCardGuide(
                step = step,
                preparing = capture.preparing,
                operation = state.operation,
                hasBack = step == IdStep.BACK && state.draft.pages.size == 2,
                snackbars = snackbars,
                onCamera = { capture.start(1) },
                onGallery = {
                    try {
                        idGallery.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly))
                    } catch (_: ActivityNotFoundException) {
                        viewModel.tell("O seletor de imagens está indisponível.")
                    }
                },
                onFrontOnly = ::composeIdCard,
                onCancel = ::cancelIdCard,
            )
        } else if (editing) {
            ScanScreen(
                state = state,
                viewModel = viewModel,
                snackbars = snackbars,
                dark = dark,
                toggleTheme = ::toggleTheme,
                startScanner = { capturePages(new = false) },
                pickImages = { pickImages(new = false) },
                pickFiles = { pickFiles(new = false) },
                savePdf = { viewModel.saveToLibrary { editing = false } },
                exportPdf = ::exportPdf,
                goHome = { editing = false },
                sharePdf = viewModel::prepareShare,
                openPdf = ::open,
            )
        } else {
            HomeScreen(
                state = library.copy(busy = library.busy || state.operation != null),
                viewModel = home,
                snackbars = snackbars,
                dark = dark,
                toggleTheme = ::toggleTheme,
                openSettings = { showingSettings = true },
                canOpenEditor = state.operation == null && !state.draftError,
                canResume = state.session.dirty && state.draft.pages.isNotEmpty(),
                startScanner = { replaceDraft { capturePages(new = true) } },
                pickImages = { replaceDraft { pickImages(new = true) } },
                startIdCard = { replaceDraft { idStep = IdStep.FRONT } },
                pickFiles = { replaceDraft { pickFiles(new = true) } },
                resumeEditor = { editing = true },
                openDocument = { document ->
                    if (document.id == state.session.documentId && state.session.dirty) editing = true
                    else replaceDraft { viewModel.openDocument(document.id) { editing = true } }
                },
                deleteDocuments = { ids -> home.delete(ids, viewModel::discardDeleted) },
                onRenamed = viewModel::syncRenamed,
            )
        }
        confirmSwitch?.let { action ->
            AlertDialog(
                onDismissRequest = { confirmSwitch = null },
                title = { Text("Trocar de documento?") },
                text = { Text("Os ajustes do rascunho atual serão descartados ao abrir outro documento.") },
                confirmButton = {
                    Button(onClick = { confirmSwitch = null; action() }) { Text("Continuar") }
                },
                dismissButton = { TextButton(onClick = { confirmSwitch = null }) { Text("Cancelar") } },
            )
        }
        if (confirmDiscardId) {
            AlertDialog(
                onDismissRequest = { confirmDiscardId = false },
                title = { Text("Descartar o cartão?") },
                confirmButton = {
                    Button(onClick = { confirmDiscardId = false; discardIdCard() }) { Text("Descartar") }
                },
                dismissButton = { TextButton(onClick = { confirmDiscardId = false }) { Text("Continuar captura") } },
            )
        }
        if (capture.preparing) {
            ScanProgress(
                title = "Preparando scanner",
                details = "Na primeira abertura, o Google Play Services pode baixar o scanner.",
                onCancel = capture.cancel,
            )
        } else if (step == null && state.operation == Operation.ADDING_SCANS) {
            ScanProgress(title = "Adicionando páginas digitalizadas")
        }
        state.sharePdf?.let { pdf ->
            PdfShareSheet(listOf(pdf), onDismiss = viewModel::dismissShare, onError = viewModel::tell)
        }
        PdfShareSheet(library.sharedPdfs, onDismiss = home::closeShare, onError = viewModel::tell)
    }
}

private fun AppTheme.isDark(systemDark: Boolean): Boolean = when (this) {
    AppTheme.SYSTEM -> systemDark
    AppTheme.LIGHT -> false
    AppTheme.DARK -> true
}

private fun ContentResolver.pdfDisplayName(uri: Uri): String? {
    return try {
        query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) {
                cursor.getString(index)?.trim()?.replace(pdfNameExtension, "")?.trimEnd()
            } else null
        }
    } catch (_: SecurityException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }
}

private val importSourcesSaver = listSaver<List<ImportSource>, Any>(
    save = { files -> files.flatMap { listOf(it.uri, it.isPdf) } },
    restore = { values -> values.chunked(2).map { ImportSource(it[0] as String, it[1] as Boolean) } },
)

private val pdfOptionsSaver = listSaver<PdfOptions, Any>(
    save = { listOf(it.name, it.paper.name, it.orientation.name, it.marginMm, it.quality.name) },
    restore = {
        PdfOptions(it[0] as String, Paper.valueOf(it[1] as String), Orientation.valueOf(it[2] as String),
            it[3] as Int, PdfQuality.valueOf(it[4] as String))
    },
)
