package com.example.ultrastarandroidtv.game

import com.example.ultrastarandroidtv.score.PlayerScorer
import com.example.ultrastarandroidtv.song.BeatTimeConverter
import com.example.ultrastarandroidtv.song.LyricLine
import com.example.ultrastarandroidtv.song.Note
import com.example.ultrastarandroidtv.song.NoteType
import com.example.ultrastarandroidtv.song.VoicePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackGeometryTest {

    /** 60 BPM in UltraStar units is a quarter-second per beat, which keeps the maths readable. */
    private val beats = BeatTimeConverter(bpm = 60.0, gapMs = 0.0)

    /**
     * Three notes with a deliberate two-second rest before the last one — the gap is the thing
     * this layout is supposed to make visible, so the fixture has one.
     */
    private val part = VoicePart(
        label = null,
        lines = listOf(
            LyricLine(
                notes = listOf(
                    Note(NoteType.NORMAL, startBeat = 0, durationBeats = 4, pitch = 0, text = "Is "),
                    Note(NoteType.NORMAL, startBeat = 4, durationBeats = 4, pitch = 4, text = "this "),
                ),
                lineBreakBeat = 12,
            ),
            LyricLine(
                notes = listOf(
                    Note(NoteType.NORMAL, startBeat = 16, durationBeats = 4, pitch = 7, text = "real"),
                ),
                lineBreakBeat = null,
            ),
        ),
    )

    private val geometry = TrackGeometry(part, beats)

    /**
     * Pinned to five seconds on screen, so the windowing tests below describe the arithmetic
     * rather than whatever the default happens to be. The default is a matter of taste and has
     * already moved once; these rules have not.
     */
    private val windowed = TrackGeometry(part, beats, windowSeconds = 5.0)

    @Test
    fun `placements line up with the scorer's notes, index for index`() {
        // The trace is looked up by index against PlayerScorer.noteScores. If these two ever
        // flatten the song differently, every singer's line would be drawn on the wrong notes
        // while the score stayed right — which is exactly the kind of bug nobody finds by eye.
        val scorer = PlayerScorer(part, beats)

        assertEquals(scorer.noteScores.size, geometry.placements.size)
        scorer.noteScores.forEachIndexed { i, score ->
            assertEquals("note $i", score.note, geometry.placements[i].note)
        }
    }

    @Test
    fun `a beat is plotted at the instant the scorer judged it`() {
        // Same rule as PlayerScorer: the midpoint between this beat and the next. Drawing the
        // trace anywhere else would put the line and the points it earned in different places.
        val first = geometry.placements[0]

        assertEquals(4, first.beatMidSeconds.size)
        assertEquals(0.125, first.beatMidSeconds[0], 1e-9)
        assertEquals(0.375, first.beatMidSeconds[1], 1e-9)
        assertEquals(0.875, first.beatMidSeconds[3], 1e-9)
        assertTrue(first.beatMidSeconds.all { it > first.startSeconds && it < first.endSeconds })
    }

    @Test
    fun `notes carry their own start, end and pitch`() {
        val second = geometry.placements[1]

        assertEquals(1.0, second.startSeconds, 1e-9)
        assertEquals(2.0, second.endSeconds, 1e-9)
        assertEquals(64, second.midi) // UltraStar pitch 4 above C4.
    }

    @Test
    fun `now sits at the playhead, and the window edges land on the track edges`() {
        val width = 1000f
        val now = 10.0

        assertEquals(300f, geometry.xFor(now, now, width), 1e-3f)
        assertEquals(0f, geometry.xFor(geometry.startOfWindow(now), now, width), 1e-3f)
        assertEquals(width, geometry.xFor(geometry.endOfWindow(now), now, width), 1e-3f)
    }

    @Test
    fun `most of the width is what is coming, not what has gone`() {
        // The singer needs to read ahead; the note just sung is only worth a glance.
        val now = 10.0
        val behind = now - geometry.startOfWindow(now)
        val ahead = geometry.endOfWindow(now) - now

        assertTrue("lookahead $ahead should beat trailing $behind", ahead > behind)
    }

    @Test
    fun `higher notes are drawn higher up`() {
        val height = 100f

        assertEquals(height, geometry.yFor(geometry.lowMidi.toFloat(), height), 1e-3f)
        assertEquals(0f, geometry.yFor(geometry.highMidi.toFloat(), height), 1e-3f)
        assertTrue(geometry.yFor(67f, height) < geometry.yFor(60f, height))
    }

    @Test
    fun `a pitch outside the drawn range is clamped rather than drawn off the track`() {
        val height = 100f

        assertEquals(height, geometry.yFor(geometry.lowMidi - 30f, height), 1e-3f)
        assertEquals(0f, geometry.yFor(geometry.highMidi + 30f, height), 1e-3f)
    }

    @Test
    fun `a song on one note still gets a sensible vertical range`() {
        // Without a floor on the span, a monotone song would stretch its single pitch across
        // the whole track and every wobble would look like a leap.
        val flat = VoicePart(
            label = null,
            lines = listOf(
                LyricLine(
                    notes = listOf(
                        Note(NoteType.NORMAL, 0, 4, 0, "one"),
                        Note(NoteType.NORMAL, 4, 4, 0, "note"),
                    ),
                    lineBreakBeat = null,
                ),
            ),
        )

        val narrow = TrackGeometry(flat, beats)
        assertTrue(narrow.highMidi - narrow.lowMidi >= 12)
        assertTrue("the note should sit near the middle", narrow.yFor(60f, 100f) in 30f..70f)
    }

    @Test
    fun `the visible span is sized by a typical line, not by the song's extremes`() {
        // One screamed high note must not shrink every verse for the whole song. "Free" spans
        // 22 semitones overall while its individual phrases are far narrower — sizing from the
        // maximum is what squashed every passage into a third of the height.
        val wide = VoicePart(
            label = null,
            lines = List(10) { line ->
                // Nine ordinary lines covering four semitones, and one that leaps two octaves.
                val pitches = if (line == 9) listOf(0, 24) else listOf(0, 4)
                LyricLine(
                    notes = pitches.mapIndexed { i, pitch ->
                        Note(NoteType.NORMAL, startBeat = line * 8 + i * 4, durationBeats = 4, pitch = pitch, text = "la")
                    },
                    lineBreakBeat = null,
                )
            },
        )

        val span = TrackGeometry(wide, beats).visibleSpanSemitones

        assertTrue("span was $span", span < 20)
        assertTrue("but still fits an ordinary line", span >= 8)
    }

    @Test
    fun `the visible span stays readable for a song that barely moves`() {
        val flat = VoicePart(
            label = null,
            lines = listOf(
                LyricLine(
                    notes = listOf(Note(NoteType.NORMAL, 0, 4, 0, "one")),
                    lineBreakBeat = null,
                ),
            ),
        )

        assertTrue(TrackGeometry(flat, beats).visibleSpanSemitones >= 12)
    }

    @Test
    fun `only the notes touching the window are visible`() {
        // At time zero the window runs -1.5 to 3.5 s, which covers the first two notes but
        // stops short of the third at 4 s.
        assertEquals(0 until 2, windowed.visibleIndices(0.0))
    }

    @Test
    fun `a note is still visible while it is only part way past the left edge`() {
        // Window 4.5 to 9.5 s. The last note runs 4 to 5 s, so it has started to leave but is
        // still on screen — dropping it here would make notes vanish as they were being sung.
        assertEquals(2 until 3, windowed.visibleIndices(6.0))
    }

    @Test
    fun `nothing is visible once the song has run out of notes`() {
        assertTrue(windowed.visibleIndices(20.0).isEmpty())
    }

    @Test
    fun `the active note is the one being sung, and there is none during a rest`() {
        assertEquals(0, windowed.activeIndex(0.5))
        assertEquals(1, windowed.activeIndex(1.5))
        assertNull("nothing is sung in the gap", windowed.activeIndex(3.0))
        assertEquals(2, windowed.activeIndex(4.5))
    }

    @Test
    fun `an octave-displaced hit is folded back onto the note it scored against`() {
        // Scoring compares pitch classes, so this is a hit. Drawing it where it was literally
        // sung would put the trace off the track while the score went up.
        assertEquals(60f, foldToOctaveNear(48f, 60), 1e-6f)
        assertEquals(60f, foldToOctaveNear(72f, 60), 1e-6f)
        assertEquals(60f, foldToOctaveNear(36f, 60), 1e-6f)
    }

    @Test
    fun `folding corrects the octave and leaves the singing alone`() {
        // A semitone flat must still look a semitone flat, or the bar becomes a liar.
        assertEquals(59f, foldToOctaveNear(59f, 60), 1e-6f)
        assertEquals(62.5f, foldToOctaveNear(62.5f, 60), 1e-6f)
        assertEquals(59f, foldToOctaveNear(71f, 60), 1e-6f)
    }

    @Test
    fun `a beat nobody sang stays unsung through folding`() {
        // NaN is how "sang nothing" is told apart from "sang the wrong note"; turning it into a
        // number here would draw a confident line through silence.
        assertTrue(foldToOctaveNear(Float.NaN, 60).isNaN())
    }
}
