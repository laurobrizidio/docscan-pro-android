package br.com.docscan.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import br.com.docscan.domain.ShareablePdf

private const val PDF_MIME_TYPE = "application/pdf"

private enum class Shortcut(val label: String) { WHATSAPP("WhatsApp"), EMAIL("E-mail"), DRIVE("Google Drive") }

private data class ShareApp(val component: ComponentName, val label: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PdfShareSheet(pdf: ShareablePdf, onDismiss: () -> Unit, onError: (String) -> Unit) {
    val context = LocalContext.current
    val send = remember(pdf) { pdf.sendIntent() }
    val apps = remember(context, send) { findShortcuts(context.packageManager, send) }
    var selected by remember(pdf) { mutableStateOf<Shortcut?>(null) }
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.75f).dp

    fun launch(intent: Intent) {
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            onError("Nenhum aplicativo compatível foi encontrado.")
        } catch (_: SecurityException) {
            onError("O aplicativo escolhido não conseguiu acessar o PDF.")
        }
        onDismiss()
    }

    fun openApp(app: ShareApp) = launch(Intent(send).setComponent(app.component))

    ModalBottomSheet(onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().heightIn(max = maxHeight).verticalScroll(rememberScrollState()).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Column(Modifier.padding(start = 24.dp, end = 24.dp, bottom = 12.dp)) {
                Text(selected?.label ?: "Compartilhar PDF", style = MaterialTheme.typography.headlineSmall)
                Text(pdf.fileName, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }

            val group = selected
            if (group == null) {
                for (shortcut in Shortcut.entries) {
                    val candidates = apps.getValue(shortcut)
                    if (candidates.isEmpty()) continue
                    ListItem(
                        headlineContent = { Text(shortcut.label) },
                        leadingContent = {
                            when (shortcut) {
                                Shortcut.WHATSAPP -> ScanGlyph(Glyph.CHAT)
                                Shortcut.EMAIL -> Icon(Icons.Default.Email, null)
                                Shortcut.DRIVE -> ScanGlyph(Glyph.CLOUD)
                            }
                        },
                        modifier = Modifier.clickable {
                            if (candidates.size == 1) openApp(candidates.first()) else selected = shortcut
                        },
                    )
                }
                if (apps.values.any { it.isNotEmpty() }) HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                ListItem(
                    headlineContent = { Text("Outros aplicativos") },
                    leadingContent = { Icon(Icons.Default.Share, null) },
                    modifier = Modifier.clickable { launch(Intent.createChooser(send, null)) },
                )
            } else {
                for (app in apps.getValue(group)) {
                    ListItem(
                        headlineContent = { Text(app.label) },
                        modifier = Modifier.clickable { openApp(app) },
                    )
                }
                TextButton(onClick = { selected = null }, modifier = Modifier.padding(start = 16.dp)) {
                    Text("Voltar")
                }
            }
        }
    }
}

private fun ShareablePdf.sendIntent(): Intent {
    val content = Uri.parse(uri)
    return Intent(Intent.ACTION_SEND).apply {
        type = PDF_MIME_TYPE
        putExtra(Intent.EXTRA_STREAM, content)
        clipData = ClipData.newRawUri(fileName, content)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}

private fun findShortcuts(packageManager: PackageManager, send: Intent): Map<Shortcut, List<ShareApp>> {
    val mail = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"))
    val emailComponents = packageManager.queryIntentActivities(mail, PackageManager.MATCH_DEFAULT_ONLY)
        .mapTo(mutableSetOf()) { ComponentName(it.activityInfo.packageName, it.activityInfo.name) }
    val emailPackages = emailComponents.mapTo(mutableSetOf()) { it.packageName }
    val apps = packageManager.queryIntentActivities(send, PackageManager.MATCH_DEFAULT_ONLY)
        .map { ShareApp(ComponentName(it.activityInfo.packageName, it.activityInfo.name), it.loadLabel(packageManager).toString()) }
    fun forPackages(packages: Set<String>) = apps.filter { it.component.packageName in packages }
        .sortedByDescending { it.component in emailComponents }
        .distinctBy { it.component.packageName }
        .sortedBy { it.label }

    return mapOf(
        Shortcut.WHATSAPP to forPackages(setOf("com.whatsapp", "com.whatsapp.w4b")),
        Shortcut.EMAIL to forPackages(emailPackages),
        Shortcut.DRIVE to forPackages(setOf("com.google.android.apps.docs")),
    )
}
