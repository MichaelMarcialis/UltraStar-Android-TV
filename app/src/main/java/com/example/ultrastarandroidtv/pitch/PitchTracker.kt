package com.example.ultrastarandroidtv.pitch

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

private const val BYTES_PER_SAMPLE = 2
private const val SHORT_SCALE = 32768f

/**
 * Turns one mic's PCM stream into a series of [PitchReading]s.
 *
 * The USB layer delivers audio in whatever size a batch of isochronous packets happened to
 * carry (~384 samples), but YIN needs a fixed power-of-two window, so this buffers chunks
 * into [windowSize] windows and slides by [hopSize] between analyses. A reading therefore
 * describes audio up to [windowSize] samples old — 43 ms at the defaults, which sets the
 * latency floor for scoring.
 *
 * Not thread-safe. One instance per mic, only ever touched from that mic's capture thread.
 *
 * @param windowSize samples per analysis. Sets the lowest detectable pitch: [Yin] searches
 *   lags up to half the window, so 2048 at 48 kHz bottoms out near 47 Hz — well below any
 *   singing voice.
 * @param hopSize samples of new audio between analyses. Smaller means more readings per
 *   second and more CPU; `windowSize / 2` is the usual compromise.
 * @param minLevel normalised RMS below which the window is treated as silence and the
 *   detector is skipped entirely. Guards against these cheap mics' noise floor reading as a
 *   confident low note, and skips the expensive part on every silent window.
 * @param minProbability confidence floor, on top of [Yin] finding anything at all.
 * @param minHz,[maxHz] plausible sung range, passed through to [Yin] — which bounds the lags
 *   it searches rather than filtering afterwards.
 */
class PitchTracker(
    private val sampleRate: Int = 48_000,
    private val windowSize: Int = 2048,
    private val hopSize: Int = 1024,
    private val minLevel: Float = 0.01f,
    private val minProbability: Float = 0.85f,
    minHz: Float = 65f,
    maxHz: Float = 1200f,
) {
    init {
        require(hopSize in 1..windowSize) { "hopSize must be in 1..$windowSize, was $hopSize" }
    }

    private val detector = Yin(sampleRate, windowSize, minHz, maxHz)
    private val window = FloatArray(windowSize)

    private var filled = 0

    /** Seconds of audio each window spans — the age of the newest reading. */
    val windowSeconds: Double get() = windowSize.toDouble() / sampleRate

    /**
     * Feeds [byteCount] bytes of mono 16-bit signed little-endian PCM from [pcm], invoking
     * [onReading] once per completed analysis window — usually zero or one time per call,
     * occasionally twice.
     *
     * Reads [pcm] by absolute index and never touches its position, so the caller's buffer is
     * left exactly as it was found.
     */
    fun process(pcm: ByteBuffer, byteCount: Int, onReading: (PitchReading) -> Unit) {
        val samples = pcm.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val available = byteCount / BYTES_PER_SAMPLE
        var consumed = 0

        while (consumed < available) {
            val take = minOf(available - consumed, windowSize - filled)
            for (i in 0 until take) {
                window[filled + i] =
                    samples.getShort((consumed + i) * BYTES_PER_SAMPLE) / SHORT_SCALE
            }
            filled += take
            consumed += take

            if (filled == windowSize) {
                onReading(analyse())
                System.arraycopy(window, hopSize, window, 0, windowSize - hopSize)
                filled = windowSize - hopSize
            }
        }
    }

    /** Drops any partially filled window. Call when capture stops or the singer changes. */
    fun reset() {
        filled = 0
    }

    private fun analyse(): PitchReading {
        val level = rms(window)
        if (level < minLevel) return PitchReading.unvoiced(level)

        if (!detector.detect(window)) return PitchReading.unvoiced(level)
        if (detector.probability < minProbability) return PitchReading.unvoiced(level)

        val hz = detector.frequencyHz
        return PitchReading(
            voiced = true,
            frequencyHz = hz,
            midi = hzToMidi(hz.toDouble()).toFloat(),
            probability = detector.probability,
            level = level,
        )
    }

    private fun rms(samples: FloatArray): Float {
        var sum = 0.0
        for (s in samples) sum += s.toDouble() * s
        return sqrt(sum / samples.size).toFloat()
    }
}
