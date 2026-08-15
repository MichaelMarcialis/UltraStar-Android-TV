package com.example.ultrastarandroidtv.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PitchRangeTest {

    private val span = 16f
    private val range = PitchRange(spanSemitones = span)

    @Test
    fun `it has nothing to say until it has seen a passage`() {
        assertFalse(range.isReady)
    }

    @Test
    fun `the first passage is snapped to, not slid into`() {
        // Easing up from nowhere would send the notes flying in from an arbitrary place at the
        // start of every song.
        range.follow(60..64, 0.0)

        assertTrue(range.isReady)
        assertTrue("the passage must fit", range.low <= 60f && range.high >= 64f)
    }

    @Test
    fun `the span never changes, whatever the melody does`() {
        // This is the whole point. A range that resized to fit each passage made the notes
        // visibly stretch and squash as the melody moved, which was distracting on the TV out
        // of all proportion to what it bought.
        range.follow(60..61, 0.0)
        assertEquals(span, range.high - range.low, 1e-4f)

        var t = 0.0
        for (target in listOf(48..72, 60..60, 70..84, 55..58)) {
            repeat(30) {
                t += 0.016
                range.follow(target, t)
                assertEquals("span moved at t=$t", span, range.high - range.low, 1e-4f)
            }
        }
    }

    @Test
    fun `the view holds perfectly still while the melody stays clear of the edges`() {
        // Most of a song should be spent motionless. Re-centring on every small move would put
        // the whole track in constant gentle drift for no benefit.
        range.follow(60..64, 0.0)
        val low = range.low

        var t = 0.0
        repeat(60) {
            t += 0.016
            range.follow(61..65, t)
        }

        assertEquals(low, range.low, 1e-4f)
    }

    @Test
    fun `it re-centres once the melody presses against an edge`() {
        range.follow(60..64, 0.0)
        val before = range.low

        var t = 0.0
        repeat(120) {
            t += 0.016
            range.follow(74..78, t)
        }

        assertTrue("it should have panned up", range.low > before)
        assertTrue("and brought the passage inside", range.low <= 74f && range.high >= 78f)
    }

    @Test
    fun `re-centring slides rather than jumping`() {
        range.follow(60..64, 0.0)
        val before = range.low

        range.follow(80..84, 0.016)

        assertTrue("it should have moved", range.low > before)
        assertTrue("but not arrived in one frame", range.low < before + 4f)
    }

    @Test
    fun `an empty window holds the view instead of drifting somewhere neutral`() {
        // Long rests and intros have nothing on screen. Moving during them would mean the notes
        // after the rest arrive somewhere the singer was not looking.
        range.follow(60..64, 0.0)
        val low = range.low

        range.follow(null, 0.5)
        range.follow(null, 1.0)

        assertEquals(low, range.low, 1e-6f)
    }

    @Test
    fun `a long stall does not teleport the view`() {
        // A paused song, a stalled frame or a restart can hand this a huge time step. Treating
        // it literally would snap the view across in one frame, which is what the easing exists
        // to prevent.
        range.follow(60..64, 0.0)
        val before = range.low

        range.follow(80..84, 60.0)

        assertTrue("it should have moved", range.low > before)
        assertTrue("but not all the way", range.low < 70f)
    }

    @Test
    fun `time running backwards is ignored rather than rewinding the ease`() {
        range.follow(60..64, 5.0)
        val low = range.low

        range.follow(80..84, 4.0)

        assertEquals("a negative step must not move it", low, range.low, 1e-6f)
    }

    @Test
    fun `after a reset the next passage is snapped to again`() {
        range.follow(60..64, 0.0)
        range.reset()

        assertFalse(range.isReady)

        range.follow(80..84, 1.0)
        assertTrue(range.low <= 80f && range.high >= 84f)
    }
}
