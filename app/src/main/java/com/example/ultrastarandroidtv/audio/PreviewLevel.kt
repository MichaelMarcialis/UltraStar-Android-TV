package com.example.ultrastarandroidtv.audio

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * The loudness every preview is brought to, as a linear RMS of full scale.
 *
 * 0.12 sits in the middle of what this library actually contains. Measured across five iTunes
 * previews on 2026-08-24: −9.3 to −17.9 LUFS, a spread of **8.6 dB** between one song and the
 * next, which is why one preview blares and the following one is barely audible.
 */
private const val TARGET_RMS = 0.12f

/** How much audio to listen to before deciding. Long enough to be a fair sample of a mix. */
private const val ANALYSIS_SECONDS = 0.3

/** A short ramp at the very start, so a preview beginning mid-note does not open with a click. */
private const val FADE_SECONDS = 0.12

/**
 * Brings every preview to the same loudness, decided before the first sample is heard.
 *
 * ## The problem, measured
 *
 * Previews come from wherever the song came from and are mastered decades apart. Five iTunes
 * previews measured on 2026-08-24 ran from −9.3 LUFS ("Yellow") to −17.9 ("Billie Jean"): the same
 * volume setting makes one of those uncomfortable and the other inaudible, and browsing a library
 * means hearing them one after another.
 *
 * ## Why it listens first instead of riding the gain
 *
 * The obvious build is a compressor that measures continuously and moves the gain to follow. It is
 * also the wrong one **here**, because its failure mode is exactly the complaint that prompted
 * this: a preview that starts at one volume and audibly changes to another a moment later. A gain
 * that moves is a gain you can hear moving.
 *
 * So this holds the first [ANALYSIS_SECONDS] of audio back, measures it, picks **one** gain, and
 * then plays everything — the held audio included — at that gain and never touches it again. The
 * cost is that sound starts 300 ms later than it otherwise would. The benefit is that it is at the
 * right level from its first sample to its last, with nothing moving.
 *
 * ## It only ever turns down
 *
 * The gain is capped at 1, so nothing is ever amplified. That is not timidity, it is what makes
 * clipping impossible: only the first 300 ms is measured, a chorus later in the clip is louder
 * than the verse it heard, and any gain above 1 would eventually square off the peaks. Attenuating
 * to the quiet end of the range equalises just as well and cannot distort.
 *
 * ## Never put this on the song itself
 *
 * It belongs to previews and nothing else. It delays audio by 300 ms, and the whole game is built
 * on a measured 127 ms round trip between the player and the microphone — dropping a third of a
 * second into that path would put every note out of time and quietly invalidate the calibration.
 * [com.example.ultrastarandroidtv.playback.SongPlayer] takes pass-through processors only.
 */
@OptIn(UnstableApi::class)
class PreviewLevel(
    private val targetRms: Float = TARGET_RMS,
    private val analysisSeconds: Double = ANALYSIS_SECONDS,
    private val fadeSeconds: Double = FADE_SECONDS,
) : BaseAudioProcessor() {

    private var frameBytes = 2
    private var held = ByteArray(0)
    private var heldBytes = 0
    private var fadeFrames = 0

    private var gain = 1f
    private var decided = false
    private var fadedFrames = 0
    private var samplesWritten = 0L

    /** The low half of a sample whose other half has not arrived yet, or -1. */
    private var pendingByte = -1

    override fun onConfigure(
        inputAudioFormat: AudioProcessor.AudioFormat,
    ): AudioProcessor.AudioFormat {
        // Bows out rather than throwing, which is the difference between a preview that is
        // merely unlevelled and one that does not play at all: returning NOT_SET leaves this
        // inactive and the pipeline routes around it. `SpectrumTap` throws instead because a
        // visualiser with no audio is a bug worth hearing about; a quiet preview is not.
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        frameBytes = inputAudioFormat.channelCount * 2
        held = ByteArray((inputAudioFormat.sampleRate * analysisSeconds).toInt() * frameBytes)
        fadeFrames = (inputAudioFormat.sampleRate * fadeSeconds).toInt()
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val available = inputBuffer.remaining()
        if (available == 0) return

        if (!decided) {
            // Everything on offer that will fit, however it happens to be cut up.
            //
            // This used to round down to whole frames, on the theory that a split sample must not
            // be left half-read. The theory was right and the cure was worse: the bytes it declined
            // to take were then *dropped*, because nothing returns to collect them -- so an input
            // buffer whose length was not a multiple of the frame size quietly lost the remainder.
            // Splitting a sample across two calls costs nothing: the halves are stored in order and
            // read back in order, and the window itself is a whole number of frames.
            val taking = minOf(held.size - heldBytes, available)
            inputBuffer.get(held, heldBytes, taking)
            heldBytes += taking

            if (heldBytes < held.size) {
                // Still listening. Nothing comes out yet, which is the whole point.
                replaceOutputBuffer(0).flip()
                return
            }
            decide()
        }

        // Everything held back, then whatever is left of this buffer, in one go.
        // Room for one extra sample: a half kept from last time completes into a whole one.
        val output = replaceOutputBuffer(heldBytes + inputBuffer.remaining() + 2)
        output.order(ByteOrder.LITTLE_ENDIAN)
        if (heldBytes > 0) {
            writeScaled(ByteBuffer.wrap(held, 0, heldBytes).order(ByteOrder.LITTLE_ENDIAN), output)
            heldBytes = 0
        }
        writeScaled(inputBuffer, output)
        output.flip()
    }

    /**
     * A clip shorter than the analysis window still has to be heard.
     *
     * Rare but real: the tail of a preview, or a song whose audio stops almost immediately. Without
     * this the last fraction of a second would be swallowed rather than played.
     */
    override fun onQueueEndOfStream() {
        if (heldBytes == 0) return
        if (!decided) decide()

        val output = replaceOutputBuffer(heldBytes + 2)
        output.order(ByteOrder.LITTLE_ENDIAN)
        writeScaled(ByteBuffer.wrap(held, 0, heldBytes).order(ByteOrder.LITTLE_ENDIAN), output)
        heldBytes = 0
        output.flip()
    }

    override fun onFlush() {
        heldBytes = 0
        decided = false
        gain = 1f
        fadedFrames = 0
        samplesWritten = 0L
        pendingByte = -1
    }

    override fun onReset() {
        onFlush()
        held = ByteArray(0)
    }

    private fun decide() {
        gain = levellingGain(rmsOf(held, heldBytes), targetRms)
        decided = true
    }

    /**
     * Copies [from] into [into], scaled by the chosen gain and by the opening ramp.
     *
     * **A sample may be split across two buffers**, and the half has to be kept rather than
     * dropped. Real PCM arrives a whole number of frames at a time, so this never happens in the
     * player — but a component that silently loses a byte whenever it is handed an odd-sized buffer
     * is one bad assumption away from white noise, and the assumption belongs to somebody else.
     */
    private fun writeScaled(from: ByteBuffer, into: ByteBuffer) {
        from.order(ByteOrder.LITTLE_ENDIAN)

        val waiting = pendingByte
        if (waiting >= 0 && from.hasRemaining()) {
            pendingByte = -1
            emit(((from.get().toInt() shl 8) or waiting).toShort().toInt(), into)
        }

        while (from.remaining() >= 2) emit(from.short.toInt(), into)

        if (from.hasRemaining()) pendingByte = from.get().toInt() and 0xFF
    }

    /** One sample out, at the settled gain and wherever the opening ramp has got to. */
    private fun emit(sample: Int, into: ByteBuffer) {
        var scaled = sample * gain
        if (fadedFrames < fadeFrames) scaled *= fadedFrames.toFloat() / fadeFrames
        into.putShort(scaled.toInt().coerceIn(-32768, 32767).toShort())

        // Counted rather than read off the buffer's position, which starts wherever the last read
        // left it. The ramp advances once per *frame*, so the two channels of a stereo pair are
        // never scaled by different amounts.
        samplesWritten++
        val channels = frameBytes / 2
        if (samplesWritten % channels == 0L && fadedFrames < fadeFrames) fadedFrames++
    }
}

// ---------------------------------------------------------------------------------------------
// The rules, as free functions: pure, and so testable without a player
// ---------------------------------------------------------------------------------------------

/** Root mean square of the first [byteCount] bytes of little-endian 16-bit PCM, as 0..1. */
fun rmsOf(pcm: ByteArray, byteCount: Int): Float {
    if (byteCount < 2) return 0f
    var sum = 0.0
    var count = 0
    var at = 0
    while (at + 1 < byteCount) {
        val sample = ((pcm[at + 1].toInt() shl 8) or (pcm[at].toInt() and 0xFF)).toShort().toInt()
        val unit = sample / 32768.0
        sum += unit * unit
        count++
        at += 2
    }
    return if (count == 0) 0f else sqrt(sum / count).toFloat()
}

/**
 * The one gain a preview is played at.
 *
 * **Never above 1.** Only the opening of a clip is measured, so a louder passage later would be
 * squared off by any amplification — and a preview that distorts is worse than one that is quiet.
 * Silence is left alone rather than being multiplied by infinity.
 */
fun levellingGain(measuredRms: Float, targetRms: Float): Float = when {
    measuredRms <= 0f -> 1f
    else -> (targetRms / measuredRms).coerceIn(0f, 1f)
}
