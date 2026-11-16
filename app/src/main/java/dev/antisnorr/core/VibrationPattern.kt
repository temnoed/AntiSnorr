package dev.antisnorr.core

/**
 * Vibration rhythm: 2 short pulses of 0.5 s with a 2 s gap, then silence.
 * Repeats every [repeatMs] while snoring continues.
 */
data class VibrationPattern(
    val pulseMs: Long = 500L,
    val pulsesPerSeries: Int = 2,
    val seriesCount: Int = 1,
    val firstGapMs: Long = 2_000L,
    val lastGapMs: Long = 2_000L,
    val interSeriesGapMs: Long = 2_000L,
) {
    fun gapMs(index: Int): Long = firstGapMs

    val totalMs: Long = run {
        var t = 0L
        for (s in 0 until seriesCount) {
            t += pulsesPerSeries * pulseMs
            t += (0 until (pulsesPerSeries - 1)).sumOf { gapMs(it) }
            if (s < seriesCount - 1) t += interSeriesGapMs
        }
        t
    }

    fun summary(): String =
        "$pulsesPerSeries × ${pulseMs}ms, пауза ${firstGapMs / 1000}s"
}
