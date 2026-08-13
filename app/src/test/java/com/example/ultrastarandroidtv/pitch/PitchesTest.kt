package com.example.ultrastarandroidtv.pitch

import org.junit.Assert.assertEquals
import org.junit.Test

class PitchesTest {

    @Test
    fun `maps the tuning reference and its octaves`() {
        assertEquals(69.0, hzToMidi(440.0), 1e-9)
        assertEquals(57.0, hzToMidi(220.0), 1e-9)
        assertEquals(81.0, hzToMidi(880.0), 1e-9)
    }

    @Test
    fun `middle C is MIDI 60`() {
        assertEquals(60.0, hzToMidi(261.6255653), 1e-6)
        assertEquals(261.6255653, midiToHz(60.0), 1e-6)
    }

    @Test
    fun `hz and midi round-trip`() {
        for (midi in 24..96) {
            assertEquals(midi.toDouble(), hzToMidi(midiToHz(midi.toDouble())), 1e-9)
        }
    }

    @Test
    fun `names notes in scientific pitch notation`() {
        assertEquals("C4", midiNoteName(60))
        assertEquals("A4", midiNoteName(69))
        assertEquals("C#3", midiNoteName(49))
        assertEquals("B3", midiNoteName(59))
        // Below MIDI 12 the octave goes negative; floorDiv keeps it from truncating to C0.
        assertEquals("C-1", midiNoteName(0))
    }

    @Test
    fun `reports cents off the nearest semitone`() {
        assertEquals(0.0, centsOffPitch(69.0), 1e-9)
        assertEquals(25.0, centsOffPitch(69.25), 1e-9)
        assertEquals(-25.0, centsOffPitch(68.75), 1e-9)
    }
}
