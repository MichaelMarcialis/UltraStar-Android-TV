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
        assertEquals(0, pitchClassDistance(60, 60))
        assertEquals(0, pitchClassDistance(60, 72))
        assertEquals(1, pitchClassDistance(71, 72)) // B to C, not eleven semitones.
        assertEquals(6, pitchClassDistance(60, 66)) // The farthest apart two notes can be.
        assertEquals(2, pitchClassDistance(58, 60))
    }

    @Test
    fun `hits ignore the octave`() {
        for (octave in -2..3) {
            val sung = (ultraStarPitchToMidi(4) + 12 * octave).toFloat()
            assertTrue("octave $octave", isPitchHit(sung, notePitch = 4, toleranceSemitones = 0))
        }
    }

    @Test
    fun `tolerance zero still allows ordinary drift`() {
        // Rounding to the nearest semitone means +-50 cents is inside "strict".
        assertTrue(isPitchHit(64.45f, notePitch = 4, toleranceSemitones = 0))
        assertTrue(isPitchHit(63.55f, notePitch = 4, toleranceSemitones = 0))
        assertFalse(isPitchHit(64.6f, notePitch = 4, toleranceSemitones = 0))
    }

    @Test
    fun `tolerance widens the window a semitone at a time`() {
        assertFalse(isPitchHit(65f, notePitch = 4, toleranceSemitones = 0))
        assertTrue(isPitchHit(65f, notePitch = 4, toleranceSemitones = 1))
        assertFalse(isPitchHit(66f, notePitch = 4, toleranceSemitones = 1))
        assertTrue(isPitchHit(66f, notePitch = 4, toleranceSemitones = 2))
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
