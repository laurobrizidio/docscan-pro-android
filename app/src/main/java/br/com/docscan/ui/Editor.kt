package br.com.docscan.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import br.com.docscan.domain.Filter
import br.com.docscan.domain.ScanPage

private enum class CropAspect(val label: String, val ratio: Float?) {
    FREE("Livre", null), A4("A4", 210f / 297), LETTER("Carta", 216f / 279), SQUARE("Quadrado", 1f),
}

@Composable
internal fun Editor(
    state: ScanUiState,
    page: ScanPage,
    wide: Boolean,
    previewHeight: Dp,
    viewModel: ScanViewModel,
    adjustments: () -> Unit,
    extractText: () -> Unit,
    requestDelete: () -> Unit,
) {
    val index = state.draft.pages.indexOfFirst { it.id == page.id }
    val ready = state.operation == null
    var cropping by rememberSaveable(page.id) { mutableStateOf(false) }
    var aspect by rememberSaveable(page.id) { mutableStateOf(CropAspect.FREE) }

    val preview: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Página ${index + 1} de ${state.draft.pages.size}", style = MaterialTheme.typography.headlineSmall)
                Text(
                    when {
                        state.original -> "ORIGINAL"
                        page.quad != null -> "RECORTE AUTOMÁTICO"
                        else -> ""
                    },
                    style = specStyle, color = MaterialTheme.colorScheme.primary,
                )
            }
            PagePreview(
                state.preview, page.crop, cropping, aspect.ratio, state.previewLoading,
                onCrop = viewModel::adjustCrop,
                modifier = Modifier.fillMaxWidth().height(previewHeight)
                    .clip(MaterialTheme.shapes.large).background(Color(0xFF172822))
                    .border(1.dp, Color(0xFF365145), MaterialTheme.shapes.large),
            )
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                FilterChip(cropping, { cropping = !cropping }, { Text("Recortar") }, enabled = ready,
                    leadingIcon = { ScanGlyph(Glyph.CROP, Modifier.size(17.dp)) })
                FilterChip(state.original, viewModel::toggleOriginal, { Text("Antes") }, enabled = ready,
                    leadingIcon = { ScanGlyph(Glyph.COMPARE, Modifier.size(17.dp)) })
                TextButton(onClick = {
                    aspect = CropAspect.FREE
                    viewModel.rotate()
                }, enabled = ready) {
                    ScanGlyph(Glyph.ROTATE, Modifier.size(17.dp))
                    Text("Girar", Modifier.padding(start = 6.dp))
                }
                TextButton(onClick = extractText, enabled = ready) {
                    ScanGlyph(Glyph.TEXT, Modifier.size(17.dp))
                    Text("Extrair texto", Modifier.padding(start = 6.dp))
                }
            }
            if (cropping) {
                Text("Arraste as alças para ajustar o recorte.", style = MaterialTheme.typography.bodySmall)
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    CropAspect.entries.forEach { choice ->
                        FilterChip(aspect == choice, onClick = {
                            aspect = choice
                            val bitmap = state.preview
                            if (bitmap != null && choice.ratio != null) {
                                viewModel.adjustCrop(page.crop.withAspect(choice.ratio, bitmap.width.toFloat() / bitmap.height))
                            }
                        }, label = { Text(choice.label) }, enabled = ready && !state.previewLoading)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    TextButton(onClick = { aspect = CropAspect.FREE; viewModel.detectFrame() }, enabled = ready) {
                        ScanGlyph(Glyph.SCAN, Modifier.size(17.dp))
                        Text("Detectar bordas", Modifier.padding(start = 5.dp))
                    }
                    TextButton(onClick = { aspect = CropAspect.FREE; viewModel.resetCrop() }, enabled = ready) {
                        Text("Imagem inteira")
                    }
                }
            }
        }
    }

    val controls: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Filtros", style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = adjustments, enabled = ready) {
                    ScanGlyph(Glyph.TUNE, Modifier.size(17.dp))
                    Text("Ajustes", Modifier.padding(start = 6.dp))
                }
            }
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val ordered = listOf(Filter.MAGIC_COLOR, Filter.LIGHTEN, Filter.SHARP_BW, Filter.GRAYSCALE, Filter.ORIGINAL)
                ordered.forEach { filter ->
                    FilterChip(page.look.filter == filter, onClick = {
                        if (state.original) viewModel.toggleOriginal()
                        viewModel.setFilter(filter)
                    }, label = { Text(filter.label()) }, enabled = ready)
                }
            }
            PageStrip(state, page, index, viewModel, requestDelete)
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (wide) {
            Row(Modifier.widthIn(max = 1180.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                Box(Modifier.weight(1.4f)) { preview() }
                Box(Modifier.weight(1f)) { controls() }
            }
        } else {
            Column(Modifier.fillMaxWidth().widthIn(max = 620.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                preview()
                controls()
            }
        }
    }
}

@Composable
private fun PageStrip(
    state: ScanUiState,
    page: ScanPage,
    index: Int,
    viewModel: ScanViewModel,
    requestDelete: () -> Unit,
) {
    val pages = state.draft.pages
    val ready = state.operation == null
    val scroll = rememberLazyListState()
    LaunchedEffect(index) { if (index >= 0 && scroll.layoutInfo.visibleItemsInfo.none { it.index == index }) scroll.scrollToItem(index) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text("Páginas", style = MaterialTheme.typography.titleMedium)
        Row {
            IconButton(onClick = { viewModel.move(-1) }, enabled = ready && index > 0) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Mover página para trás", Modifier.size(19.dp))
            }
            IconButton(onClick = { viewModel.move(1) }, enabled = ready && index < pages.lastIndex) {
                Icon(Icons.AutoMirrored.Filled.ArrowForward, "Mover página para frente", Modifier.size(19.dp))
            }
            IconButton(onClick = requestDelete, enabled = ready) {
                Icon(Icons.Default.Delete, "Remover página", Modifier.size(19.dp))
            }
        }
    }
    LazyRow(state = scroll, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        itemsIndexed(pages, key = { _, item -> item.id }) { position, item ->
            val thumbnail by produceState<Bitmap?>(null, item.id) { value = viewModel.thumbnail(item.id) }
            Thumbnail(thumbnail, item, position, page.id == item.id, ready) { viewModel.select(item.id) }
        }
    }
}

@Composable
private fun Thumbnail(
    bitmap: Bitmap?,
    page: ScanPage,
    position: Int,
    selected: Boolean,
    enabled: Boolean,
    select: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(11.dp)
    Box(
        Modifier.width(63.dp).height(77.dp).clip(shape)
            .background(colors.surfaceContainerHigh)
            .border(if (selected) 2.dp else 1.dp, if (selected) colors.primary else colors.outlineVariant, shape)
            .selectable(selected, enabled, Role.Button, select),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap.asImageBitmap(), "Página ${position + 1}",
                Modifier.fillMaxSize().padding(6.dp).graphicsLayer {
                    rotationZ = page.quarterTurns * 90f
                    if (page.quarterTurns % 2 == 1) {
                        val scale = minOf(size.width, size.height) / maxOf(size.width, size.height)
                        scaleX = scale
                        scaleY = scale
                    }
                },
                contentScale = ContentScale.Fit,
            )
        } else {
            ScanGlyph(Glyph.PDF, description = "Página ${position + 1}", tint = colors.primary)
        }
        Surface(
            modifier = Modifier.align(Alignment.BottomStart).padding(4.dp),
            shape = RoundedCornerShape(4.dp),
            color = if (selected) colors.primary else colors.surfaceContainer,
            contentColor = if (selected) colors.onPrimary else colors.onSurface,
        ) {
            Text("${position + 1}", Modifier.padding(horizontal = 5.dp, vertical = 1.dp), style = specStyle)
        }
    }
}
