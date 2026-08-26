package com.example.ultrastarandroidtv.audio

import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deciding how loud a preview should be.
 *
 * The measurement that prompted it, taken from five real iTunes previews on 2026-08-24: −9.3 LUFS
 * for "Yellow" against −17.9 for "Billie Jean". Nearly nine decibels between one song and the next
 * one somebody scrolls onto, which is the difference between comfortable and inaudible.
 */
class PreviewLevelTest {

    @Test
    fun `measures the level of a tone`() {
        // A full-scale sine has an RMS of 1/root-2, whatever its frequency.
        val loud = pcm(amplitude = 1.0)

        assertEquals(0.707, rmsOf(loud, loud.size).toDouble(), 0.01)
    }

    @Test
    fun `silence measures nothing`() {
        assertEquals(0f, rmsOf(ByteArray(2048), 2048), 0f)
    }

    @Test
    fun `only reads as far as it is told to`() {
        val half = ByteArray(4096)
        pcm(amplitude = 0.5).copyInto(half, 0, 0, 2048)

        // Over the signal alone: a half-scale sine, so 0.5 / root-2.
        assertEquals(0.354, rmsOf(half, 2048).toDouble(), 0.02)
        // Over signal and the silence after it: the same energy spread across twice the samples,
        // so down by another root-2. Counting the tail is exactly what a byte count prevents.
        assertEquals(0.25, rmsOf(half, 4096).toDouble(), 0.02)
    }

    // -------------------------------------------------------------------------------------
    // The gain
    // -------------------------------------------------------------------------------------

    /** The whole job: two clips mastered nine decibels apart end up at the same level. */
    @Test
    fun `brings two very different clips to the same loudness`() {
        val target = 0.12f
        val loud = 0.31f // about -10 LUFS, "Yellow"
        val quiet = 0.13f // about -18 LUFS, "Billie Jean"

        val levelledLoud = loud * levellingGain(loud, target)
        val levelledQuiet = quiet * levellingGain(quiet, target)

        assertEquals(levelledLoud.toDouble(), levelledQuiet.toDouble(), 0.015)
    }

    /**
     * The rule that makes clipping impossible.
     *
     * Only the opening of a clip is measured, so a chorus later on is louder than anything the
     * gain was chosen against. Any amplification at all would eventually square off those peaks,
     * and a preview that distorts is worse than one that is quiet.
     */
    @Test
    fun `never amplifies, however quiet the clip is`() {
        assertEquals(1f, levellingGain(0.001f, 0.12f), 0f)
        assertEquals(1f, levellingGain(0.12f, 0.5f), 0f)
    }

    @Test
    fun `turns a loud clip down in proportion`() {
        assertEquals(0.5f, levellingGain(0.24f, 0.12f), 0.001f)
        assertEquals(0.25f, levellingGain(0.48f, 0.12f), 0.001f)
    }

    /** Silence must not be multiplied by infinity. */
    @Test
    fun `silence is left alone`() {
        assertEquals(1f, levellingGain(0f, 0.12f), 0f)
        assertEquals(1f, levellingGain(-1f, 0.12f), 0f)
    }

    /** 16-bit little-endian PCM of a 440 Hz tone, which is what the audio path carries. */
    private fun pcm(amplitude: Double, samples: Int = 1024): ByteArray {
        val bytes = ByteArray(samples * 2)
        for (n in 0 until samples) {
            val value = (sin(2.0 * PI * 440.0 * n / 48_000.0) * amplitude * 32767).toInt()
            bytes[n * 2] = (value and 0xFF).toByte()
            bytes[n * 2 + 1] = ((value shr 8) and 0xFF).toByte()
        }
        return bytes
    }
}
