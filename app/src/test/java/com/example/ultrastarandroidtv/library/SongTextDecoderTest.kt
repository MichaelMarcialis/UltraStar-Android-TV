package com.example.ultrastarandroidtv.library

import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private val WINDOWS_1252: Charset = Charset.forName("windows-1252")

class SongTextDecoderTest {

    @Test
    fun `reads plain ASCII`() {
        assertEquals("#TITLE:Hello", SongTextDecoder.decode("#TITLE:Hello".toByteArray()))
    }

    @Test
    fun `reads UTF-8 accents`() {
        val text = "#ARTIST:Sigur Rós\n#TITLE:Hoppípolla"

        assertEquals(text, SongTextDecoder.decode(text.toByteArray(StandardCharsets.UTF_8)))
    }

    @Test
    fun `strips a UTF-8 byte order mark`() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
            "#TITLE:Hello".toByteArray(StandardCharsets.UTF_8)

        val text = SongTextDecoder.decode(bytes)

        assertEquals("#TITLE:Hello", text)
        assertTrue("a leftover BOM breaks the very first header", text.startsWith("#"))
    }

    @Test
    fun `reads UTF-16 with either byte order`() {
        val text = "#TITLE:Hüsker Dü"

        assertEquals(text, SongTextDecoder.decode(text.toByteArray(StandardCharsets.UTF_16LE).withBom(0xFF, 0xFE)))
        assertEquals(text, SongTextDecoder.decode(text.toByteArray(StandardCharsets.UTF_16BE).withBom(0xFE, 0xFF)))
    }

    @Test
    fun `falls back to Windows-1252 for accents that are not valid UTF-8`() {
        // How a German or French song title written in Notepad twenty years ago is stored.
        val text = "#ARTIST:Blümchen\n#TITLE:Härzilein"

        val decoded = SongTextDecoder.decode(text.toByteArray(WINDOWS_1252))

        assertEquals(text, decoded)
    }

    @Test
    fun `keeps the characters that separate Windows-1252 from Latin-1`() {
        // Curly quotes, an em dash and a euro sign all live in 0x80-0x9F, which ISO-8859-1
        // leaves as control characters. These turn up in real song titles.
        val text = "#TITLE:Don’t Stop — Live\n#EDITION:€5 Hits"

        assertEquals(text, SongTextDecoder.decode(text.toByteArray(WINDOWS_1252)))
    }

    @Test
    fun `prefers UTF-8 when the bytes are valid as both`() {
        // "Rós" in UTF-8 is a valid Windows-1252 string too ("RÃ³s"), so the order of the
        // attempts is what decides this, and UTF-8 has to win.
        val bytes = "#ARTIST:Sigur Rós".toByteArray(StandardCharsets.UTF_8)

        assertEquals("#ARTIST:Sigur Rós", SongTextDecoder.decode(bytes))
    }

    @Test
    fun `handles an empty file`() {
        assertEquals("", SongTextDecoder.decode(ByteArray(0)))
    }
}

private fun ByteArray.withBom(vararg bom: Int): ByteArray =
    bom.map { it.toByte() }.toByteArray() + this
