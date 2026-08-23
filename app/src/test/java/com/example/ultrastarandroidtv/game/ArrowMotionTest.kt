package com.example.ultrastarandroidtv.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArrowMotionTest {

    /**
     * The smoothing is off by default now, so everything below that is *about* smoothing has
     * to ask for it. The behaviour is still supported and still worth pinning: it was removed
     * because it put the arrow behind the score, not because it was wrong in itself.
     */
    private val motion = ArrowMotion(secondsToSettle = 0.03, fadeSeconds = 0.12)

    @Test
    fun `the arrow fades in rather than appearing`() {
        // A voice stops and starts constantly — between syllables, between breaths — and an
        // arrow that blinks on every one of those is exhausting to watch.
        var t = 0.0
        motion.update(60f, t)
        repeat(4) {
            t += 0.016
            motion.update(60f, t)
        }

        assertTrue("should have started to appear", motion.alpha > 0f)
        assertTrue("but not be fully there yet", motion.alpha < 0.95f)
    }

    @Test
    fun `silence fades the arrow out rather than cutting it`() {
        var t = 0.0
        repeat(40) {
            t += 0.016
            motion.update(60f, t)
        }
        val singing = motion.alpha
        assertTrue("should be solid while singing", singing > 0.8f)

        repeat(3) {
            t += 0.016
            motion.update(Float.NaN, t)
        }
        assertTrue("should be fading", motion.alpha < singing)
        assertTrue("but not gone in three frames", motion.alpha > 0.2f)

        repeat(60) {
            t += 0.016
            motion.update(Float.NaN, t)
        }
        assertFalse("eventually gone entirely", motion.isVisible)
    }

    @Test
    fun `the arrow holds its place while it fades out`() {
        // Darting somewhere neutral on the way out would draw a movement nobody sang.
        var t = 0.0
        repeat(20) {
            t += 0.016
            motion.update(60f, t)
        }

        assertEquals(60f, motion.update(Float.NaN, t + 0.016), 0.5f)
    }

    @Test
    fun `the first note is snapped to`() {
        // There is nowhere to slide from, and sliding in from a default would claim the singer
        // sang something they did not.
        assertEquals(60f, motion.update(60f, 0.0), 1e-4f)
    }

    @Test
    fun `moving pitch slides rather than jumping`() {
        motion.update(60f, 0.0)

        val next = motion.update(67f, 0.016)

        assertTrue("it should have moved", next > 60f)
        assertTrue("but not arrived in one frame", next < 67f)
    }

    @Test
    fun `it keeps up with a singer, quickly`() {
        // This is an instrument, not a decoration: a singer correcting their pitch has to see
        // the arrow agree within a few frames, or the feedback is useless.
        motion.update(60f, 0.0)

        var t = 0.0
        repeat(10) {
            t += 0.016
            motion.update(67f, t)
        }

        assertEquals(67f, motion.update(67f, t + 0.016), 0.3f)
    }

    @Test
    fun `a breath mid-phrase does not cost the arrow its place`() {
        // Voicing flickers between syllables. Snapping on every flicker would make the arrow
        // twitch exactly when the singer is doing nothing wrong.
        motion.update(60f, 0.0)
        motion.update(Float.NaN, 0.05)

        val back = motion.update(67f, 0.10)

        assertTrue("it should ease, not snap", back < 67f)
        assertTrue(back > 60f)
    }

    @Test
    fun `a real silence snaps, because the singer has moved on`() {
        // Sliding across the whole track to reach a note sung after a long rest would draw a
        // glide nobody sang.
        motion.update(60f, 0.0)
        motion.update(Float.NaN, 0.5)

        assertEquals(72f, motion.update(72f, 1.0), 1e-4f)
    }

    @Test
    fun `a stalled frame still moves by a bounded step`() {
        motion.update(60f, 0.0)

        // A late frame must not let the arrow lurch further than a plausible frame would carry
        // it, or a hitch in rendering shows up as the singer's pitch leaping.
        val next = motion.update(72f, 0.3)

        assertTrue(next > 60f)
        assertTrue("should not have arrived", next < 71.5f)
    }

    @Test
    fun `a long gap between readings snaps, because the old position means nothing`() {
        // Thirty seconds without a reading is indistinguishable from thirty seconds of silence:
        // wherever the arrow was, the singer is not there now, and gliding to the new note
        // would draw a slide that never happened.
        motion.update(60f, 0.0)

        assertEquals(72f, motion.update(72f, 30.0), 1e-4f)
    }

    @Test
    fun `time running backwards is ignored rather than rewinding the ease`() {
        motion.update(60f, 5.0)
        val held = motion.update(72f, 4.0)

        assertEquals(60f, held, 1e-4f)
    }

    @Test
    fun `after a reset the next note is snapped to again`() {
        motion.update(60f, 0.0)
        motion.reset()

        assertEquals(72f, motion.update(72f, 0.1), 1e-4f)
    }
}
