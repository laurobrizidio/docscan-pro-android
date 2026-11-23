package br.com.docscan.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

private const val DEVELOPER_EMAIL = "laurobrizidio@hotmail.com"
private const val DEVELOPER_LINKEDIN = "linkedin.com/in/laurobrizidio/"
private const val DEVELOPER_PHONE = "(92) 99379-5337"
private const val DEVELOPER_WHATSAPP = "https://wa.me/5592993795337"
private const val PROJECT_URL = "https://github.com/laurobrizidio/docscan-pro-android"
private val whatsAppPackages = listOf("com.whatsapp", "com.whatsapp.w4b")

@Composable
internal fun AboutDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var launchError by remember { mutableStateOf(false) }
    var showLicenses by rememberSaveable { mutableStateOf(false) }
    val version = remember(context) {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }

    fun openContact(action: String, link: String, preferredPackages: List<String> = emptyList()) {
        val intent = Intent(action, Uri.parse(link))
        launchError = preferredPackages.none { context.tryOpenContact(Intent(intent).setPackage(it)) } &&
            !context.tryOpenContact(intent)
    }

    if (showLicenses) {
        AlertDialog(
            onDismissRequest = { showLicenses = false },
            title = { Text("Licenças e termos") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    TextButton(onClick = {
                        openContact(Intent.ACTION_VIEW, "https://android.googlesource.com/platform/frameworks/support/+/androidx-main/LICENSE.txt")
                    }) { Text("AndroidX · Apache 2.0") }
                    TextButton(onClick = {
                        openContact(Intent.ACTION_VIEW, "https://www.apache.org/licenses/LICENSE-2.0")
                    }) { Text("Kotlin e kotlinx.coroutines · Apache 2.0") }
                    TextButton(onClick = {
                        openContact(Intent.ACTION_VIEW, "https://developers.google.com/ml-kit/terms")
                    }) { Text("Google ML Kit · Termos e privacidade") }
                    if (launchError) LinkUnavailable()
                }
            },
            confirmButton = { TextButton(onClick = { showLicenses = false; launchError = false }) { Text("Voltar") } },
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sobre o DocScan Pro") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                version?.let { Text("Versão $it", style = MaterialTheme.typography.bodySmall) }
                Text("Desenvolvedor", style = MaterialTheme.typography.labelMedium)
                Text("Lauro Brizidio", style = MaterialTheme.typography.titleLarge)
                HorizontalDivider(Modifier.padding(vertical = 12.dp))
                ContactItem("E-mail", DEVELOPER_EMAIL, { Icon(Icons.Default.Email, null) }) {
                    openContact(Intent.ACTION_SENDTO, "mailto:$DEVELOPER_EMAIL")
                }
                ContactItem("LinkedIn", DEVELOPER_LINKEDIN, { Icon(Icons.Default.Person, null) }) {
                    openContact(Intent.ACTION_VIEW, "https://$DEVELOPER_LINKEDIN")
                }
                ContactItem("WhatsApp", DEVELOPER_PHONE, { ScanGlyph(Glyph.CHAT) }) {
                    openContact(Intent.ACTION_VIEW, DEVELOPER_WHATSAPP, whatsAppPackages)
                }
                HorizontalDivider(Modifier.padding(vertical = 12.dp))
                Text("Licença do projeto em definição.", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { openContact(Intent.ACTION_VIEW, PROJECT_URL) }) { Text("Código-fonte") }
                TextButton(onClick = { launchError = false; showLicenses = true }) { Text("Licenças e termos") }
                if (launchError) LinkUnavailable()
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fechar") } },
    )
}

@Composable
private fun LinkUnavailable() {
    Text(
        "Nenhum aplicativo conseguiu abrir este link.",
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.padding(top = 8.dp),
    )
}

private fun Context.tryOpenContact(intent: Intent): Boolean = try {
    startActivity(intent)
    true
} catch (_: ActivityNotFoundException) {
    false
} catch (_: SecurityException) {
    false
}

@Composable
private fun ContactItem(label: String, contact: String, icon: @Composable () -> Unit, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(label) },
        supportingContent = { Text(contact) },
        leadingContent = icon,
        modifier = Modifier.clickable(role = Role.Button, onClick = onClick),
    )
}
