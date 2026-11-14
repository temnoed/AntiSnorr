package dev.antisnorr.audio

/**
 * Streaming mono resampler.
 *
 * For integer ratios (48 kHz → 16 kHz, which is the common fallback) a box
 * filter is used: averaging groups of samples is a cheap anti-alias filter and
 * is good enough for a mel-spectrogram frontend. Other ratios fall back to
 * linear interpolation.
 */
class Resampler(
    private val inputRate: Int,
    private val outputRate: Int = TARGET_RATE,
) {
    init {
        require(inputRate > 0 && outputRate > 0)
    }

    private val integerFactor =
        if (inputRate > outputRate && inputRate % outputRate == 0) inputRate / outputRate else 0

    // Box filter state.
    private var accumulator = 0f
    private var accumulated = 0

    // Linear interpolation state.
    private val step = inputRate.toDouble() / outputRate
    private var position = 0.0
    private var tail = FloatArray(0)

    fun process(input: FloatArray, count: Int): FloatArray {
        if (count <= 0) return FloatArray(0)
        return if (integerFactor > 0) processBox(input, count) else processLinear(input, count)
    }

    private fun processBox(input: FloatArray, count: Int): FloatArray {
        val output = FloatArray(count / integerFactor + 1)
        var written = 0
        for (i in 0 until count) {
            accumulator += input[i]
            accumulated++
            if (accumulated == integerFactor) {
                output[written++] = accumulator / integerFactor
                accumulator = 0f
                accumulated = 0
            }
        }
        return if (written == output.size) output else output.copyOf(written)
    }

    private fun processLinear(input: FloatArray, count: Int): FloatArray {
        val buffer = FloatArray(tail.size + count)
        tail.copyInto(buffer)
        input.copyInto(buffer, destinationOffset = tail.size, startIndex = 0, endIndex = count)

        val output = FloatArray((buffer.size / step).toInt() + 2)
        var written = 0
        while (position + 1.0 < buffer.size) {
            val index = position.toInt()
            val fraction = (position - index).toFloat()
            output[written++] = buffer[index] * (1f - fraction) + buffer[index + 1] * fraction
            position += step
        }

        val consumed = position.toInt().coerceAtMost(buffer.size - 1)
        tail = buffer.copyOfRange(consumed, buffer.size)
        position -= consumed
        return if (written == output.size) output else output.copyOf(written)
    }

    companion object {
        const val TARGET_RATE = 16_000
    }
}
