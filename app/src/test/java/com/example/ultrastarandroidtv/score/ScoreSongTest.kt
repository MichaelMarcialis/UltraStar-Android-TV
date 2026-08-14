package com.example.ultrastarandroidtv.score

import com.example.ultrastarandroidtv.pitch.PitchReading
import com.example.ultrastarandroidtv.pitch.midiToHz
import com.example.ultrastarandroidtv.song.BeatTimeConverter
import com.example.ultrastarandroidtv.song.Note
import com.example.ultrastarandroidtv.song.UltraStarSong
import com.example.ultrastarandroidtv.song.UltraStarSongParser
import com.example.ultrastarandroidtv.song.VoicePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Scoring driven from real song text rather than hand-built notes, so the parser's output and
 * the scorer's input are checked against each other — including a `#GAP`, which offsets every
 * beat in the song and is easy to get wrong in only one of the two places.
 */
class ScoreSongTest {

    private val duet = UltraStarSongParser.parse(
        """
        #TITLE:Both Of Us
        #ARTIST:Test Artist
        #MP3:song.mp3
        #BPM:320
        #GAP:1750
        P1
        : 0 8 0 One
        : 8 8 7 two
        - 16
        : 16 8 4 three
        * 24 8 -5 four
        P2
        : 32 8 2 five
        : 40 8 9 six
        - 48
        R 48 8 0 sev-en
        : 56 8 -3 eight
        """.trimIndent(),
    )

    private val beats = BeatTimeConverter(duet.metadata)

    @Test
    fun `each singer is scored only on their own part`() {
        val p1 = PlayerScorer(duet.voiceParts[0], beats)
        val p2 = PlayerScorer(duet.voiceParts[1], beats)

        // P1 sings their part perfectly. P2 sings P1's notes — right pitches, wrong half of
        // the song — and so should score nothing at all.
        duet.play { time ->
            val note = duet.voiceParts[0].noteAt(beats, time)
            val reading = note?.let { reading(ultraStarPitchToMidi(it.pitch).toFloat()) }
                ?: PitchReading.unvoiced(0.001f)
            p1.update(time, reading)
            p2.update(time, reading)
        }

        assertEquals(MAX_SCORE, p1.snapshot().total)
        assertEquals(32, p1.snapshot().beatsScored)

        assertEquals(0, p2.snapshot().beatsHit)
        assertEquals(32, p2.snapshot().beatsScored)
    }

    @Test
    fun `the GAP shifts scoring along with the song`() {
        // Singing on the beat as written, but ignoring the 1.75 s intro, hits nothing.
        val scorer = PlayerScorer(duet.voiceParts[0], beats)
        val early = BeatTimeConverter(bpm = duet.metadata.bpm, gapMs = 0.0)

        duet.play { time ->
            val note = duet.voiceParts[0].noteAt(early, time)
            scorer.update(
                time,
                note?.let { reading(ultraStarPitchToMidi(it.pitch).toFloat()) }
                    ?: PitchReading.unvoiced(0.001f),
            )
        }

        assertEquals(0, scorer.snapshot().beatsHit)
    }

    @Test
    fun `a rap note in a real song scores on rhythm alone`() {
        val p2 = PlayerScorer(duet.voiceParts[1], beats)

        // A monotone drone: only the rap note and any note that happens to share its pitch
        // class can be hit. Pitch 0 here, so the rap note plus nothing else.
        duet.play { time -> p2.update(time, reading(ultraStarPitchToMidi(0).toFloat())) }

        val score = p2.snapshot()
        assertEquals(8, score.beatsHit)
        assertTrue(score.total > 0)
    }
}

private fun reading(midi: Float) = PitchReading(
    voiced = true,
    frequencyHz = midiToHz(midi.toDouble()).toFloat(),
    midi = midi,
    probability = 0.95f,
    level = 0.2f,
)

/** Runs the clock over the whole song at the pitch tracker's real rate. */
private fun UltraStarSong.play(onTick: (Double) -> Unit) {
    val beats = BeatTimeConverter(metadata)
    val lastBeat = voiceParts.flatMap { it.lines }.flatMap { it.notes }
        .maxOf { it.startBeat + it.durationBeats }
    val end = beats.beatToSeconds(lastBeat) + 0.2

    var time = 0.0
    while (time <= end) {
        onTick(time)
        time += 1024.0 / 48_000.0
    }
}

private fun VoicePart.noteAt(beats: BeatTimeConverter, time: Double): Note? =
    lines.asSequence().flatMap { it.notes }.firstOrNull { note ->
        time >= beats.beatToSeconds(note.startBeat) &&
            time < beats.beatToSeconds(note.startBeat + note.durationBeats)
    }
