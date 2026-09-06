package com.example.ultrastarandroidtv.game

import com.example.ultrastarandroidtv.song.LyricLine
import com.example.ultrastarandroidtv.song.Note
import com.example.ultrastarandroidtv.song.NoteType
import com.example.ultrastarandroidtv.song.SongMetadata
import com.example.ultrastarandroidtv.song.VoicePart
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The held-syllable bridge: Karaoke Revolution's angled connector, from the one thing the
 * UltraStar format says about it.
 *
 * Worth pinning because the whole feature turns on recognising a lone `~`, and because the rule
 * has one edge that is easy to get wrong in the other direction — a line's first note can never
 * be a continuation of anything, and bridging across a line break would join two phrases a
 * singer breathes between.
 */
class HeldSyllableTest {

    @Test
    fun `a lone tilde is a held syllable and anything else is a word`() {
        assertTrue(isHeldSyllable("~"))
        assertTrue(isHeldSyllable(" ~ "))
        assertFalse(isHeldSyllable("~la"))
        assertFalse(isHeldSyllable("la~"))
        assertFalse(isHeldSyllable(""))
        assertFalse(isHeldSyllable("la "))
    }

    @Test
    fun `a held note is bridged from the one before it`() {
        val geometry = geometryOf(listOf("hold " to 0, "~" to 4))

        assertFalse(geometry.placements[0].heldFromPrevious)
        assertTrue(geometry.placements[1].heldFromPrevious)
    }

    @Test
    fun `a line never bridges back into the line before it`() {
        // Two lines, the second opening on a tilde -- which happens in real charts, and which
        // must not draw a connector across the gap somebody breathes in.
        val geometry = TrackGeometry(
            part = VoicePart(
                label = null,
                lines = listOf(
                    LyricLine(listOf(note("hold ", 0, 0)), lineBreakBeat = 8),
                    LyricLine(listOf(note("~", 16, 4)), lineBreakBeat = null),
                ),
            ),
            beats = com.example.ultrastarandroidtv.song.BeatTimeConverter(metadata()),
        )

        assertFalse(geometry.placements[1].heldFromPrevious)
    }

    private fun metadata() = SongMetadata(
        title = "T", artist = "A", mp3 = "a.mp3", bpm = 120.0,
    )

    private fun note(text: String, start: Int, pitch: Int) =
        Note(NoteType.NORMAL, startBeat = start, durationBeats = 4, pitch = pitch, text = text)

    private fun geometryOf(syllables: List<Pair<String, Int>>) = TrackGeometry(
        part = VoicePart(
            label = null,
            lines = listOf(
                LyricLine(
                    syllables.mapIndexed { at, (text, pitch) -> note(text, at * 8, pitch) },
                    lineBreakBeat = null,
                ),
            ),
        ),
        beats = com.example.ultrastarandroidtv.song.BeatTimeConverter(metadata()),
    )
}
