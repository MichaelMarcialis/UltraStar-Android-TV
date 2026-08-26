package com.example.ultrastarandroidtv.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val RATE = 48_000

/**
 * The processor itself, rather than the two arithmetic rules underneath it.
 *
 * [PreviewLevelTest] proves the loudness measurement and the gain. Neither says anything about the
 * part that can actually lose somebody's audio: this holds 300 ms back, decides, and then replays
 * it. A regression that swallowed a short preview, played the held audio twice, carried a gain over
 * from the previous song, or scaled the two channels of a stereo pair differently would leave both
 * of those tests perfectly green.
 */
class PreviewLevelProcessorTest {

    // -------------------------------------------------------------------------------------
    // Holding, then replaying
    // -------------------------------------------------------------------------------------

    /** Nothing may be lost: every sample put in comes out, once. */
    @Test
    fun `every sample comes out exactly once`() {
        val pcm = tone(seconds = 1.0, amplitude = 0.2)

        val out = through(level(), pcm)

        assertEquals(pcm.size, out.size)
    }

    /** The audio held back for analysis is the audio that plays first, not audio that is dropped. */
    @Test
    fun `the held opening is replayed rather than discarded`() {
        // A quiet clip is left alone by the gain, so the bytes should survive unchanged.
        val pcm = tone(seconds = 1.0, amplitude = 0.05)

        val out = through(level(fade = 0.0), pcm)

        assertArrayEquals(pcm, out)
    }

    /** Nothing comes out at all until the window is full — that is what makes the gain one value. */
    @Test
    fun `nothing is emitted before the window has been heard`() {
        val processor = level(analysis = 0.3)
        configure(processor)

        val short = tone(seconds = 0.1, amplitude = 0.5)
        processor.queueInput(ByteBuffer.wrap(short).order(ByteOrder.LITTLE_ENDIAN))

        assertEquals(0, processor.output.remaining())
    }

    /** However the buffers happen to be cut up, the result is the same. */
    @Test
    fun `the slicing of the input does not change the output`() {
        val pcm = tone(seconds = 0.8, amplitude = 0.4)

        val inOneGo = through(level(), pcm, sliceBytes = pcm.size)
        val inSmallPieces = through(level(), pcm, sliceBytes = 512)
        val awkwardly = through(level(), pcm, sliceBytes = 1_001)

        assertArrayEquals(inOneGo, inSmallPieces)
        assertArrayEquals(inOneGo, awkwardly)
    }

    // -------------------------------------------------------------------------------------
    // A clip shorter than the window
    // -------------------------------------------------------------------------------------

    /**
     * The tail of a preview, or a clip that stops almost at once. Without the end-of-stream path
     * the last fraction of a second would be swallowed rather than played.
     */
    @Test
    fun `a clip shorter than the analysis window is still heard`() {
        val pcm = tone(seconds = 0.1, amplitude = 0.05)

        val out = through(level(analysis = 0.3, fade = 0.0), pcm)

        assertEquals(pcm.size, out.size)
        assertArrayEquals(pcm, out)
    }

    // -------------------------------------------------------------------------------------
    // One gain, and a new one for the next song
    // -------------------------------------------------------------------------------------

    @Test
    fun `a loud clip is brought down and a quiet one is left alone`() {
        val loud = through(level(fade = 0.0), tone(seconds = 1.0, amplitude = 0.9))
        val quiet = through(level(fade = 0.0), tone(seconds = 1.0, amplitude = 0.05))

        assertEquals(0.12, rmsOf(loud, loud.size).toDouble(), 0.01)
        assertEquals(
            "already below the target, so nothing to do",
            0.035,
            rmsOf(quiet, quiet.size).toDouble(),
            0.01,
        )
    }

    /** One gain for the whole clip — a level you can hear moving is the fault this exists to avoid. */
    @Test
    fun `the gain does not drift across the clip`() {
        val out = through(level(fade = 0.0), tone(seconds = 2.0, amplitude = 0.9))

        val third = out.size / 3 / 2 * 2
        val first = rmsOf(out.copyOfRange(0, third), third)
        val last = rmsOf(out.copyOfRange(out.size - third, out.size), third)

        assertEquals(first.toDouble(), last.toDouble(), 0.005)
    }

    /**
     * A flush is a new song, or a seek. Carrying the last one's gain over would play a quiet
     * record at a loud record's setting.
     */
    @Test
    fun `a flush forgets the gain it had settled on`() {
        val processor = level(fade = 0.0)
        configure(processor)

        val afterLoud = drain(processor, tone(seconds = 1.0, amplitude = 0.9))
        assertEquals(0.12, rmsOf(afterLoud, afterLoud.size).toDouble(), 0.01)

        processor.flush()
        val afterQuiet = drain(processor, tone(seconds = 1.0, amplitude = 0.05))

        assertEquals(
            "the quiet clip must not inherit the loud one's attenuation",
            0.035,
            rmsOf(afterQuiet, afterQuiet.size).toDouble(),
            0.01,
        )
    }

    // -------------------------------------------------------------------------------------
    // Stereo
    // -------------------------------------------------------------------------------------

    /**
     * The ramp advances once per *frame*, so a stereo pair is never scaled by two different
     * amounts — which would move the image about for the length of the fade.
     */
    @Test
    fun `both channels of a stereo frame are scaled together`() {
        // The same sample in both channels: whatever the gain and the ramp do, they must stay equal.
        val frames = RATE / 2
        val pcm = ByteArray(frames * 4)
        val buffer = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN)
        for (n in 0 until frames) {
            val value = (sin(2.0 * PI * 220.0 * n / RATE) * 0.8 * 32767).toInt().toShort()
            buffer.putShort(value)
            buffer.putShort(value)
        }

        val out = through(level(), pcm, channels = 2)
        val read = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)

        assertEquals(pcm.size, out.size)
        var worst = 0
        while (read.remaining() >= 4) {
            worst = maxOf(worst, abs(read.short - read.short))
        }
        assertEquals("the two channels drifted apart", 0, worst)
    }

    // -------------------------------------------------------------------------------------
    // Formats it cannot level
    // -------------------------------------------------------------------------------------

    /**
     * Bows out rather than throwing, which is the difference between a preview that is merely
     * unlevelled and one that does not play at all.
     */
    @Test
    fun `an unexpected encoding leaves the processor inactive`() {
        val processor = level()

        processor.configure(
            AudioProcessor.AudioFormat(RATE, 2, C.ENCODING_PCM_FLOAT),
        )

        assertTrue("a preview that will not play is worse than one that is loud", !processor.isActive)
    }

    // -------------------------------------------------------------------------------------

    private fun level(analysis: Double = 0.3, fade: Double = 0.12) =
        PreviewLevel(analysisSeconds = analysis, fadeSeconds = fade)

    private fun configure(processor: PreviewLevel, channels: Int = 1) {
        processor.configure(AudioProcessor.AudioFormat(RATE, channels, C.ENCODING_PCM_16BIT))
        processor.flush()
    }

    /** Configures, pushes and drains in one go. */
    private fun through(
        processor: PreviewLevel,
        pcm: ByteArray,
        sliceBytes: Int = 4_096,
        channels: Int = 1,
    ): ByteArray {
        configure(processor, channels)
        return drain(processor, pcm, sliceBytes)
    }

    /** Pushes [pcm] through an already-configured processor and collects everything it gives back. */
    private fun drain(
        processor: PreviewLevel,
        pcm: ByteArray,
        sliceBytes: Int = 4_096,
    ): ByteArray {
        val collected = ByteArrayOutputStream()

        fun take() {
            val out = processor.output
            while (out.hasRemaining()) collected.write(out.get().toInt())
        }

        var at = 0
        while (at < pcm.size) {
            val size = minOf(sliceBytes, pcm.size - at)
            processor.queueInput(
                ByteBuffer.wrap(pcm, at, size).slice().order(ByteOrder.LITTLE_ENDIAN),
            )
            take()
            at += size
        }

        processor.queueEndOfStream()
        take()
        return collected.toByteArray()
    }

    /** A 440 Hz tone as little-endian 16-bit PCM, which is what the audio path carries. */
    private fun tone(seconds: Double, amplitude: Double): ByteArray {
        val samples = (RATE * seconds).toInt()
        val pcm = ByteArray(samples * 2)
        val buffer = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN)
        for (n in 0 until samples) {
            buffer.putShort((sin(2.0 * PI * 440.0 * n / RATE) * amplitude * 32767).toInt().toShort())
        }
        return pcm
    }
}
