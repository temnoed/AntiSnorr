package dev.antisnorr.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlin.math.max

/**
 * Microphone source that always delivers 16 kHz mono float samples.
 *
 * 16 kHz is requested first (no conversion at all). If the device refuses it,
 * 48 kHz or 44.1 kHz are captured and resampled.
 */
class AudioCapture {

    private var record: AudioRecord? = null
    private var resampler: Resampler? = null
    private var inputChunk = CHUNK_SAMPLES

    private var shortBuffer = ShortArray(0)
    private var floatBuffer = FloatArray(0)

    /** Software gain applied to samples. 1.0 = no change. */
    @Volatile
    var gain: Float = 1f

    var description: String = ""
        private set

    fun start(): Boolean {
        for (rate in SAMPLE_RATES) {
            for (source in SOURCES) {
                if (tryStart(rate, source)) return true
            }
        }
        return false
    }

    @SuppressLint("MissingPermission")
    private fun tryStart(rate: Int, source: Int): Boolean {
        val minBufferBytes = AudioRecord.getMinBufferSize(
            rate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBufferBytes <= 0) return false

        val chunk = CHUNK_SAMPLES * rate / TARGET_RATE
        val recordBufferBytes = max(minBufferBytes * 2, chunk * 2 * 4)

        val candidate = try {
            AudioRecord(
                source,
                rate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                recordBufferBytes,
            )
        } catch (t: Throwable) {
            Log.w(TAG, "AudioRecord($source, $rate) failed: ${t.message}")
            return false
        }

        if (candidate.state != AudioRecord.STATE_INITIALIZED) {
            candidate.release()
            return false
        }

        try {
            candidate.startRecording()
        } catch (t: Throwable) {
            Log.w(TAG, "startRecording($source, $rate) failed: ${t.message}")
            candidate.release()
            return false
        }

        if (candidate.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            candidate.release()
            return false
        }

        record = candidate
        inputChunk = chunk
        shortBuffer = ShortArray(chunk)
        floatBuffer = FloatArray(chunk)
        resampler = if (rate == TARGET_RATE) null else Resampler(rate, TARGET_RATE)
        description = "${sourceName(source)}, $rate Hz → ${TARGET_RATE} Hz"
        return true
    }

    /** Blocking read of roughly 100 ms of 16 kHz audio. Returns null on failure. */
    fun read(): FloatArray? {
        val active = record ?: return null
        val samplesRead = try {
            active.read(shortBuffer, 0, inputChunk, AudioRecord.READ_BLOCKING)
        } catch (t: Throwable) {
            Log.w(TAG, "read failed: ${t.message}")
            return null
        }
        if (samplesRead <= 0) return null

        for (i in 0 until samplesRead) {
            floatBuffer[i] = (shortBuffer[i] / 32768f) * gain
        }

        val resampler = resampler
        return if (resampler == null) {
            floatBuffer.copyOf(samplesRead)
        } else {
            resampler.process(floatBuffer, samplesRead)
        }
    }

    fun stop() {
        val active = record ?: return
        record = null
        try {
            if (active.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                active.stop()
            }
        } catch (_: Throwable) {
            // Already stopped or the device disappeared.
        }
        active.release()
    }

    private fun sourceName(source: Int): String = when (source) {
        MediaRecorder.AudioSource.UNPROCESSED -> "UNPROCESSED"
        MediaRecorder.AudioSource.VOICE_RECOGNITION -> "VOICE_RECOGNITION"
        MediaRecorder.AudioSource.MIC -> "MIC"
        else -> source.toString()
    }

    companion object {
        private const val TAG = "AudioCapture"
        const val TARGET_RATE = 16_000
        private const val CHUNK_SAMPLES = 1_600 // 100 ms at 16 kHz

        private val SAMPLE_RATES = intArrayOf(16_000, 48_000, 44_100)

        // Prefer sources without AGC / noise suppression: they change the signal
        // that the snore classifier was trained on.
        private val SOURCES = intArrayOf(
            MediaRecorder.AudioSource.UNPROCESSED,
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.MIC,
        )
    }
}
