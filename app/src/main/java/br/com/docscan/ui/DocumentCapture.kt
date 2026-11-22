package br.com.docscan.ui

import android.app.Activity
import android.content.Intent
import android.content.IntentSender
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts.StartIntentSenderForResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.lifecycle.withResumed
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine

internal class DocumentCapture(
    val preparing: Boolean,
    val start: (Int) -> Unit,
    val cancel: () -> Unit,
)

@Composable
internal fun rememberDocumentCapture(
    activity: ComponentActivity,
    onPages: (List<String>) -> Unit,
    onError: (String) -> Unit,
): DocumentCapture {
    val reportError by rememberUpdatedState(onError)
    var pageLimit by remember { mutableIntStateOf(0) }
    val launcher = rememberLauncherForActivityResult(StartIntentSenderForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val pages = scannedPages(result.data)
            if (pages.isEmpty()) reportError("O scanner não retornou páginas.") else onPages(pages)
        } else if (result.data?.hasExtra(StartIntentSenderForResult.EXTRA_SEND_INTENT_EXCEPTION) == true) {
            reportError("Não foi possível abrir o scanner. Tente novamente.")
        }
    }
    LaunchedEffect(activity, pageLimit) {
        if (pageLimit <= 0) return@LaunchedEffect
        try {
            val sender = scannerIntent(activity, pageLimit).awaitIntentSender()
            activity.lifecycle.withResumed {
                launcher.launch(IntentSenderRequest.Builder(sender).build())
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            reportError(
                if (error is MlKitException && error.errorCode == MlKitException.UNSUPPORTED) {
                    "O scanner do Google não é compatível com este aparelho. Você pode importar imagens."
                } else {
                    "Não foi possível iniciar o scanner. Confira a conexão e o Google Play Services."
                },
            )
        } finally {
            pageLimit = 0
        }
    }
    return DocumentCapture(pageLimit > 0, { limit -> if (pageLimit == 0) pageLimit = limit }, { pageLimit = 0 })
}

private fun scannerIntent(activity: ComponentActivity, pageLimit: Int): Task<IntentSender> {
    val options = GmsDocumentScannerOptions.Builder()
        .setGalleryImportAllowed(false)
        .setPageLimit(pageLimit)
        .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
        .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
        .build()
    return GmsDocumentScanning.getClient(options).getStartScanIntent(activity)
}

private fun scannedPages(data: Intent?): List<String> =
    GmsDocumentScanningResult.fromActivityResultIntent(data)?.pages.orEmpty().map { it.imageUri.toString() }

private suspend fun Task<IntentSender>.awaitIntentSender(): IntentSender = suspendCancellableCoroutine { continuation ->
    addOnCompleteListener { task ->
        if (task.isSuccessful) {
            continuation.resume(task.result)
        } else {
            continuation.resumeWithException(task.exception ?: IllegalStateException("Scanner indisponível"))
        }
    }
}
