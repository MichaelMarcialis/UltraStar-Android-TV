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
    private val hits = BooleanArray(note.durationBeats)
    private val levels = FloatArray(note.durationBeats) { Float.NaN }
    private val probabilities = FloatArray(note.durationBeats) { Float.NaN }

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

    /**
     * Whether beat [beatOffset] counted as a hit — false for beats not yet reached.
     *
     * Kept rather than re-derived so the pitch bar lights up exactly the beats that were paid
     * for. Working it out again at draw time from [sungMidi] would mean two copies of the
     * tolerance rule, and the day they disagreed the bar would quietly start lying about the
     * score sitting next to it.
     */
    fun wasHit(beatOffset: Int): Boolean = hits[beatOffset]

    /**
     * How loud the beat was, or NaN if no reading covered it at all.
     *
     * Only ever read afterwards, to answer *why* a beat was missed: too quiet for the gate, or
     * loud enough but with no pitch the detector would commit to. Those two want opposite fixes
     * and are indistinguishable once the beat is recorded as simply unsung.
     */
    fun levelAt(beatOffset: Int): Float = levels[beatOffset]

    /** How near the detector came to committing to a pitch here, 0..1. */
    fun probabilityAt(beatOffset: Int): Float = probabilities[beatOffset]

    internal fun record(
        beatOffset: Int,
        midi: Float,
        hit: Boolean,
        points: Int,
        level: Float,
        probability: Float,
    ) {
        sung[beatOffset] = midi
        hits[beatOffset] = hit
        levels[beatOffset] = level
        probabilities[beatOffset] = probability
        beatsScored++
        if (hit) {
            beatsHit++
            earnedPoints += points
        }
    }

    /**
     * Credits a beat that was already scored as missed, because the singer landed this note
     * within the onset grace — see [com.example.ultrastarandroidtv.score.ScoringConfig].
     *
     * [beatsScored] deliberately does not move: the beat was counted the first time round, and
     * counting it twice would make the song's maximum depend on how it was sung. What was
     * *sung* during the beat is left exactly as it was recorded, because that is the honest
     * record of the voice — a back-filled beat is one the singer is not being charged for, not
     * one they are pretended to have sung.
     *
     * @return whether this actually changed anything.
     */
    internal fun creditOnset(beatOffset: Int, points: Int): Boolean {
        if (hits[beatOffset]) return false
        hits[beatOffset] = true
        beatsHit++
        earnedPoints += points
        return true
    }

    internal fun reset() {
        sung.fill(Float.NaN)
        hits.fill(false)
        levels.fill(Float.NaN)
        probabilities.fill(Float.NaN)
        beatsScored = 0
        beatsHit = 0
        earnedPoints = 0
    }
}
