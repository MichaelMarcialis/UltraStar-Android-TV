package com.example.ultrastarandroidtv.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MicClaimTest {

    private val claim = MicClaim(minLevel = 0.06f, dominance = 2.0f, holdSeconds = 0.35)
    private val bothFree = booleanArrayOf(true, true)

    /** Feeds the same levels for [seconds], returning the first claim if one lands. */
    private fun hold(vararg levels: Float, seconds: Double, eligible: BooleanArray = bothFree): Int? {
        var t = 0.0
        var claimed: Int? = null
        while (t <= seconds) {
            claimed = claimed ?: claim.update(levels, eligible, t)
            t += 0.016
        }
        return claimed
    }

    @Test
    fun `singing into one mic claims it`() {
        assertEquals(0, hold(0.30f, 0.01f, seconds = 0.5))
    }

    @Test
    fun `a moment of noise is not a claim`() {
        // A cough or a chair should not commit somebody to a slot they then have to undo.
        assertNull(claim.update(floatArrayOf(0.30f, 0.01f), bothFree, 0.0))
        assertNull(claim.update(floatArrayOf(0.30f, 0.01f), bothFree, 0.1))
    }

    @Test
    fun `a quiet voice does not claim`() {
        // Same threshold gameplay scores with: a mic that can claim is a mic that can score.
        assertNull(hold(0.03f, 0.0f, seconds = 1.0))
    }

    @Test
    fun `the other mic hearing the same voice does not steal the slot`() {
        // Both mics hear the room. Without a margin, one singer could take both slots — which
        // is the entire reason the sensitivity setting exists.
        assertEquals(0, hold(0.30f, 0.12f, seconds = 0.6))
    }

    @Test
    fun `two mics hearing the same voice equally claims neither`() {
        // Somebody stood between them, or a song playing loudly. Better to wait than to guess.
        assertNull(hold(0.30f, 0.28f, seconds = 1.0))
    }

    @Test
    fun `the hold has to be one continuous voice`() {
        // Switching singers restarts the clock rather than crediting the accumulated time to
        // whoever happened to be loudest at the end.
        var t = 0.0
        repeat(15) {
            claim.update(floatArrayOf(0.30f, 0.01f), bothFree, t)
            t += 0.016
        }
        repeat(15) {
            assertNull(claim.update(floatArrayOf(0.01f, 0.30f), bothFree, t))
            t += 0.016
        }
    }

    @Test
    fun `a claimed mic cannot be claimed again`() {
        val onlySecondFree = booleanArrayOf(false, true)

        assertEquals(1, hold(0.10f, 0.40f, seconds = 0.6, eligible = onlySecondFree))
    }

    @Test
    fun `the first singer's voice cannot claim the second mic for them`() {
        // The margin has to be measured against every mic, not only the ones still free. Counting
        // just the free ones left the last slot with nothing to be louder than, so it went to
        // whoever was already singing — across the room, into a microphone they were not holding.
        val onlySecondFree = booleanArrayOf(false, true)

        assertNull(hold(0.40f, 0.10f, seconds = 1.0, eligible = onlySecondFree))
    }

    @Test
    fun `a single free mic needs no margin, only a voice`() {
        // There is nothing to be louder than.
        assertEquals(1, hold(0.0f, 0.08f, seconds = 0.6, eligible = booleanArrayOf(false, true)))
    }

    @Test
    fun `it reports when more than one mic hears a voice`() {
        // Both children singing at once is the commonest reason nothing happens, and the screen
        // has to be able to say so rather than looking broken.
        claim.update(floatArrayOf(0.30f, 0.28f), bothFree, 0.0)
        assertTrue(claim.contested)

        claim.update(floatArrayOf(0.30f, 0.01f), bothFree, 0.1)
        assertFalse(claim.contested)
    }

    @Test
    fun `one voice loud enough to reach both mics is not contested`() {
        // Crosstalk below the gate is the normal case, not a problem to announce.
        claim.update(floatArrayOf(0.30f, 0.04f), bothFree, 0.0)
        assertFalse(claim.contested)
    }

    @Test
    fun `it reports who is leading, and how far along`() {
        // The screen needs this to show something happening while the singer holds the note.
        claim.update(floatArrayOf(0.30f, 0.01f), bothFree, 0.0)
        assertEquals(0, claim.leading)
        assertEquals(0f, claim.progress(0.0), 1e-4f)

        claim.update(floatArrayOf(0.30f, 0.01f), bothFree, 0.175)
        assertEquals(0.5f, claim.progress(0.175), 0.05f)
    }

    @Test
    fun `silence clears the leader`() {
        claim.update(floatArrayOf(0.30f, 0.01f), bothFree, 0.0)
        claim.update(floatArrayOf(0.0f, 0.0f), bothFree, 0.05)

        assertEquals(-1, claim.leading)
        assertEquals(0f, claim.progress(0.05), 1e-4f)
    }

    @Test
    fun `reset forgets a hold in progress`() {
        claim.update(floatArrayOf(0.30f, 0.01f), bothFree, 0.0)
        claim.reset()

        assertNull(claim.update(floatArrayOf(0.30f, 0.01f), bothFree, 0.5))
        assertTrue(claim.progress(0.5) < 1f)
    }

    // -----------------------------------------------------------------------------------------

    /**
     * The one bar each microphone draws, and why it is one bar.
     *
     * The screen used to stack a level meter with a separate hold bar underneath, which asked the
     * room to read two moving things at once and work out which of them meant "keep going". This
     * is both of them on one scale: the lower half is getting loud enough, the upper half is
     * holding it.
     */
    @Test
    fun `a bar below the gate fills the lower half in proportion`() {
        assertEquals(0f, claim.claimProgress(0, 0f, 0.0), 0.001f)
        assertEquals(0.25f, claim.claimProgress(0, 0.03f, 0.0), 0.001f)
        assertEquals(0.5f, claim.claimProgress(0, 0.06f, 0.0), 0.001f)
        // Loud but not leading -- another mic is louder still -- stops at the middle rather than
        // creeping on towards a claim that is not going to happen.
        assertEquals(0.5f, claim.claimProgress(0, 0.9f, 0.0), 0.001f)
    }

    @Test
    fun `the halves join rather than jump`() {
        // Leading requires the level to have reached the gate, so the lower half is always full
        // by the time the upper half starts. The bar therefore passes through the middle once.
        claim.update(floatArrayOf(0.5f, 0.0f), booleanArrayOf(true, true), 0.0)

        assertEquals(0.5f, claim.claimProgress(0, 0.5f, 0.0), 0.001f)
        assertEquals(0.75f, claim.claimProgress(0, 0.5f, 0.175), 0.001f)
        assertEquals(1f, claim.claimProgress(0, 0.5f, 0.35), 0.001f)
    }
}
