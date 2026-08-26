package com.example.ultrastarandroidtv.library

import com.example.ultrastarandroidtv.song.SongMetadata
import com.example.ultrastarandroidtv.song.UltraStarSong
import com.example.ultrastarandroidtv.song.VoicePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Arranging a library that somebody actually has to find something in.
 *
 * All pure, which is the point: the rules for what a grid shows and where a letter jumps to are
 * ordinary logic over a list, and testing them needs neither a card nor a television.
 */
class SongBrowsingTest {

    // -------------------------------------------------------------------------------------
    // Sorting
    // -------------------------------------------------------------------------------------

    @Test
    fun `sorts by title, ignoring case`() {
        val songs = listOf(song("zebra", "A"), song("Apple", "B"), song("mango", "C"))

        assertEquals(
            listOf("Apple", "mango", "zebra"),
            arrange(songs, SongSort.Title, SongFilterState.All).map { it.song.metadata.title },
        )
    }

    /**
     * The tie-break is what stops an artist's songs shuffling between visits. Without one they come
     * out in whatever order the card was walked in, which changes with every rescan.
     */
    @Test
    fun `songs by one artist keep a stable order`() {
        val songs = listOf(
            song("Starman", "David Bowie"),
            song("Golden Years", "David Bowie"),
            song("Modern Love", "David Bowie"),
        )

        assertEquals(
            listOf("Golden Years", "Modern Love", "Starman"),
            arrange(songs, SongSort.Artist, SongFilterState.All).map { it.song.metadata.title },
        )
    }

    @Test
    fun `falls back to the folder name when a song has no title`() {
        val nameless = song("", "", folder = "Some Folder")

        assertEquals("Some Folder", sortKeyOf(nameless, SongSort.Title))
        assertEquals("Some Folder", sortKeyOf(nameless, SongSort.Artist))
    }

    /**
     * The comparator has to be a *total* order, not merely a sort.
     *
     * A duet arrangement sits beside its original sharing both title and artist, which the scanner
     * supports on purpose. Tied, the two keep whatever order the card happened to be walked in —
     * and that is not the same order twice, so they would quietly swap places between rescans.
     */
    @Test
    fun `two arrangements of one song keep their order between scans`() {
        val original = song("Circle Of Life", "Elton John", folder = "Elton John - Circle Of Life")
            .copy(textId = "text-original")
        val duet = song("Circle Of Life", "Elton John", folder = "Elton John - Circle Of Life")
            .copy(textId = "text-duet")

        val oneWalk = listOf(original, duet).sortedWith(songOrder(SongSort.Title))
        val theOther = listOf(duet, original).sortedWith(songOrder(SongSort.Title))

        assertEquals(oneWalk.map { it.textId }, theOther.map { it.textId })
    }

    /** And the same holds when they are in different folders with the same name ordering. */
    @Test
    fun `songs sharing a title and artist are separated by their folder`() {
        val a = song("Hello", "Adele", folder = "Adele - Hello").copy(textId = "t1")
        val b = song("Hello", "Adele", folder = "Adele - Hello (2)").copy(textId = "t2")

        assertEquals(
            listOf("t1", "t2"),
            listOf(b, a).sortedWith(songOrder(SongSort.Title)).map { it.textId },
        )
    }

    // -------------------------------------------------------------------------------------
    // Filtering
    // -------------------------------------------------------------------------------------

    @Test
    fun `each filter shows exactly what it says`() {
        val complete = song("A", "X", audio = "a", video = "v", cover = "c")
        val noMusic = song("B", "X", audio = null, video = "v", cover = "c")
        val noVideo = song("C", "X", audio = "a", video = null, cover = "c")
        val noCover = song("D", "X", audio = "a", video = "v", cover = null)
        val all = listOf(complete, noMusic, noVideo, noCover)

        assertEquals(4, arrange(all, SongSort.Title, SongFilterState.All).size)
        assertEquals(
            listOf("A", "C", "D"),
            arrange(all, SongSort.Title, SongFilterState.Ready).map { it.song.metadata.title },
        )
        assertEquals(
            listOf("B"),
            arrange(all, SongSort.Title, SongFilterState.MissingMusic).map { it.song.metadata.title },
        )
        assertEquals(
            listOf("C"),
            arrange(all, SongSort.Title, SongFilterState.MissingVideo).map { it.song.metadata.title },
        )
        assertEquals(
            listOf("D"),
            arrange(all, SongSort.Title, SongFilterState.MissingArtwork).map { it.song.metadata.title },
        )
    }

    /** A song with no music is not "ready" however much artwork it has. */
    @Test
    fun `only the music decides whether a song is ready`() {
        assertTrue(matches(song("A", "X", audio = "a"), SongFilterState.Ready))
        assertFalse(matches(song("A", "X", audio = null, cover = "c"), SongFilterState.Ready))
    }

    // -------------------------------------------------------------------------------------
    // The letter rail
    // -------------------------------------------------------------------------------------

    /**
     * Only the letters that are there. A full A-Z on a fifty-song library is mostly dead ends, and
     * on a remote a dead end costs a press to find and a press to leave.
     */
    @Test
    fun `offers only the letters something files under`() {
        val songs = listOf(song("Apple", "X"), song("Banana", "Y"), song("Apricot", "Z"))

        assertEquals(listOf('A', 'B'), indexLetters(songs, SongSort.Title))
    }

    /** "9 To 5" and "'74-'75" are real songs on this card and neither begins with a letter. */
    @Test
    fun `anything not starting with a letter files under hash, first`() {
        assertEquals('#', indexLetterOf("9 To 5"))
        assertEquals('#', indexLetterOf("'74-'75"))
        assertEquals('#', indexLetterOf(""))
        assertEquals('A', indexLetterOf("  a-ha"))

        val songs = listOf(song("Zebra", "X"), song("9 To 5", "Y"), song("Apple", "Z"))
        assertEquals(listOf('#', 'A', 'Z'), indexLetters(songs, SongSort.Title))
    }

    /**
     * The rail has to be built from the list a letter jumps *into*.
     *
     * Built from the whole library instead, a filter like "No music" left letters on the rail
     * with nothing behind them — pressing one found no song and silently did nothing, which on
     * a remote cannot be told apart from a broken button.
     */
    @Test
    fun `every letter offered has something behind it`() {
        val songs = listOf(
            song("Apple", "X", audio = null),
            song("Banana", "Y", audio = "a"),
            song("Cherry", "Z", audio = "a"),
        )
        val broken = arrange(songs, SongSort.Title, SongFilterState.MissingMusic)

        assertEquals(listOf('A'), indexLetters(broken, SongSort.Title))
        for (letter in indexLetters(broken, SongSort.Title)) {
            assertTrue(
                "the rail must not offer a letter that jumps nowhere",
                firstIndexUnder(broken, SongSort.Title, letter) >= 0,
            )
        }
    }

    @Test
    fun `a letter jumps to the first song filed under it`() {
        val songs = listOf(song("Apple", "X"), song("Banana", "Y"), song("Blossom", "Z"))
        val arranged = arrange(songs, SongSort.Title, SongFilterState.All)

        assertEquals(0, firstIndexUnder(arranged, SongSort.Title, 'A'))
        assertEquals(1, firstIndexUnder(arranged, SongSort.Title, 'B'))
        assertEquals(-1, firstIndexUnder(arranged, SongSort.Title, 'Q'))
    }

    /** Sorting by artist has to re-index the rail, or the letters point at the wrong rows. */
    @Test
    fun `the rail follows what the list is sorted by`() {
        val songs = listOf(song("Apple", "Zebra"), song("Zoo", "Aardvark"))

        assertEquals(listOf('A', 'Z'), indexLetters(songs, SongSort.Title))
        assertEquals(listOf('A', 'Z'), indexLetters(songs, SongSort.Artist))

        val byArtist = arrange(songs, SongSort.Artist, SongFilterState.All)
        assertEquals(0, firstIndexUnder(byArtist, SongSort.Artist, 'A'))
        assertEquals("Zoo", byArtist.first().song.metadata.title)
    }

    private fun song(
        title: String,
        artist: String,
        folder: String = "$artist - $title",
        audio: String? = "audio",
        video: String? = null,
        cover: String? = null,
    ) = ScannedSong(
        song = UltraStarSong(
            metadata = SongMetadata(title = title, artist = artist, mp3 = "x.mp3", bpm = 120.0),
            voiceParts = listOf(VoicePart(label = null, lines = emptyList())),
        ),
        folderId = "folder-$folder",
        textId = "text-$folder",
        folderName = folder,
        audioId = audio,
        videoId = video,
        coverId = cover,
        backgroundId = null,
    )
}
