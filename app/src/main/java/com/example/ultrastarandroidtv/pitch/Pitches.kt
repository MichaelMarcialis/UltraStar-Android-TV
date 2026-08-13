package com.example.ultrastarandroidtv.pitch

import kotlin.math.ln
import kotlin.math.pow

/** MIDI note number of A4, the tuning reference. */
const val A4_MIDI: Int = 69

/** Frequency of A4 in Hz. Standard concert pitch; UltraStar songs assume it. */
const val A4_HZ: Double = 440.0

private val LN_2 = ln(2.0)

private val NOTE_NAMES =
    arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

/**
 * Fractional MIDI note number for [hz] — 69.0 is A440 exactly, 69.5 is half a semitone sharp.
 *
 * Fractional because scoring cares how far off the singer is, not just which key they landed
 * nearest. UltraStar note pitches are semitone values on this same scale, so once a song's
 * reference offset is applied the two are directly comparable.
 */
fun hzToMidi(hz: Double): Double = A4_MIDI + 12.0 * ln(hz / A4_HZ) / LN_2

fun midiToHz(midi: Double): Double = A4_HZ * 2.0.pow((midi - A4_MIDI) / 12.0)

/** Scientific pitch notation, e.g. `A4`, `C#3`. MIDI 60 is C4. */
fun midiNoteName(midi: Int): String =
    NOTE_NAMES[Math.floorMod(midi, 12)] + (Math.floorDiv(midi, 12) - 1)

/**
 * How far [midi] sits from the nearest semitone, in cents (hundredths of a semitone), so
 * always in `-50..50`. Negative is flat.
 */
fun centsOffPitch(midi: Double): Double = (midi - Math.round(midi)) * 100.0
