package com.example.ultrastarandroidtv.score

import com.example.ultrastarandroidtv.pitch.PitchReading
import com.example.ultrastarandroidtv.pitch.midiToHz
import com.example.ultrastarandroidtv.song.BeatTimeConverter
import com.example.ultrastarandroidtv.song.LyricLine
import com.example.ultrastarandroidtv.song.Note
import com.example.ultrastarandroidtv.song.NoteType
import com.example.ultrastarandroidtv.song.VoicePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private val BEATS = BeatTimeConverter(bpm = 240.0, gapMs = 0.0)
private const val HOP_SECONDS = 1024.0 / 48_000.0

/**
 * The breakdown that decides what to work on next.
 *
 * A missed beat is either one nothing was heard in — this app's problem, a latency one — or one
 * where a pitch arrived and was too far away, which is a difficulty judgement no latency work
 * will touch. These pin that the two are actually told apart.
 */
class ScoreDiagnosticsTest {

    private val part = VoicePart(
        label = null,
        lines = listOf(
            LyricLine(listOf(Note(NoteType.NORMAL, 0, 8, 0, "laa")), lineBreakBeat = null),
        ),
    )

    @Test
    fun `a silent singer's misses are all unheard`() {
        val breakdown = run { PitchReading.unvoiced(0.001f) }

        assertEquals(8, breakdown.beatsScored)
        assertEquals(0, breakdown.beatsHit)
        assertEquals(8, breakdown.missedUnheard)
        assertEquals(0, breakdown.missedOffPitch)
    }

    @Test
    fun `a singer who is heard but flat is off pitch, not unheard`() {
        val breakdown = run { reading(ultraStarPitchToMidi(0).toFloat() - 1.4f) }

        assertEquals(0, breakdown.beatsHit)
        assertEquals(0, breakdown.missedUnheard)
        assertEquals(8, breakdown.missedOffPitch)
        assertEquals(1.4f, breakdown.medianOffPitch, 0.05f)
    }

    /**
     * The number the whole thing exists for: how much a wider window would actually buy. A
     * singer 1.4 semitones out is caught by 1.5 and not by 1.25, and if that barely moves the
     * score then being more generous is only handing out points.
     */
    @Test
    fun `it says what a wider tolerance would have scored`() {
        val breakdown = run { reading(ultraStarPitchToMidi(0).toFloat() - 1.4f) }

        assertEquals(0.0, breakdown.accuracyAt(1.25f), 1e-9)
        assertEquals(1.0, breakdown.accuracyAt(1.5f), 1e-9)
        assertEquals(1.0, breakdown.accuracyAt(2f), 1e-9)
    }

    @Test
    fun `a perfect performance has nothing to explain`() {
        val breakdown = run { reading(ultraStarPitchToMidi(0).toFloat()) }

        assertEquals(8, breakdown.beatsHit)
        assertEquals(1.0, breakdown.accuracy, 1e-9)
        assertEquals(0, breakdown.missedUnheard)
        assertEquals(0, breakdown.missedOffPitch)
        assertTrue(breakdown.summary().contains("hit 8"))
    }

    /** Octave-agnostic scoring has to stay octave-agnostic here, or every child reads as flat. */
    @Test
    fun `an octave out is not counted as a miss`() {
        val breakdown = run { reading(ultraStarPitchToMidi(0).toFloat() + 12f) }

        assertEquals(8, breakdown.beatsHit)
        assertEquals(0, breakdown.missedOffPitch)
    }

    private fun run(singer: () -> PitchReading): MissBreakdown {
        val scorer = PlayerScorer(part, BEATS)
        var time = 0.0
        while (time <= BEATS.beatToSeconds(8) + 0.2) {
            scorer.update(time, singer())
            time += HOP_SECONDS
        }
        return missBreakdown(scorer.noteScores, ScoringConfig(), gate = 0.06f)
    }
}

private fun reading(midi: Float) = PitchReading(
    voiced = true,
    frequencyHz = midiToHz(midi.toDouble()).toFloat(),
    midi = midi,
    probability = 0.95f,
    level = 0.2f,
)
