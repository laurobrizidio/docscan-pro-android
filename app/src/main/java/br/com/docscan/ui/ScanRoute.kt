package br.com.docscan.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.CreateDocument
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
import br.com.docscan.domain.ImageOrigin
import br.com.docscan.domain.MAX_PAGES
import br.com.docscan.domain.Orientation
import br.com.docscan.domain.Paper
import br.com.docscan.domain.PdfOptions
import br.com.docscan.domain.PdfQuality

@Composable
internal fun ScanRoute(
    activity: ComponentActivity,
    viewModel: ScanViewModel,
    home: HomeViewModel,
    onThemeChanged: (Boolean) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val library by home.state.collectAsStateWithLifecycle()
    val systemDark = isSystemInDarkTheme()
    var darkOverride by rememberSaveable { mutableStateOf<Boolean?>(null) }
    val dark = darkOverride ?: systemDark
    SideEffect { onThemeChanged(dark) }
    val snackbars = remember { SnackbarHostState() }
    var editing by rememberSaveable { mutableStateOf(false) }
    var newDocument by rememberSaveable { mutableStateOf(false) }
    var confirmSwitch by remember { mutableStateOf<(() -> Unit)?>(null) }
    var pendingImages by rememberSaveable { mutableStateOf<List<String>>(arrayListOf()) }
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
    val createPdf = rememberLauncherForActivityResult(CreateDocument("application/pdf")) { uri ->
        if (uri != null) destination = uri.toString()
    }

    LaunchedEffect(viewModel) { viewModel.messages.collect { snackbars.showSnackbar(it) } }
    LaunchedEffect(home) { home.messages.collect { snackbars.showSnackbar(it) } }
    LaunchedEffect(pendingImages, state.operation, state.draftError) {
        if (pendingImages.isNotEmpty() && state.operation == null && !state.draftError) {
            val sources = pendingImages.toList()
            pendingImages = arrayListOf()
            viewModel.import(sources, imageOrigin, newDocument) { editing = true }
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

    fun exportPdf() {
        exportOptions = state.options
        try {
            createPdf.launch(exportOptions.fileName)
        } catch (_: ActivityNotFoundException) {
            viewModel.tell("O seletor de destino está indisponível.")
        }
    }

    fun open(uri: String) {
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(uri), "application/pdf")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            activity.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            viewModel.tell("Nenhum aplicativo disponível para abrir o PDF.")
        } catch (_: SecurityException) {
            viewModel.tell("O PDF não está mais acessível.")
        }
    }

    ScanTheme(dark) {
        BackHandler(editing) { if (state.operation == null) editing = false }
        if (editing) {
            ScanScreen(
                state = state,
                viewModel = viewModel,
                snackbars = snackbars,
                dark = dark,
                toggleTheme = { darkOverride = !dark },
                startScanner = { capturePages(new = false) },
                pickImages = { pickImages(new = false) },
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
                toggleTheme = { darkOverride = !dark },
                canOpenEditor = state.operation == null && !state.draftError,
                canResume = state.session.dirty && state.draft.pages.isNotEmpty(),
                startScanner = { replaceDraft { capturePages(new = true) } },
                pickImages = { replaceDraft { pickImages(new = true) } },
                resumeEditor = { editing = true },
                openDocument = { document ->
                    if (document.id == state.session.documentId && state.session.dirty) editing = true
                    else replaceDraft { viewModel.openDocument(document.id) { editing = true } }
                },
                deleteDocuments = { ids -> home.delete(ids, viewModel::discardDeleted) },
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
        if (capture.preparing) {
            ScanProgress(
                title = "Preparando scanner",
                details = "Na primeira abertura, o Google Play Services pode baixar o scanner.",
                onCancel = capture.cancel,
            )
        } else if (state.operation == Operation.ADDING_SCANS) {
            ScanProgress(title = "Adicionando páginas digitalizadas")
        }
        state.sharePdf?.let { pdf ->
            PdfShareSheet(pdf, onDismiss = viewModel::dismissShare, onError = viewModel::tell)
        }
    }
}

private val pdfOptionsSaver = listSaver<PdfOptions, Any>(
    save = { listOf(it.name, it.paper.name, it.orientation.name, it.marginMm, it.quality.name) },
    restore = {
        PdfOptions(it[0] as String, Paper.valueOf(it[1] as String), Orientation.valueOf(it[2] as String),
            it[3] as Int, PdfQuality.valueOf(it[4] as String))
    },
)
