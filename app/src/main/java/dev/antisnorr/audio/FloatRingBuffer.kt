package dev.antisnorr.audio

/**
 * Fixed-capacity circular buffer of mono float samples.
 *
 * Only ever touched from the detection thread, so no synchronisation is needed.
 */
class FloatRingBuffer(private val capacity: Int) {
    private val data = FloatArray(capacity)
    private var writeIndex = 0

    var size: Int = 0
        private set

    fun write(samples: FloatArray, count: Int = samples.size) {
        require(count <= samples.size)
        for (i in 0 until count) {
            data[writeIndex] = samples[i]
            writeIndex = (writeIndex + 1) % capacity
        }
        size = minOf(capacity, size + count)
    }

    /** Copies the most recent [dest].size samples into [dest]. */
    fun copyLast(dest: FloatArray) {
        require(dest.size <= size) {
            "Requested ${dest.size} samples, only $size available"
        }
        var index = (writeIndex - dest.size + capacity) % capacity
        for (i in dest.indices) {
            dest[i] = data[index]
            index = (index + 1) % capacity
        }
    }

    fun clear() {
        writeIndex = 0
        size = 0
    }
}
