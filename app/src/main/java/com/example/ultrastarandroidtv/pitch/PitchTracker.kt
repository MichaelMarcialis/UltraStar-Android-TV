package com.example.ultrastarandroidtv.pitch

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ceil
import kotlin.math.sqrt

private const val BYTES_PER_SAMPLE = 2
private const val SHORT_SCALE = 32768f

/**
 * How much quieter a voice may go once it is established as singing, as a share of [minLevel].
 *
 * Half is a step down clear enough to matter without being so low that the other singer, heard
 * across a room, could hold the gate open once this one stops.
 */
private const val HOLD_FRACTION = 0.5f

/** How long a voice keeps the lower gate after its last voiced window. */
private const val HOLD_SECONDS = 0.15

/**
 * Samples per analysis, and samples of new audio between analyses.
 *
 * Named rather than left as literals in the constructor because **three different latencies are
 * computed from these two numbers and they are not the same number**. Half the window is how far
 * back a reading's centre sits; the hop is how often one is published, which sets both the median
 * filter's delay and how stale the newest reading is when a frame picks it up. Both used to be
 * written as `1024.0 / 48_000.0` in `GameSession`, which was correct only by coincidence — they
 * meant different things and happened to agree.
 *
 * **The hop is half what it was.** Doubling the reading rate to about 94 a second buys 16 ms off
 * how far behind the voice the arrow is drawn — a hop off the median's delay and half a hop off
 * the reading's age — and it costs a second pass of [Yin] per window, on a device with three
 * idle cores. The window is unchanged and cannot usefully shrink: [Yin] needs about two periods
 * of the lowest pitch it is asked for, and 2048 samples at 48 kHz is already close to that for
 * the 65 Hz floor.
 */
const val DEFAULT_WINDOW_SIZE = 2048
const val DEFAULT_HOP_SIZE = 512

/** What these microphones deliver, and what every latency here is measured in. */
const val DEFAULT_SAMPLE_RATE = 48_000

/**
 * Turns one mic's PCM stream into a series of [PitchReading]s.
 *
 * The USB layer delivers audio in whatever size a batch of isochronous packets happened to
 * carry (~384 samples), but YIN needs a fixed power-of-two window, so this buffers chunks
 * into [windowSize] windows and slides by [hopSize] between analyses. A reading therefore
 * describes audio up to [windowSize] samples old — 43 ms at the defaults, which sets the
 * latency floor for scoring.
 *
 * **The loudness gate is measured over the newest audio only, and it has two heights.** Both
 * were forced by measurement rather than taste, and both are about the *start* of a note:
 *
 *  - Averaging loudness over the whole window means a window that is half silence reads as half
 *    as loud, so a note's attack is thrown away and the beats at its start score nothing.
 *    Measured against the real song library, a singer with a soft attack lost a quarter of every
 *    beat that way. [levelWindowSize] is therefore the newest slice while YIN still sees the
 *    whole window: the gate answers "is someone singing *now*", not "has someone been singing".
 *  - One threshold cannot both keep the other singer out and decide whether this one is making a
 *    sound. A voice must clear [minLevel] to *begin* but only [holdLevel] to *carry on*, which
 *    is what lets a quiet singer keep scoring through a phrase, and stops every note's attack
 *    after the first paying full price. Crosstalk from across the room never clears the higher
 *    gate, so it still never starts.
 *
 * Not thread-safe. One instance per mic, only ever touched from that mic's capture thread.
 *
 * @param windowSize samples per analysis. Sets the lowest detectable pitch: [Yin] searches
 *   lags up to half the window, so 2048 at 48 kHz bottoms out near 47 Hz — well below any
 *   singing voice.
 * @param hopSize samples of new audio between analyses. Smaller means more readings per
 *   second and more CPU; `windowSize / 2` is the usual compromise.
 * @param minLevel normalised RMS a voice must reach to count as singing at all. Guards against
 *   these cheap mics' noise floor reading as a confident low note, and is the only lever against
 *   one singer's mic scoring the other singer's voice.
 * @param holdLevel normalised RMS needed to *stay* counted as singing once [minLevel] has been
 *   cleared. Never above [minLevel], or the gate would work backwards.
 * @param levelWindowSize samples at the end of the window that the gate measures. Passing the
 *   whole window restores the old behaviour, which is how the attack tests pin the difference.
 * @param minProbability confidence floor, on top of [Yin] finding anything at all.
 * @param minHz,[maxHz] plausible sung range, passed through to [Yin] — which bounds the lags
 *   it searches rather than filtering afterwards.
 * @param holdSeconds how long the lower gate survives silence. Long enough to cross the gaps
 *   between notes of a phrase, short enough that a rest ends it.
 */
class PitchTracker(
    private val sampleRate: Int = DEFAULT_SAMPLE_RATE,
    private val windowSize: Int = DEFAULT_WINDOW_SIZE,
    private val hopSize: Int = DEFAULT_HOP_SIZE,
    private val minLevel: Float = 0.01f,
    private val holdLevel: Float = minLevel * HOLD_FRACTION,
    private val levelWindowSize: Int = windowSize / 2,
    private val minProbability: Float = 0.85f,
    minHz: Float = 65f,
    maxHz: Float = 1200f,
    holdSeconds: Double = HOLD_SECONDS,
) {
    init {
        require(hopSize in 1..windowSize) { "hopSize must be in 1..$windowSize, was $hopSize" }
        require(levelWindowSize in 1..windowSize) {
            "levelWindowSize must be in 1..$windowSize, was $levelWindowSize"
        }
        require(holdLevel <= minLevel) {
            "holdLevel ($holdLevel) must not exceed minLevel ($minLevel)"
        }
    }

    private val detector = Yin(sampleRate, windowSize, minHz, maxHz)
    private val window = FloatArray(windowSize)

    private var filled = 0

    /** Windows of quiet allowed before the higher gate applies again. */
    private val holdWindows = ceil(holdSeconds * sampleRate / hopSize).toInt().coerceAtLeast(0)

    /**
     * Quiet windows since the last voiced one. Starts past the hold, so the first note of a
     * session has to earn the lower gate rather than inheriting it.
     */
    private var quietWindows = Int.MAX_VALUE

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

    /** Drops any partially filled window, and forgets that anyone was singing. */
    fun reset() {
        filled = 0
        quietWindows = Int.MAX_VALUE
    }

    private fun analyse(): PitchReading {
        val level = rms(window, windowSize - levelWindowSize, windowSize)
        val gate = if (quietWindows <= holdWindows) holdLevel else minLevel

        val reading = when {
            level < gate -> PitchReading.unvoiced(level)
            !detector.detect(window) -> PitchReading.unvoiced(level)
            detector.probability < minProbability ->
                PitchReading.unvoiced(level, detector.probability)
            else -> {
                val hz = detector.frequencyHz
                PitchReading(
                    voiced = true,
                    frequencyHz = hz,
                    midi = hzToMidi(hz.toDouble()).toFloat(),
                    probability = detector.probability,
                    level = level,
                )
            }
        }

        // Only a window that actually produced a pitch holds the gate open. Loud noise YIN can
        // find no period in is not someone singing, and must not lower the bar for what follows.
        //
        // Saturating rather than counting on: this starts at Int.MAX_VALUE so that the first note
        // of a session has to clear the higher gate, and incrementing from there wraps to
        // Int.MIN_VALUE — which reads as "sang a moment ago" and hands a cold tracker the *lower*
        // gate, precisely backwards.
        quietWindows = when {
            reading.voiced -> 0
            quietWindows > holdWindows -> quietWindows
            else -> quietWindows + 1
        }
        return reading
    }

    private fun rms(samples: FloatArray, from: Int, to: Int): Float {
        var sum = 0.0
        for (i in from until to) sum += samples[i].toDouble() * samples[i]
        return sqrt(sum / (to - from)).toFloat()
    }
}
