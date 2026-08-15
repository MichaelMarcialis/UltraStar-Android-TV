package com.example.ultrastarandroidtv.game

import com.example.ultrastarandroidtv.song.Note

/**
 * Turns a lyric line's raw syllables into what actually gets drawn.
 *
 * Two things happen here, both of them about a singer being able to read a word that has been
 * cut into pieces and spread across several notes.
 *
 * **Hyphens.** UltraStar marks the end of a word by putting a trailing space on the last
 * syllable of it; syllables without one run into the next. That is invisible once each syllable
 * is drawn under its own note, so "future's" arrives as "fu" "ture's" with nothing to say they
 * belong together. A trailing hyphen — "fu-" "ture's" — is how every karaoke game has always
 * shown this, and it is the difference between reading a word and reading two.
 *
 * **Tildes.** `~` is an UltraStar convention from the song files, not anything this app adds:
 * it marks a syllable held across an extra note, and community charts use it inconsistently.
 * On screen it is just noise, so it is stripped. A syllable that was *only* a tilde becomes
 * empty and draws nothing, which is right — the note is a continuation of the one before it,
 * and it already has its own bar.
 *
 * Pure and line-local, so it can be tested without a song, a screen or a device.
 */
fun syllableTexts(notes: List<Note>): List<String> {
    val cleaned = notes.map { it.text.replace("~", "").trim() }

    return notes.indices.map { i ->
        val text = cleaned[i]
        if (text.isEmpty()) return@map ""

        // The raw text decides this, not the cleaned one: it is the trailing space that carries
        // the meaning, and trimming is exactly what throws it away.
        val runsIntoNext = notes[i].text.isNotEmpty() && !notes[i].text.last().isWhitespace()

        // No hyphen pointing at nothing — the next note may be a bare tilde that draws no text,
        // or this may be the last syllable on the line.
        val nextIsVisible = i + 1 < cleaned.size && cleaned[i + 1].isNotEmpty()

        if (runsIntoNext && nextIsVisible) "$text-" else text
    }
}
