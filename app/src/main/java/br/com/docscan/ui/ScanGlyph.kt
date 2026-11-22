package br.com.docscan.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

internal enum class Glyph { SCAN, CAMERA, IMAGE, CROP, ROTATE, TUNE, COMPARE, PDF, CHAT, CLOUD, SUN, MOON }

@Composable
internal fun ScanGlyph(
    glyph: Glyph,
    modifier: Modifier = Modifier.size(22.dp),
    description: String? = null,
    tint: Color = LocalContentColor.current,
) {
    Canvas(if (description == null) modifier else modifier.semantics { contentDescription = description }) {
        val stroke = Stroke(1.65f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        scale(size.width / 24f, size.height / 24f, pivot = Offset.Zero) {
            fun line(x: Float, y: Float, tx: Float, ty: Float) =
                drawLine(tint, Offset(x, y), Offset(tx, ty), stroke.width, StrokeCap.Round)

            fun path(vararg xy: Float) {
                val p = Path().apply {
                    moveTo(xy[0], xy[1])
                    for (i in 2 until xy.size step 2) lineTo(xy[i], xy[i + 1])
                }
                drawPath(p, tint, style = stroke)
            }

            when (glyph) {
                Glyph.SCAN -> {
                    path(3f, 8f, 3f, 3f, 8f, 3f)
                    path(16f, 3f, 21f, 3f, 21f, 8f)
                    path(21f, 16f, 21f, 21f, 16f, 21f)
                    path(8f, 21f, 3f, 21f, 3f, 16f)
                    line(7f, 9f, 17f, 9f)
                    line(7f, 12f, 17f, 12f)
                    line(7f, 15f, 14f, 15f)
                }
                Glyph.CAMERA -> {
                    path(3f, 7f, 7f, 7f, 9f, 4f, 15f, 4f, 17f, 7f, 21f, 7f, 21f, 20f, 3f, 20f, 3f, 7f)
                    drawCircle(tint, 3.5f, Offset(12f, 13f), style = stroke)
                }
                Glyph.IMAGE -> {
                    drawRoundRect(tint, Offset(3f, 4f), Size(18f, 16f), CornerRadius(2f), style = stroke)
                    drawCircle(tint, 1.3f, Offset(8f, 9f))
                    path(4f, 17f, 9f, 13f, 12f, 15f, 16f, 11f, 21f, 16f)
                }
                Glyph.CROP -> {
                    path(7f, 3f, 7f, 17f, 21f, 17f)
                    path(3f, 7f, 17f, 7f, 17f, 21f)
                }
                Glyph.ROTATE -> {
                    drawArc(tint, -90f, 286f, false, Offset(4f, 4f), Size(16f, 16f), style = stroke)
                    path(6f, 3f, 4f, 10f, 10f, 9f)
                }
                Glyph.TUNE -> {
                    line(3f, 6f, 8f, 6f); line(12f, 6f, 21f, 6f)
                    line(3f, 12f, 14f, 12f); line(18f, 12f, 21f, 12f)
                    line(3f, 18f, 7f, 18f); line(11f, 18f, 21f, 18f)
                    drawCircle(tint, 2f, Offset(10f, 6f), style = stroke)
                    drawCircle(tint, 2f, Offset(16f, 12f), style = stroke)
                    drawCircle(tint, 2f, Offset(9f, 18f), style = stroke)
                }
                Glyph.COMPARE -> {
                    path(9f, 5f, 4f, 5f, 4f, 19f, 9f, 19f)
                    path(15f, 5f, 20f, 5f, 20f, 19f, 15f, 19f)
                    line(12f, 3f, 12f, 21f)
                }
                Glyph.PDF -> {
                    path(5f, 3f, 14f, 3f, 19f, 8f, 19f, 21f, 5f, 21f, 5f, 3f)
                    path(14f, 3f, 14f, 8f, 19f, 8f)
                    line(8f, 13f, 16f, 13f)
                    line(8f, 17f, 14f, 17f)
                }
                Glyph.CHAT -> {
                    path(4f, 5f, 20f, 5f, 20f, 16f, 10f, 16f, 5f, 20f, 5f, 16f, 4f, 16f, 4f, 5f)
                    line(8f, 10f, 16f, 10f)
                }
                Glyph.CLOUD -> {
                    val cloud = Path().apply {
                        moveTo(6f, 18f)
                        cubicTo(0f, 18f, 2f, 10f, 7f, 10f)
                        cubicTo(8f, 3f, 18f, 4f, 18f, 11f)
                        cubicTo(23f, 11f, 24f, 18f, 18f, 18f)
                        lineTo(6f, 18f)
                    }
                    drawPath(cloud, tint, style = stroke)
                }
                Glyph.SUN -> {
                    drawCircle(tint, 4f, Offset(12f, 12f), style = stroke)
                    val rays = listOf(Offset(1f, 0f), Offset(0.71f, 0.71f), Offset(0f, 1f), Offset(-0.71f, 0.71f))
                    for (ray in rays) {
                        val center = Offset(12f, 12f)
                        drawLine(tint, center + ray * 7f, center + ray * 9f, stroke.width, StrokeCap.Round)
                        drawLine(tint, center - ray * 7f, center - ray * 9f, stroke.width, StrokeCap.Round)
                    }
                }
                Glyph.MOON -> {
                    val crescent = Path().apply {
                        moveTo(20f, 15f)
                        cubicTo(11f, 18f, 7f, 8f, 12f, 3f)
                        cubicTo(1f, 4f, 1f, 18f, 10f, 20f)
                        cubicTo(14f, 22f, 18f, 19f, 20f, 15f)
                    }
                    drawPath(crescent, tint, style = stroke)
                }
            }
        }
    }
}
