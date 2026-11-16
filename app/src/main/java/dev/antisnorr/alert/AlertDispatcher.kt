package dev.antisnorr.alert

import android.content.Context
import dev.antisnorr.R
import dev.antisnorr.core.AlertMode
import dev.antisnorr.core.Settings
import dev.antisnorr.core.VibrationPattern
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

enum class AlertReason { SNORE, TEST }

/**
 * Runs the configured vibration pattern (see [VibrationPattern]).
 *
 * The actual buzz is produced by the companion app — AntiSnorr only schedules
 * the start/stop commands (fake call via Gadgetbridge) or repeated local
 * notifications that Gadgetbridge or Zepp forward to the band.
 * NOTIFICATION is the default: it works with both Gadgetbridge and Zepp.
 */
class AlertDispatcher(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    @Volatile
    private var callActive = false

    fun alert(reason: AlertReason, peakProbability: Float = 0f) {
        val config = Settings.config.value
        val title = when (reason) {
            AlertReason.SNORE -> context.getString(R.string.alert_title)
            AlertReason.TEST -> context.getString(R.string.alert_test_title)
        }
        val text = when (reason) {
            AlertReason.SNORE -> context.getString(
                R.string.alert_text,
                (peakProbability.coerceIn(0f, 1f) * 100).roundToInt(),
            )

            AlertReason.TEST -> context.getString(R.string.alert_test_text)
        }

        val mode = if (GadgetbridgeBridge.isInstalled(context)) {
            config.alertMode
        } else {
            AlertMode.NOTIFICATION
        }

        val prev = job
        job = scope.launch {
            prev?.cancelAndJoin()
            runPattern(mode, config.pattern, title, text)
        }
    }

    fun shutdown() {
        job?.cancel()
        job = null
        if (callActive) {
            GadgetbridgeBridge.endFakeCall(context)
            callActive = false
        }
    }

    private suspend fun runPattern(mode: AlertMode, pattern: VibrationPattern, title: String, text: String) {
        // One local notification as a trace in GB modes; the rhythm itself is
        // driven by broadcasts / fake calls.
        if (mode == AlertMode.GB_INTENT_NOTIFICATION || mode == AlertMode.GB_INTENT_CALL) {
            AlertNotifier.postAlert(context, title, text)
        }

        try {
            for (series in 0 until pattern.seriesCount) {
                for (pulse in 0 until pattern.pulsesPerSeries) {
                    startPulse(mode, title, pulseText(text, pulse))
                    delay(pattern.pulseMs)
                    endPulse(mode)
                    if (pulse < pattern.pulsesPerSeries - 1) {
                        delay(pattern.gapMs(pulse))
                    }
                }
                if (series < pattern.seriesCount - 1) {
                    delay(pattern.interSeriesGapMs)
                }
            }
        } finally {
            endPulse(mode)
        }
    }

    /**
     * Companion apps may collapse visually identical notifications, so each
     * pulse carries a distinguishable text ("… ·2", "… ·3").
     */
    private fun pulseText(base: String, pulse: Int): String =
        if (pulse == 0) base else "$base ·${pulse + 1}"

    private fun startPulse(mode: AlertMode, title: String, text: String) {
        when (mode) {
            AlertMode.NOTIFICATION -> AlertNotifier.postAlert(context, title, text)
            AlertMode.GB_INTENT_NOTIFICATION -> GadgetbridgeBridge.sendNotification(context, title, text)
            AlertMode.GB_INTENT_CALL -> {
                GadgetbridgeBridge.startFakeCall(context, title)
                callActive = true
            }
        }
    }

    private fun endPulse(mode: AlertMode) {
        if (mode == AlertMode.GB_INTENT_CALL && callActive) {
            GadgetbridgeBridge.endFakeCall(context)
            callActive = false
        }
    }
}
