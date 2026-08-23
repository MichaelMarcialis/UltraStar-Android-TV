package com.example.ultrastarandroidtv.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which way the arrow leans.
 *
 * Rotation on screen is clockwise-positive because y runs downward, and the arrow pivots about
 * its tip with its body to the left — so a positive angle swings the *tail* up and leaves the
 * arrow pointing down. That is two sign conventions stacked on each other, which is exactly the
 * sort of thing that is invisible in review and obvious on a television, so it is pinned here.
 */
class ArrowTiltTest {

    @Test
    fun `singing flat points the arrow up, to say sing higher`() {
        assertTrue("flat should tilt upward, i.e. negative", tiltDegrees(58f, 60) < 0f)
    }

    @Test
    fun `singing sharp points the arrow down, to say sing lower`() {
        assertTrue("sharp should tilt downward, i.e. positive", tiltDegrees(62f, 60) > 0f)
    }

    @Test
    fun `being on the note is level`() {
        assertEquals(0f, tiltDegrees(60f, 60), 1e-4f)
    }

    /** Proportional, because the useful question mid-note is whether you are getting closer. */
    @Test
    fun `the tilt grows with the error`() {
        val small = tiltDegrees(60.5f, 60)
        val larger = tiltDegrees(61.5f, 60)
        assertTrue("$larger should exceed $small", larger > small)
        assertEquals(small * 3f, larger, 0.01f)
    }

    @Test
    fun `it stops at the maximum however far off the singer is`() {
        assertEquals(GameTheme.arrowMaxTiltDegrees, tiltDegrees(90f, 60), 1e-4f)
        assertEquals(-GameTheme.arrowMaxTiltDegrees, tiltDegrees(20f, 60), 1e-4f)
    }

    @Test
    fun `full tilt is reached at the semitone distance it is named for`() {
        assertEquals(
            GameTheme.arrowMaxTiltDegrees,
            tiltDegrees(60f + GameTheme.arrowFullTiltSemitones, 60),
            1e-4f,
        )
    }

    /**
     * During a rest there is nothing to be off *from*, and a tilt held over from the last note
     * would be advice about a note that has already gone.
     */
    @Test
    fun `no note under the arrow means no tilt`() {
        assertEquals(0f, tiltDegrees(60f, null), 1e-4f)
    }

    @Test
    fun `silence has nothing to say either`() {
        assertEquals(0f, tiltDegrees(Float.NaN, 60), 1e-4f)
    }
}
