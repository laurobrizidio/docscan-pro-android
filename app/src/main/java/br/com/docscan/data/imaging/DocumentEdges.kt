package br.com.docscan.data.imaging

import android.graphics.Bitmap
import br.com.docscan.domain.DocumentQuad
import br.com.docscan.domain.PagePoint
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Paper segmentation and border validation adapted from scanner_pdf. */
internal class DocumentEdges(private val width: Int, private val height: Int) {
    private val size = width * height
    private val queue = IntArray(size)

    private data class Point(val x: Int, val y: Int)
    private data class Candidate(val corners: List<Point>, val score: Float)

    fun detect(bitmap: Bitmap): DocumentQuad? {
        if (width < 16 || height < 16) return null
        val pixels = IntArray(size)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val luminance = FloatArray(size)
        val paperScore = FloatArray(size)
        for (i in pixels.indices) {
            val pixel = pixels[i]
            val r = (pixel ushr 16) and 255
            val g = (pixel ushr 8) and 255
            val b = pixel and 255
            val highest = maxOf(r, g, b)
            val saturation = if (highest == 0) 0f else (highest - minOf(r, g, b)).toFloat() / highest
            luminance[i] = luma(r.toFloat(), g.toFloat(), b.toFloat())
            paperScore[i] = luminance[i] - 140 * saturation
        }
        val smooth = blur(blur(luminance))
        val paper = blur(paperScore)
        val threshold = otsu(paper)
        val candidates = mutableListOf(paperCandidate(paper, smooth, threshold))
        if (paper.count { it > threshold } > size * 0.2) {
            candidates += paperCandidate(paper, smooth, otsu(paper, threshold))
        }

        val gradient = FloatArray(size)
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val i = y * width + x
                val gx = smooth[i - width + 1] + 2 * smooth[i + 1] + smooth[i + width + 1] -
                    smooth[i - width - 1] - 2 * smooth[i - 1] - smooth[i + width - 1]
                val gy = smooth[i + width - 1] + 2 * smooth[i + width] + smooth[i + width + 1] -
                    smooth[i - width - 1] - 2 * smooth[i - width] - smooth[i - width + 1]
                gradient[i] = sqrt(gx * gx + gy * gy)
            }
        }
        val edgeThreshold = otsu(gradient).coerceIn(20, 45)
        val edges = morph(BooleanArray(size) { gradient[it] > edgeThreshold }, 1, true)
        val outside = floodOutside(edges)
        candidates += validate(morph(BooleanArray(size) { !outside[it] }, 1, false), smooth)

        val corners = candidates.filterNotNull().maxByOrNull { it.score }?.corners ?: return null
        val cx = corners.map(Point::x).average().toFloat()
        val cy = corners.map(Point::y).average().toFloat()
        val (tl, tr, br, bl) = corners.map {
            PagePoint((cx + (it.x - cx) * 0.992f) / (width - 1), (cy + (it.y - cy) * 0.992f) / (height - 1))
        }
        return DocumentQuad(tl, tr, br, bl)
    }

    private fun paperCandidate(paper: FloatArray, smooth: FloatArray, threshold: Int): Candidate? {
        val mask = BooleanArray(size) { paper[it] > threshold }
        return validate(morph(morph(mask, 2, false), 2, true), smooth)
    }

    private fun validate(mask: BooleanArray, luminance: FloatArray): Candidate? {
        val component = largestComponent(mask) ?: return null
        val outside = floodOutside(component)
        val corners = arrayOfNulls<Point>(4)
        var minSum = Int.MAX_VALUE
        var maxSum = Int.MIN_VALUE
        var minDifference = Int.MAX_VALUE
        var maxDifference = Int.MIN_VALUE
        var count = 0
        for (i in 0 until size) {
            if (outside[i]) continue
            count++
            val x = i % width
            val y = i / width
            val sum = x + y
            val difference = x - y
            if (sum < minSum) { minSum = sum; corners[0] = Point(x, y) }
            if (difference > maxDifference) { maxDifference = difference; corners[1] = Point(x, y) }
            if (sum > maxSum) { maxSum = sum; corners[2] = Point(x, y) }
            if (difference < minDifference) { minDifference = difference; corners[3] = Point(x, y) }
        }
        val fraction = count.toFloat() / size
        if (fraction !in 0.10f..0.985f) return null
        val points = corners.requireNoNulls().toList()
        val sides = points.indices.map { distance(points[it], points[(it + 1) % 4]) }
        if (sides.min() < 0.15 * minOf(width, height)) return null
        if (minOf(sides[0], sides[2]) < 0.6 * maxOf(sides[0], sides[2])) return null
        if (minOf(sides[1], sides[3]) < 0.6 * maxOf(sides[1], sides[3])) return null

        var twiceArea = 0
        var confirmed = 0
        var totalContrast = 0f
        for (i in points.indices) {
            val p = points[(i + 3) % 4]
            val c = points[i]
            val n = points[(i + 1) % 4]
            val dot = (p.x - c.x) * (n.x - c.x) + (p.y - c.y) * (n.y - c.y)
            if (abs(dot / (sides[(i + 3) % 4] * sides[i])) > 0.574) return null
            twiceArea += c.x * n.y - n.x * c.y
            val contrast = contrast(c, n, luminance) ?: continue
            if (contrast < 10f) return null
            confirmed++
            totalContrast += contrast
        }
        if (confirmed < 2 || (confirmed == 2 && fraction < 0.6)) return null
        val rectangularity = count / (abs(twiceArea) / 2f)
        if (rectangularity !in 0.85f..1.10f) return null
        return Candidate(points, 1 - abs(1 - rectangularity) + 0.002f * minOf(60f, totalContrast / confirmed))
    }

    private fun distance(a: Point, b: Point) = hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble())

    private fun contrast(a: Point, b: Point, luminance: FloatArray): Float? {
        if ((a.x <= 3 && b.x <= 3) || (a.y <= 3 && b.y <= 3) ||
            (a.x >= width - 4 && b.x >= width - 4) || (a.y >= height - 4 && b.y >= height - 4)
        ) return null
        val length = distance(a, b)
        val nx = -(b.y - a.y) * 4 / length
        val ny = (b.x - a.x) * 4 / length
        val differences = mutableListOf<Float>()
        for (step in 1..23) {
            val t = 0.08 + 0.84 * step / 24
            val x = a.x + (b.x - a.x) * t
            val y = a.y + (b.y - a.y) * t
            val inside = sample(luminance, (x + nx).roundToInt(), (y + ny).roundToInt())
            val outside = sample(luminance, (x - nx).roundToInt(), (y - ny).roundToInt())
            if (inside != null && outside != null) differences += abs(inside - outside)
        }
        return if (differences.size >= 6) differences.sorted()[differences.size / 2] else null
    }

    private fun sample(values: FloatArray, x: Int, y: Int): Float? =
        if (x in 0 until width && y in 0 until height) values[y * width + x] else null

    private inline fun neighbors(i: Int, visit: (Int) -> Unit) {
        val x = i % width
        if (x > 0) visit(i - 1)
        if (x < width - 1) visit(i + 1)
        if (i >= width) visit(i - width)
        if (i < size - width) visit(i + width)
    }

    private fun largestComponent(mask: BooleanArray): BooleanArray? {
        val labels = IntArray(size)
        var label = 0
        var bestLabel = 0
        var bestSize = 0
        for (seed in 0 until size) {
            if (!mask[seed] || labels[seed] != 0) continue
            label++
            var front = 0
            var back = 1
            queue[0] = seed
            labels[seed] = label
            while (front < back) {
                neighbors(queue[front++]) { next ->
                    if (mask[next] && labels[next] == 0) {
                        labels[next] = label
                        queue[back++] = next
                    }
                }
            }
            if (back > bestSize) {
                bestSize = back
                bestLabel = label
            }
        }
        return if (bestLabel == 0) null else BooleanArray(size) { labels[it] == bestLabel }
    }

    private fun floodOutside(blocked: BooleanArray): BooleanArray {
        val visited = BooleanArray(size)
        var back = 0
        fun add(i: Int) {
            if (blocked[i] || visited[i]) return
            visited[i] = true
            queue[back++] = i
        }
        for (x in 0 until width) {
            add(x)
            add(size - width + x)
        }
        for (y in 0 until height) {
            add(y * width)
            add(y * width + width - 1)
        }
        var front = 0
        while (front < back) neighbors(queue[front++], ::add)
        return visited
    }

    private fun morph(mask: BooleanArray, radius: Int, dilate: Boolean): BooleanArray {
        fun scan(input: BooleanArray, horizontal: Boolean) = BooleanArray(size) { i ->
            val position = if (horizontal) i % width else i / width
            val last = if (horizontal) width - 1 else height - 1
            val stride = if (horizontal) 1 else width
            (maxOf(-radius, -position)..minOf(radius, last - position)).any { offset ->
                input[i + offset * stride] == dilate
            } == dilate
        }
        return scan(scan(mask, true), false)
    }

    private fun blur(values: FloatArray): FloatArray {
        fun scan(input: FloatArray, horizontal: Boolean) = FloatArray(size) { i ->
            val position = if (horizontal) i % width else i / width
            val last = if (horizontal) width - 1 else height - 1
            val stride = if (horizontal) 1 else width
            val before = input[i - if (position > 0) stride else 0]
            val after = input[i + if (position < last) stride else 0]
            (before + input[i] + after) / 3f
        }
        return scan(scan(values, true), false)
    }

    private fun otsu(values: FloatArray, above: Int = -256): Int {
        val histogram = IntArray(256)
        var count = 0
        var sum = 0
        for (value in values) {
            if (value <= above) continue
            val bin = value.toInt().coerceIn(0, 255)
            histogram[bin]++
            count++
            sum += bin
        }
        var lower = 0
        var lowerSum = 0
        var bestVariance = 0.0
        var best = above.coerceAtLeast(0)
        for (bin in histogram.indices) {
            lower += histogram[bin]
            lowerSum += bin * histogram[bin]
            val upper = count - lower
            if (lower == 0 || upper == 0) continue
            val difference = lowerSum.toDouble() / lower - (sum - lowerSum).toDouble() / upper
            val variance = lower.toDouble() * upper * difference * difference
            if (variance > bestVariance) {
                bestVariance = variance
                best = bin
            }
        }
        return best
    }
}

internal fun luma(r: Float, g: Float, b: Float): Float = 0.299f * r + 0.587f * g + 0.114f * b
