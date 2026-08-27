package com.example.ultrastarandroidtv.game

import com.example.ultrastarandroidtv.score.NoteScore
import com.example.ultrastarandroidtv.song.Note
import com.example.ultrastarandroidtv.song.NoteType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * When the game says "Nice!".
 *
 * The rule that matters most here is the one about *when*: a word per syllable is a slot machine,
 * a word per phrase is somebody in the room reacting to what you just sang. The tracker is pure so
 * that can be checked without singing at a television.
 */
class PraiseTest {

    /**
     * One note of [beats] beats on line [line], with the first [hits] of them landed.
     *
     * Left unscored entirely when [scored] is false, which is how a line still being sung is
     * represented — the distinction the tracker turns on.
     */
    private fun note(
        line: Int,
        beats: Int,
        hits: Int,
        scored: Boolean = true,
        type: NoteType = NoteType.NORMAL,
    ): NoteScore {
        val score = NoteScore(Note(type, 0, beats, 0, "la"), line)
        if (scored) {
            for (beat in 0 until beats) {
                score.record(beat, 60f, beat < hits, type.let { if (it == NoteType.FREESTYLE) 0 else 1 }, 0.5f, 0.9f)
            }
        }
        return score
    }

    @Test
    fun `nothing is said until a whole line has been sung`() {
        val tracker = PraiseTracker(listOf(note(0, 4, 4), note(0, 4, 4, scored = false)))
        assertNull(tracker.poll())
    }

    @Test
    fun `a line sung well earns a word the moment it finishes`() {
        val tracker = PraiseTracker(listOf(note(0, 4, 4), note(0, 4, 4)))
        assertEquals(Praise.PERFECT, tracker.poll())
    }

    @Test
    fun `and it is only said once`() {
        val tracker = PraiseTracker(listOf(note(0, 4, 4)))
        assertEquals(Praise.PERFECT, tracker.poll())
        assertNull(tracker.poll())
    }

    @Test
    fun `the word follows how much of the line landed`() {
        assertEquals(Praise.PERFECT, PraiseTracker(listOf(note(0, 10, 10))).poll())
        assertEquals(Praise.GREAT, PraiseTracker(listOf(note(0, 10, 9))).poll())
        assertEquals(Praise.NICE, PraiseTracker(listOf(note(0, 10, 7))).poll())
        assertEquals(Praise.GOOD, PraiseTracker(listOf(note(0, 10, 6))).poll())
    }

    /**
     * The one rule that is not about accuracy. A child having trouble can already see the notes
     * going past unfilled, and a caption confirming it in large letters is the last thing a
     * family karaoke game should put on the screen.
     */
    @Test
    fun `a line that went badly is not commented on at all`() {
        assertNull(PraiseTracker(listOf(note(0, 10, 4))).poll())
        assertNull(PraiseTracker(listOf(note(0, 10, 0))).poll())
    }

    @Test
    fun `each line is judged on its own`() {
        val tracker = PraiseTracker(
            listOf(
                note(0, 4, 4),
                note(1, 4, 0),
                note(2, 4, 3),
                note(2, 4, 3, scored = false),
            ),
        )
        assertEquals("first line", Praise.PERFECT, tracker.poll())
        assertNull("second line went badly, third is unfinished", tracker.poll())
    }

    /**
     * Two lines can finish between two frames on a fast song, and stacking two captions on top of
     * each other reads as a glitch. The one that just happened wins.
     */
    @Test
    fun `two lines finishing at once say one thing, the later one`() {
        val tracker = PraiseTracker(listOf(note(0, 10, 7), note(1, 10, 10)))
        assertEquals(Praise.PERFECT, tracker.poll())
    }

    /** Freestyle notes score nothing, so a line of them is not a phrase anybody sang. */
    @Test
    fun `a freestyle line is not praised`() {
        assertNull(PraiseTracker(listOf(note(0, 8, 0, type = NoteType.FREESTYLE))).poll())
    }

    @Test
    fun `restarting the song starts the praise over`() {
        val tracker = PraiseTracker(listOf(note(0, 4, 4)))
        assertEquals(Praise.PERFECT, tracker.poll())
        tracker.reset()
        assertEquals(Praise.PERFECT, tracker.poll())
    }
}
