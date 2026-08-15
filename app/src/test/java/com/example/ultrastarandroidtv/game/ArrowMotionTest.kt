package com.example.ultrastarandroidtv.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArrowMotionTest {

    private val motion = ArrowMotion()

    @Test
    fun `silence hides the arrow rather than freezing it`() {
        // A frozen arrow says "singing this note"; there is no note. Not singing has to look
        // different from singing.
        motion.update(60f, 0.0)

        assertTrue(motion.update(Float.NaN, 0.016).isNaN())
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
