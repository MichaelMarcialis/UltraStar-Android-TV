package com.example.ultrastarandroidtv.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The calibration song: sixteen tones, one second apart, first at one second. */
private val ONSETS = List(16) { 1.0 + it }

/** What `PitchTracker` runs at: a reading every 1024 samples at 48 kHz. */
private const val HOP = 1024.0 / 48_000.0

class LatencyProbeTest {

    @Test
    fun `reports nothing before it has heard anything`() {
        val probe = LatencyProbe(ONSETS)

        assertNull(probe.medianSeconds)
        assertNull(probe.spreadSeconds)
        assertEquals(0, probe.count)
    }

    @Test
    fun `measures a constant delay`() {
        val probe = LatencyProbe(ONSETS)

        probe.play(latency = 0.150)

        assertEquals(16, probe.count)
        // A reading only lands every 21 ms, so a detection can be up to one hop late.
        assertEquals(0.150, probe.medianSeconds!!, HOP)
        assertTrue("spread ${probe.spreadSeconds}", probe.spreadSeconds!! <= HOP)
    }

    @Test
    fun `measures a system with no delay at all`() {
        val probe = LatencyProbe(ONSETS)

        probe.play(latency = 0.0)

        assertEquals(0.0, probe.medianSeconds!!, HOP)
    }

    @Test
    fun `a tone that flickers is still only one measurement`() {
        // Real notes drop below the confidence gate for a window now and then. Counting the
        // recovery as a fresh onset would report a latency hundreds of milliseconds too long.
        val probe = LatencyProbe(ONSETS)

        probe.play(latency = 0.100) { positionInTone -> positionInTone !in 0.2..0.25 }

        assertEquals(16, probe.count)
        assertEquals(0.100, probe.medianSeconds!!, HOP)
    }

    @Test
    fun `ignores a detection too far from any tone`() {
        val probe = LatencyProbe(ONSETS, toleranceSeconds = 0.4)

        // Someone coughs halfway between two tones.
        probe.onReading(playerPositionSeconds = 1.5, voiced = false)
        probe.onReading(playerPositionSeconds = 1.52, voiced = true)

        assertEquals(0, probe.count)
    }

    @Test
    fun `one bad tone does not move the answer`() {
        val probe = LatencyProbe(ONSETS)
        probe.play(latency = 0.120)
        val clean = probe.medianSeconds!!

        // The tenth tone is missed until very late — a median shrugs this off, a mean would not.
        probe.reset()
        probe.play(latency = 0.120) { true }
        probe.onReading(9.0, voiced = false)
        probe.onReading(9.35, voiced = true)

        assertEquals(clean, probe.medianSeconds!!, 0.005)
    }

    @Test
    fun `reset clears everything`() {
        val probe = LatencyProbe(ONSETS)
        probe.play(latency = 0.150)

        probe.reset()

        assertEquals(0, probe.count)
        assertNull(probe.medianSeconds)
    }
}

/**
 * Plays the calibration song past the probe at the real reading rate, with each tone heard
 * [latency] seconds after the player says it started.
 *
 * [voicedDuringTone] decides whether a given moment inside a tone is detected, so a test can
 * make the detector stutter the way it does on a real signal.
 */
private fun LatencyProbe.play(
    latency: Double,
    voicedDuringTone: (positionInTone: Double) -> Boolean = { true },
) {
    val toneLength = 0.5
    var time = 0.0
    while (time <= 18.0) {
        val heardTone = ONSETS.firstOrNull { time >= it + latency && time < it + latency + toneLength }
        val voiced = heardTone != null && voicedDuringTone(time - heardTone - latency)
        onReading(time, voiced)
        time += HOP
    }
}
