package com.example.ultrastarandroidtv.pitch

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val SAMPLE_RATE = 48_000

/** Samples the USB layer hands over in one callback: 8 isochronous packets of 1 ms. */
private const val USB_CHUNK_SAMPLES = 384

class PitchTrackerTest {

    @Test
    fun `detects a steady tone and reports it as the right note`() {
        val readings = feed(sine(220.0, seconds = 1.0))

        assertTrue("expected several readings, got ${readings.size}", readings.size > 10)
        assertTrue("every window should be voiced", readings.all { it.voiced })
        readings.forEach {
            assertEquals(220.0, it.frequencyHz.toDouble(), 1.0)
            assertEquals(57, it.midi.roundToInt())  // A3
        }
    }

    @Test
    fun `tracks tones across the sung range without octave errors`() {
        for (hz in listOf(98.0, 196.0, 440.0, 880.0)) {
            val readings = feed(sine(hz, seconds = 0.5))
            assertTrue("no readings for $hz Hz", readings.isNotEmpty())
            readings.forEach {
                assertEquals("$hz Hz misdetected", hz, it.frequencyHz.toDouble(), hz * 0.01)
            }
        }
    }

    @Test
    fun `reports silence as unvoiced rather than guessing`() {
        val readings = feed(ShortArray(SAMPLE_RATE))

        assertTrue(readings.isNotEmpty())
        assertTrue(readings.none { it.voiced })
        assertTrue(readings.all { it.level < 0.001f })
    }

    @Test
    fun `gates out fundamentals outside the sung range`() {
        val tracker = PitchTracker(sampleRate = SAMPLE_RATE, minHz = 200f, maxHz = 400f)
        val readings = feed(sine(100.0, seconds = 0.5), tracker)

        assertTrue(readings.isNotEmpty())
        assertTrue("100 Hz should be rejected by a 200-400 Hz gate", readings.none { it.voiced })
    }

    @Test
    fun `produces one reading per hop regardless of how audio is chunked`() {
        // Taken from the tracker's own defaults rather than written out again: the rule under
        // test is "one reading per hop", and baking the hop in meant halving it broke a test
        // that was never about the number.
        val samples = sine(440.0, seconds = 1.0)
        val expected = (samples.size - DEFAULT_WINDOW_SIZE) / DEFAULT_HOP_SIZE + 1

        val inUsbChunks = feed(samples, chunkSamples = USB_CHUNK_SAMPLES)
        val inOneGo = feed(samples, chunkSamples = samples.size)
        val inTinyChunks = feed(samples, chunkSamples = 7)

        assertEquals(expected, inUsbChunks.size)
        assertEquals(expected, inOneGo.size)
        assertEquals(expected, inTinyChunks.size)
    }

    @Test
    fun `leaves the caller's buffer position untouched`() {
        val tracker = PitchTracker(sampleRate = SAMPLE_RATE)
        val buffer = pcmBuffer(sine(440.0, seconds = 0.1))
        buffer.position(0)

        tracker.process(buffer, buffer.capacity()) { }

        assertEquals(0, buffer.position())
    }

    @Test
    fun `reset discards a half-filled window`() {
        val tracker = PitchTracker(sampleRate = SAMPLE_RATE)
        val readings = mutableListOf<PitchReading>()
        val tone = sine(440.0, seconds = 1.0)

        // Half a window in, throw it away; the count should be exactly what a clean run gives.
        val half = DEFAULT_WINDOW_SIZE / 2
        tracker.process(pcmBuffer(tone.copyOfRange(0, half)), half * 2) { readings += it }
        tracker.reset()
        tracker.process(pcmBuffer(tone), tone.size * 2) { readings += it }

        assertEquals((tone.size - DEFAULT_WINDOW_SIZE) / DEFAULT_HOP_SIZE + 1, readings.size)
    }
}

private fun sine(hz: Double, seconds: Double): ShortArray {
    val count = (SAMPLE_RATE * seconds).toInt()
    return ShortArray(count) { i ->
        (sin(2.0 * PI * hz * i / SAMPLE_RATE) * 0.5 * Short.MAX_VALUE).toInt().toShort()
    }
}

private fun pcmBuffer(samples: ShortArray): ByteBuffer {
    val buffer = ByteBuffer.allocateDirect(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
    samples.forEach { buffer.putShort(it) }
    buffer.position(0)
    return buffer
}

/** Pushes [samples] through a tracker the way the capture thread would, in fixed-size chunks. */
private fun feed(
    samples: ShortArray,
    tracker: PitchTracker = PitchTracker(sampleRate = SAMPLE_RATE),
    chunkSamples: Int = USB_CHUNK_SAMPLES,
): List<PitchReading> {
    val readings = mutableListOf<PitchReading>()
    var offset = 0
    while (offset < samples.size) {
        val take = minOf(chunkSamples, samples.size - offset)
        val chunk = pcmBuffer(samples.copyOfRange(offset, offset + take))
        tracker.process(chunk, take * 2) { readings += it }
        offset += take
    }
    return readings
}
