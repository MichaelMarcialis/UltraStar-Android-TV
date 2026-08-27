package com.example.ultrastarandroidtv.game

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The octave the arrow is drawn in.
 *
 * Scoring compares pitch classes, so an octave-displaced note is a hit and the arrow has to be
 * folded into the drawn octave or it would sit off the bottom of the track while the score went
 * up. The fold is where the arrow's remaining jumpiness was hiding: it is a *step in the target*,
 * so no amount of easing removes it — easing only draws the teleport more slowly.
 */
class OctaveFoldTest {

    @Test
    fun `with no previous position the nearest octave wins outright`() {
        assertEquals(71f, foldToOctaveNear(59f, 72, Float.NaN), 1e-4f)
    }

    /**
     * The commonest flicker there is: a voice sitting near a tritone from the note wanders across
     * the boundary on ordinary wobble, and without a band the arrow teleports an octave and back
     * several times a second.
     */
    @Test
    fun `a voice wobbling across the tritone boundary stays in the octave it is in`() {
        val below = foldToOctaveNear(65.9f, 60, Float.NaN)
        assertEquals("starts in the octave nearest the note", 65.9f, below, 1e-4f)

        // Crossing the boundary would ordinarily fold it down to 54.1.
        assertEquals(66.1f, foldToOctaveNear(66.1f, 60, below), 1e-4f)
    }

    /**
     * The other half, and the one the singer feels most: a melody that leaps re-folds a
     * completely steady voice, so the arrow moves twelve semitones with nobody having sung
     * anything.
     */
    @Test
    fun `a leap in the melody does not move a steady voice`() {
        // Note goes C4 to G4; the singer holds C4 throughout.
        val onC = foldToOctaveNear(60f, 60, Float.NaN)
        assertEquals(60f, onC, 1e-4f)

        assertEquals("the voice has not moved, so nor should the arrow", 60f, foldToOctaveNear(60f, 67, onC), 1e-4f)
    }

    @Test
    fun `but a singer who really changes octave is followed`() {
        val low = foldToOctaveNear(60f, 60, Float.NaN)
        assertEquals(72f, foldToOctaveNear(72f, 72, low), 1e-4f)
    }

    /** The band is finite: past it the arrow belongs in the octave nearest the note. */
    @Test
    fun `the reluctance runs out`() {
        // Staying put would leave the arrow nine semitones from the note, past 6 + 1.5, so the
        // octave it is in is no longer a defensible reading of the voice and it folds up.
        assertEquals(75f, foldToOctaveNear(63f, 72, 63f), 1e-4f)
    }

    @Test
    fun `silence stays silence`() {
        assert(foldToOctaveNear(Float.NaN, 60, 60f).isNaN())
    }
}
