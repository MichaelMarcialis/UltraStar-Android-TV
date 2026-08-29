package com.example.ultrastarandroidtv.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DifficultyTest {

    @Test
    fun `a stored name comes back as itself`() {
        for (difficulty in Difficulty.entries) {
            assertEquals(difficulty, Difficulty.byName(difficulty.name))
        }
    }

    @Test
    fun `an unknown or missing name falls back to the default`() {
        // A build that renamed one of these, or a first run with nothing stored. Neither is a
        // reason to refuse to start a song.
        assertEquals(Difficulty.DEFAULT, Difficulty.byName(null))
        assertEquals(Difficulty.DEFAULT, Difficulty.byName("MEDIUM"))
        assertEquals(Difficulty.DEFAULT, Difficulty.byName(""))
    }

    @Test
    fun `the default is normal, which is easier than the game used to be`() {
        assertEquals(Difficulty.NORMAL, Difficulty.DEFAULT)
        // 1.0 was the shipped constant before there was a setting at all, and Hard is what that
        // became — so nobody who liked it has lost it.
        assertEquals(1.0f, Difficulty.HARD.toleranceSemitones, 1e-6f)
        assertTrue(Difficulty.NORMAL.toleranceSemitones > Difficulty.HARD.toleranceSemitones)
    }

    @Test
    fun `they are declared easiest first, because the settings dial indexes by ordinal`() {
        // The setting row steps left and right through `entries` by ordinal and draws a bar that
        // fills as the value rises. Declaring these in any other order would have the bar filling
        // as the game got *easier*, which is the wrong way round for a difficulty.
        val tolerances = Difficulty.entries.map { it.toleranceSemitones }
        assertEquals(tolerances.sortedDescending(), tolerances)
    }

    @Test
    fun `even the easiest setting still refuses most of the octave`() {
        // Pitch classes are never more than six semitones apart, so a tolerance of six would
        // score literally any note. Anything approaching that stops being forgiving and starts
        // paying for wrong notes.
        assertTrue(Difficulty.EASY.toleranceSemitones <= 2f)
    }
}
