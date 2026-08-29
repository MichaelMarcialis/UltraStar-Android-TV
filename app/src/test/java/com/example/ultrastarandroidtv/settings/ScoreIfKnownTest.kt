package com.example.ultrastarandroidtv.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The rule that keeps a deleted singer's name off the song cards.
 *
 * `HighScores.forget` is what actually clears their records, and this is the guard in front of the
 * display for everything that got past it: records written before that existed, or a prefs file
 * carried over from an older build. Without it a name nobody can pick any more would sit on a
 * library card for ever, unbeatable by the person it belongs to and removable from nowhere.
 */
class ScoreIfKnownTest {

    private val score = HighScore(points = 8_000, name = "Mia")

    @Test
    fun `a record by somebody the app still knows is shown`() {
        assertEquals(score, scoreIfKnown(score, listOf("Ben", "Mia")))
    }

    @Test
    fun `a record by a deleted profile is not`() {
        assertNull(scoreIfKnown(score, listOf("Ben")))
        assertNull(scoreIfKnown(score, emptyList()))
    }

    @Test
    fun `no record stays no record`() {
        assertNull(scoreIfKnown(null, listOf("Mia")))
    }

    @Test
    fun `matching ignores case and spacing, the same way Profiles does`() {
        // `Profiles.use` folds "mia" onto an existing "Mia". If this compared differently, a name
        // in the wrong case would be a profile that exists and a record that claims it does not.
        assertEquals(score, scoreIfKnown(score, listOf("MIA")))
        val padded = HighScore(points = 1, name = " Mia ")
        assertEquals(padded, scoreIfKnown(padded, listOf("Mia")))
    }
}
