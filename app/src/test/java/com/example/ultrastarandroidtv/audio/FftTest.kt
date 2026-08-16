package com.example.ultrastarandroidtv.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class FftTest {

    private val size = 1024
    private val sampleRate = 48_000
    private val fft = Fft(size)
    private val magnitudes = FloatArray(size / 2)

    private fun tone(hz: Double, amplitude: Float = 1f) = FloatArray(size) {
        (amplitude * sin(2.0 * PI * hz * it / sampleRate)).toFloat()
    }

    private fun peakBin(): Int = magnitudes.indices.maxByOrNull { magnitudes[it] } ?: -1

    /** Which bin a frequency belongs in, for this size and rate. */
    private fun binFor(hz: Double) = (hz * size / sampleRate).toInt()

    @Test
    fun `a pure tone peaks in its own bin`() {
        fft.magnitudes(tone(1000.0), magnitudes)

        assertEquals(binFor(1000.0), peakBin())
    }

    @Test
    fun `it finds the right bin across the audible range`() {
        for (hz in listOf(200.0, 500.0, 2000.0, 5000.0, 10_000.0)) {
            fft.magnitudes(tone(hz), magnitudes)
            // Windowing spreads a tone over its neighbours, so landing next door is correct.
            assertTrue("$hz Hz landed in bin ${peakBin()}, expected ${binFor(hz)}",
                kotlin.math.abs(peakBin() - binFor(hz)) <= 1)
        }
    }

    @Test
    fun `two tones show up as two peaks`() {
        val mixed = FloatArray(size)
        val low = tone(500.0, 0.5f)
        val high = tone(4000.0, 0.5f)
        for (i in 0 until size) mixed[i] = low[i] + high[i]

        fft.magnitudes(mixed, magnitudes)

        // Each should stand well clear of the noise between them.
        val lowPeak = magnitudes[binFor(500.0)]
        val highPeak = magnitudes[binFor(4000.0)]
        val between = magnitudes[binFor(2000.0)]
        assertTrue(lowPeak > between * 10)
        assertTrue(highPeak > between * 10)
    }

    @Test
    fun `silence produces no output`() {
        fft.magnitudes(FloatArray(size), magnitudes)

        assertTrue(magnitudes.all { it < 1e-5f })
    }

    @Test
    fun `a louder tone reads louder`() {
        fft.magnitudes(tone(1000.0, 0.25f), magnitudes)
        val quiet = magnitudes[binFor(1000.0)]

        fft.magnitudes(tone(1000.0, 1.0f), magnitudes)
        val loud = magnitudes[binFor(1000.0)]

        assertEquals(4.0, (loud / quiet).toDouble(), 0.1)
    }

    @Test
    fun `it can be run repeatedly without its scratch buffers going stale`() {
        // It reuses its arrays between calls on purpose — allocating per audio buffer is how
        // you get audible glitches — so leftovers from the previous call must not survive.
        fft.magnitudes(tone(1000.0), magnitudes)
        fft.magnitudes(FloatArray(size), magnitudes)

        assertTrue(magnitudes.all { it < 1e-5f })
    }

    @Test
    fun `a size that is not a power of two is refused`() {
        val error = runCatching { Fft(1000) }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
    }
}
