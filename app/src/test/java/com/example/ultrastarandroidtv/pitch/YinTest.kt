package com.example.ultrastarandroidtv.pitch

import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private const val RATE = 48_000
private const val WINDOW = 2048

/** The gate [PitchTracker] applies, so "confident" here means "would reach scoring". */
private const val TRACKER_CONFIDENCE_GATE = 0.85f

class YinTest {

    private fun yin(minHz: Float = 65f, maxHz: Float = 1200f) = Yin(RATE, WINDOW, minHz, maxHz)

    @Test
    fun `finds the fundamental of a pure tone precisely`() {
        val detector = yin()

        assertTrue(detector.detect(sine(440.0)))
        // Tight enough to prove parabolic interpolation is working: whole-sample lag
        // quantisation alone would be off by ~2 Hz here.
        assertEquals(440.0, detector.frequencyHz.toDouble(), 0.5)
    }

    @Test
    fun `reproduces the four reference tones verified on hardware`() {
        for (hz in listOf(110.0, 220.0, 440.0, 880.0)) {
            val detector = yin()
            assertTrue("no detection at $hz Hz", detector.detect(sine(hz)))
            assertEquals("$hz Hz", hz, detector.frequencyHz.toDouble(), hz * 0.005)
        }
    }

    @Test
    fun `does not halve or double the pitch of harmonically rich tones`() {
        // A sawtooth has every harmonic, which is exactly the case where the difference
        // function dips at 2x the true period as hard as at the period itself.
        for (hz in listOf(98.0, 147.0, 220.0, 330.0, 523.0)) {
            val detector = yin()
            assertTrue("no detection at $hz Hz", detector.detect(sawtooth(hz)))

            val found = detector.frequencyHz.toDouble()
            assertEquals("sawtooth $hz Hz -> $found Hz", hz, found, hz * 0.02)
        }
    }

    @Test
    fun `does not halve or double the pitch of a square wave`() {
        for (hz in listOf(110.0, 261.0, 440.0)) {
            val detector = yin()
            assertTrue("no detection at $hz Hz", detector.detect(square(hz)))
            assertEquals("square $hz Hz", hz, detector.frequencyHz.toDouble(), hz * 0.02)
        }
    }

    @Test
    fun `hears the missing fundamental`() {
        // Harmonics 2, 3 and 4 with no energy at f0 at all. The waveform still repeats at
        // f0, and a period-based method should say so — a spectral peak-picker would answer
        // 2*f0. This is also what a small speaker does to a low note.
        val f0 = 150.0
        val detector = yin()

        assertTrue(detector.detect(harmonics(f0, listOf(2, 3, 4))))
        assertEquals(f0, detector.frequencyHz.toDouble(), f0 * 0.02)
    }

    @Test
    fun `is not confident about white noise`() {
        val random = Random(20260812)
        val noise = FloatArray(WINDOW) { (random.nextFloat() * 2f - 1f) * 0.5f }

        val detector = yin()
        val detected = detector.detect(noise)

        assertTrue(
            "noise produced a confident reading at ${detector.frequencyHz} Hz",
            !detected || detector.probability < TRACKER_CONFIDENCE_GATE,
        )
    }

    @Test
    fun `finds nothing in silence`() {
        assertFalse(yin().detect(FloatArray(WINDOW)))
    }

    @Test
    fun `ignores tones outside the searched range`() {
        val detector = yin(minHz = 200f, maxHz = 400f)

        assertFalse("100 Hz is below the range", detector.detect(sine(100.0)))
        assertTrue("300 Hz is inside the range", detector.detect(sine(300.0)))
    }

    @Test
    fun `rejects a window too short for the lowest requested pitch`() {
        // 65 Hz needs a 738-sample lag, which a 64-sample window cannot represent. Better to
        // fail loudly at construction than to silently never detect low notes.
        val error = runCatching { Yin(RATE, windowSize = 64, minHz = 65f, maxHz = 1200f) }
            .exceptionOrNull()

        assertTrue("expected a clear failure, got $error", error is IllegalArgumentException)
    }

    @Test
    fun `leaves the caller's samples untouched`() {
        val samples = sawtooth(220.0)
        val original = samples.copyOf()

        yin().detect(samples)

        assertTrue(original.contentEquals(samples))
    }
}

private fun sine(hz: Double, amplitude: Double = 0.5): FloatArray =
    FloatArray(WINDOW) { i -> (sin(2.0 * PI * hz * i / RATE) * amplitude).toFloat() }

/** Band-limited so the test signal is a real waveform rather than an aliased staircase. */
private fun sawtooth(hz: Double): FloatArray {
    val partials = ((RATE / 2) / hz).toInt().coerceAtMost(30)
    return harmonics(hz, (1..partials).toList()) { 1.0 / it }
}

private fun square(hz: Double): FloatArray {
    val partials = ((RATE / 2) / hz).toInt().coerceAtMost(30)
    return harmonics(hz, (1..partials).filter { it % 2 == 1 }) { 1.0 / it }
}

private fun harmonics(
    f0: Double,
    partials: List<Int>,
    weight: (Int) -> Double = { 1.0 },
): FloatArray {
    val samples = DoubleArray(WINDOW)
    for (n in partials) {
        for (i in 0 until WINDOW) {
            samples[i] += weight(n) * sin(2.0 * PI * f0 * n * i / RATE)
        }
    }
    val peak = samples.maxOf { kotlin.math.abs(it) }.coerceAtLeast(1e-9)
    return FloatArray(WINDOW) { i -> (samples[i] / peak * 0.7).toFloat() }
}
