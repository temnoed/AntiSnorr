package dev.antisnorr.detection

/**
 * Turns a stream of per-window "is it snoring?" probabilities into episodes.
 *
 * YAMNet scores single 0.975 s windows, so a raw threshold produces a lot of
 * one-window noise. This state machine requires several consecutive hits,
 * keeps the episode alive through short pauses (hysteresis) and limits how
 * often we buzz the wrist while the person keeps snoring.
 */
class SnoreAggregator(confirmHits: Int = 2) {

    data class Decision(
        val trigger: Boolean,
        val justStarted: Boolean,
        val episodeEnded: Boolean,
    )

    private val confirmHits = confirmHits.coerceAtLeast(1)

    private var active = false
    private var consecutiveHits = 0
    private var lastHitAt = 0L
    private var lastTriggerAt = NO_TRIGGER

    val isActive: Boolean
        get() = active

    fun onFrame(
        nowMs: Long,
        probability: Float,
        gated: Boolean,
        threshold: Float,
        releaseMs: Long,
        repeatMs: Long,
    ): Decision {
        val hit = gated && probability >= threshold

        if (!active) {
            if (hit) {
                consecutiveHits++
                lastHitAt = nowMs
                if (consecutiveHits >= confirmHits) {
                    active = true
                    lastHitAt = nowMs
                    if (repeatMs <= 0 || nowMs - lastTriggerAt >= repeatMs) {
                        lastTriggerAt = nowMs
                        return Decision(trigger = true, justStarted = true, episodeEnded = false)
                    }
                    return Decision(trigger = false, justStarted = true, episodeEnded = false)
                }
            } else {
                consecutiveHits = 0
            }
            return Decision(trigger = false, justStarted = false, episodeEnded = false)
        }

        if (hit) {
            lastHitAt = nowMs
            if (repeatMs > 0 && nowMs - lastTriggerAt >= repeatMs) {
                lastTriggerAt = nowMs
                return Decision(trigger = true, justStarted = false, episodeEnded = false)
            }
            return Decision(trigger = false, justStarted = false, episodeEnded = false)
        }

        if (nowMs - lastHitAt >= releaseMs) {
            active = false
            consecutiveHits = 0
            return Decision(trigger = false, justStarted = false, episodeEnded = true)
        }

        return Decision(trigger = false, justStarted = false, episodeEnded = false)
    }

    fun reset() {
        active = false
        consecutiveHits = 0
        lastHitAt = 0L
        lastTriggerAt = NO_TRIGGER
    }

    private companion object {
        /** Sentinel for "never triggered yet"; chosen so any realistic clock
         *  value minus the sentinel stays positive and never overflows. */
        const val NO_TRIGGER = Long.MIN_VALUE / 2
    }
}
