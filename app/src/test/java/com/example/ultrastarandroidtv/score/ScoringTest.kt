package com.example.ultrastarandroidtv.score

import com.example.ultrastarandroidtv.pitch.midiNoteName
import com.example.ultrastarandroidtv.song.NoteType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScoringTest {

    @Test
    fun `pitch zero is middle C`() {
        assertEquals(60, ultraStarPitchToMidi(0))
        assertEquals("C4", midiNoteName(ultraStarPitchToMidi(0)))
        assertEquals("C5", midiNoteName(ultraStarPitchToMidi(12)))
        assertEquals("A3", midiNoteName(ultraStarPitchToMidi(-3)))
    }

    @Test
    fun `the pitch range real songs use lands on a singable range`() {
        // The sanity check behind the offset: songs write pitches around -20..20, which has to
        // come out as notes a person can actually sing.
        assertEquals("E2", midiNoteName(ultraStarPitchToMidi(-20)))
        assertEquals("G#5", midiNoteName(ultraStarPitchToMidi(20)))
    }

    @Test
    fun `pitch class distance goes the short way round`() {
        assertEquals(0f, pitchClassDistance(60f, 60f), 1e-4f)
        assertEquals(0f, pitchClassDistance(60f, 72f), 1e-4f)
        assertEquals(1f, pitchClassDistance(71f, 72f), 1e-4f) // B to C, not eleven semitones.
        assertEquals(6f, pitchClassDistance(60f, 66f), 1e-4f) // The farthest apart two notes can be.
        assertEquals(2f, pitchClassDistance(58f, 60f), 1e-4f)
    }

    @Test
    fun `pitch class distance is measured in fractions of a semitone`() {
        // Rounding to whole semitones first is what let a singer sit half a semitone outside
        // the drawn note and still be credited for it.
        assertEquals(0.4f, pitchClassDistance(60.4f, 60f), 1e-4f)
        assertEquals(0.5f, pitchClassDistance(71.5f, 72f), 1e-4f)
    }

    @Test
    fun `hits ignore the octave`() {
        for (octave in -2..3) {
            val sung = (ultraStarPitchToMidi(4) + 12 * octave).toFloat()
            assertTrue("octave $octave", isPitchHit(sung, notePitch = 4, toleranceSemitones = 0f))
        }
    }

    @Test
    fun `the window is exactly the tolerance, with nothing added behind the scenes`() {
        // This is the promise the note bar's drawn height depends on: what is inside the bar
        // scores, what is outside does not, with no hidden half-semitone either side.
        assertTrue(isPitchHit(65f, notePitch = 4, toleranceSemitones = 1f))
        assertTrue(isPitchHit(63f, notePitch = 4, toleranceSemitones = 1f))
        assertFalse(isPitchHit(65.01f, notePitch = 4, toleranceSemitones = 1f))
        assertFalse(isPitchHit(62.99f, notePitch = 4, toleranceSemitones = 1f))
    }

    @Test
    fun `tolerance widens the window a semitone at a time`() {
        assertFalse(isPitchHit(65f, notePitch = 4, toleranceSemitones = 0f))
        assertTrue(isPitchHit(65f, notePitch = 4, toleranceSemitones = 1f))
        assertFalse(isPitchHit(66f, notePitch = 4, toleranceSemitones = 1f))
        assertTrue(isPitchHit(66f, notePitch = 4, toleranceSemitones = 2f))
    }

    @Test
    fun `a half-semitone tolerance is half a semitone, not a whole one`() {
        assertTrue(isPitchHit(64.5f, notePitch = 4, toleranceSemitones = 0.5f))
        assertFalse(isPitchHit(64.6f, notePitch = 4, toleranceSemitones = 0.5f))
    }

    @Test
    fun `golden notes are worth double and freestyle nothing`() {
        assertEquals(1, NoteType.NORMAL.beatWeight)
        assertEquals(1, NoteType.RAP.beatWeight)
        assertEquals(2, NoteType.GOLDEN.beatWeight)
        assertEquals(2, NoteType.GOLDEN_RAP.beatWeight)
        assertEquals(0, NoteType.FREESTYLE.beatWeight)
    }

    @Test
    fun `only rap notes ignore pitch`() {
        assertTrue(NoteType.RAP.ignoresPitch)
        assertTrue(NoteType.GOLDEN_RAP.ignoresPitch)
        assertFalse(NoteType.NORMAL.ignoresPitch)
        assertFalse(NoteType.GOLDEN.ignoresPitch)
        assertFalse(NoteType.FREESTYLE.ignoresPitch)
    }
}
