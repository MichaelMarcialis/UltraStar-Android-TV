package com.example.ultrastarandroidtv.song

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class UltraStarSongParserTest {

    @Test
    fun `parses metadata, note types, and line breaks for a solo song`() {
        val song = UltraStarSongParser.parse(
            """
            #TITLE:Test Song
            #ARTIST:Test Artist
            #MP3:song.mp3
            #BPM:200
            #GAP:500
            #COVER:cover.jpg
            #GENRE:Pop
            #YEAR:1999

            : 0 4 0 Hel-
            : 4 4 2 lo
            - 8
            * 8 4 5 Gol-
            * 12 4 5 den
            - 16
            F 16 8 0 Freestyle bit
            E
            """.trimIndent(),
        )

        with(song.metadata) {
            assertEquals("Test Song", title)
            assertEquals("Test Artist", artist)
            assertEquals("song.mp3", mp3)
            assertEquals(200.0, bpm, 0.0)
            assertEquals(500.0, gapMs, 0.0)
            assertEquals("cover.jpg", cover)
            assertEquals("Pop", genre)
            assertEquals(1999, year)
            assertTrue(!relative)
        }

        assertEquals(1, song.voiceParts.size)
        val lines = song.voiceParts.single().lines
        assertEquals(3, lines.size)

        assertEquals(
            listOf(
                Note(NoteType.NORMAL, 0, 4, 0, "Hel-"),
                Note(NoteType.NORMAL, 4, 4, 2, "lo"),
            ),
            lines[0].notes,
        )
        assertEquals(8, lines[0].lineBreakBeat)

        assertEquals(
            listOf(
                Note(NoteType.GOLDEN, 8, 4, 5, "Gol-"),
                Note(NoteType.GOLDEN, 12, 4, 5, "den"),
            ),
            lines[1].notes,
        )
        assertEquals(16, lines[1].lineBreakBeat)

        assertEquals(
            listOf(Note(NoteType.FREESTYLE, 16, 8, 0, "Freestyle bit")),
            lines[2].notes,
        )
        assertNull(lines[2].lineBreakBeat)
    }

    @Test
    fun `accepts comma decimal separators in BPM and GAP`() {
        val song = UltraStarSongParser.parse(
            """
            #TITLE:Comma Song
            #ARTIST:Artist
            #MP3:song.mp3
            #BPM:123,5
            #GAP:250,75

            : 0 4 0 Test
            E
            """.trimIndent(),
        )

        assertEquals(123.5, song.metadata.bpm, 0.0)
        assertEquals(250.75, song.metadata.gapMs, 0.0)
    }

    @Test
    fun `throws when a required header tag is missing`() {
        val text = """
            #ARTIST:Artist
            #MP3:song.mp3
            #BPM:120

            : 0 4 0 Test
            E
        """.trimIndent()

        assertThrows(SongParseException::class.java) { UltraStarSongParser.parse(text) }
    }

    @Test
    fun `throws on an unrecognized note line type`() {
        val text = """
            #TITLE:Bad Song
            #ARTIST:Artist
            #MP3:song.mp3
            #BPM:100

            X 0 4 0 Test
            E
        """.trimIndent()

        assertThrows(SongParseException::class.java) { UltraStarSongParser.parse(text) }
    }

    @Test
    fun `groups alternating P1 P2 duet sections into two voice parts`() {
        val song = UltraStarSongParser.parse(
            """
            #TITLE:Duet Song
            #ARTIST:Artist
            #MP3:song.mp3
            #BPM:100
            #GAP:0

            P1
            : 0 4 0 Hi
            - 4
            P2
            : 4 4 0 Yo
            - 8
            P1
            : 8 4 0 Bye
            E
            """.trimIndent(),
        )

        assertEquals(2, song.voiceParts.size)
        assertEquals("P1", song.voiceParts[0].label)
        assertEquals("P2", song.voiceParts[1].label)

        val p1Lines = song.voiceParts[0].lines
        assertEquals(2, p1Lines.size)
        assertEquals(listOf(Note(NoteType.NORMAL, 0, 4, 0, "Hi")), p1Lines[0].notes)
        assertEquals(4, p1Lines[0].lineBreakBeat)
        assertEquals(listOf(Note(NoteType.NORMAL, 8, 4, 0, "Bye")), p1Lines[1].notes)
        assertNull(p1Lines[1].lineBreakBeat)

        val p2Lines = song.voiceParts[1].lines
        assertEquals(1, p2Lines.size)
        assertEquals(listOf(Note(NoteType.NORMAL, 4, 4, 0, "Yo")), p2Lines[0].notes)
        assertEquals(8, p2Lines[0].lineBreakBeat)
    }
}
