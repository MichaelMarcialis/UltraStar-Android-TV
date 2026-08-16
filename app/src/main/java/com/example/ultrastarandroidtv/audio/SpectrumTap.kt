package com.example.ultrastarandroidtv.audio

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

/** Samples per transform. 1024 at 48 kHz is a 21 ms picture, updated about 47 times a second. */
private const val WINDOW = 1024

/** Lowest and highest frequency worth drawing. Below and above this there is nothing to see. */
private const val LOW_HZ = 40.0
private const val HIGH_HZ = 14_000.0

/** How fast a band falls when the music stops pushing it. Per update, so ~47 times a second. */
private const val DECAY = 0.86f

/**
 * Ceiling on the treble tilt below, so the top bands do not amplify hiss into a wall of bars.
 */
private const val MAX_TILT = 8f

/**
 * Listens to the song as it plays and publishes a spectrum for the visualiser.
 *
 * Sits in ExoPlayer's audio pipeline and passes every sample through untouched — it is a tap,
 * not an effect. That matters: this is the same audio the singers are scored against, and the
 * one thing this must never do is change it.
 *
 * The alternative was Android's `Visualizer` effect, which needs the `RECORD_AUDIO` permission
 * to listen to the device's own output — an alarming thing to ask a family for so that bars can
 * wiggle, and unnecessary when the audio is already passing through this app.
 *
 * Bands are spaced **logarithmically**, because pitch is: linear bands would spend most of their
 * width on the top two octaves, where music has almost nothing, and cram every bass note into
 * the first bar.
 *
 * Threading: written on the audio thread, read on the UI thread. Two band arrays are swapped
 * behind a volatile reference, so a reader always sees one complete set rather than a half
 * updated one, and nothing allocates once playing.
 */
@OptIn(UnstableApi::class)
class SpectrumTap(val bandCount: Int = 56) : BaseAudioProcessor() {

    private val fft = Fft(WINDOW)
    private val samples = FloatArray(WINDOW)
    private val magnitudes = FloatArray(WINDOW / 2)
    private var filled = 0

    private var channels = 1
    private var sampleRate = 48_000

    private val bandsA = FloatArray(bandCount)
    private val bandsB = FloatArray(bandCount)
    private var writingToA = true

    @Volatile
    private var published: FloatArray = bandsB

    /** Bin index each band starts at, worked out once per configuration. */
    private var bandStart = IntArray(bandCount + 1)

    /**
     * Per-band gain that rises with frequency.
     *
     * Without it the display is all bass and nothing else, because that is genuinely where music
     * keeps its energy — drawn honestly, a spectrum analyser is a cliff on the left and a flat
     * line everywhere else. Every visualiser ever built tilts the display upwards to compensate;
     * this one uses roughly +3 dB per octave, which is the usual choice.
     */
    private var bandGain = FloatArray(bandCount)

    /**
     * Whether to actually analyse. False costs nothing but the pass-through.
     *
     * The tap has to be installed when the player is built — it lives in the audio pipeline —
     * but whether anyone is *looking* is not known until then: a song with a video file that
     * turns out not to decode needs the visualiser after all. So it is always fitted and
     * switched on by whoever draws it.
     */
    @Volatile
    var enabled: Boolean = false

    /** Copies the latest spectrum into [out]. Safe from any thread. */
    fun copyInto(out: FloatArray) {
        val current = published
        System.arraycopy(current, 0, out, 0, minOf(out.size, current.size))
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            // Anything else and the tap simply does not run; the song still plays.
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        channels = inputAudioFormat.channelCount
        sampleRate = inputAudioFormat.sampleRate
        computeBandEdges()
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return

        if (enabled) analyse(inputBuffer)

        // Pass-through, byte for byte.
        val output = replaceOutputBuffer(remaining)
        output.put(inputBuffer)
        output.flip()
    }

    override fun onFlush() {
        filled = 0
        bandsA.fill(0f)
        bandsB.fill(0f)
    }

    /** Reads without disturbing the caller's buffer — the pass-through still needs it intact. */
    private fun analyse(buffer: ByteBuffer) {
        val view = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val bytesPerFrame = 2 * channels
        val frames = view.remaining() / bytesPerFrame
        var index = view.position()

        for (frame in 0 until frames) {
            var sum = 0
            for (channel in 0 until channels) {
                sum += view.getShort(index)
                index += 2
            }
            samples[filled++] = sum.toFloat() / channels / 32768f

            if (filled == WINDOW) {
                updateBands()
                filled = 0
            }
        }
    }

    private fun updateBands() {
        fft.magnitudes(samples, magnitudes)

        val target = if (writingToA) bandsA else bandsB
        val previous = published

        for (band in 0 until bandCount) {
            var peak = 0f
            val from = bandStart[band]
            val to = bandStart[band + 1]
            for (bin in from until to) {
                if (magnitudes[bin] > peak) peak = magnitudes[bin]
            }

            // A full-scale sine through a Hann window lands near size/4, so this is roughly
            // 0..1. The square root is there because loudness is not linear in amplitude and a
            // linear bar spends its whole height on the loudest moment of the song.
            val level = sqrt((peak * bandGain[band] / (WINDOW / 4f)).coerceIn(0f, 1f))

            // Rise instantly, fall slowly: a bar that decays is readable, one that follows the
            // signal exactly is a flicker.
            target[band] = maxOf(level, previous[band] * DECAY)
        }

        published = target
        writingToA = !writingToA
    }

    /**
     * Works out which FFT bins belong to which band, log-spaced.
     *
     * Every band gets at least one bin: at the bottom of the range several bands would otherwise
     * map to the same bin and sit permanently identical.
     */
    private fun computeBandEdges() {
        val nyquistBins = WINDOW / 2
        val hzPerBin = sampleRate.toDouble() / WINDOW
        val ratio = ln(HIGH_HZ / LOW_HZ)

        var previous = 0
        for (edge in 0..bandCount) {
            val hz = LOW_HZ * Math.E.pow(ratio * edge / bandCount)
            val bin = (hz / hzPerBin).toInt().coerceIn(0, nyquistBins)
            bandStart[edge] = if (edge == 0) bin else maxOf(bin, previous + 1).coerceAtMost(nyquistBins)
            previous = bandStart[edge]
        }

        for (band in 0 until bandCount) {
            val centreHz = LOW_HZ * Math.E.pow(ratio * (band + 0.5) / bandCount)
            bandGain[band] = sqrt(centreHz / LOW_HZ).toFloat().coerceAtMost(MAX_TILT)
        }
    }
}
