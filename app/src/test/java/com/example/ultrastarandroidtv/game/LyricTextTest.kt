package com.example.ultrastarandroidtv.game

import com.example.ultrastarandroidtv.song.Note
import com.example.ultrastarandroidtv.song.NoteType
import org.junit.Assert.assertEquals
import org.junit.Test

class LyricTextTest {

    private fun line(vararg texts: String): List<Note> =
        texts.mapIndexed { i, text ->
            Note(NoteType.NORMAL, startBeat = i * 4, durationBeats = 4, pitch = 0, text = text)
        }

    @Test
    fun `a word split across notes is hyphenated`() {
        // "The fu- ture's o- pen" — how every karaoke game shows this, and the difference
        // between reading a word and reading two.
        assertEquals(
            listOf("The", "fu-", "ture's", "o-", "pen"),
            syllableTexts(line("The ", "fu", "ture's ", "o", "pen")),
        )
    }

    @Test
    fun `a trailing space is what ends a word`() {
        // It is the only signal UltraStar gives, and trimming for display is exactly what
        // throws it away — so the raw text has to decide.
        assertEquals(listOf("but", "you're"), syllableTexts(line("but ", "you're ")))
        assertEquals(listOf("be-", "cause"), syllableTexts(line("be", "cause ")))
    }

    @Test
    fun `the last syllable on a line never gets a hyphen`() {
        // Nothing follows it to join to, whatever the file says about spaces.
        assertEquals(listOf("end"), syllableTexts(line("end")))
    }

    @Test
    fun `tildes are stripped`() {
        // An UltraStar convention from the song files marking a held syllable, and pure noise
        // on screen.
        // "goo" runs into "d," to make "good", so it keeps its hyphen — stripping the tilde
        // must not also lose the fact that this is one word.
        assertEquals(listOf("goo-", "d,"), syllableTexts(line("goo", "~d, ")))
        assertEquals(listOf("with", "you"), syllableTexts(line("with ", "~you ")))
    }

    @Test
    fun `a syllable that was only a tilde draws nothing`() {
        // The note is a continuation and already has its own bar; there is no word to show.
        assertEquals(listOf("hold", "", "on"), syllableTexts(line("hold ", "~", "on ")))
    }

    @Test
    fun `no hyphen is left pointing at nothing`() {
        // "hold" runs into the next syllable by the space rule, but that syllable draws no
        // text — a dangling hyphen would be worse than none.
        assertEquals(listOf("hold", ""), syllableTexts(line("hold", "~")))
    }

    @Test
    fun `an empty line is handled without complaint`() {
        assertEquals(emptyList<String>(), syllableTexts(emptyList()))
    }
}
