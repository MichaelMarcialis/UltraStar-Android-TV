package com.example.ultrastarandroidtv.score

import com.example.ultrastarandroidtv.pitch.PitchReading
import com.example.ultrastarandroidtv.pitch.midiToHz
import com.example.ultrastarandroidtv.song.BeatTimeConverter
import com.example.ultrastarandroidtv.song.LyricLine
import com.example.ultrastarandroidtv.song.Note
import com.example.ultrastarandroidtv.song.NoteType
import com.example.ultrastarandroidtv.song.VoicePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #BPM:240 — 62.5 ms a beat, so the 65 ms grace reaches beat 1 and stops short of beat 2. */
private const val BPM = 240.0
private val BEATS = BeatTimeConverter(bpm = BPM, gapMs = 0.0)
private const val BEAT_SECONDS = 60.0 / (BPM * 4.0)
private const val HOP_SECONDS = 1024.0 / 48_000.0

/**
 * Start times whose first *scored* beat is the one named.
 *
 * Not the same as the moment that beat begins: a beat is judged by the reading nearest its
 * midpoint and readings land every 21 ms, so the singer has to be voiced a little before the
 * midpoint for that beat to count.
 */
private const val LANDS_ON_BEAT_1 = 0.08
private const val LANDS_ON_BEAT_2 = 0.14
private const val LANDS_ON_BEAT_3 = 0.21

/**
 * The run-up to a note.
 *
 * A note takes real time to become a pitch — a voice has an attack before it has a fundamental,
 * singers slide into notes, and the detector needs a window of audio before it will name one.
 * The part of that which this app is responsible for was measured at 65 ms worst case, and it
 * scored as silence, which is what made a phrase's opening note appear to register halfway
 * through.
 *
 * These pin the deal that fixes it: land the note about on time and the run-up is free, turn up
 * genuinely late and it is not, and never get more than half a note for free.
 */
class OnsetGraceTest {

    @Test
    fun `landing the note inside the grace pays for the run-up`() {
        val scorer = sing(note(beats = 8), silentUntil = LANDS_ON_BEAT_1)

        assertEquals("every beat of the note should end up credited", 8, scorer.snapshot().beatsHit)
        assertTrue(scorer.noteScores[0].wasHit(0))
    }

    /** 125 ms in is past the 65 ms this app owes, so it is the singer who was late. */
    @Test
    fun `landing just outside the grace pays for nothing`() {
        val scorer = sing(note(beats = 8), silentUntil = LANDS_ON_BEAT_2)

        assertEquals(6, scorer.snapshot().beatsHit)
        assertFalse(scorer.noteScores[0].wasHit(0))
        assertFalse(scorer.noteScores[0].wasHit(1))
    }

    @Test
    fun `turning up very late pays for nothing`() {
        val scorer = sing(note(beats = 8), silentUntil = 4.4 * BEAT_SECONDS)

        val hit = scorer.snapshot().beatsHit
        assertTrue("expected only the sung beats, got $hit", hit in 3..4)
        assertFalse("the beats before a late entry stay lost", scorer.noteScores[0].wasHit(0))
    }

    // -------------------------------------------------------------------------------------
    // You always have to sing at least half a note
    // -------------------------------------------------------------------------------------

    /**
     * The cap earns its place as soon as the grace is raised: a third of the notes in a real
     * library are one or two beats long, so a generous window swallows a whole note and catching
     * only its last beat would hand over all of it.
     */
    @Test
    fun `a generous grace still cannot buy more than half a note`() {
        val generous = ScoringConfig(onsetGraceSeconds = 0.2)
        val scorer = sing(note(beats = 4), silentUntil = LANDS_ON_BEAT_3, config = generous)

        assertEquals("only the beat actually sung", 1, scorer.snapshot().beatsHit)
        assertFalse(scorer.noteScores[0].wasHit(0))
    }

    /** The same performance with the cap lifted, so the cap is shown to be what stopped it. */
    @Test
    fun `without the cap that same performance would take the whole note`() {
        val uncapped = ScoringConfig(onsetGraceSeconds = 0.2, maxGraceShare = 1.0)
        val scorer = sing(note(beats = 4), silentUntil = LANDS_ON_BEAT_3, config = uncapped)

        assertEquals(4, scorer.snapshot().beatsHit)
    }

    // -------------------------------------------------------------------------------------

    /**
     * The load-bearing property. The grace hands back beats that were already counted once, so
     * if it ever counted them twice a song's maximum would depend on how it was sung — and the
     * whole per-beat scoring design exists to stop that.
     */
    @Test
    fun `the song is worth the same however it is sung`() {
        val part = note(beats = 8)
        val performances = listOf(
            sing(part, silentUntil = 0.0),
            sing(part, silentUntil = LANDS_ON_BEAT_1),
            sing(part, silentUntil = LANDS_ON_BEAT_2),
            sing(part, silentUntil = Double.MAX_VALUE),
        )

        for (scorer in performances) assertEquals(8, scorer.snapshot().beatsScored)
        assertEquals(MAX_SCORE, performances.first().snapshot().total)
        assertEquals(0, performances.last().snapshot().beatsHit)
    }

    /**
     * Grace is for the run-up and nothing else. A singer who lands a note and then drops out of
     * it mid-way is not being caught out by the detector — they stopped singing.
     */
    @Test
    fun `dropping out in the middle of a note is not refunded`() {
        val part = note(beats = 8)
        val scorer = PlayerScorer(part, BEATS)
        val gap = 2.5 * BEAT_SECONDS..5.5 * BEAT_SECONDS
        play(scorer, part) { time -> if (time in gap) PitchReading.unvoiced(0.001f) else onPitch() }

        val hit = scorer.snapshot().beatsHit
        assertTrue("only the beats actually sung, got $hit", hit in 4..6)
        assertFalse("the hole in the middle stays a hole", scorer.noteScores[0].wasHit(4))
    }

    @Test
    fun `a zero grace restores the old behaviour exactly`() {
        val strict = ScoringConfig(onsetGraceSeconds = 0.0)
        val scorer = sing(note(beats = 8), silentUntil = LANDS_ON_BEAT_1, config = strict)

        assertFalse(scorer.noteScores[0].wasHit(0))
        assertEquals(7, scorer.snapshot().beatsHit)
    }

    /** Grace cannot invent a hit: it only ever gives back beats before one that really happened. */
    @Test
    fun `singing the wrong note throughout is credited with nothing`() {
        val part = note(beats = 8)
        val scorer = PlayerScorer(part, BEATS)
        play(scorer, part) { reading(ultraStarPitchToMidi(0).toFloat() + 5f) }

        assertEquals(0, scorer.snapshot().beatsHit)
        assertEquals(8, scorer.snapshot().beatsScored)
    }

    // -------------------------------------------------------------------------------------

    private fun note(beats: Int) = VoicePart(
        label = null,
        lines = listOf(
            LyricLine(listOf(Note(NoteType.NORMAL, 0, beats, 0, "laaa")), lineBreakBeat = null),
        ),
    )

    private fun sing(
        part: VoicePart,
        silentUntil: Double,
        config: ScoringConfig = ScoringConfig(),
    ): PlayerScorer {
        val scorer = PlayerScorer(part, BEATS, config)
        play(scorer, part) { time ->
            if (time < silentUntil) PitchReading.unvoiced(0.001f) else onPitch()
        }
        return scorer
    }

    private fun play(scorer: PlayerScorer, part: VoicePart, singer: (Double) -> PitchReading) {
        val lastBeat = part.lines.flatMap { it.notes }.maxOf { it.startBeat + it.durationBeats }
        val end = BEATS.beatToSeconds(lastBeat) + 0.2
        var time = 0.0
        while (time <= end) {
            scorer.update(time, singer(time))
            time += HOP_SECONDS
        }
    }

    private fun onPitch() = reading(ultraStarPitchToMidi(0).toFloat())
}

private fun reading(midi: Float) = PitchReading(
    voiced = true,
    frequencyHz = midiToHz(midi.toDouble()).toFloat(),
    midi = midi,
    probability = 0.95f,
    level = 0.2f,
)
