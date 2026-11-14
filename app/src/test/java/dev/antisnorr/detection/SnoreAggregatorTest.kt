package dev.antisnorr.detection

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SnoreAggregatorTest {

    private val threshold = 0.4f
    private val releaseMs = 4_000L
    private val repeatMs = 30_000L

    private fun SnoreAggregator.frame(
        now: Long,
        probability: Float,
        gated: Boolean = true,
        repeat: Long = repeatMs,
    ) = onFrame(now, probability, gated, threshold, releaseMs, repeat)

    @Test
    fun `single hit does not trigger`() {
        val aggregator = SnoreAggregator(confirmHits = 2)
        val decision = aggregator.frame(0, 0.9f)
        assertFalse(decision.trigger)
        assertFalse(aggregator.isActive)
    }

    @Test
    fun `two consecutive hits trigger once`() {
        val aggregator = SnoreAggregator(confirmHits = 2)

        assertFalse(aggregator.frame(0, 0.9f).trigger)
        val decision = aggregator.frame(300, 0.9f)

        assertTrue(decision.trigger)
        assertTrue(decision.justStarted)
        assertTrue(aggregator.isActive)
    }

    @Test
    fun `miss resets the confirmation counter`() {
        val aggregator = SnoreAggregator(confirmHits = 2)

        aggregator.frame(0, 0.9f)
        aggregator.frame(300, 0.1f)
        assertFalse(aggregator.frame(600, 0.9f).trigger)
        assertTrue(aggregator.frame(900, 0.9f).trigger)
    }

    @Test
    fun `energy gate prevents detection`() {
        val aggregator = SnoreAggregator(confirmHits = 2)

        aggregator.frame(0, 0.9f, gated = false)
        aggregator.frame(300, 0.9f, gated = false)

        assertFalse(aggregator.isActive)
    }

    @Test
    fun `episode survives a short pause`() {
        val aggregator = SnoreAggregator(confirmHits = 1)
        aggregator.frame(0, 0.9f)

        assertFalse(aggregator.frame(2_000, 0.0f).episodeEnded)
        assertTrue(aggregator.isActive)
        assertFalse(aggregator.frame(3_000, 0.9f).episodeEnded)
    }

    @Test
    fun `episode ends after release time`() {
        val aggregator = SnoreAggregator(confirmHits = 1)
        aggregator.frame(0, 0.9f)

        assertFalse(aggregator.frame(3_999, 0.0f).episodeEnded)
        val end = aggregator.frame(4_000, 0.0f)

        assertTrue(end.episodeEnded)
        assertFalse(aggregator.isActive)
    }

    @Test
    fun `repeats while snoring but respects the interval`() {
        val aggregator = SnoreAggregator(confirmHits = 1)
        assertTrue(aggregator.frame(0, 0.9f).trigger)

        assertFalse(aggregator.frame(10_000, 0.9f).trigger)
        assertTrue(aggregator.frame(30_000, 0.9f).trigger)
        assertFalse(aggregator.frame(35_000, 0.9f).trigger)
    }

    @Test
    fun `zero repeat means once per episode`() {
        val aggregator = SnoreAggregator(confirmHits = 1)
        assertTrue(aggregator.frame(0, 0.9f, repeat = 0).trigger)

        assertFalse(aggregator.frame(60_000, 0.9f, repeat = 0).trigger)
        assertFalse(aggregator.frame(120_000, 0.9f, repeat = 0).trigger)
    }

    @Test
    fun `probability below threshold is not a hit`() {
        val aggregator = SnoreAggregator(confirmHits = 1)
        assertFalse(aggregator.frame(0, 0.39f).trigger)
        assertTrue(aggregator.frame(300, 0.40f).trigger)
    }
}
