package com.example.ultrastarandroidtv.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VocalBreaksTest {

    /** A phrase of four one-second notes starting at [from]. */
    private fun phrase(from: Double): List<VocalSpan> =
        (0 until 4).map { VocalSpan(from + it, from + it + 1.0) }

    @Test
    fun `stays visible right through a phrase`() {
        val breaks = VocalBreaks(phrase(20.0))

        for (t in 200..239) {
            assertEquals("at ${t / 10.0}s", 1f, breaks.hudAlpha(t / 10.0), 0.0001f)
        }
    }

    @Test
    fun `short gaps between phrases never move anything`() {
        // Six seconds apart: long enough that both fades would overlap it, which is exactly the
        // case that would dip halfway and come straight back if the minimum were not enforced.
        val breaks = VocalBreaks(phrase(20.0) + phrase(30.0))

        for (t in 200..339) {
            assertEquals("at ${t / 10.0}s", 1f, breaks.hudAlpha(t / 10.0), 0.0001f)
        }
    }

    @Test
    fun `hides through the middle of a long instrumental`() {
        val breaks = VocalBreaks(phrase(20.0) + phrase(60.0))

        assertEquals(1f, breaks.hudAlpha(24.0), 0.0001f) // last note still sounding
        assertEquals(1f, breaks.hudAlpha(25.4), 0.0001f) // held on afterwards
        assertEquals(0f, breaks.hudAlpha(30.0), 0.0001f) // gone
        assertEquals(0f, breaks.hudAlpha(50.0), 0.0001f)
        assertEquals(1f, breaks.hudAlpha(57.0), 0.0001f) // back, three seconds early
        assertEquals(1f, breaks.hudAlpha(60.0), 0.0001f)
    }

    @Test
    fun `fades rather than switching, at both ends`() {
        val breaks = VocalBreaks(phrase(20.0) + phrase(60.0))

        val leaving = breaks.hudAlpha(26.0)
        assertTrue("mid fade-out was $leaving", leaving > 0.05f && leaving < 0.95f)

        val returning = breaks.hudAlpha(56.5)
        assertTrue("mid fade-in was $returning", returning > 0.05f && returning < 0.95f)
    }

    @Test
    fun `never goes backwards while returning`() {
        val breaks = VocalBreaks(phrase(20.0) + phrase(60.0))

        var previous = 0f
        var t = 52.0
        while (t <= 57.0) {
            val alpha = breaks.hudAlpha(t)
            assertTrue("dipped at ${t}s: $previous then $alpha", alpha >= previous - 0.0001f)
            previous = alpha
            t += 0.05
        }
    }

    @Test
    fun `a long intro is a break like any other`() {
        // Nothing has been sung yet, so there is nothing to keep on screen. The game arrives
        // just before the first line instead of sitting over the introduction.
        val breaks = VocalBreaks(phrase(40.0))

        assertEquals(0f, breaks.hudAlpha(0.0), 0.0001f)
        assertEquals(0f, breaks.hudAlpha(30.0), 0.0001f)
        assertEquals(1f, breaks.hudAlpha(37.0), 0.0001f)
    }

    @Test
    fun `a song that starts singing immediately shows the game from the first frame`() {
        val breaks = VocalBreaks(phrase(1.0))

        assertEquals(1f, breaks.hudAlpha(0.0), 0.0001f)
    }

    @Test
    fun `stays hidden after the last note, since the vocals never come back`() {
        val breaks = VocalBreaks(phrase(20.0))

        assertEquals(0f, breaks.hudAlpha(40.0), 0.0001f)
        assertEquals(0f, breaks.hudAlpha(400.0), 0.0001f)
    }

    @Test
    fun `a long note in one part covers short rests in the other`() {
        // The gap between the first part's notes is thirty seconds, but the second part is
        // singing through all of it. Nobody should see the game leave.
        val held = listOf(VocalSpan(10.0, 11.0), VocalSpan(40.0, 41.0))
        val under = (0 until 30).map { VocalSpan(11.0 + it, 12.0 + it) }

        val breaks = VocalBreaks(held + under)

        for (t in 100..410) {
            assertEquals("at ${t / 10.0}s", 1f, breaks.hudAlpha(t / 10.0), 0.0001f)
        }
    }

    @Test
    fun `unsorted input is handled, since two parts interleave`() {
        val interleaved = listOf(
            VocalSpan(60.0, 61.0),
            VocalSpan(20.0, 21.0),
            VocalSpan(61.0, 62.0),
            VocalSpan(21.0, 22.0),
        )

        val breaks = VocalBreaks(interleaved)

        assertEquals(1f, breaks.hudAlpha(21.5), 0.0001f)
        assertEquals(0f, breaks.hudAlpha(40.0), 0.0001f)
        assertEquals(1f, breaks.hudAlpha(57.5), 0.0001f)
    }

    @Test
    fun `a song with no notes at all never hides the game`() {
        val breaks = VocalBreaks(emptyList())

        assertEquals(1f, breaks.hudAlpha(0.0), 0.0001f)
        assertEquals(1f, breaks.hudAlpha(120.0), 0.0001f)
    }

    @Test
    fun `the break threshold is what decides, not the fade lengths`() {
        val spans = phrase(20.0) + phrase(20.0 + 4.0 + 9.0)
        val strict = VocalBreaks(spans, minBreakSeconds = 20.0)
        val loose = VocalBreaks(spans, minBreakSeconds = 8.0)

        assertEquals(1f, strict.hudAlpha(29.0), 0.0001f)
        assertTrue(loose.hudAlpha(29.0) < 0.5f)
    }
}
