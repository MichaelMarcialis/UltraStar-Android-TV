package com.example.ultrastarandroidtv.download

import com.example.ultrastarandroidtv.usdb.UsdbSong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Recognising another chart of the same song.
 *
 * The matching is the whole risk here. Too strict and it finds nothing in exactly the cases it
 * exists for, because charts are typed by different people over twenty years and no two of them
 * punctuate an artist the same way. Too loose and it quietly downloads the wrong song, which is far
 * worse than saying "that version has gone" — a wrong song on the card is one nobody notices until
 * somebody presses play on it.
 */
class AlternateVersionsTest {

    /** The case that prompted all of this: one upload is blocked, another chart is not. */
    @Test
    fun `finds other charts of the same song`() {
        val wanted = song(1, "Kelly Clarkson", "Since U Been Gone")
        val found = listOf(
            wanted,
            song(2, "Kelly Clarkson", "Since U Been Gone"),
            song(3, "Kelly Clarkson", "Behind These Hazel Eyes"),
        )

        assertEquals(listOf(2), sameSongAs(wanted, found).map { it.songId })
    }

    /** USDB marks duet arrangements in the title, and they are still the same song. */
    @Test
    fun `a duet arrangement is the same song`() {
        val wanted = song(1, "ABBA", "Gimme! Gimme! Gimme!")
        val found = listOf(song(2, "ABBA", "Gimme! Gimme! Gimme! [DUET]"))

        assertEquals(listOf(2), sameSongAs(wanted, found).map { it.songId })
    }

    @Test
    fun `punctuation and spacing do not matter`() {
        val wanted = song(1, "Guns N' Roses", "Sweet Child O' Mine")
        val found = listOf(song(2, "Guns N Roses", "Sweet  Child  O  Mine"))

        assertEquals(listOf(2), sameSongAs(wanted, found).map { it.songId })
    }

    /**
     * Learned on the iTunes cover search and true again here: requiring every word of an artist
     * loses every soundtrack, because one chart bills a song to the film and another to the singer.
     */
    @Test
    fun `a soundtrack billed two ways is still the same song`() {
        val wanted = song(1, "Disney's Moana", "How Far I'll Go")
        val found = listOf(song(2, "Moana", "How Far I'll Go"))

        assertEquals(listOf(2), sameSongAs(wanted, found).map { it.songId })
    }

    // -------------------------------------------------------------------------------------
    // What must never match
    // -------------------------------------------------------------------------------------

    /** A wrong song on the card is worse than no song: nobody finds out until they press play. */
    @Test
    fun `a different song by the same artist is not a version of this one`() {
        val wanted = song(1, "David Bowie", "Starman")
        val found = listOf(song(2, "David Bowie", "Golden Years"))

        assertTrue(sameSongAs(wanted, found).isEmpty())
    }

    @Test
    fun `the same title by a different artist is not a version of this one`() {
        val wanted = song(1, "Katy Perry", "Firework")
        val found = listOf(song(2, "Siouxsie and the Banshees", "Firework"))

        assertTrue(sameSongAs(wanted, found).isEmpty())
    }

    /** The chart that just failed must never be offered as its own replacement. */
    @Test
    fun `never offers the version that just failed`() {
        val wanted = song(1, "Kelly Clarkson", "Since U Been Gone")

        assertTrue(sameSongAs(wanted, listOf(wanted)).isEmpty())
    }

    /**
     * A short word can be shared by two unrelated names — "the", "of", "and" — so a match has to
     * turn on something more substantial than that.
     */
    @Test
    fun `a shared little word is not a shared artist`() {
        val wanted = song(1, "The Monkees", "I'm A Believer")
        val found = listOf(song(2, "The Beatles", "I'm A Believer"))

        assertTrue(sameSongAs(wanted, found).isEmpty())
    }

    @Test
    fun `a song with no artist or title matches nothing`() {
        assertTrue(sameSongAs(song(1, "", "Title"), listOf(song(2, "", "Title"))).isEmpty())
        assertTrue(sameSongAs(song(1, "Artist", ""), listOf(song(2, "Artist", ""))).isEmpty())
    }

    private fun song(id: Int, artist: String, title: String) = UsdbSong(
        songId = id,
        artist = artist,
        title = title,
        genre = "",
        year = "",
        edition = "",
        hasGoldenNotes = false,
        language = "English",
        creator = "someone",
        rating = 0,
        views = 1,
        coverUrl = null,
        sampleUrl = null,
    )
}
