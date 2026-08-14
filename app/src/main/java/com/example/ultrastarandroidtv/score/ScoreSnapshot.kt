package com.example.ultrastarandroidtv.score

/**
 * One player's score at a moment in the song. The three point fields add up to [total] and are
 * kept apart because that is the breakdown these games traditionally show.
 *
 * Points are always measured against the *whole* song's maximum, so the total climbs towards
 * [MAX_SCORE] as the song plays rather than jumping around as a running percentage would.
 */
data class ScoreSnapshot(
    /** Points for hitting notes, excluding the extra each golden note is worth. */
    val notePoints: Int,
    /** The bonus half of every golden note hit. */
    val goldenPoints: Int,
    /** Reward for completing whole lines, up to [MAX_LINE_BONUS]. */
    val lineBonus: Int,
    val beatsHit: Int,
    /** Scorable beats evaluated so far — freestyle beats are not counted. */
    val beatsScored: Int,
) {
    val total: Int get() = notePoints + goldenPoints + lineBonus

    /** Share of the beats sung so far that were hit, 0..1. */
    val accuracy: Double
        get() = if (beatsScored == 0) 0.0 else beatsHit.toDouble() / beatsScored

    companion object {
        val EMPTY = ScoreSnapshot(0, 0, 0, 0, 0)
    }
}
