package com.example.ultrastarandroidtv.playback

import kotlin.math.abs

/**
 * Measures the round trip in [SyncCalibration] by listening for notes that start at known times.
 *
 * Point a mic at the speaker while the calibration song plays and the mic hears the song
 * itself. Every tone starts at a song position written in the song file, so the gap between
 * where the player says the song is and where a tone was actually heard is the entire delay
 * through the TV, the mic, and the analysis window — the one number scoring needs.
 *
 * Only the first detection of each tone counts, so a note that briefly drops below the
 * detector's confidence gate partway through cannot be mistaken for a second onset.
 *
 * @param expectedOnsets song positions, in seconds, where a tone is known to start.
 * @param toleranceSeconds how far from an expected onset a detection may fall and still be
 *   attributed to it. Must stay below half the gap between tones, or a very late detection
 *   could be credited to the following tone and report a wildly negative latency.
 */
class LatencyProbe(
    private val expectedOnsets: List<Double>,
    private val toleranceSeconds: Double = 0.4,
) {
    private val measured = HashMap<Int, Double>()
    private var wasVoiced = false

    /** One measurement per tone heard, in the order the tones appear in the song. */
    val samples: List<Double>
        get() = measured.entries.sortedBy { it.key }.map { it.value }

    /** Tones heard so far, out of [expectedOnsets]. */
    val count: Int get() = measured.size

    /**
     * Best estimate of the round trip in seconds, or null before anything has been heard.
     * A median rather than a mean: one tone masked by a cough should not move the answer.
     */
    val medianSeconds: Double?
        get() {
            val sorted = measured.values.sorted()
            if (sorted.isEmpty()) return null
            val middle = sorted.size / 2
            return if (sorted.size % 2 == 1) {
                sorted[middle]
            } else {
                (sorted[middle - 1] + sorted[middle]) / 2.0
            }
        }

    /** Spread between the earliest and latest measurement — how much to trust the median. */
    val spreadSeconds: Double?
        get() = measured.values.let {
            if (it.isEmpty()) null else it.max() - it.min()
        }

    /**
     * Feeds one pitch reading in. [playerPositionSeconds] is the raw player position when the
     * reading arrived, with no latency subtracted — the whole point is to measure that.
     */
    fun onReading(playerPositionSeconds: Double, voiced: Boolean) {
        if (voiced && !wasVoiced) {
            val index = nearestOnset(playerPositionSeconds)
            if (index >= 0 && index !in measured) {
                measured[index] = playerPositionSeconds - expectedOnsets[index]
            }
        }
        wasVoiced = voiced
    }

    fun reset() {
        measured.clear()
        wasVoiced = false
    }

    private fun nearestOnset(position: Double): Int {
        var best = -1
        var bestDistance = toleranceSeconds
        expectedOnsets.forEachIndexed { index, onset ->
            val distance = abs(position - onset)
            if (distance <= bestDistance) {
                best = index
                bestDistance = distance
            }
        }
        return best
    }
}
