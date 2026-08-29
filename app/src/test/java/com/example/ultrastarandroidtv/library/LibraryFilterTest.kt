package com.example.ultrastarandroidtv.library

import com.example.ultrastarandroidtv.song.SongMetadata
import com.example.ultrastarandroidtv.song.UltraStarSong
import com.example.ultrastarandroidtv.song.VoicePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Narrowing the song picker: what somebody typed, and the three questions worth asking about a
 * library that has grown past what fits on a television.
 *
 * Every rule here was written against what is actually on the card, which is why several of them
 * are less clever than they might be — see the notes on genre.
 */
class LibraryFilterTest {

    // -------------------------------------------------------------------------------------
    // Searching
    // -------------------------------------------------------------------------------------

    @Test
    fun `an empty query keeps everything`() {
        val songs = listOf(song("Yellow", "Coldplay"), song("Creep", "Radiohead"))
        assertEquals(songs.size, browse(songs, SongSort.Title, LibraryFilter()).size)
        assertEquals(songs.size, browse(songs, SongSort.Title, LibraryFilter(query = "   ")).size)
    }

    @Test
    fun `searches the artist as well as the title`() {
        // Half of remembering a song is remembering who sang it, and on a remote nobody wants to
        // be told which of two fields they are typing into.
        val songs = listOf(song("Yellow", "Coldplay"), song("Creep", "Radiohead"))

        assertEquals(
            listOf("Yellow"),
            browse(songs, SongSort.Title, LibraryFilter(query = "cold")).map { it.song.metadata.title },
        )
        assertEquals(
            listOf("Creep"),
            browse(songs, SongSort.Title, LibraryFilter(query = "ree")).map { it.song.metadata.title },
        )
    }

    @Test
    fun `searching ignores case, because the keyboard only offers capitals`() {
        val songs = listOf(song("Yellow", "Coldplay"))
        assertEquals(1, browse(songs, SongSort.Title, LibraryFilter(query = "YELL")).size)
        assertEquals(1, browse(songs, SongSort.Title, LibraryFilter(query = "yell")).size)
    }

    // -------------------------------------------------------------------------------------
    // Genre
    // -------------------------------------------------------------------------------------

    @Test
    fun `a genre field holding a list is read as a list`() {
        // Four songs on the real card are tagged "Soundtrack, K-Pop". Read whole, that is a genre
        // nothing else shares, and both of the genres it actually names would go missing.
        val song = song("Golden", "Huntrix", genre = "Soundtrack, K-Pop")
        assertEquals(listOf("Soundtrack", "K-Pop"), genresOf(song))
    }

    @Test
    fun `spellings of one genre are offered once`() {
        // "Pop Rock" and "Pop-Rock" are both on the card. Two chips for one genre is two dead
        // ends where one would do.
        val songs = listOf(
            song("A", "One", genre = "Pop-Rock"),
            song("B", "Two", genre = "Pop Rock"),
            song("C", "Three", genre = "pop rock"),
        )
        assertEquals(1, genreChoices(songs).size)
        // All three are reachable from the single chip.
        val chosen = genreChoices(songs).first()
        assertEquals(3, browse(songs, SongSort.Title, LibraryFilter(genre = chosen)).size)
    }

    @Test
    fun `a longer genre is not folded into a shorter one`() {
        // The control for the rule above. Deciding "Rock" and "Pop Rock" are the same genre is an
        // opinion, and one the person who wrote the chart did not share.
        val songs = listOf(song("A", "One", genre = "Rock"), song("B", "Two", genre = "Pop Rock"))
        assertEquals(2, genreChoices(songs).size)
        assertEquals(1, browse(songs, SongSort.Title, LibraryFilter(genre = "Rock")).size)
    }

    @Test
    fun `genres are offered commonest first`() {
        // A remote pays per press, so the genre most of the library is in should be the first one
        // reached rather than whichever happened to be scanned first.
        val songs = listOf(
            song("A", "One", genre = "Rock"),
            song("B", "Two", genre = "Pop"),
            song("C", "Three", genre = "Pop"),
            song("D", "Four", genre = "Pop"),
        )
        assertEquals(listOf("Pop", "Rock"), genreChoices(songs))
    }

    @Test
    fun `a song with no genre is offered under none of them`() {
        // Twenty-six of the charts on this card have no genre line at all. They are not a genre,
        // and inventing "Unknown" for them would put a chip on the screen nobody is looking for.
        val songs = listOf(song("A", "One"), song("B", "Two", genre = "Pop"))
        assertEquals(listOf("Pop"), genreChoices(songs))
        assertEquals(1, browse(songs, SongSort.Title, LibraryFilter(genre = "Pop")).size)
    }

    // -------------------------------------------------------------------------------------
    // Decade
    // -------------------------------------------------------------------------------------

    @Test
    fun `a year becomes the decade it falls in`() {
        assertEquals(1970, decadeOf(song("A", "One", year = 1971)))
        assertEquals(1970, decadeOf(song("A", "One", year = 1979)))
        assertEquals(1980, decadeOf(song("A", "One", year = 1980)))
    }

    @Test
    fun `a missing or nonsense year is not a decade`() {
        assertEquals(null, decadeOf(song("A", "One")))
        // Charts are typed by hand and "97" for 1997 happens. Filing that under "90s" would be a
        // guess; showing a "90s" chip that means the year ninety is worse than showing neither.
        assertEquals(null, decadeOf(song("A", "One", year = 97)))
    }

    @Test
    fun `decades are offered oldest first and only where there is something`() {
        val songs = listOf(
            song("A", "One", year = 2015),
            song("B", "Two", year = 1984),
            song("C", "Three", year = 1987),
            song("D", "Four"),
        )
        assertEquals(listOf(1980, 2010), decadeChoices(songs))
        assertEquals(2, browse(songs, SongSort.Title, LibraryFilter(decade = 1980)).size)
    }

    @Test
    fun `a decade is written the way anybody would say it`() {
        assertEquals("1980s", decadeLabel(1980))
    }

    // -------------------------------------------------------------------------------------
    // Duet or versus
    // -------------------------------------------------------------------------------------

    @Test
    fun `a chart with two parts is a duet and one with a single part is not`() {
        assertTrue(isDuetChart(song("A", "One", parts = 2)))
        assertFalse(isDuetChart(song("B", "Two", parts = 1)))
    }

    @Test
    fun `mode picks out duets and versus songs`() {
        val songs = listOf(song("Duet", "One", parts = 2), song("Solo", "Two", parts = 1))

        assertEquals(2, browse(songs, SongSort.Title, LibraryFilter(mode = SongMode.Any)).size)
        assertEquals(
            listOf("Duet"),
            browse(songs, SongSort.Title, LibraryFilter(mode = SongMode.Duet))
                .map { it.song.metadata.title },
        )
        assertEquals(
            listOf("Solo"),
            browse(songs, SongSort.Title, LibraryFilter(mode = SongMode.Versus))
                .map { it.song.metadata.title },
        )
    }

    // -------------------------------------------------------------------------------------
    // All of it at once
    // -------------------------------------------------------------------------------------

    @Test
    fun `the filters narrow together rather than replacing each other`() {
        val songs = listOf(
            song("Dancing Queen", "ABBA", genre = "Pop", year = 1976, parts = 2),
            song("Waterloo", "ABBA", genre = "Pop", year = 1974, parts = 1),
            song("Creep", "Radiohead", genre = "Rock", year = 1992, parts = 1),
        )

        val filter = LibraryFilter(query = "abba", genre = "Pop", decade = 1970, mode = SongMode.Duet)
        assertEquals(
            listOf("Dancing Queen"),
            browse(songs, SongSort.Title, filter).map { it.song.metadata.title },
        )
    }

    @Test
    fun `a filtered library is still filed the same way as an unfiltered one`() {
        // The letters under the row are built from the filtered list, so it has to be sorted by
        // the same comparator or a letter would jump somewhere else.
        val songs = listOf(
            song("The Wall", "Pink Floyd", genre = "Rock"),
            song("Apple", "Zed", genre = "Rock"),
            song("Creep", "Radiohead", genre = "Pop"),
        )
        val filtered = browse(songs, SongSort.Title, LibraryFilter(genre = "Rock"))
        // "The Wall" files under W, so it comes after "Apple" rather than under T.
        assertEquals(listOf("Apple", "The Wall"), filtered.map { it.song.metadata.title })
        assertEquals(listOf('A', 'W'), indexLetters(filtered, SongSort.Title))
    }

    @Test
    fun `an empty filter knows it is empty`() {
        assertTrue(LibraryFilter().isEmpty)
        assertTrue(LibraryFilter(query = "  ").isEmpty)
        assertFalse(LibraryFilter(query = "a").isEmpty)
        assertFalse(LibraryFilter(genre = "Pop").isEmpty)
        assertFalse(LibraryFilter(decade = 1980).isEmpty)
        assertFalse(LibraryFilter(mode = SongMode.Duet).isEmpty)
    }

    private fun song(
        title: String,
        artist: String,
        genre: String? = null,
        year: Int? = null,
        parts: Int = 1,
    ) = ScannedSong(
        song = UltraStarSong(
            metadata = SongMetadata(
                title = title,
                artist = artist,
                mp3 = "x.mp3",
                bpm = 120.0,
                genre = genre,
                year = year,
            ),
            voiceParts = List(parts) { VoicePart(label = null, lines = emptyList()) },
        ),
        folderId = "folder-$artist-$title",
        textId = "text-$artist-$title",
        folderName = "$artist - $title",
        audioId = "audio",
        videoId = null,
        coverId = null,
        backgroundId = null,
    )
}
