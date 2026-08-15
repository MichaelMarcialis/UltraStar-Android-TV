package com.example.ultrastarandroidtv.game

import com.example.ultrastarandroidtv.song.Note
import com.example.ultrastarandroidtv.song.NoteType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricLayoutTest {

    /** 100 px per second keeps every expected number in this file arithmetic you can check. */
    private val pxPerSecond = 100f

    private fun syllable(startSeconds: Double, lineIndex: Int = 0) = PlacedNote(
        note = Note(NoteType.NORMAL, startBeat = 0, durationBeats = 1, pitch = 0, text = "la"),
        lineIndex = lineIndex,
        displayText = "la",
        startSeconds = startSeconds,
        endSeconds = startSeconds + 0.1,
        midi = 60,
        beatMidSeconds = doubleArrayOf(startSeconds + 0.05),
    )

    private fun layout(
        starts: List<Pair<Double, Int>>,
        widths: FloatArray,
        maxLagSeconds: Float = 10f,
    ) = LyricLayout(
        placements = starts.map { (start, line) -> syllable(start, line) },
        widths = widths,
        pixelsPerSecond = pxPerSecond,
        minGapPixels = 10f,
        maxLagSeconds = maxLagSeconds,
    )

    @Test
    fun `syllables with room to breathe are left exactly where their notes are`() {
        // The whole point of this layout is that a syllable sits under its own note. Anything
        // that moved them when there was space would be throwing that away for nothing.
        val result = layout(listOf(0.0 to 0, 2.0 to 0), floatArrayOf(50f, 50f))

        assertEquals(0f, result.offsets[0], 1e-3f)
        assertEquals(0f, result.offsets[1], 1e-3f)
    }

    @Test
    fun `a syllable that would collide is pushed clear of the one before it`() {
        // Notes 0.2 s apart is 20 px at this scale, and the first syllable is 50 px wide, so
        // without this they overlap by more than half — which is how "But something" became
        // "Butsomething" on the TV.
        val result = layout(listOf(0.0 to 0, 0.2 to 0), floatArrayOf(50f, 50f))

        // First ends at 50, plus a 10 px gap, so the second sits at 60 instead of 20.
        assertEquals(40f, result.offsets[1], 1e-3f)
    }

    @Test
    fun `syllables are only ever pushed later, never earlier`() {
        // Pulling one left would put a syllable before the note it belongs to, so the singer
        // would be reading the right word at the wrong time.
        val result = layout(
            listOf(0.0 to 0, 0.05 to 0, 0.1 to 0, 0.12 to 0),
            floatArrayOf(60f, 60f, 60f, 60f),
        )

        assertTrue(result.offsets.all { it >= 0f })
    }

    @Test
    fun `a run of crowded syllables stays in order`() {
        val result = layout(
            listOf(0.0 to 0, 0.05 to 0, 0.1 to 0),
            floatArrayOf(40f, 40f, 40f),
        )

        val positions = listOf(0.0, 0.05, 0.1).mapIndexed { i, start ->
            (start * pxPerSecond).toFloat() + result.offsets[i]
        }
        assertTrue("$positions should increase", positions.zipWithNext().all { it.second > it.first })
    }

    @Test
    fun `pushing stops before a syllable drifts far from its note`() {
        // Past a point, a crowded syllable is better than one that arrives long after the note
        // it belongs to — being late is worse than being tight.
        val result = layout(
            listOf(0.0 to 0, 0.05 to 0),
            floatArrayOf(200f, 50f),
            maxLagSeconds = 0.1f,
        )

        assertEquals(10f, result.offsets[1], 1e-3f) // 0.1 s at 100 px/s.
    }

    @Test
    fun `a new lyric line does not land on top of the last one`() {
        // Starting each line afresh looks tidier and reads worse: the first syllable of the new
        // line lands on the pushed tail of the old one, which is the most visible collision
        // there is. Seen on the TV as "ting through" running into "But".
        val result = layout(
            listOf(0.0 to 0, 0.05 to 0, 0.08 to 1),
            floatArrayOf(60f, 60f, 60f),
        )

        val rights = listOf(0.0, 0.05, 0.08).mapIndexed { i, start ->
            (start * pxPerSecond).toFloat() + result.offsets[i] + 60f
        }
        val lefts = listOf(0.0, 0.05, 0.08).mapIndexed { i, start ->
            (start * pxPerSecond).toFloat() + result.offsets[i]
        }

        assertTrue("the crowded pair should move", result.offsets[1] > 0f)
        assertTrue("the new line must clear the old one", lefts[2] >= rights[1])
    }

    @Test
    fun `lateness cannot compound over a long dense passage`() {
        // The cap, not a per-line reset, is what bounds drift: every syllable is measured
        // against its own note, so a hundred crowded syllables in a row are no later than one.
        val starts = List(100) { it * 0.05 to 0 }
        val result = layout(starts, FloatArray(100) { 60f }, maxLagSeconds = 0.2f)

        assertTrue(result.offsets.all { it <= 0.2f * pxPerSecond + 1e-3f })
    }

    @Test
    fun `a note with no text does not shove its neighbours`() {
        val result = layout(listOf(0.0 to 0, 0.05 to 0), floatArrayOf(0f, 50f))

        assertEquals(0f, result.offsets[1], 1e-3f)
    }

    @Test
    fun `an empty song lays out without complaint`() {
        val result = LyricLayout(emptyList(), FloatArray(0), pxPerSecond, 10f)

        assertEquals(0, result.offsets.size)
    }
}
