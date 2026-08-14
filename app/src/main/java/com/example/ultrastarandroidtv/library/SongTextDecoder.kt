package com.example.ultrastarandroidtv.library

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * Turns the bytes of an UltraStar `.txt` into text.
 *
 * The format has no encoding declaration and the community library is a genuine mix: files
 * written on Windows over twenty years, some UTF-8, some UTF-8 with a BOM, plenty of
 * Windows-1252. Guessing wrong does not fail loudly — it quietly mangles the accented
 * characters in half the artist names, which is exactly the kind of thing nobody notices until
 * the song list is full of them.
 *
 * The rule: believe a byte order mark if there is one, otherwise try UTF-8 strictly and fall
 * back to Windows-1252. That works because UTF-8 is a demanding shape — multi-byte sequences
 * have to follow a specific pattern, and Windows-1252 text with accents in it almost never
 * satisfies that pattern by accident.
 *
 * Windows-1252 rather than ISO-8859-1 on purpose. They differ exactly in `0x80`–`0x9F`, which
 * is where Windows puts curly quotes, dashes and the euro sign — the characters that actually
 * turn up in song titles.
 */
object SongTextDecoder {

    private val WINDOWS_1252: Charset = Charset.forName("windows-1252")

    private val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
    private val UTF16_LE_BOM = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
    private val UTF16_BE_BOM = byteArrayOf(0xFE.toByte(), 0xFF.toByte())

    fun decode(bytes: ByteArray): String {
        if (bytes.startsWith(UTF8_BOM)) {
            return String(bytes, UTF8_BOM.size, bytes.size - UTF8_BOM.size, StandardCharsets.UTF_8)
        }
        if (bytes.startsWith(UTF16_LE_BOM)) {
            return String(bytes, 2, bytes.size - 2, StandardCharsets.UTF_16LE)
        }
        if (bytes.startsWith(UTF16_BE_BOM)) {
            return String(bytes, 2, bytes.size - 2, StandardCharsets.UTF_16BE)
        }
        return decodeStrictUtf8(bytes) ?: String(bytes, WINDOWS_1252)
    }

    /** Null when [bytes] is not valid UTF-8, which is the signal to fall back. */
    private fun decodeStrictUtf8(bytes: ByteArray): String? {
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            decoder.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            null
        }
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
        if (size < prefix.size) return false
        return prefix.indices.all { this[it] == prefix[it] }
    }
}
