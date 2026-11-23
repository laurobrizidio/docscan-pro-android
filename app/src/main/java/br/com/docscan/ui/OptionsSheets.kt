package br.com.docscan.ui

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import br.com.docscan.domain.Filter
import br.com.docscan.domain.Orientation
import br.com.docscan.domain.Paper
import br.com.docscan.domain.PdfOptions
import br.com.docscan.domain.PdfQuality
import br.com.docscan.domain.ScanPage
import br.com.docscan.domain.bestQualityFor
import br.com.docscan.domain.qualityLadder
import kotlin.math.roundToInt

private val pdfSizeTargets = listOf(null, 1_000_000L, 2_000_000L, 5_000_000L, 10_000_000L)

@Composable
internal fun PdfOptionsSheet(
    options: PdfOptions,
    estimates: Map<PdfQuality, Long>?,
    change: (PdfOptions) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var target by rememberSaveable { mutableStateOf<Long?>(null) }
    val bytes = target
    val canEstimate = !estimates.isNullOrEmpty()
    val best = if (bytes != null && estimates != null) bestQualityFor(bytes, estimates) else null
    val exceedsTarget = bytes != null && canEstimate && best == null

    LaunchedEffect(target, estimates) {
        if (bytes != null && canEstimate) {
            val choice = best ?: PdfQuality.MINIMAL
            if (choice != options.quality) change(options.copy(quality = choice))
        }
    }

    EditorSheet(onDismiss) {
        Text("Opções do PDF", style = MaterialTheme.typography.headlineSmall)
        OutlinedTextField(
            value = options.name,
            onValueChange = { change(options.copy(name = it.take(110))) },
            label = { Text("Nome do arquivo") },
            placeholder = { Text("Documento_Digitalizado") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        ChoiceGroup("Papel", Paper.entries, options.paper, {
            when (it) {
                Paper.A4 -> "A4"
                Paper.LETTER -> "Carta"
                Paper.FIT_IMAGE -> "Ajustar à imagem"
            }
        }) { change(options.copy(paper = it)) }
        ChoiceGroup("Orientação", Orientation.entries, options.orientation, {
            when (it) {
                Orientation.AUTO -> "Automática"
                Orientation.PORTRAIT -> "Retrato"
                Orientation.LANDSCAPE -> "Paisagem"
            }
        }) { change(options.copy(orientation = it)) }
        ChoiceGroup("Margem", listOf(0, 10, 20), options.marginMm, { "$it mm" }) { change(options.copy(marginMm = it)) }
        ChoiceGroup("Qualidade", qualityLadder.asReversed(), options.quality, { quality ->
            val label = when (quality) {
                PdfQuality.COMPACT -> "Compacta"
                PdfQuality.BALANCED -> "Equilibrada"
                PdfQuality.HIGH -> "Alta"
                PdfQuality.MINIMAL -> "Mínima"
            }
            val size = estimates?.get(quality)
            when {
                size != null -> "$label · ~${Formatter.formatShortFileSize(context, size)}"
                estimates == null -> "$label · …"
                else -> label
            }
        }) { quality ->
            target = null
            change(options.copy(quality = quality))
        }
        Text("Mínima oferece ~94 dpi em A4. Texto pequeno pode ficar ilegível.", style = MaterialTheme.typography.bodySmall)
        ChoiceGroup(
            "Tamanho final",
            pdfSizeTargets,
            target,
            label = { if (it == null) "Livre" else "até ${it / 1_000_000} MB" },
            enabled = canEstimate,
        ) { target = it }
        if (exceedsTarget) {
            estimates?.get(PdfQuality.MINIMAL)?.let { minimum ->
                val formatted = Formatter.formatShortFileSize(context, minimum)
                Text("Mesmo na qualidade mínima o PDF fica com ~$formatted.", style = MaterialTheme.typography.bodySmall)
            }
        }
        Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Concluir") }
    }
}

@Composable
internal fun AdjustmentsSheet(page: ScanPage, pageCount: Int, viewModel: ScanViewModel, onDismiss: () -> Unit) {
    val look = page.look
    EditorSheet(onDismiss) {
        Text("Ajustes da página", style = MaterialTheme.typography.headlineSmall)
        ValueSlider("Inclinação", page.fineRotation, -25f..25f, { "${it}°" }) {
            viewModel.adjustAngle((it * 2).roundToInt() / 2f)
        }
        if (look.filter != Filter.ORIGINAL) {
            ValueSlider("Intensidade", look.strength.toFloat(), 0f..100f, ::percent) { value ->
                viewModel.updateLook { it.copy(strength = value.roundToInt()) }
            }
        }
        ValueSlider("Brilho", look.brightness.toFloat(), 50f..150f, ::percent) { value ->
            viewModel.updateLook { it.copy(brightness = value.roundToInt()) }
        }
        ValueSlider("Contraste", look.contrast.toFloat(), 50f..160f, ::percent) { value ->
            viewModel.updateLook { it.copy(contrast = value.roundToInt()) }
        }
        if (look.filter != Filter.ORIGINAL) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Text("Realce com IA local", style = MaterialTheme.typography.titleSmall)
                    Text("Tiny ML distingue tinta e papel.", style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = look.useMl, onCheckedChange = { enabled -> viewModel.updateLook { it.copy(useMl = enabled) } })
            }
        }
        if (pageCount > 1) {
            TextButton(onClick = viewModel::applyLookToAll) { Text("Aplicar filtro a todas as páginas") }
        }
        TextButton(onClick = viewModel::resetPage) { Text("Redefinir página") }
        Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Concluir") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditorSheet(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.76f).dp
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().heightIn(max = maxHeight).imePadding()
                .verticalScroll(rememberScrollState()).padding(start = 22.dp, end = 22.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            content()
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> ChoiceGroup(
    title: String,
    values: List<T>,
    selected: T,
    label: (T) -> String,
    enabled: Boolean = true,
    change: (T) -> Unit,
) {
    Column {
        Text(title, style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (value in values) FilterChip(selected == value, { change(value) }, { Text(label(value)) }, enabled = enabled)
        }
    }
}

@Composable
private fun ValueSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, format: (Float) -> String, commit: (Float) -> Unit) {
    var editing by remember(value) { mutableFloatStateOf(value) }
    Column {
        Text("$label · ${format(editing)}", style = MaterialTheme.typography.titleSmall)
        Slider(
            value = editing,
            onValueChange = { editing = it },
            onValueChangeFinished = { commit(editing) },
            valueRange = range,
        )
    }
}

private fun percent(value: Float) = "${value.roundToInt()}%"
