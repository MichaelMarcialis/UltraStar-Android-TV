package com.example.ultrastarandroidtv.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PitchRangeTest {

    private val range = PitchRange()

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
    fun `a passage is padded so its extremes are not flush against the edge`() {
        range.follow(60..72, 0.0)

        assertTrue(range.low < 60f)
        assertTrue(range.high > 72f)
    }

    @Test
    fun `a passage on one note is not magnified until a wobble looks like a leap`() {
        range.follow(60..60, 0.0)

        assertTrue("span was ${range.high - range.low}", range.high - range.low >= 11f)
        assertTrue("the note should sit near the middle", 60f - range.low in 4f..7f)
    }

    @Test
    fun `moving to a new passage slides rather than jumps`() {
        range.follow(60..64, 0.0)
        val startedAt = range.low

        range.follow(76..80, 0.016)

        assertTrue("it should have moved", range.low > startedAt)
        assertTrue("but not arrived in one frame", range.low < 70f)
    }

    @Test
    fun `it does arrive, given a moment`() {
        range.follow(60..64, 0.0)

        var t = 0.0
        repeat(200) {
            t += 0.016
            range.follow(76..80, t)
        }

        // Same rule the first snap uses: padded to 73.5..82.5, then widened to the 11-semitone
        // minimum span because a four-semitone passage does not fill the track on its own.
        assertEquals(72.5f, range.low, 0.2f)
        assertEquals(83.5f, range.high, 0.2f)
    }

    @Test
    fun `an empty window holds the range instead of collapsing it`() {
        // Long rests and intros have nothing on screen. Collapsing to some default would mean
        // the notes after the rest slid in from somewhere the singer was not looking.
        range.follow(60..64, 0.0)
        val low = range.low
        val high = range.high

        range.follow(null, 0.5)
        range.follow(null, 1.0)

        assertEquals(low, range.low, 1e-6f)
        assertEquals(high, range.high, 1e-6f)
    }

    @Test
    fun `a long stall does not teleport the view`() {
        // A paused song, a stalled frame or a restart can hand this a huge time step. Treating
        // it literally would snap the range across in a single frame, which is the one thing
        // the easing exists to prevent.
        range.follow(60..64, 0.0)
        val startedAt = range.low

        range.follow(76..80, 60.0)

        assertTrue("it should have moved", range.low > startedAt)
        assertTrue("but not all the way", range.low < 70f)
    }

    @Test
    fun `time running backwards is ignored rather than rewinding the ease`() {
        range.follow(60..64, 5.0)
        val low = range.low

        range.follow(76..80, 4.0)

        assertEquals("a negative step must not move it", low, range.low, 1e-6f)
    }

    @Test
    fun `after a reset the next passage is snapped to again`() {
        range.follow(60..64, 0.0)
        range.reset()

        assertFalse(range.isReady)

        range.follow(76..80, 1.0)
        assertTrue(range.low > 70f)
    }
}
