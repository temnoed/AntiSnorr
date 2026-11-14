package dev.antisnorr.core

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class AlertMode {
    /** Post a local notification; Gadgetbridge forwards it to the band. */
    NOTIFICATION,

    /** Broadcast Gadgetbridge's DEBUG_SEND_NOTIFICATION intent. */
    GB_INTENT_NOTIFICATION,

    /** Simulate an incoming call for a few seconds (strongest vibration). */
    GB_INTENT_CALL,
}

data class DetectionConfig(
    val threshold: Float = 0.35f,
    val minRms: Float = 0.001f,
    val confirmHits: Int = 1,
    val releaseMs: Long = 4_000L,
    val repeatMs: Long = 30_000L,
    val alertMode: AlertMode = AlertMode.GB_INTENT_CALL,
    val callDurationMs: Long = 6_000L,
    val pattern: VibrationPattern = VibrationPattern(),
    val gain: Float = 3.0f,
)

/**
 * Tiny SharedPreferences-backed settings store.
 *
 * A singleton is fine here: the app has exactly one user profile and both the
 * UI and the foreground service must observe the same values.
 */
object Settings {
    private const val PREFS_NAME = "antisnorr_settings"

    private lateinit var prefs: SharedPreferences

    private val _config = MutableStateFlow(DetectionConfig())
    val config: StateFlow<DetectionConfig> = _config.asStateFlow()

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        _config.value = read()
    }

    fun update(transform: (DetectionConfig) -> DetectionConfig) {
        _config.update { current ->
            val next = transform(current)
            write(next)
            next
        }
    }

    private fun read(): DetectionConfig = DetectionConfig(
        threshold = prefs.getFloat(KEY_THRESHOLD, 0.35f),
        minRms = prefs.getFloat(KEY_MIN_RMS, 0.001f),
        confirmHits = prefs.getInt(KEY_CONFIRM_HITS, 2),
        releaseMs = prefs.getLong(KEY_RELEASE_MS, 4_000L),
        repeatMs = prefs.getLong(KEY_REPEAT_MS, 30_000L),
        alertMode = runCatching {
            AlertMode.valueOf(
                prefs.getString(KEY_ALERT_MODE, null) ?: AlertMode.GB_INTENT_CALL.name,
            )
        }.getOrDefault(AlertMode.GB_INTENT_CALL),
        callDurationMs = prefs.getLong(KEY_CALL_DURATION_MS, 6_000L),
        pattern = VibrationPattern(),
        gain = prefs.getFloat(KEY_GAIN, 3.0f),
    )

    private fun write(config: DetectionConfig) {
        prefs.edit()
            .putFloat(KEY_THRESHOLD, config.threshold)
            .putFloat(KEY_MIN_RMS, config.minRms)
            .putInt(KEY_CONFIRM_HITS, config.confirmHits)
            .putLong(KEY_RELEASE_MS, config.releaseMs)
            .putLong(KEY_REPEAT_MS, config.repeatMs)
            .putString(KEY_ALERT_MODE, config.alertMode.name)
            .putLong(KEY_CALL_DURATION_MS, config.callDurationMs)
            .putFloat(KEY_GAIN, config.gain)
            .apply()
    }

    private const val KEY_THRESHOLD = "threshold"
    private const val KEY_MIN_RMS = "min_rms"
    private const val KEY_CONFIRM_HITS = "confirm_hits"
    private const val KEY_RELEASE_MS = "release_ms"
    private const val KEY_REPEAT_MS = "repeat_ms"
    private const val KEY_ALERT_MODE = "alert_mode"
    private const val KEY_CALL_DURATION_MS = "call_duration_ms"
    private const val KEY_GAIN = "gain"
}
