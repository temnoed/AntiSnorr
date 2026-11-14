package dev.antisnorr.ml

import android.content.Context
import android.util.Log
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.Closeable
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.channels.FileChannel

/**
 * Thin wrapper around Google's YAMNet (AudioSet) TFLite classifier.
 *
 * The model expects a raw 16 kHz mono float32 waveform (0.975 s, 15600
 * samples) and returns 521 class probabilities. The spectrogram is computed
 * inside the graph, so no TFLite Task Library audio frontend is required.
 */
class YamNetClassifier(context: Context) : Closeable {

    data class Prediction(
        val snoreProbability: Float,
        val topIndex: Int,
        val topProbability: Float,
    )

    private val labels: List<String>
    private val interpreter: Interpreter
    private val inputBuffer: ByteBuffer
    private val inputFloats: FloatBuffer

    private val outputIndex: Int
    private val floatOutput: Array<FloatArray>?
    private val byteOutput: Array<ByteArray>?
    private val outputScale: Float
    private val outputZeroPoint: Int

    private val probabilities: FloatArray

    val windowSize: Int
    val snoreIndex: Int
    val snoreLabel: String
    val snoreRelatedIndices: List<Int>

    init {
        labels = ClassMap.load(context)
        snoreIndex = labels.indexOfFirst { it.equals(SNORE_LABEL, ignoreCase = true) }
            .takeIf { it >= 0 }
            ?: FALLBACK_SNORE_INDEX.also {
                Log.w(TAG, "$SNORE_LABEL not found in the class map, falling back to index $it")
            }
        snoreLabel = labels.getOrElse(snoreIndex) { SNORE_LABEL }

        snoreRelatedIndices = listOf("Snoring", "Snort", "Wheeze", "Breathing")
            .mapNotNull { name -> labels.indexOfFirst { it.equals(name, ignoreCase = true) }.takeIf { it >= 0 } }
            .distinct()
            .ifEmpty { listOf(snoreIndex) }

        interpreter = Interpreter(
            loadModel(context),
            Interpreter.Options().apply { setNumThreads(2) },
        )

        val inputTensor = interpreter.getInputTensor(0)
        check(inputTensor.dataType() == DataType.FLOAT32) {
            "Unexpected YAMNet input type: ${inputTensor.dataType()}"
        }
        windowSize = inputTensor.shape().fold(1) { acc, value -> acc * value }

        inputBuffer = ByteBuffer
            .allocateDirect(windowSize * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
        inputFloats = inputBuffer.asFloatBuffer()

        outputIndex = (0 until interpreter.outputTensorCount).firstOrNull { index ->
            interpreter.getOutputTensor(index).numElements() == labels.size
        } ?: 0

        val outputTensor = interpreter.getOutputTensor(outputIndex)
        probabilities = FloatArray(outputTensor.numElements())

        when (outputTensor.dataType()) {
            DataType.FLOAT32 -> {
                floatOutput = Array(1) { FloatArray(outputTensor.numElements()) }
                byteOutput = null
                outputScale = 0f
                outputZeroPoint = 0
            }

            DataType.UINT8, DataType.INT8 -> {
                val quantization = outputTensor.quantizationParams()
                outputScale = quantization.scale
                outputZeroPoint = quantization.zeroPoint
                floatOutput = null
                byteOutput = Array(1) { ByteArray(outputTensor.numElements()) }
            }

            else -> error("Unsupported YAMNet output type: ${outputTensor.dataType()}")
        }

        Log.i(
            TAG,
            "YAMNet ready: input=$windowSize samples, classes=${labels.size}, " +
                "snoreIndex=$snoreIndex, output=${outputTensor.dataType()}",
        )
    }

    /** [window] must contain exactly [windowSize] samples in the -1..1 range. */
    fun classify(window: FloatArray): Prediction {
        require(window.size == windowSize) {
            "Expected $windowSize samples, got ${window.size}"
        }

        inputFloats.rewind()
        inputFloats.put(window)
        inputBuffer.rewind()

        if (floatOutput != null) {
            interpreter.run(inputBuffer, floatOutput)
            val source = floatOutput[0]
            System.arraycopy(source, 0, probabilities, 0, minOf(source.size, probabilities.size))
        } else {
            interpreter.run(inputBuffer, byteOutput)
            val source = byteOutput!![0]
            for (i in probabilities.indices) {
                probabilities[i] =
                    (((source[i].toInt() and 0xFF) - outputZeroPoint) * outputScale)
            }
        }

        var topIndex = -1
        var topProbability = 0f
        for (i in probabilities.indices) {
            val value = probabilities[i]
            if (value > topProbability) {
                topProbability = value
                topIndex = i
            }
        }

        return Prediction(
            snoreProbability = snoreRelatedIndices.maxOfOrNull { probabilities.getOrElse(it) { 0f } }?.coerceIn(0f, 1f)
                ?: 0f,
            topIndex = topIndex,
            topProbability = topProbability,
        )
    }

    fun labelAt(index: Int): String? = labels.getOrNull(index)

    override fun close() {
        interpreter.close()
    }

    private fun loadModel(context: Context): ByteBuffer {
        return try {
            val descriptor = context.assets.openFd(ASSET)
            try {
                val stream = FileInputStream(descriptor.fileDescriptor)
                stream.channel.map(
                    FileChannel.MapMode.READ_ONLY,
                    descriptor.startOffset,
                    descriptor.declaredLength,
                )
            } finally {
                descriptor.close()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Memory mapping failed (${t.message}), reading into direct buffer")
            val bytes = context.assets.open(ASSET).use { it.readBytes() }
            ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).apply {
                put(bytes)
                rewind()
            }
        }
    }

    companion object {
        private const val TAG = "YamNetClassifier"
        private const val ASSET = "yamnet.tflite"
        private const val SNORE_LABEL = "Snoring"

        /** Index of "Snoring" in yamnet_class_map.csv — kept only as a safety net. */
        private const val FALLBACK_SNORE_INDEX = 38
    }
}
