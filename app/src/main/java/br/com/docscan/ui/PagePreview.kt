package br.com.docscan.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import br.com.docscan.domain.Crop
import br.com.docscan.domain.MIN_CROP
import kotlin.math.roundToInt

@Composable
internal fun PagePreview(
    bitmap: Bitmap?,
    crop: Crop,
    cropping: Boolean,
    ratio: Float?,
    busy: Boolean,
    onCrop: (Crop) -> Unit,
    modifier: Modifier = Modifier,
) {
    val image = remember(bitmap) { bitmap?.asImageBitmap() }
    val currentCrop = rememberUpdatedState(crop)
    val change = rememberUpdatedState(onCrop)
    val accent = Color(0xFF81F5C5)

    Box(modifier, contentAlignment = Alignment.Center) {
        if (image != null) {
            Canvas(
                Modifier.fillMaxSize().padding(13.dp)
                    .semantics { contentDescription = if (cropping) "Prévia com alças de recorte" else "Prévia da página" }
                    .pointerInput(image.width, image.height, cropping, busy, ratio) {
                        if (!cropping || busy) return@pointerInput
                        val frame = fit(size.width.toFloat(), size.height.toFloat(), image.width, image.height)
                        val touchRadius = 28.dp.toPx()
                        var handle: Handle? = null
                        var start = currentCrop.value
                        var total = Offset.Zero
                        detectDragGestures(
                            onDragStart = { position ->
                                start = currentCrop.value
                                total = Offset.Zero
                                val rect = start.rectIn(frame)
                                handle = handles.entries.minByOrNull { (_, point) -> (point(rect) - position).getDistance() }
                                    ?.takeIf { (_, point) -> (point(rect) - position).getDistance() <= touchRadius }?.key
                                if (handle == null && rect.contains(position)) handle = Handle.MOVE
                            },
                        ) { event, amount ->
                            val active = handle ?: return@detectDragGestures
                            event.consume()
                            total += amount
                            val normalizedRatio = ratio?.let { it * image.height / image.width }
                            change.value(active.drag(start, total.x / frame.width, total.y / frame.height, normalizedRatio))
                        }
                    },
            ) {
                val source = if (cropping) Crop.Full else crop
                val left = (image.width * source.left).roundToInt().coerceIn(0, image.width - 1)
                val top = (image.height * source.top).roundToInt().coerceIn(0, image.height - 1)
                val right = (image.width * source.right).roundToInt().coerceIn(left + 1, image.width)
                val bottom = (image.height * source.bottom).roundToInt().coerceIn(top + 1, image.height)
                val frame = fit(size.width, size.height, right - left, bottom - top)
                drawImage(
                    image,
                    srcOffset = IntOffset(left, top),
                    srcSize = IntSize(right - left, bottom - top),
                    dstOffset = IntOffset(frame.left.roundToInt(), frame.top.roundToInt()),
                    dstSize = IntSize(frame.width.roundToInt().coerceAtLeast(1), frame.height.roundToInt().coerceAtLeast(1)),
                    filterQuality = FilterQuality.Medium,
                )
                if (!cropping) return@Canvas
                val rect = crop.rectIn(frame)
                val shaded = Color(0xA515211D)
                fun shade(x: Float, y: Float, width: Float, height: Float) =
                    drawRect(shaded, Offset(x, y), Size(width.coerceAtLeast(0f), height.coerceAtLeast(0f)))
                shade(frame.left, frame.top, frame.width, rect.top - frame.top)
                shade(frame.left, rect.bottom, frame.width, frame.bottom - rect.bottom)
                shade(frame.left, rect.top, rect.left - frame.left, rect.height)
                shade(rect.right, rect.top, frame.right - rect.right, rect.height)
                drawRect(accent, rect.topLeft, rect.size, style = Stroke(2.dp.toPx()))
                for (part in 1..2) {
                    val x = rect.left + rect.width * part / 3
                    val y = rect.top + rect.height * part / 3
                    drawLine(accent.copy(alpha = 0.65f), Offset(x, rect.top), Offset(x, rect.bottom))
                    drawLine(accent.copy(alpha = 0.65f), Offset(rect.left, y), Offset(rect.right, y))
                }
                for (point in handles.values) {
                    val center = point(rect)
                    drawCircle(Color(0xFF173D31), 7.dp.toPx(), center)
                    drawCircle(accent, 5.dp.toPx(), center)
                }
            }
        }
        if (busy) {
            CircularProgressIndicator(
                modifier = if (image == null) Modifier else Modifier.align(Alignment.TopEnd).padding(10.dp),
                color = if (image == null) MaterialTheme.colorScheme.primary else accent,
            )
        }
    }
}

private fun fit(width: Float, height: Float, sourceWidth: Int, sourceHeight: Int): Rect {
    val scale = minOf(width / sourceWidth, height / sourceHeight)
    val w = scale * sourceWidth
    val h = scale * sourceHeight
    return Rect((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2)
}

private fun Crop.rectIn(frame: Rect) = Rect(
    frame.left + frame.width * left,
    frame.top + frame.height * top,
    frame.left + frame.width * right,
    frame.top + frame.height * bottom,
)

private enum class Handle(val x: Int, val y: Int) {
    NW(-1, -1), N(0, -1), NE(1, -1), W(-1, 0), E(1, 0), SW(-1, 1), S(0, 1), SE(1, 1), MOVE(0, 0);

    fun drag(crop: Crop, dx: Float, dy: Float, ratio: Float?): Crop {
        val (left, top, right, bottom) = crop
        if (this == MOVE) {
            val mx = dx.coerceIn(-left, 1 - right)
            val my = dy.coerceIn(-top, 1 - bottom)
            return Crop(
                (left + mx).coerceIn(0f, 1f), (top + my).coerceIn(0f, 1f),
                (right + mx).coerceIn(0f, 1f), (bottom + my).coerceIn(0f, 1f),
            )
        }
        val minWidth = minOf(MIN_CROP, crop.width)
        val minHeight = minOf(MIN_CROP, crop.height)
        if (ratio == null) return Crop(
            if (x < 0) (left + dx).coerceIn(0f, right - minWidth) else left,
            if (y < 0) (top + dy).coerceIn(0f, bottom - minHeight) else top,
            if (x > 0) (right + dx).coerceIn(left + minWidth, 1f) else right,
            if (y > 0) (bottom + dy).coerceIn(top + minHeight, 1f) else bottom,
        )

        val centerX = (left + right) / 2
        val centerY = (top + bottom) / 2
        val allowedWidth = when (x) {
            -1 -> right
            1 -> 1 - left
            else -> 2 * minOf(centerX, 1 - centerX)
        }
        val allowedHeight = when (y) {
            -1 -> bottom
            1 -> 1 - top
            else -> 2 * minOf(centerY, 1 - centerY)
        }
        val maxWidth = minOf(allowedWidth, allowedHeight * ratio)
        val fromX = crop.width + dx * x
        val fromY = (crop.height + dy * y) * ratio
        val target = when {
            x == 0 -> fromY
            y == 0 -> fromX
            kotlin.math.abs(dx) >= kotlin.math.abs(dy * ratio) -> fromX
            else -> fromY
        }
        val nextWidth = target.coerceIn(minOf(minWidth, maxWidth), maxWidth)
        val nextHeight = nextWidth / ratio
        val nextX = when (x) { -1 -> right - nextWidth; 1 -> left; else -> centerX - nextWidth / 2 }
        val nextY = when (y) { -1 -> bottom - nextHeight; 1 -> top; else -> centerY - nextHeight / 2 }
        return Crop(nextX.coerceAtLeast(0f), nextY.coerceAtLeast(0f),
            (nextX + nextWidth).coerceAtMost(1f), (nextY + nextHeight).coerceAtMost(1f))
    }
}

private val handles = mapOf<Handle, (Rect) -> Offset>(
    Handle.NW to { it.topLeft }, Handle.N to { Offset(it.center.x, it.top) }, Handle.NE to { it.topRight },
    Handle.W to { Offset(it.left, it.center.y) }, Handle.E to { Offset(it.right, it.center.y) },
    Handle.SW to { it.bottomLeft }, Handle.S to { Offset(it.center.x, it.bottom) }, Handle.SE to { it.bottomRight },
)
