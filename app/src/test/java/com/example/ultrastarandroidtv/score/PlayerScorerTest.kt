package com.example.ultrastarandroidtv.score

import com.example.ultrastarandroidtv.pitch.PitchReading
import com.example.ultrastarandroidtv.pitch.midiToHz
import com.example.ultrastarandroidtv.song.BeatTimeConverter
import com.example.ultrastarandroidtv.song.LyricLine
import com.example.ultrastarandroidtv.song.Note
import com.example.ultrastarandroidtv.song.NoteType
import com.example.ultrastarandroidtv.song.VoicePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #BPM:240 — 0.0625 s per beat unit, an ordinary song tempo. */
private const val BPM = 240.0

private val BEATS = BeatTimeConverter(bpm = BPM, gapMs = 0.0)

/** What `PitchTracker` runs at on the Shield: 2048-sample windows sliding by 1024 at 48 kHz. */
private const val HOP_SECONDS = 1024.0 / 48_000.0

class PlayerScorerTest {

    /**
     * Two lines of two eight-beat notes — 32 scorable beats.
     *
     * No two pitches here are a whole number of octaves apart, or within the default tolerance
     * of each other. Scoring is octave-agnostic, so `0` and `12` would be the same note as far
     * as it is concerned, and a fixture using both could not tell a hit from a mix-up.
     */
    private val twoLines = VoicePart(
        label = null,
        lines = listOf(
            LyricLine(listOf(note(0, 8, 0), note(8, 8, 4)), lineBreakBeat = 16),
            LyricLine(listOf(note(16, 8, 7), note(24, 8, 9)), lineBreakBeat = null),
        ),
    )

    @Test
    fun `a perfect performance scores the full 10000`() {
        val scorer = PlayerScorer(twoLines, BEATS)

        scorer.sing(twoLines)

        val score = scorer.snapshot()
        assertEquals(MAX_SCORE, score.total)
        assertEquals(MAX_SCORE - MAX_LINE_BONUS, score.notePoints)
        assertEquals(MAX_LINE_BONUS, score.lineBonus)
        assertEquals(1.0, score.accuracy, 1e-9)
        assertEquals(32, score.beatsScored)
    }

    @Test
    fun `singing an octave out still scores full marks`() {
        // The point of pitch-class scoring: a child an octave above the recording, or an adult
        // an octave below it, is singing the song correctly.
        for (octaves in listOf(-2, -1, 1, 2)) {
            val scorer = PlayerScorer(twoLines, BEATS)

            scorer.sing(twoLines, singer = onPitch(twoLines, offsetSemitones = 12 * octaves))

            assertEquals("$octaves octaves out", MAX_SCORE, scorer.snapshot().total)
        }
    }

    @Test
    fun `a semitone off is forgiven but three semitones is not`() {
        val forgiven = PlayerScorer(twoLines, BEATS)
        forgiven.sing(twoLines, singer = onPitch(twoLines, offsetSemitones = 1))
        assertEquals(MAX_SCORE, forgiven.snapshot().total)

        val wrong = PlayerScorer(twoLines, BEATS)
        wrong.sing(twoLines, singer = onPitch(twoLines, offsetSemitones = 3))
        assertEquals(0, wrong.snapshot().total)
    }

    @Test
    fun `strict scoring rejects the semitone a default game forgives`() {
        val scorer = PlayerScorer(twoLines, BEATS, ScoringConfig(toleranceSemitones = 0f))

        scorer.sing(twoLines, singer = onPitch(twoLines, offsetSemitones = 1))

        assertEquals(0, scorer.snapshot().total)
    }

    @Test
    fun `silence scores nothing and leaves no trace`() {
        val scorer = PlayerScorer(twoLines, BEATS)

        scorer.sing(twoLines) { PitchReading.unvoiced(0.001f) }

        val score = scorer.snapshot()
        assertEquals(0, score.total)
        assertEquals(0, score.beatsHit)
        assertEquals(32, score.beatsScored) // Every beat was judged; none was sung.
        for (noteScore in scorer.noteScores) {
            for (beat in 0 until noteScore.note.durationBeats) {
                assertTrue(noteScore.sungMidi(beat).isNaN())
            }
        }
    }

    @Test
    fun `a wrong note is recorded differently from silence`() {
        // Both score zero, but the pitch bar has to draw one of them and not the other.
        val scorer = PlayerScorer(twoLines, BEATS)

        scorer.sing(twoLines, singer = onPitch(twoLines, offsetSemitones = 5))

        assertEquals(0, scorer.snapshot().beatsHit)
        assertEquals(65f, scorer.noteScores.first().sungMidi(4), 1e-3f)
    }

    @Test
    fun `golden notes are worth twice a normal note`() {
        val part = VoicePart(
            null,
            listOf(
                LyricLine(
                    listOf(note(0, 8, 0), note(8, 8, 4, NoteType.GOLDEN)),
                    lineBreakBeat = null,
                ),
            ),
        )
        val scorer = PlayerScorer(part, BEATS)

        // Hold the golden note's pitch throughout, missing the normal note entirely.
        scorer.sing(part) { reading(ultraStarPitchToMidi(4).toFloat()) }

        // 8 golden beats at weight 2, out of a maximum of 8*1 + 8*2 = 24.
        val score = scorer.snapshot()
        assertEquals(3000, score.notePoints)
        assertEquals(3000, score.goldenPoints)
        assertEquals(667, score.lineBonus)
        assertEquals(6667, score.total)
    }

    @Test
    fun `freestyle notes neither earn points nor dilute the song`() {
        val withFreestyle = VoicePart(
            null,
            listOf(
                LyricLine(
                    listOf(note(0, 8, 0), note(8, 8, 4, NoteType.FREESTYLE)),
                    lineBreakBeat = null,
                ),
            ),
        )
        val scorer = PlayerScorer(withFreestyle, BEATS)

        // Sing the scored note correctly and something unrelated over the freestyle one.
        scorer.sing(withFreestyle) { time ->
            val note = withFreestyle.noteAt(time)
            when {
                note == null -> PitchReading.unvoiced(0.001f)
                note.type == NoteType.FREESTYLE -> reading(50f)
                else -> reading(ultraStarPitchToMidi(note.pitch).toFloat())
            }
        }

        val score = scorer.snapshot()
        assertEquals(MAX_SCORE, score.total)
        assertEquals(8, score.beatsScored) // The freestyle note's 8 beats are not scorable.

        val freestyle = scorer.noteScores.last()
        assertEquals(0, freestyle.maxPoints)
        assertEquals(50f, freestyle.sungMidi(4), 1e-3f) // Still drawn, though.
    }

    @Test
    fun `rap notes score any pitch but still need singing`() {
        val rap = VoicePart(
            null,
            listOf(LyricLine(listOf(note(0, 8, 0, NoteType.RAP)), lineBreakBeat = null)),
        )

        val spoken = PlayerScorer(rap, BEATS)
        spoken.sing(rap) { reading(52f) } // Nowhere near pitch 0.
        assertEquals(MAX_SCORE, spoken.snapshot().total)

        val silent = PlayerScorer(rap, BEATS)
        silent.sing(rap) { PitchReading.unvoiced(0.001f) }
        assertEquals(0, silent.snapshot().total)
    }

    @Test
    fun `the line bonus is shared evenly between lines`() {
        val scorer = PlayerScorer(twoLines, BEATS)

        // Hold the opening pitch all the way through: the first note matches, nothing else does.
        scorer.sing(twoLines) { reading(ultraStarPitchToMidi(0).toFloat()) }

        val score = scorer.snapshot()
        assertEquals(8, score.beatsHit)
        assertEquals(2250, score.notePoints) // 9000 * 8/32.
        assertEquals(250, score.lineBonus) // Half of line one, none of line two.
    }

    @Test
    fun `the score does not depend on how often readings arrive`() {
        val totals = listOf(HOP_SECONDS / 2, HOP_SECONDS, HOP_SECONDS * 2).map { hop ->
            val scorer = PlayerScorer(twoLines, BEATS)
            scorer.sing(twoLines, hopSeconds = hop)
            scorer.snapshot().total
        }

        assertEquals(listOf(MAX_SCORE, MAX_SCORE, MAX_SCORE), totals)
    }

    @Test
    fun `beats lost to a capture dropout are not scored from stale audio`() {
        // One note, held perfectly on pitch throughout, with the USB stream stalling in the
        // middle of it. Holding the last good reading across the stall would score every beat
        // as a hit — from audio that was never captured. Beats the stall swallowed have to be
        // lost, and only those: the beats either side of it are covered by real readings.
        val oneNote = VoicePart(
            null,
            listOf(LyricLine(listOf(note(0, 8, 0)), lineBreakBeat = null)),
        )
        val scorer = PlayerScorer(oneNote, BEATS)
        val singer = onPitch(oneNote)

        var time = 0.0
        while (time <= BEATS.beatToSeconds(8) + 0.2) {
            if (time < 0.1 || time > 0.45) scorer.update(time, singer(time))
            time += HOP_SECONDS
        }

        val score = scorer.snapshot()
        assertEquals(8, score.beatsScored)
        assertEquals(5, score.beatsHit)
        assertTrue(score.total < MAX_SCORE)

        // The lost beats leave no trace, exactly as silence would — nothing was heard.
        assertTrue(scorer.noteScores.first().sungMidi(4).isNaN())
    }

    @Test
    fun `notes are handed over in order as the song plays`() {
        val scorer = PlayerScorer(twoLines, BEATS)
        val singer = onPitch(twoLines)
        val seen = mutableListOf<Int>()

        var time = 0.0
        while (time <= BEATS.beatToSeconds(32) + 0.2) {
            scorer.update(time, singer(time))
            scorer.activeNote?.note?.pitch?.let { if (seen.lastOrNull() != it) seen.add(it) }
            time += HOP_SECONDS
        }

        assertEquals(listOf(0, 4, 7, 9), seen)
        assertNull("the part is finished", scorer.activeNote)
    }

    @Test
    fun `reset clears the score and the trace`() {
        val scorer = PlayerScorer(twoLines, BEATS)
        scorer.sing(twoLines)
        assertNotEquals(0, scorer.snapshot().total)

        scorer.reset()

        assertEquals(ScoreSnapshot.EMPTY, scorer.snapshot())
        assertTrue(scorer.noteScores.first().sungMidi(0).isNaN())
        assertEquals(0, scorer.noteScores.first().beatsScored)

        // And it can be sung again from the top.
        scorer.sing(twoLines)
        assertEquals(MAX_SCORE, scorer.snapshot().total)
    }

    @Test
    fun `a song of nothing but freestyle scores zero rather than dividing by zero`() {
        val part = VoicePart(
            null,
            listOf(LyricLine(listOf(note(0, 8, 0, NoteType.FREESTYLE)), lineBreakBeat = null)),
        )
        val scorer = PlayerScorer(part, BEATS)

        scorer.sing(part)

        assertEquals(ScoreSnapshot.EMPTY, scorer.snapshot())
    }
}

private fun note(
    startBeat: Int,
    durationBeats: Int,
    pitch: Int,
    type: NoteType = NoteType.NORMAL,
) = Note(type, startBeat, durationBeats, pitch, "la")

/** A voiced reading of [midi], as confident and as loud as a real sustained note. */
private fun reading(midi: Float) = PitchReading(
    voiced = true,
    frequencyHz = midiToHz(midi.toDouble()).toFloat(),
    midi = midi,
    probability = 0.95f,
    level = 0.2f,
)

/** A singer who is on the current note, optionally transposed, and silent between notes. */
private fun onPitch(part: VoicePart, offsetSemitones: Int = 0): (Double) -> PitchReading =
    { time ->
        part.noteAt(time)
            ?.let { reading((ultraStarPitchToMidi(it.pitch) + offsetSemitones).toFloat()) }
            ?: PitchReading.unvoiced(0.001f)
    }

/**
 * Plays [part] through at a fixed reading rate, asking [singer] what was sung at each moment.
 *
 * The singer is asked at exactly the instants the scorer is fed, so it is no better informed
 * than a real one: it cannot know a note has changed until a reading covering it arrives.
 * Readings run on past the last note, as they would in a real song, so the closing beats get
 * judged rather than being left unscored.
 */
private fun PlayerScorer.sing(
    part: VoicePart,
    hopSeconds: Double = HOP_SECONDS,
    singer: (Double) -> PitchReading = onPitch(part),
) {
    val lastBeat = part.lines.flatMap { it.notes }.maxOf { it.startBeat + it.durationBeats }
    val end = BEATS.beatToSeconds(lastBeat) + 0.2

    var time = 0.0
    while (time <= end) {
        update(time, singer(time))
        time += hopSeconds
    }
}

/** The note being sung at [time], or null in the gaps. */
private fun VoicePart.noteAt(time: Double): Note? =
    lines.asSequence().flatMap { it.notes }.firstOrNull { note ->
        time >= BEATS.beatToSeconds(note.startBeat) &&
            time < BEATS.beatToSeconds(note.startBeat + note.durationBeats)
    }
