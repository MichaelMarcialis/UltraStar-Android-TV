package com.example.ultrastarandroidtv.usdb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parsing is tested against **real captured markup** — `test/resources/usdb/search_results.html`
 * is a genuine results page from 2026-08-20, trimmed to three songs and with the account name
 * replaced. Hand-written HTML would only prove the parser agrees with my idea of the page, which
 * is the thing actually in doubt: USDB's markup is old, partly unclosed, and not what you would
 * write today.
 */
class UsdbSearchTest {

    private val realPage: String by lazy {
        checkNotNull(javaClass.getResourceAsStream("/usdb/search_results.html")) {
            "fixture missing"
        }.use { it.readBytes().decodeToString() }
    }

    // -----------------------------------------------------------------------------------------
    // Reading a real page
    // -----------------------------------------------------------------------------------------

    @Test
    fun `reads every song on the page`() {
        val page = parseSearchPage(realPage)
        assertEquals(3, page.songs.size)
        assertEquals(listOf(17720, 19308, 19535), page.songs.map { it.songId })
    }

    @Test
    fun `reads the fields a person chooses a song by`() {
        val song = parseSearchPage(realPage).songs.first()
        assertEquals("David Bowie", song.artist)
        assertEquals("China Girl", song.title)
        assertEquals("1983", song.year)
        assertEquals("English", song.language)
        assertEquals("thursday", song.creator)
        assertEquals(584, song.views)
        assertTrue(song.hasGoldenNotes)
    }

    @Test
    fun `reads the fields that are often blank`() {
        val young = parseSearchPage(realPage).songs.last()
        assertEquals("Glam Rock", young.genre)
        assertEquals("Rock Band Store 2011 Vol. 1", young.edition)
        // The first song has neither, and blank must not shift the other columns along.
        val china = parseSearchPage(realPage).songs.first()
        assertEquals("", china.genre)
        assertEquals("", china.edition)
        assertEquals("China Girl", china.title)
    }

    @Test
    fun `golden notes is a yes or no, not a truthy string`() {
        val songs = parseSearchPage(realPage).songs
        assertTrue(songs[0].hasGoldenNotes)
        assertFalse(songs[1].hasGoldenNotes)
        assertTrue(songs[2].hasGoldenNotes)
    }

    /** The two things that make results browsable rather than a wall of text. */
    @Test
    fun `finds the cover and the audio sample`() {
        val song = parseSearchPage(realPage).songs.first()
        assertEquals("https://usdb.animux.de/data/cover/17720.jpg", song.coverUrl)
        assertNotNull(song.sampleUrl)
        assertTrue(song.sampleUrl!!.startsWith("https://audio-ssl.itunes.apple.com/"))
    }

    @Test
    fun `reads the result and page counts`() {
        val page = parseSearchPage(realPage)
        assertEquals(51, page.totalResults)
        assertEquals(2, page.totalPages)
        assertTrue(page.hasMore)
    }

    @Test
    fun `knows when there is nothing after this page`() {
        assertFalse(parseSearchPage(realPage, page = 1).hasMore)
    }

    /**
     * An unrated song shows five *empty* stars, so counting every star image would rate the whole
     * database five out of five.
     */
    @Test
    fun `empty stars are not a rating`() {
        assertEquals(0, parseSearchPage(realPage).songs.first().rating)
    }

    @Test
    fun `names a song the way the library would`() {
        assertEquals("David Bowie - China Girl", parseSearchPage(realPage).songs.first().folderName)
    }

    // -----------------------------------------------------------------------------------------
    // Columns move, so they are found rather than counted
    // -----------------------------------------------------------------------------------------

    /**
     * `details=1` inserts Sample and Cover at the *front*, so artist is column 2 with details on
     * and column 0 with it off. Anything that counted positions would be wrong in one of the two.
     */
    @Test
    fun `locates columns by their header id`() {
        val columns = columnIndexes(realPage)
        assertEquals(2, columns["list_artist"])
        assertEquals(3, columns["list_title"])
        assertEquals(11, columns["list_views"])
    }

    @Test
    fun `reads a page whose columns are shifted`() {
        // The same page as USDB renders it with details off: no sample, no cover.
        val shifted = realPage
            .replace("<td>&nbsp;</td>", "")
            .let { stripLeadingColumns(it) }
        val page = parseSearchPage(shifted)
        assertEquals("David Bowie", page.songs.first().artist)
        assertEquals("China Girl", page.songs.first().title)
    }

    /** No header means no way to know what any cell is, which must yield nothing rather than junk. */
    @Test
    fun `a page with no header yields no songs`() {
        val headless = realPage.replace("list_head", "something_else")
        assertTrue(parseSearchPage(headless).songs.isEmpty())
    }

    @Test
    fun `a page with no results is empty rather than broken`() {
        val empty = """<html><br>There are  0  results on  0 page(s)<br><br>
            <table><tr class="list_head"><td><a id="list_artist">Artist</a></td></tr></table></html>"""
        val page = parseSearchPage(empty)
        assertTrue(page.songs.isEmpty())
        assertEquals(0, page.totalResults)
        assertFalse(page.hasMore)
    }

    // -----------------------------------------------------------------------------------------
    // Building the search
    // -----------------------------------------------------------------------------------------

    @Test
    fun `always asks for the cover and sample columns`() {
        assertEquals("1", searchFields(SongFilter(), 0)["details"])
    }

    @Test
    fun `sends the filter under the names usdb uses`() {
        val fields = searchFields(
            SongFilter(artist = "Bowie", title = "Heroes", language = "English", year = "1977"),
            page = 0,
        )
        assertEquals("Bowie", fields["interpret"])
        assertEquals("Heroes", fields["title"])
        assertEquals("English", fields["language"])
        assertEquals("1977", fields["year"])
    }

    /**
     * PHP of this vintage reads a checkbox with `isset()`, so sending `golden=0` would switch the
     * filter *on*. An unchecked box has to be absent, not false.
     */
    @Test
    fun `an unticked checkbox is left out entirely`() {
        assertFalse(searchFields(SongFilter(goldenNotesOnly = false), 0).containsKey("golden"))
        assertEquals("1", searchFields(SongFilter(goldenNotesOnly = true), 0)["golden"])
    }

    @Test
    fun `pages by offset, not by page number`() {
        assertEquals("0", searchFields(SongFilter(pageSize = 30), 0)["start"])
        assertEquals("30", searchFields(SongFilter(pageSize = 30), 1)["start"])
        assertEquals("100", searchFields(SongFilter(pageSize = 50), 2)["start"])
    }

    @Test
    fun `sends the sort order`() {
        val fields = searchFields(SongFilter(order = SongOrder.VIEWS, ascending = false), 0)
        assertEquals("views", fields["order"])
        assertEquals("desc", fields["ud"])
    }

    @Test
    fun `trims what was typed`() {
        assertEquals("Bowie", searchFields(SongFilter(artist = "  Bowie  "), 0)["interpret"])
    }

    @Test
    fun `an untouched filter is empty`() {
        assertTrue(SongFilter().isEmpty)
        assertFalse(SongFilter(artist = "Bowie").isEmpty)
        assertFalse(SongFilter(goldenNotesOnly = true).isEmpty)
    }

    // -----------------------------------------------------------------------------------------
    // Text
    // -----------------------------------------------------------------------------------------

    @Test
    fun `decodes the entities that turn up in titles`() {
        assertEquals("Rock & Roll", unescapeHtml("Rock &amp; Roll"))
        assertEquals("\"Heroes\"", unescapeHtml("&quot;Heroes&quot;"))
        assertEquals("Don't", unescapeHtml("Don&#39;t"))
        assertEquals("Für", unescapeHtml("F&uuml;r"))
        assertEquals("—", unescapeHtml("&#x2014;"))
    }

    @Test
    fun `leaves text without entities alone`() {
        assertEquals("China Girl", unescapeHtml("China Girl"))
    }

    @Test
    fun `an unknown entity is left as written rather than dropped`() {
        assertEquals("&frobnicate;", unescapeHtml("&frobnicate;"))
    }

    @Test
    fun `strips markup and collapses whitespace`() {
        assertEquals("China Girl", htmlToText("""<a href="?x">China   Girl</a>"""))
        assertEquals("A B", htmlToText("A\n\t  B"))
    }

    // -----------------------------------------------------------------------------------------

    /** Removes the first two `<td>` of every row and of the header, as `details=0` would. */
    private fun stripLeadingColumns(html: String): String {
        val cell = Regex("""<td[^>]*>.*?</td>""", RegexOption.DOT_MATCHES_ALL)
        return Regex("""<tr[^>]*>.*?(?=<tr[ >]|</table>)""", RegexOption.DOT_MATCHES_ALL)
            .replace(html) { row ->
                var dropped = 0
                cell.replace(row.value) { if (dropped++ < 2) "" else it.value }
            }
    }

    // -----------------------------------------------------------------------------------------
    // One box that searches both fields
    // -----------------------------------------------------------------------------------------

    /**
     * USDB's form has no field spanning artist and title, so one keyword has to become two
     * searches. Measured on the live site: `interpret=gone` finds one song on the whole of USDB
     * and `title=gone` finds fifty-nine, so running only one of them loses most of the answer.
     */
    @Test
    fun `one keyword becomes an artist search and a title search`() {
        val (byArtist, byTitle) = keywordSearches(SongFilter(keyword = "gone"))

        assertEquals("gone", byArtist.artist)
        assertEquals("", byArtist.title)
        assertEquals("gone", byTitle.title)
        assertEquals("", byTitle.artist)
    }

    /**
     * The search people type most often, and the one the two above cannot answer.
     *
     * USDB matches each field as a *substring*, so "beatles yesterday" is contained in no artist
     * and in no title and used to return nothing at all. Split at the first space it is two real
     * server-side matches rather than a guess made here.
     */
    @Test
    fun `two words are also sent as an artist and a title`() {
        val searches = keywordSearches(SongFilter(keyword = "beatles yesterday"))

        assertEquals(3, searches.size)
        val split = searches[2]
        assertEquals("beatles", split.artist)
        assertEquals("yesterday", split.title)
        assertEquals("", split.keyword)
    }

    /** Everything after the first space is the title, so "abba dancing queen" keeps its song whole. */
    @Test
    fun `only the first word becomes the artist`() {
        val split = keywordSearches(SongFilter(keyword = "abba dancing queen"))[2]

        assertEquals("abba", split.artist)
        assertEquals("dancing queen", split.title)
    }

    @Test
    fun `one word is not split`() {
        assertEquals(2, keywordSearches(SongFilter(keyword = "queen")).size)
    }

    /** With a field already named, splitting would be arguing with what was asked for. */
    @Test
    fun `a keyword is not split when the artist was given explicitly`() {
        val searches = keywordSearches(SongFilter(keyword = "dancing queen", artist = "abba"))

        assertEquals(2, searches.size)
    }

    /**
     * The band's name is as often last as first.
     *
     * "god gave kiss" is somebody remembering three words of a song and the group who sang it, in
     * the order they came to mind. Splitting at the *first* space reads that backwards — artist
     * "god", title "gave kiss" — and finds nothing, which is what happened on the television.
     */
    @Test
    fun `a last resort puts the last word in the artist field`() {
        val tries = lastResorts(SongFilter(keyword = "god gave kiss"))

        val split = tries.first()
        assertEquals("kiss", split.artist)
        assertEquals("god gave", split.title)
        assertEquals("", split.keyword)
    }

    /**
     * "ymca" finds nothing because the song is filed as "Y.M.C.A." and USDB matches substrings,
     * so the letters somebody types are never contiguous in the title.
     */
    @Test
    fun `a last resort spells a short word out with full stops`() {
        assertEquals("y.m.c.a", dottedAcronym("ymca"))
        assertEquals("s.o.s", dottedAcronym("SOS"))

        val tries = lastResorts(SongFilter(keyword = "ymca"))
        assertEquals(1, tries.size)
        assertEquals("y.m.c.a", tries.first().title)
        assertEquals("", tries.first().artist)
    }

    @Test
    fun `only a short word of letters is spelled out`() {
        assertNull(dottedAcronym("a"))
        assertNull(dottedAcronym("yesterday"))
        assertNull(dottedAcronym("abba1"))
        assertNull(dottedAcronym("two words"))
    }

    /** With a field already named, a last resort would be arguing with what was asked for. */
    @Test
    fun `nothing is tried when the artist or title was given explicitly`() {
        assertTrue(lastResorts(SongFilter(keyword = "god gave kiss", artist = "kiss")).isEmpty())
        assertTrue(lastResorts(SongFilter(keyword = "ymca", title = "y")).isEmpty())
    }

    /** Neither half may keep the keyword, or the second search would run it a third time. */
    @Test
    fun `the keyword is spent once it has been split`() {
        keywordSearches(SongFilter(keyword = "abba")).forEach {
            assertEquals("", it.keyword)
        }
    }

    @Test
    fun `everything else about the search is carried across unchanged`() {
        val filter = SongFilter(
            keyword = "queen",
            language = "English",
            goldenNotesOnly = true,
            order = SongOrder.TITLE,
            ascending = false,
            pageSize = 50,
        )
        keywordSearches(filter).forEach {
            assertEquals("English", it.language)
            assertTrue(it.goldenNotesOnly)
            assertEquals(SongOrder.TITLE, it.order)
            assertFalse(it.ascending)
            assertEquals(50, it.pageSize)
        }
    }

    @Test
    fun `a keyword counts as having searched for something`() {
        assertFalse(SongFilter(keyword = "abba").isEmpty)
        assertTrue(SongFilter().isEmpty)
    }

    // -----------------------------------------------------------------------------------------
    // Folding the two answers into one list
    // -----------------------------------------------------------------------------------------

    @Test
    fun `artist matches come first, then title matches`() {
        val merged = mergePages(
            page(listOf(song(1), song(2)), total = 2),
            page(listOf(song(3)), total = 1),
            page = 0,
        )
        assertEquals(listOf(1, 2, 3), merged.songs.map { it.songId })
    }

    /** A song matching on both halves is one song, and must be counted once. */
    @Test
    fun `a song found by both searches appears once and is counted once`() {
        val merged = mergePages(
            page(listOf(song(1), song(2)), total = 2),
            page(listOf(song(2), song(3)), total = 2),
            page = 0,
        )
        assertEquals(listOf(1, 2, 3), merged.songs.map { it.songId })
        assertEquals(3, merged.totalResults)
    }

    @Test
    fun `the count can never be smaller than what is on screen`() {
        val merged = mergePages(
            page(listOf(song(1), song(2)), total = 0),
            page(emptyList(), total = 0),
            page = 0,
        )
        assertEquals(2, merged.totalResults)
    }

    /** More to see on either side means there is more to see. */
    @Test
    fun `there is more to come while either search has more pages`() {
        val merged = mergePages(
            page(listOf(song(1)), total = 1, pages = 1),
            page(listOf(song(2)), total = 90, pages = 3),
            page = 0,
        )
        assertTrue(merged.hasMore)
        assertEquals(3, merged.totalPages)
    }

    @Test
    fun `nothing on either side is nothing at all`() {
        val merged = mergePages(page(emptyList(), 0, 0), page(emptyList(), 0, 0), page = 0)
        assertTrue(merged.songs.isEmpty())
        assertEquals(0, merged.totalResults)
        assertFalse(merged.hasMore)
    }

    private fun page(songs: List<UsdbSong>, total: Int, pages: Int = 1) =
        SearchPage(songs = songs, totalResults = total, totalPages = pages, page = 0)

    private fun song(id: Int) = UsdbSong(
        songId = id,
        artist = "Artist $id",
        title = "Title $id",
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

}
