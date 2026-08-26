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
     * The deliberate miss, written down so it is not mistaken for a bug later.
     *
     * An artist whose name merely *contains* another is not the same act, so a chart billed as
     * plain "Moana" is not matched against one billed "Disney's Moana". That loses a genuine
     * alternative now and then, and it is the right way round: the rule that would find it is the
     * same rule that matches "Queen" to "Queens of the Stone Age", and a wrong replacement deletes
     * the original folder. A missed alternative is a song somebody searches for by hand.
     */
    @Test
    fun `an artist that merely contains another is not the same act`() {
        val wanted = song(1, "Disney's Moana", "How Far I'll Go")
        val found = listOf(song(2, "Moana", "How Far I'll Go"))

        assertTrue(sameSongAs(wanted, found).isEmpty())
    }

    /** The pair that turned that from a judgement call into a rule. */
    @Test
    fun `Queen is not Queens of the Stone Age`() {
        val wanted = song(1, "Queen", "Sail Away Sweet Sister")
        val found = listOf(song(2, "Queens of the Stone Age", "Sail Away Sweet Sister"))

        assertTrue(sameSongAs(wanted, found).isEmpty())
    }

    /**
     * The case measured on the real library, and the one the first version of this got wrong.
     *
     * "How Far I'll Go" is on USDB twice, as *Disney's Moana (Auli'i Cravalho)* and as *Disney's
     * Moana (Alessia Cara)* — the same song from the same film, billed to two different singers.
     * Comparing the letters of the whole artist rejects that pair, so the alternative was never
     * even checked; comparing them again with the bracketed credit removed accepts it.
     */
    @Test
    fun `a soundtrack billed to two different singers is the same song`() {
        val wanted = song(1, "Disney's Moana (Auli'i Cravalho)", "How Far I'll Go")
        val found = listOf(song(2, "Disney's Moana (Alessia Cara)", "How Far I'll Go"))

        assertEquals(listOf(2), sameSongAs(wanted, found).map { it.songId })
    }

    /** A bracket must not be able to make two different acts into one. */
    @Test
    fun `stripping the credit does not merge unrelated artists`() {
        val wanted = song(1, "The Monkees (Davy Jones)", "I'm A Believer")
        val found = listOf(song(2, "The Beatles (John Lennon)", "I'm A Believer"))

        assertTrue(sameSongAs(wanted, found).isEmpty())
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

    /**
     * The dangerous case, and the reason titles are compared for equality rather than containment.
     *
     * "Hello" is contained in "Hello Again". Under the old rule a song whose music had gone could
     * be silently replaced by a *different song* with a longer name -- which is the worst outcome
     * this file has, because nobody finds out until they press play on it.
     */
    @Test
    fun `a longer title by the same artist is a different song`() {
        val wanted = song(1, "Adele", "Hello")
        val found = listOf(song(2, "Adele", "Hello Again"))

        assertTrue(sameSongAs(wanted, found).isEmpty())
    }

    /** A remix or a re-recording is its own thing, and its own chart timing. */
    @Test
    fun `a bracketed arrangement in the title is not the same recording`() {
        val wanted = song(1, "Disney's Moana", "How Far I'll Go")
        val found = listOf(song(2, "Disney's Moana", "How Far I'll Go (Hardstyle)"))

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
