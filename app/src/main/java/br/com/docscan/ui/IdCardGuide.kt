package br.com.docscan.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

internal enum class IdStep { FRONT, BACK }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun IdCardGuide(
    step: IdStep,
    preparing: Boolean,
    operation: Operation?,
    hasBack: Boolean,
    snackbars: SnackbarHostState,
    onCamera: () -> Unit,
    onGallery: () -> Unit,
    onFrontOnly: () -> Unit,
    onCancel: () -> Unit,
) {
    val enabled = !preparing && operation == null
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Cartão de ID") },
                actions = {
                    TextButton(onClick = onCancel, enabled = enabled) { Text("Cancelar") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbars) },
    ) { padding ->
        Box(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                Modifier.widthIn(max = 470.dp).fillMaxWidth().padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ) {
                    ScanGlyph(Glyph.ID, Modifier.padding(14.dp).size(36.dp))
                }
                Text(if (step == IdStep.FRONT) "Frente" else "Verso", style = MaterialTheme.typography.headlineSmall)
                if (!hasBack) {
                    Text(
                        "Posicione o documento sobre uma superfície plana e com boa luz.",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Button(onClick = onCamera, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                        Text("Usar câmera")
                    }
                    OutlinedButton(onClick = onGallery, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                        Text("Escolher da galeria")
                    }
                }
                if (step == IdStep.BACK) {
                    if (hasBack) {
                        Button(onClick = onFrontOnly, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                            Text("Montar página")
                        }
                    } else {
                        TextButton(onClick = onFrontOnly, enabled = enabled, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                            Text("Só frente")
                        }
                    }
                }
                operation?.let { CurrentOperation(it, done = 0, total = 0) }
            }
        }
    }
}
