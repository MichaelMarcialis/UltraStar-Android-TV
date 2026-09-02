package com.example.ultrastarandroidtv.ui

import com.example.ultrastarandroidtv.usdb.UsdbSong
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What "closest answer first" means on a page of USDB results.
 *
 * The ordering is graded rather than split in two, and the cases worth pinning are the ones where
 * the old coarse rule — every artist match, then everything else — got it wrong.
 */
class ResultOrderTest {

    private fun song(artist: String, title: String, id: Int = artist.hashCode() + title.hashCode()) =
        UsdbSong(
            songId = id,
            artist = artist,
            title = title,
            genre = "",
            year = "",
            edition = "",
            hasGoldenNotes = false,
            language = "",
            creator = "",
            rating = 0,
            views = 0,
            coverUrl = null,
            sampleUrl = null,
        )

    @Test
    fun `the band somebody named comes first`() {
        val results = listOf(
            song("Queens of the Stone Age", "No One Knows"),
            song("Queen", "Bohemian Rhapsody"),
            song("ABBA", "Dancing Queen"),
        )

        assertEquals(
            listOf("Queen", "Queens of the Stone Age", "ABBA"),
            orderedForDisplay(results, "queen").map { it.artist },
        )
    }

    @Test
    fun `an exact title beats an artist the query merely begins`() {
        val results = listOf(
            song("Yesterday's News", "Something Else"),
            song("The Beatles", "Yesterday"),
        )

        assertEquals(
            listOf("Yesterday", "Something Else"),
            orderedForDisplay(results, "yesterday").map { it.title },
        )
    }

    @Test
    fun `punctuation cannot change the ranking`() {
        // The same normalisation the library search uses, so "ymca" is as good a match for
        // "Y.M.C.A." as it looks to the person who typed it.
        assertEquals(1, relevance(song("Village People", "Y.M.C.A."), "ymca"))
        assertEquals(0, relevance(song("a-ha", "Take On Me"), "aha"))
    }

    @Test
    fun `songs of equal standing are filed past the leading article`() {
        val results = listOf(
            song("The Zombies", "Love Song"),
            song("Adele", "Love Song"),
            song("The Cure", "Love Song"),
        )

        // All three are an exact title match, so the artist decides — and "The Cure" files under C.
        assertEquals(
            listOf("Adele", "The Cure", "The Zombies"),
            orderedForDisplay(results, "love song").map { it.artist },
        )
    }

    @Test
    fun `with nothing typed it is simply alphabetical`() {
        val results = listOf(song("The Beatles", "Help"), song("ABBA", "SOS"))

        assertEquals(
            listOf("ABBA", "The Beatles"),
            orderedForDisplay(results, "").map { it.artist },
        )
    }
}
