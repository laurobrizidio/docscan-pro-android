package br.com.docscan.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.CreateDocument
import androidx.activity.result.contract.ActivityResultContracts.PickMultipleVisualMedia
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.SnackbarHostState
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
internal fun ScanRoute(activity: ComponentActivity, viewModel: ScanViewModel, onThemeChanged: (Boolean) -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val systemDark = isSystemInDarkTheme()
    var darkOverride by rememberSaveable { mutableStateOf<Boolean?>(null) }
    val dark = darkOverride ?: systemDark
    SideEffect { onThemeChanged(dark) }
    val snackbars = remember { SnackbarHostState() }
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
    LaunchedEffect(pendingImages, state.operation, state.draftError) {
        if (pendingImages.isNotEmpty() && state.operation == null && !state.draftError) {
            val sources = pendingImages.toList()
            pendingImages = arrayListOf()
            viewModel.import(sources, imageOrigin)
        }
    }
    LaunchedEffect(destination, state.operation, state.draftError) {
        val target = destination
        if (target != null && state.operation == null && !state.draftError) {
            destination = null
            viewModel.export(target, exportOptions)
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
        ScanScreen(
            state = state,
            viewModel = viewModel,
            snackbars = snackbars,
            dark = dark,
            toggleTheme = { darkOverride = !dark },
            startScanner = { capture.start(MAX_PAGES - state.draft.pages.size) },
            pickImages = {
                try {
                    gallery.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly))
                } catch (_: ActivityNotFoundException) {
                    viewModel.tell("O seletor de imagens está indisponível.")
                }
            },
            savePdf = {
                exportOptions = state.options
                try {
                    createPdf.launch(exportOptions.fileName)
                } catch (_: ActivityNotFoundException) {
                    viewModel.tell("O seletor de destino está indisponível.")
                }
            },
            sharePdf = viewModel::prepareShare,
            openPdf = ::open,
        )
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
