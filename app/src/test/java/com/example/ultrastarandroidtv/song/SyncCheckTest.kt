package com.example.ultrastarandroidtv.song

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

/**
 * The timing check, driven by synthesised songs.
 *
 * Real audio is not needed and would be worse: what has to be pinned is that the sweep finds an
 * offset it was *given*, and a fixture built by playing a chart's own notes is the only way to
 * know the true answer to the millisecond. The chroma builder and the FFT under it are the real
 * ones, so this exercises everything but the decoder.
 */
class SyncCheckTest {

    // ------------------------------------------------------------------------------------------
    // Finding the offset
    // ------------------------------------------------------------------------------------------

    @Test
    fun `a chart played in time with its own audio reads as aligned`() {
        val song = songOf(MELODY)
        val profile = renderProfile(song, shiftSeconds = 0.0)

        val verdict = checkSync(song, profile)

        assertTrue("expected aligned, was $verdict", verdict is SyncVerdict.Aligned)
    }

    @Test
    fun `audio that starts late is measured, and by how much`() {
        // The Jimmy Cliff case: the chart is right about this recording and starts too early.
        val song = songOf(MELODY)
        val profile = renderProfile(song, shiftSeconds = 0.5)

        val verdict = checkSync(song, profile)

        assertTrue("expected shifted, was $verdict", verdict is SyncVerdict.Shifted)
        val shifted = verdict as SyncVerdict.Shifted
        // One hop is 46 ms, so anything inside a hop of the truth is exact as far as this can see.
        assertEquals(0.5, shifted.offsetSeconds, CHROMA_HOP_SECONDS)
        assertTrue("confidence ${shifted.confidence}", shifted.confidence >= CONFIDENT_PEAK)
    }

    @Test
    fun `audio that starts early is measured too`() {
        val song = songOf(MELODY)
        val profile = renderProfile(song, shiftSeconds = -1.2)

        val verdict = checkSync(song, profile) as SyncVerdict.Shifted

        assertEquals(-1.2, verdict.offsetSeconds, CHROMA_HOP_SECONDS)
    }

    @Test
    fun `a chart against a different recording is a mismatch, not a shift`() {
        // The Magic Dance case, and the one that matters most: there is no offset that fits, so
        // the only safe thing to do is nothing. A chart moved by a meaningless number is a song
        // that is wrong *and* edited.
        val song = songOf(MELODY)
        val other = renderProfile(songOf(OTHER_MELODY), shiftSeconds = 0.0)

        val verdict = checkSync(song, other)

        assertTrue("expected mismatch, was $verdict", verdict is SyncVerdict.Mismatch)
    }

    @Test
    fun `a song this cannot read is not accused of being the wrong recording`() {
        // The Heroes case, found by sweeping the real card: a chart with little melodic signal
        // scores low at every offset, so its best guess is near enough noise -- and a rule that
        // looked only at confidence announced "the notes do not match this recording" about a
        // song that plays perfectly. Saying nothing is the honest answer there.
        val song = songOf(MELODY)

        // Built rather than played, so "faint" is exact: every frame nearly flat across the
        // twelve classes, with a bias of a few percent towards what the chart asks for, six
        // frames — a third of a second — from where it claims. That is the shape of a song whose
        // melody this cannot hear, and it must not be read as an accusation.
        val verdict = checkSync(song, faintProfile(song, biases = listOf(6 to 0.03f)))

        assertTrue("expected no accusation, was $verdict", verdict !is SyncVerdict.Mismatch)
    }

    @Test
    fun `a chart that fits where it says is never called the wrong recording`() {
        // The case a review caught. Judging a weak result by *where its peak landed* is judging
        // noise: across a ninety-second sweep a meaningless peak is more than a second from zero
        // almost every time, so that test alone would eventually accuse any song this cannot
        // hear. Here the chart plainly does describe this recording -- it scores well at the
        // position it claims -- and something further out happens to score a little better.
        val song = songOf(MELODY)
        val profile = faintProfile(song, biases = listOf(0 to 0.05f, 90 to 0.055f))

        val verdict = checkSync(song, profile)

        assertTrue("expected no accusation, was $verdict", verdict !is SyncVerdict.Mismatch)
    }

    @Test
    fun `noise is a mismatch rather than a confident nonsense`() {
        val song = songOf(MELODY)
        val random = Random(7)
        val builder = ChromaBuilder()
        val block = FloatArray(CHROMA_RATE)
        repeat(40) {
            for (i in block.indices) block[i] = random.nextFloat() * 2f - 1f
            builder.add(block)
        }

        val verdict = checkSync(song, builder.build())

        assertTrue("expected mismatch, was $verdict", verdict !is SyncVerdict.Shifted)
    }

    @Test
    fun `a chart with almost no notes is left alone rather than guessed at`() {
        // Rap, spoken word and a two-line fragment all land here. Silence is the right answer
        // when the instrument cannot see, and it must never be mistaken for "in time".
        val song = songOf(MELODY.take(4))
        val profile = renderProfile(songOf(MELODY), shiftSeconds = 3.0)

        assertEquals(SyncVerdict.Unscoreable, checkSync(song, profile))
    }

    @Test
    fun `an offset too large to be a gap error is refused`() {
        // A confident-looking peak forty seconds out is a coincidence of a repetitive song, not a
        // chart somebody wrote three quarters of a minute early.
        val song = songOf(MELODY)
        val profile = renderProfile(song, shiftSeconds = 12.0)

        assertTrue(checkSync(song, profile) !is SyncVerdict.Shifted)
    }

    // ------------------------------------------------------------------------------------------
    // Writing the correction back
    // ------------------------------------------------------------------------------------------

    @Test
    fun `shifting the gap moves one header and nothing else`() {
        val chart = "#TITLE:A Song\r\n#BPM:200\r\n#GAP:6880\r\n: 0 4 0 la \r\nE\r\n"

        val moved = shiftGap(chart, 0.51)!!

        assertEquals("#TITLE:A Song\r\n#BPM:200\r\n#GAP:7390\r\n: 0 4 0 la \r\nE\r\n", moved)
    }

    @Test
    fun `a gap written with a comma is understood, and a chart without one is refused`() {
        // Comma decimals are ordinary in these files -- USDB is a German site.
        assertTrue(shiftGap("#GAP:1000,5\n: 0 4 0 la\n", 0.5)!!.contains("#GAP:1501"))
        assertNull(shiftGap("#TITLE:No Gap Here\n: 0 4 0 la\n", 0.5))
    }

    @Test
    fun `a negative shift can take a gap below zero without corrupting the file`() {
        val moved = shiftGap("#GAP:100\n#BPM:200\n", -0.5)!!
        assertEquals("#GAP:-400\n#BPM:200\n", moved)
    }

    // ------------------------------------------------------------------------------------------

    /**
     * A tune long enough and varied enough to be identifiable.
     *
     * Both of these are pseudorandom rather than hand-written, and long, because a short melody
     * of five pitch classes repeating every few seconds can be matched by a *different* one at
     * some offset by luck — harmonics leak across pitch classes, so nothing ever scores zero.
     * Real songs are constrained the same way this is: by having a lot of notes.
     */
    private val MELODY get() = tune(seed = 11)

    /** A different tune, for the "this is not your recording" case. */
    private val OTHER_MELODY get() = tune(seed = 29)

    private fun tune(seed: Int): List<Int> {
        val random = Random(seed)
        return List(60) { random.nextInt(12) }
    }

    /**
     * A song whose notes are [pitches], one per beat, starting a second in.
     *
     * BPM 15 means an UltraStar beat is a second — four quarter-beats at 15 BPM — so every note
     * is one second long and the arithmetic in the test stays readable.
     */
    private fun songOf(pitches: List<Int>): UltraStarSong {
        val notes = pitches.mapIndexed { at, pitch ->
            Note(NoteType.NORMAL, startBeat = at, durationBeats = 1, pitch = pitch, text = "la ")
        }
        return UltraStarSong(
            metadata = SongMetadata(
                title = "Test", artist = "Test", mp3 = "a.mp3", bpm = 15.0, gapMs = 1_000.0,
            ),
            voiceParts = listOf(VoicePart(label = null, lines = listOf(LyricLine(notes, null)))),
        )
    }

    /**
     * Plays a song's own notes as tones and reduces them the way the app would.
     *
     * [shiftSeconds] moves the *audio* later, which is what a chart starting too early looks like
     * from the outside — so a positive shift here should come back as a positive offset.
     */
    /**
     * A profile that barely favours the chart, by [biases] — each an offset in frames and how
     * much to add there.
     *
     * Flat everywhere else, so the peak-to-mean ratio is whatever the biases make it and nothing
     * about the fixture is left to luck. More than one bias is what lets a chart score well
     * where it claims *and* have its maximum somewhere else, which is the interesting case.
     */
    private fun faintProfile(song: UltraStarSong, biases: List<Pair<Int, Float>>): ChromaProfile {
        val beats = BeatTimeConverter(song.metadata)
        val notes = song.voiceParts.flatMap { it.lines }.flatMap { it.notes }
        val last = notes.last()
        val frames = ((beats.beatToSeconds(last.startBeat + last.durationBeats) + 8.0) /
            CHROMA_HOP_SECONDS).toInt()
        val data = FloatArray(frames * 12) { 1f / 12f }

        for ((biasFrames, bias) in biases) {
            for (note in notes) {
                val from = (beats.beatToSeconds(note.startBeat) / CHROMA_HOP_SECONDS).toInt() +
                    biasFrames
                val to = (beats.beatToSeconds(note.startBeat + note.durationBeats) /
                    CHROMA_HOP_SECONDS).toInt() + biasFrames
                val pitchClass = ((note.pitch % 12) + 12) % 12
                for (frame in from until minOf(to, frames)) {
                    if (frame >= 0) data[frame * 12 + pitchClass] += bias
                }
            }
        }
        return ChromaProfile(data, frames)
    }

    private fun renderProfile(
        song: UltraStarSong,
        shiftSeconds: Double,
    ): ChromaProfile {
        val beats = BeatTimeConverter(song.metadata)
        val notes = song.voiceParts.flatMap { it.lines }.flatMap { it.notes }
        val lastEnd = beats.beatToSeconds(notes.last().startBeat + notes.last().durationBeats)
        val seconds = lastEnd + abs(shiftSeconds) + 2.0
        val samples = FloatArray((seconds * CHROMA_RATE).toInt())

        for (note in notes) {
            // Pitch 0 is C4 = MIDI 60, which is the app's own convention everywhere else.
            val hz = 440.0 * Math.pow(2.0, (60 + note.pitch - 69) / 12.0)
            val from = ((beats.beatToSeconds(note.startBeat) + shiftSeconds) * CHROMA_RATE).toInt()
            val to = ((beats.beatToSeconds(note.startBeat + note.durationBeats) + shiftSeconds) * CHROMA_RATE).toInt()
            for (i in maxOf(0, from) until minOf(samples.size, to)) {
                val t = (i - from).toDouble() / CHROMA_RATE
                // A fundamental and two harmonics: a bare sine is easier to find than any real
                // voice, and the point is to be representative rather than kind.
                samples[i] += (sin(2 * PI * hz * t) * 0.6 +
                    sin(2 * PI * hz * 2 * t) * 0.3 +
                    sin(2 * PI * hz * 3 * t) * 0.1).toFloat()
            }
        }

        val builder = ChromaBuilder()
        builder.add(samples)
        return builder.build()
    }
}
