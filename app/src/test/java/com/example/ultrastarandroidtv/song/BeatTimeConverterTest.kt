package com.example.ultrastarandroidtv.song

import org.junit.Assert.assertEquals
import org.junit.Test

class BeatTimeConverterTest {

    @Test
    fun `converts beats to seconds using quarter-beat BPM`() {
        // #BPM:60 means 60 quarter-beats per minute, i.e. 0.25s per beat unit.
        val converter = BeatTimeConverter(bpm = 60.0, gapMs = 0.0)

        assertEquals(0.0, converter.beatToSeconds(0), 1e-9)
        assertEquals(1.0, converter.beatToSeconds(4), 1e-9)
        assertEquals(2.0, converter.beatToSeconds(8), 1e-9)
    }

    @Test
    fun `offsets by GAP milliseconds`() {
        val converter = BeatTimeConverter(bpm = 60.0, gapMs = 2000.0)

        assertEquals(2.0, converter.beatToSeconds(0), 1e-9)
        assertEquals(3.0, converter.beatToSeconds(4), 1e-9)
    }

    @Test
    fun `can be built directly from song metadata`() {
        val metadata = SongMetadata(
            title = "Song",
            artist = "Artist",
            mp3 = "song.mp3",
            bpm = 200.0,
            gapMs = 500.0,
        )
        val converter = BeatTimeConverter(metadata)

        assertEquals(0.5, converter.beatToSeconds(0), 1e-9)
        assertEquals(1.1, converter.beatToSeconds(8), 1e-9)
    }
}
