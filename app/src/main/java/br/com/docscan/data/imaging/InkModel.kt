package br.com.docscan.data.imaging

import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Inference with the trained 10 → 8 → 1 network from scanner_pdf. */
internal object InkModel {
    private val mean = floatArrayOf(
        0.86704f, 0.86692f, 0.86727f, 0.86788f, 0.099213f, -0.00023384f, -0.00083753f, 0.0036016f, 0.11602f, 0.62632f,
    )
    private val deviation = floatArrayOf(
        0.25431f, 0.22433f, 0.20088f, 0.17734f, 0.11775f, 0.14487f, 0.17177f, 0.1524f, 0.24561f, 0.13613f,
    )
    private val weights = floatArrayOf(
        -0.01959f, -0.3008f, 0.063286f, -0.85325f, -1.0713f, -1.0369f, -0.77898f, 1.0563f, 0.62548f, -0.51572f,
        -0.030855f, -0.033524f, 0.029149f, 0.038243f, -0.50624f, 0.18597f, 0.18132f, 0.71651f, -0.58644f, 0.31449f,
        0.0018368f, 1.4305f, 0.20421f, 0.16096f, 0.11382f, 0.75674f, 0.59963f, 0.14904f, 0.11427f, -0.092906f,
        0.27128f, -0.36227f, 0.42783f, -1.0122f, -0.303f, -0.89141f, 0.97389f, 0.53974f, 1.2161f, -0.46541f,
        -0.71474f, 0.11719f, 0.25178f, -0.832f, -0.72644f, -0.11123f, -0.071897f, 0.52722f, 1.2545f, -0.74132f,
        -0.34704f, 0.83863f, -0.40908f, 0.1302f, -2.4531f, -0.042483f, 1.0039f, -0.63148f, -1.9366f, 0.01118f,
        0.12002f, -0.32516f, -1.0807f, 0.13114f, -0.49321f, 0.69993f, -0.29814f, -0.71617f, -0.11579f, 0.39736f,
        -1.0164f, 2.4798f, -0.35531f, -0.89697f, -0.72651f, 0.23347f, -0.20531f, 0.35726f, -2.0658f, 0.18077f,
    )
    private val bias = floatArrayOf(
        -0.51084f, 2.0709f, 0.76372f, 0.13253f, 0.84557f, -1.6199f, 0.48573f, -0.15311f,
    )
    private val outputWeights = floatArrayOf(
        1.3269f, -1.2067f, -0.88261f, 1.2507f, -0.5655f, -3.9067f, 0.81232f, -2.497f,
    )

    private val inverseDeviation = FloatArray(deviation.size) { 1f / deviation[it] }

    fun features(luminance: FloatArray, width: Int, height: Int, fullScale: Float): LocalInkFeatures {
        val small = fullScale.roundToInt().coerceAtLeast(1)
        val medium = (4 * fullScale).roundToInt().coerceAtLeast(2)
        val large = (12 * fullScale).roundToInt().coerceAtLeast(3)
        val squared = FloatArray(luminance.size) { luminance[it] * luminance[it] }
        val scratch = FloatArray(luminance.size)
        return LocalInkFeatures(
            boxMean(luminance, scratch, width, height, small),
            boxMean(luminance, scratch, width, height, medium),
            boxMean(luminance, scratch, width, height, large),
            boxMean(squared, scratch, width, height, medium),
        )
    }

    fun probability(
        i: Int,
        luminance: Float,
        saturation: Float,
        background: Float,
        local: LocalInkFeatures,
        input: FloatArray,
    ): Float {
        val medium = local.medium[i]
        val large = local.large[i]
        val sd = sqrt((local.square[i] - medium * medium).coerceAtLeast(0f))
        input[0] = luminance
        input[1] = local.small[i]
        input[2] = medium
        input[3] = large
        input[4] = sd
        input[5] = luminance - medium
        input[6] = luminance - large
        input[7] = ((luminance - medium) / (sd + 0.02f)).coerceIn(-4f, 4f) / 4
        input[8] = saturation
        input[9] = background
        for (feature in input.indices) input[feature] = (input[feature] - mean[feature]) * inverseDeviation[feature]
        var output = -0.18397f
        for (neuron in bias.indices) {
            var value = bias[neuron]
            val offset = neuron * input.size
            for (feature in input.indices) value += weights[offset + feature] * input[feature]
            output += outputWeights[neuron] * value.coerceAtLeast(0f)
        }
        return 1f / (1f + exp(-output))
    }

    private fun boxMean(src: FloatArray, scratch: FloatArray, width: Int, height: Int, radius: Int): FloatArray {
        val out = FloatArray(src.size)
        for (y in 0 until height) {
            val row = y * width
            var sum = 0f
            for (x in 0..minOf(radius, width - 1)) sum += src[row + x]
            for (x in 0 until width) {
                val low = maxOf(0, x - radius)
                val high = minOf(width - 1, x + radius)
                scratch[row + x] = sum / (high - low + 1)
                if (x + radius + 1 < width) sum += src[row + x + radius + 1]
                if (x >= radius) sum -= src[row + x - radius]
            }
        }
        for (x in 0 until width) {
            var sum = 0f
            for (y in 0..minOf(radius, height - 1)) sum += scratch[y * width + x]
            for (y in 0 until height) {
                val low = maxOf(0, y - radius)
                val high = minOf(height - 1, y + radius)
                out[y * width + x] = sum / (high - low + 1)
                if (y + radius + 1 < height) sum += scratch[(y + radius + 1) * width + x]
                if (y >= radius) sum -= scratch[(y - radius) * width + x]
            }
        }
        return out
    }

    data class LocalInkFeatures(
        val small: FloatArray,
        val medium: FloatArray,
        val large: FloatArray,
        val square: FloatArray,
    )
}
