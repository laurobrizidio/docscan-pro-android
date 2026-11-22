package br.com.docscan.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable
internal fun ScanProgress(title: String, details: String? = null, onCancel: (() -> Unit)? = null) {
    Dialog(
        onDismissRequest = { onCancel?.invoke() },
        properties = DialogProperties(dismissOnBackPress = onCancel != null, dismissOnClickOutside = false),
    ) {
        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer) {
            Column(
                Modifier.padding(18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    color = Color(0xFF132E26),
                ) {
                    DocumentIllustration(animated = true)
                }
                Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                if (details != null) {
                    Text(details, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                }
                if (onCancel != null) TextButton(onClick = onCancel) { Text("Cancelar") }
            }
        }
    }
}

@Composable
internal fun DocumentIllustration(animated: Boolean = false) {
    val scanLine = if (animated) {
        val transition = rememberInfiniteTransition(label = "Varredura")
        transition.animateFloat(
            initialValue = 0.18f,
            targetValue = 0.82f,
            animationSpec = infiniteRepeatable(tween(1250, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "Altura da linha",
        )
    } else null
    Canvas(Modifier.fillMaxWidth().aspectRatio(1.48f)) {
        val width = size.width
        val height = size.height
        val mint = Color(0xFF9BF1CB)
        val paper = Color(0xFFECF6ED)
        val grid = Color(0xFF28483C)
        for (x in 1..17) for (y in 1..11) {
            drawCircle(grid, 1.dp.toPx(), Offset(width * x / 18, height * y / 12))
        }
        val w = width * .29f
        val h = height * .63f
        val center = Offset(width * .50f, height * .50f)
        val start = Offset(center.x - w / 2, center.y - h / 2)
        val rounding = CornerRadius(10.dp.toPx())
        rotate(13f, center) {
            drawRoundRect(Color(0xFF32614F), start + Offset(w * .15f, h * .015f), Size(w, h), rounding)
            drawRoundRect(Color(0xFF639382), start + Offset(w * .15f, h * .015f), Size(w, h), rounding, style = Stroke(1.dp.toPx()))
        }
        rotate(-9f, center) {
            drawRoundRect(paper, start, Size(w, h), rounding)
            drawRoundRect(Color(0xFFB7DFCC), start + Offset(w * .16f, h * .15f), Size(w * .29f, h * .055f), CornerRadius(2.dp.toPx()))
            val lines = listOf(.65f, .55f, .68f, .43f, .60f, .30f)
            lines.forEachIndexed { index, length ->
                val from = start + Offset(w * .16f, h * (.29f + .093f * index))
                drawLine(Color(0xFF6A8B7D), from, from + Offset(w * length, 0f), 1.7.dp.toPx(), StrokeCap.Round)
            }
        }
        val left = width * .263f
        val right = width * .745f
        val top = height * .13f
        val bottom = height * .87f
        for ((x, dx) in listOf(left to 1, right to -1)) {
            for ((y, dy) in listOf(top to 1, bottom to -1)) {
                val corner = Offset(x, y)
                drawLine(mint, corner, corner + Offset(16.dp.toPx() * dx, 0f), 2.4.dp.toPx(), StrokeCap.Round)
                drawLine(mint, corner, corner + Offset(0f, 16.dp.toPx() * dy), 2.4.dp.toPx(), StrokeCap.Round)
            }
        }
        val lineY = height * (scanLine?.value ?: .57f)
        val lineLeft = left + 5.dp.toPx()
        val lineRight = right - 5.dp.toPx()
        if (animated) {
            val glowHeight = 24.dp.toPx()
            drawRect(
                Brush.verticalGradient(listOf(Color.Transparent, mint.copy(alpha = 0.36f)), lineY - glowHeight, lineY),
                topLeft = Offset(lineLeft, lineY - glowHeight),
                size = Size(lineRight - lineLeft, glowHeight),
            )
        }
        drawLine(mint.copy(alpha = .88f), Offset(lineLeft, lineY), Offset(lineRight, lineY), 1.5.dp.toPx(), StrokeCap.Round)
    }
}
