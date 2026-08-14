package com.example.ultrastarandroidtv.score

import com.example.ultrastarandroidtv.song.Note

/**
 * How one note is going, beat by beat.
 *
 * As well as the points, this keeps what was actually sung on each beat — the data an
 * UltraStar-style pitch bar draws as the singer's trace across the note's rectangle. It is
 * fractional MIDI rather than a hit flag so the trace can sit visibly sharp or flat inside the
 * note, and so an octave-displaced hit can be folded back into the note's octave for drawing.
 *
 * Filled in by [PlayerScorer]; read from anywhere.
 */
class NoteScore internal constructor(
    val note: Note,
    /** Index of the lyric line this note sits on, within its voice part. */
    val lineIndex: Int,
) {
    private val sung = FloatArray(note.durationBeats) { Float.NaN }

    /** Points this note is worth if every beat is hit. Zero for freestyle notes. */
    val maxPoints: Int = note.type.beatWeight * note.durationBeats

    /** Beats of this note evaluated so far — it grows as the note is sung through. */
    var beatsScored: Int = 0
        private set

    var beatsHit: Int = 0
        private set

    var earnedPoints: Int = 0
        private set

    /**
     * Pitch sung during beat [beatOffset] as a fractional MIDI number, or [Float.NaN] if
     * nothing was sung then — or if the beat has not been reached yet. Silence and a wrong
     * note both score zero but are not the same thing, and this is what tells them apart.
     */
    fun sungMidi(beatOffset: Int): Float = sung[beatOffset]

    internal fun record(beatOffset: Int, midi: Float, hit: Boolean, points: Int) {
        sung[beatOffset] = midi
        beatsScored++
        if (hit) {
            beatsHit++
            earnedPoints += points
        }
    }

    internal fun reset() {
        sung.fill(Float.NaN)
        beatsScored = 0
        beatsHit = 0
        earnedPoints = 0
    }
}
