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

    /**
     * How many stars out of five this is worth.
     *
     * A percentage is precise and says nothing anybody wants to hear. Five stars is the language
     * every game of this kind uses, and it is read at a glance from a sofa — which a two-digit
     * number next to a five-digit number is not.
     *
     * Taken from the **points**, not from the beat accuracy, because the points are the game's own
     * currency: they already carry the golden notes and the line bonus, so a singer who nailed the
     * golden run is credited for it here in the same way they are credited on the scoreboard. The
     * two numbers are close but not identical, and having the stars disagree with the score they
     * sit next to would be worse than either.
     */
    val stars: Int get() = starsFor(total.toDouble() / MAX_SCORE)

    companion object {
        val EMPTY = ScoreSnapshot(0, 0, 0, 0, 0)
    }
}

/**
 * Stars out of five for a performance scoring [fraction] of the maximum, 0..1.
 *
 * Bands of a fifth each, so four stars is a good performance and five is an excellent one — which
 * is about where a real singer lands: the calibration harness feeding a song back to itself scores
 * 98 %, and a person singing well scores around 70 %.
 *
 * **Zero stars only for zero.** Anything at all that was sung earns the first star, because the
 * one person who never needs telling they did badly is the one who already knows.
 */
fun starsFor(fraction: Double): Int =
    kotlin.math.ceil(fraction.coerceIn(0.0, 1.0) * MAX_STARS).toInt().coerceIn(0, MAX_STARS)

const val MAX_STARS: Int = 5

/**
 * The pair's score in a duet, as one number.
 *
 * A duet is the two of them singing *one* song, and two separate scoreboards invite exactly the
 * comparison the song is not about — one singer's part being shorter or lower does not mean they
 * did worse. Versus mode keeps its two scores, because there the comparison is the whole point.
 *
 * **Averaged rather than summed**, so a duet is still marked out of 10000 and its stars mean the
 * same thing a solo song's do. Averaging weights each *part* equally rather than each beat, which
 * is the honest reading of a duet: singing a short part perfectly is as much as anyone can do with
 * it. The beats are summed, because "142 of 190 beats" is a count of what happened in the room.
 */
fun combined(snapshots: List<ScoreSnapshot>): ScoreSnapshot {
    if (snapshots.isEmpty()) return ScoreSnapshot.EMPTY
    val n = snapshots.size
    return ScoreSnapshot(
        notePoints = snapshots.sumOf { it.notePoints } / n,
        goldenPoints = snapshots.sumOf { it.goldenPoints } / n,
        lineBonus = snapshots.sumOf { it.lineBonus } / n,
        beatsHit = snapshots.sumOf { it.beatsHit },
        beatsScored = snapshots.sumOf { it.beatsScored },
    )
}
