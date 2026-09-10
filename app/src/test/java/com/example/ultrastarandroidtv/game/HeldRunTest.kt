package com.example.ultrastarandroidtv.game

import com.example.ultrastarandroidtv.song.BeatTimeConverter
import com.example.ultrastarandroidtv.song.LyricLine
import com.example.ultrastarandroidtv.song.Note
import com.example.ultrastarandroidtv.song.NoteType
import com.example.ultrastarandroidtv.song.SongMetadata
import com.example.ultrastarandroidtv.song.VoicePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A held run — the notes a `~` joins — drawn as one shape rather than three stacked ones.
 *
 * Both things pinned here were reported from the sofa against the version that stacked them, and
 * both are invisible in review and obvious on a television:
 *
 * 1. **A ramp that stands proud of the bars it joins.** A stroked line is measured across its own
 *    direction, so a tilted one is taller than the flat bar it meets and leaves a notch at the
 *    corner. The fix is that a ramp starts and ends at the *centre of a bar's end cap*, where the
 *    bar is still at full height, and is offset vertically from there — which is what the spine
 *    test below is checking.
 * 2. **Pieces that overlap blend twice.** A singer's fill is translucent, so the half a note
 *    height a ramp reaches back inside the bar it leaves came out visibly brighter than the rest
 *    of the note. Merging the spans before any of them is drawn is the fix.
 */
class HeldRunTest {

    /**
     * The two ends of a ramp sit at the centres of the bars' end caps.
     *
     * That is the whole geometric claim: a rounded bar is at full height at its cap centre, so a
     * band of the same *vertical* thickness meets its top and bottom edges exactly there. Half a
     * note height in from each edge, and nowhere else.
     */
    @Test
    fun `a ramp starts and ends at the centre of a bar's end cap`() {
        val geometry = geometryOf(listOf("hold " to 0, "~" to 4))
        val scratch = RibbonScratch()
        val noteHeight = 40f
        val radius = noteHeight / 2f

        scratch.spine(geometry, 0, 1, NOW, WIDTH, NOTE_AREA, noteHeight, LOW, HIGH)

        assertEquals(4, scratch.pointCount)
        val first = geometry.placements[0]
        val second = geometry.placements[1]

        // The ramp runs from point 1 to point 2.
        assertEquals(
            geometry.xFor(first.endSeconds - GameTheme.noteGapSeconds, NOW, WIDTH) - radius,
            scratch.pointX(1),
            0.01f,
        )
        assertEquals(
            geometry.xFor(second.startSeconds, NOW, WIDTH) + radius,
            scratch.pointX(2),
            0.01f,
        )

        // And it lands on each bar's own pitch, so the band it becomes is level with them.
        assertEquals(
            geometry.yFor(first.midi.toFloat(), NOTE_AREA, LOW, HIGH),
            scratch.pointY(1),
            0.01f,
        )
        assertEquals(
            geometry.yFor(second.midi.toFloat(), NOTE_AREA, LOW, HIGH),
            scratch.pointY(2),
            0.01f,
        )
    }

    /**
     * A note drawn narrower than it is tall keeps its cap inside its own bar.
     *
     * `drawRoundRect` squashes a corner radius that will not fit, and the run has to squash it
     * the same way or the run's outer end bulges past where the bar stops. Not a corner case:
     * more than a third of this library's notes are one or two beats long, and a fast song's beat
     * is about 54 ms.
     */
    @Test
    fun `a bar narrower than it is tall does not turn the run inside out`() {
        val geometry = geometryOf(listOf("hold " to 0, "~" to 4))
        val scratch = RibbonScratch()

        // Taller than either bar is wide, at this window and width.
        scratch.spine(geometry, 0, 1, NOW, WIDTH, NOTE_AREA, noteHeight = 4000f, low = LOW, high = HIGH)

        for (i in 1 until scratch.pointCount) {
            assertTrue(
                "spine went backwards at $i",
                scratch.pointX(i) >= scratch.pointX(i - 1),
            )
        }
        val first = geometry.placements[0]
        assertTrue(
            "the run's left end reached outside its own bar",
            scratch.pointX(0) >= geometry.xFor(first.startSeconds, NOW, WIDTH),
        )
    }

    /** A run reaching in from off screen is drawn whole, or its ramp goes missing until it isn't. */
    @Test
    fun `a run hanging off the left of the window is completed`() {
        val geometry = geometryOf(listOf("hold " to 0, "~" to 4, "~" to 7, "next " to 2))
        val runs = mutableListOf<Pair<Int, Int>>()

        forEachHeldRun(geometry, 1..3) { first, last -> runs += first to last }

        assertEquals(listOf(0 to 2, 3 to 3), runs)
    }

    @Test
    fun `a note nobody holds through is a run of one`() {
        val geometry = geometryOf(listOf("one " to 0, "two " to 4))
        val runs = mutableListOf<Pair<Int, Int>>()

        forEachHeldRun(geometry, 0..1) { first, last -> runs += first to last }

        assertEquals(listOf(0 to 0, 1 to 1), runs)
    }

    /**
     * Overlapping spans become one, which is the fix for the bright patch.
     *
     * A ramp's span deliberately reaches half a note height back inside the bar it leaves, so it
     * genuinely overlaps that bar's last beat. Drawn one after the other at 55% opacity, the
     * overlap comes out at about 80% — a lighter block sitting exactly where the eye is anyway.
     */
    @Test
    fun `spans that touch are merged and spans that do not are kept apart`() {
        val scratch = RibbonScratch()

        scratch.addSpan(0f, 10f)
        scratch.addSpan(8f, 15f)
        assertEquals(1, scratch.spanCount)
        assertEquals(0f, scratch.spanFrom(0), 0.001f)
        assertEquals(15f, scratch.spanTo(0), 0.001f)

        scratch.addSpan(20f, 25f)
        assertEquals(2, scratch.spanCount)
        assertEquals(20f, scratch.spanFrom(1), 0.001f)

        // A span swallowed whole by the one before it must not shorten it.
        scratch.addSpan(21f, 22f)
        assertEquals(2, scratch.spanCount)
        assertEquals(25f, scratch.spanTo(1), 0.001f)
    }

    @Test
    fun `spans are cleared between singers`() {
        val scratch = RibbonScratch()
        scratch.addSpan(0f, 10f)
        scratch.clearSpans()

        assertEquals(0, scratch.spanCount)
    }

    private companion object {
        const val NOW = 0.0
        const val WIDTH = 1920f
        const val NOTE_AREA = 260f
        const val LOW = 48f
        const val HIGH = 72f
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
        beats = BeatTimeConverter(metadata()),
    )
}
