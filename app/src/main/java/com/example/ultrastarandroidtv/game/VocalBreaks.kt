package com.example.ultrastarandroidtv.game

/** A stretch of song someone is singing over, in song seconds. */
data class VocalSpan(val startSeconds: Double, val endSeconds: Double)

/** Below this, a gap is a breath or a rest and the track stays put. */
const val DEFAULT_MIN_BREAK_SECONDS: Double = 10.0

/** How long the track stays up after the last note of a phrase before it starts to go. */
private const val DEFAULT_HOLD_SECONDS: Double = 1.5

/** How long before the next note the track is fully back, ready to be read. */
private const val DEFAULT_LEAD_SECONDS: Double = 3.0

/** The fade itself, at each end. */
private const val DEFAULT_FADE_SECONDS: Double = 1.0

/**
 * When there is nothing to sing, and the screen belongs to the video.
 *
 * Intros, solos and outros are a real part of a song and can run half a minute. Leaving an empty
 * pitch track sitting over the video for all of it says "something is wrong" when the answer is
 * "nothing is happening yet" — so during a long enough gap the game gets out of the way, and
 * comes back a few seconds before it is needed again.
 *
 * **The gap is measured across every part in play, not per singer.** In a duet one voice resting
 * while the other sings is not a break in the song; it is a break in one person's line, and the
 * screen still has something on it worth reading.
 *
 * The three thresholds are asymmetric on purpose. Leaving is unhurried — a phrase can end on a
 * held note and the singer deserves to see it finish. Returning is early, because arriving with
 * the first syllable already at the line is arriving late: the notes need to have scrolled in
 * and the eye needs to have found the sing line before anyone is asked to sing.
 *
 * Pure and stateless, so it is unit-tested against made-up songs rather than on a television.
 */
class VocalBreaks(
    spans: List<VocalSpan>,
    private val minBreakSeconds: Double = DEFAULT_MIN_BREAK_SECONDS,
    private val holdSeconds: Double = DEFAULT_HOLD_SECONDS,
    private val leadSeconds: Double = DEFAULT_LEAD_SECONDS,
    private val fadeSeconds: Double = DEFAULT_FADE_SECONDS,
) {
    private val starts: DoubleArray
    private val latestEnd: DoubleArray

    init {
        val sorted = spans.sortedBy { it.startSeconds }
        starts = DoubleArray(sorted.size) { sorted[it].startSeconds }

        // A running maximum rather than each note's own end: notes from two parts interleave, and
        // a long note in one part can still be sounding while three short ones in the other have
        // come and gone. What matters is the latest anybody has stopped singing.
        latestEnd = DoubleArray(sorted.size)
        var running = Double.NEGATIVE_INFINITY
        for (i in sorted.indices) {
            running = maxOf(running, sorted[i].endSeconds)
            latestEnd[i] = running
        }
    }

    /**
     * How strongly to draw the game at [songSeconds]: 1 while there is singing to do, 0 in the
     * middle of a long instrumental, and partway through a fade at either end of one.
     */
    fun hudAlpha(songSeconds: Double): Float {
        if (starts.isEmpty()) return 1f

        val since = secondsSinceVocal(songSeconds)
        val until = secondsUntilVocal(songSeconds)

        // Short gaps never move anything. Without this a gap only a little longer than the two
        // fades would dip halfway down and come straight back up, which is worse than either
        // staying or going.
        if (since + until < minBreakSeconds) return 1f

        val leaving = ((holdSeconds + fadeSeconds - since) / fadeSeconds).toFloat()
        val returning = ((leadSeconds + fadeSeconds - until) / fadeSeconds).toFloat()
        return maxOf(leaving, returning).coerceIn(0f, 1f)
    }

    /**
     * Seconds since anyone last stopped singing, 0 while a note is sounding.
     *
     * Infinite before the first note, which is what makes a song's intro a break like any other:
     * the game arrives just before the first line rather than sitting through the introduction.
     */
    private fun secondsSinceVocal(songSeconds: Double): Double {
        val index = lastStartAtOrBefore(songSeconds)
        if (index < 0) return Double.POSITIVE_INFINITY
        return (songSeconds - latestEnd[index]).coerceAtLeast(0.0)
    }

    /** Seconds until the next note begins, or infinite once the last one has passed. */
    private fun secondsUntilVocal(songSeconds: Double): Double {
        val index = firstStartAfter(songSeconds)
        if (index >= starts.size) return Double.POSITIVE_INFINITY
        return starts[index] - songSeconds
    }

    /** Index of the last note to have started by [songSeconds], or -1. */
    private fun lastStartAtOrBefore(songSeconds: Double): Int = firstStartAfter(songSeconds) - 1

    private fun firstStartAfter(songSeconds: Double): Int {
        var lo = 0
        var hi = starts.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (starts[mid] <= songSeconds) lo = mid + 1 else hi = mid
        }
        return lo
    }
}
