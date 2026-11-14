package dev.antisnorr.detection

import android.content.Context
import android.os.SystemClock
import android.util.Log
import dev.antisnorr.R
import dev.antisnorr.audio.AudioCapture
import dev.antisnorr.audio.FloatRingBuffer
import dev.antisnorr.core.NightLogger
import dev.antisnorr.core.Settings
import dev.antisnorr.ml.YamNetClassifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Capture → YAMNet → aggregation loop.
 *
 * Runs on [Dispatchers.IO] because AudioRecord.read() is blocking. One
 * inference every 300 ms is plenty: YAMNet needs a 0.975 s window, and snoring
 * episodes last several seconds.
 */
class DetectionEngine(
    private val context: Context,
    private val onFrame: (Frame) -> Unit,
    private val onAlert: (peakProbability: Float) -> Unit,
    private val onEpisodeEnd: (startedAtMs: Long, durationMs: Long, peakProbability: Float) -> Unit,
    private val onError: (Int) -> Unit,
) {
    data class Frame(
        val snoreProbability: Float,
        val topLabel: String,
        val topProbability: Float,
        val rmsDb: Float,
        val snoreActive: Boolean,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var capture: AudioCapture? = null

    fun start() {
        if (job != null) return
        job = scope.launch { detectLoop() }
    }

    fun stop() {
        job?.cancel()
        job = null
        // Unblocks AudioRecord.read() so the loop can finish and close the model.
        capture?.stop()
    }

    private suspend fun detectLoop() {
        val classifier = try {
            YamNetClassifier(context)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to load YAMNet", t)
            onError(R.string.error_model)
            return
        }

        val capture = AudioCapture()
        this.capture = capture

        if (!capture.start()) {
            classifier.close()
            onError(R.string.error_audio_init)
            return
        }

        Log.i(TAG, "Capturing from ${capture.description}")

        try {
            val window = FloatArray(classifier.windowSize)
            val ring = FloatRingBuffer(classifier.windowSize + HOP_SAMPLES + 4_000)
            val aggregator = SnoreAggregator(Settings.config.value.confirmHits)

            var samplesSinceHop = 0
            var episodeStartElapsed = 0L
            var episodeStartWall = 0L
            var peak = 0f

            while (currentCoroutineContext().isActive) {
                val samples = capture.read()
                if (samples == null) {
                    delay(100)
                    continue
                }
                if (samples.isEmpty()) continue

                ring.write(samples)
                samplesSinceHop += samples.size
                if (samplesSinceHop < HOP_SAMPLES || ring.size < window.size) continue
                samplesSinceHop = 0

                ring.copyLast(window)

                val config = Settings.config.value
                capture.gain = config.gain
                val rms = rms(window)
                val rmsDb = 20f * log10(max(rms, 1e-6f))
                val gated = rms >= config.minRms

                val prediction = if (gated) classifier.classify(window) else null
                val probability = prediction?.snoreProbability ?: 0f

                val now = SystemClock.elapsedRealtime()
                val snoreRelated = prediction == null || classifier.snoreRelatedIndices.contains(prediction.topIndex) || probability >= 0.6f
                val decision = aggregator.onFrame(
                    nowMs = now,
                    probability = if (snoreRelated) probability else 0f,
                    gated = gated,
                    threshold = config.threshold,
                    releaseMs = config.releaseMs,
                    repeatMs = config.repeatMs,
                )

                if (decision.justStarted) {
                    episodeStartElapsed = now
                    episodeStartWall = System.currentTimeMillis()
                    peak = probability
                } else if (aggregator.isActive) {
                    peak = max(peak, probability)
                }

                if (decision.trigger) {
                    onAlert(peak)
                    NightLogger.log("TRIGGER peak=%.2f".format(peak))
                }

                if (decision.episodeEnded) {
                    onEpisodeEnd(
                        episodeStartWall,
                        now - episodeStartElapsed,
                        peak,
                    )
                    NightLogger.log("EPISODE_END dur=%dms peak=%.2f".format(now - episodeStartElapsed, peak))
                    peak = 0f
                }

                NightLogger.log(
                    "p=%.2f top=%s rms=%.1f gated=%b active=%b".format(
                        probability,
                        prediction?.topIndex?.let { classifier.labelAt(it) } ?: "-",
                        rmsDb,
                        gated,
                        aggregator.isActive,
                    ),
                )

                onFrame(
                    Frame(
                        snoreProbability = probability,
                        topLabel = prediction?.topIndex?.let { classifier.labelAt(it) }.orEmpty(),
                        topProbability = prediction?.topProbability ?: 0f,
                        rmsDb = rmsDb,
                        snoreActive = aggregator.isActive,
                    ),
                )

                Log.d(
                    TAG,
                    "p=%.2f top=%s(%.2f) rms=%.1f dB gated=%b active=%b".format(
                        probability,
                        prediction?.topIndex?.let { classifier.labelAt(it) } ?: "-",
                        prediction?.topProbability ?: 0f,
                        rmsDb,
                        gated,
                        aggregator.isActive,
                    ),
                )
            }
        } catch (t: Throwable) {
            if (t !is kotlinx.coroutines.CancellationException) {
                Log.e(TAG, "Detection loop failed", t)
                onError(R.string.error_audio_init)
            }
        } finally {
            capture.stop()
            classifier.close()
            this.capture = null
        }
    }

    private fun rms(samples: FloatArray): Float {
        var sum = 0.0
        for (sample in samples) {
            sum += sample.toDouble() * sample
        }
        return sqrt(sum / samples.size).toFloat()
    }

    companion object {
        private const val TAG = "DetectionEngine"

        /** 300 ms at 16 kHz. Window is 975 ms, so windows overlap. */
        private const val HOP_SAMPLES = 4_800
    }
}
