package com.example.ultrastarandroidtv.game

import com.example.ultrastarandroidtv.score.NoteScore

/**
 * A word of encouragement, and how strongly it is meant.
 *
 * [rank] exists so the drawing does not have to switch on the word: it decides the size and the
 * colour, so adding a word between two others changes one list rather than three.
 */
enum class Praise(val word: String, val rank: Int) {
    GOOD("Good", 0),
    NICE("Nice!", 1),
    GREAT("Great!", 2),
    PERFECT("Perfect!", 3),
}

/**
 * How much of a line has to land to earn each word.
 *
 * There is deliberately nothing below [Praise.GOOD]. A singer having trouble does not need a
 * caption saying so — they can see the notes going past unfilled, and the one thing a family
 * karaoke game must not do is tell a seven-year-old they were bad at it in large letters.
 */
private val THRESHOLDS = listOf(
    0.97 to Praise.PERFECT,
    0.85 to Praise.GREAT,
    0.70 to Praise.NICE,
    0.55 to Praise.GOOD,
)

/** The word a line hitting [fraction] of its scorable beats earns, or null for none. */
fun praiseFor(fraction: Double): Praise? =
    THRESHOLDS.firstOrNull { fraction >= it.first }?.second

/**
 * Watches one singer's notes and says when a phrase has been finished well.
 *
 * **A lyric line is the unit, not a note.** A note is a syllable, and a word of praise per
 * syllable is a slot machine; a line is a phrase, which is the smallest thing a singer thinks of
 * themselves as having got right or wrong. It is also already in the data — every [NoteScore]
 * carries the index of the line it belongs to — so there is nothing to infer.
 *
 * Walks forward only, one cursor, no allocation per poll. Pure, so the timing rules are tested
 * against made-up songs rather than by singing at a television.
 *
 * Not thread-safe; owned by the draw pass, which is also the only thing that reads scores.
 */
class PraiseTracker(private val noteScores: List<NoteScore>) {

    /** First note not yet accounted for. Everything before it belongs to a line already judged. */
    private var cursor = 0

    /**
     * The word earned since the last call, or null.
     *
     * Returns at most one line's worth per call: two lines finishing between two frames is
     * possible on a fast song, and stacking two captions on top of each other reads as a glitch.
     * The later one is kept, since it is the one that just happened.
     */
    fun poll(): Praise? {
        var earned: Praise? = null
        while (true) {
            val line = completedLineAt(cursor) ?: return earned
            earned = rate(cursor, line) ?: earned
            cursor = line
        }
    }

    /**
     * Index one past the end of the line starting at [from], if every note in it has been fully
     * scored, or null if the line is unfinished or there is nothing left.
     */
    private fun completedLineAt(from: Int): Int? {
        if (from >= noteScores.size) return null
        val lineIndex = noteScores[from].lineIndex
        var end = from
        while (end < noteScores.size && noteScores[end].lineIndex == lineIndex) {
            val score = noteScores[end]
            if (score.beatsScored < score.note.durationBeats) return null
            end++
        }
        return end
    }

    /** The word for the notes in `[from, to)`, or null when the line was worth no points at all. */
    private fun rate(from: Int, to: Int): Praise? {
        var hit = 0
        var scored = 0
        for (i in from until to) {
            val score = noteScores[i]
            // Freestyle notes are worth nothing and are excluded from the maximum, so counting
            // them here would let a line of them read as a phrase nobody sang.
            if (score.maxPoints == 0) continue
            hit += score.beatsHit
            scored += score.note.durationBeats
        }
        if (scored == 0) return null
        return praiseFor(hit.toDouble() / scored)
    }

    fun reset() {
        cursor = 0
    }
}
