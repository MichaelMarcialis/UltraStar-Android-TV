package com.example.ultrastarandroidtv.usdb

/** The site root, for turning the relative paths in its markup into fetchable URLs. */
const val USDB_ROOT = "https://usdb.animux.de/"

/** What to sort a search by. The value is the name USDB's own form uses. */
enum class SongOrder(val field: String) {
    DATE("id"),
    ARTIST("interpret"),
    TITLE("title"),
    GENRE("genre"),
    YEAR("year"),
    EDITION("edition"),
    GOLDEN_NOTES("golden"),
    LANGUAGE("language"),
    CREATOR("autor"),
    RATING("rating"),
    VIEWS("views"),
    LAST_MODIFIED("lastchange"),
}

/**
 * A search. Every text field is a substring match on USDB's side, and an empty one is simply not
 * a constraint — so the default is "everything", which is 28,623 songs and a perfectly reasonable
 * thing to browse.
 */
data class SongFilter(
    /**
     * One box that searches artist *and* title — what somebody actually types when they want a
     * song. USDB has no field that spans both, so this becomes two searches; see [UsdbSearch].
     * Set alongside [artist] or [title] it simply adds to them.
     */
    val keyword: String = "",
    val artist: String = "",
    val title: String = "",
    val edition: String = "",
    val language: String = "",
    val genre: String = "",
    val year: String = "",
    val creator: String = "",
    val goldenNotesOnly: Boolean = false,
    val order: SongOrder = SongOrder.ARTIST,
    val ascending: Boolean = true,
    val pageSize: Int = 30,
) {
    /** True when nothing has been narrowed down — useful for deciding what to show on arrival. */
    val isEmpty: Boolean
        get() = keyword.isBlank() && artist.isBlank() && title.isBlank() && edition.isBlank() &&
            language.isBlank() && genre.isBlank() && year.isBlank() && creator.isBlank() &&
            !goldenNotesOnly
}

/**
 * One song as the search list describes it.
 *
 * Enough to decide whether you want it without opening anything: who it is by, what it is called,
 * a cover to recognise it from and a thirty-second sample to hear it. [songId] is the only field
 * needed to actually fetch it.
 */
data class UsdbSong(
    val songId: Int,
    val artist: String,
    val title: String,
    val genre: String,
    val year: String,
    val edition: String,
    val hasGoldenNotes: Boolean,
    val language: String,
    val creator: String,
    /** Stars out of five, 0 when nobody has rated it. */
    val rating: Int,
    val views: Int,
    /** 200x200 JPEG on USDB, already absolute. Null when the song has no cover. */
    val coverUrl: String?,
    /**
     * A thirty-second preview, served by Apple rather than by USDB.
     *
     * Worth taking seriously: it is the only way to hear a song *before* committing to a download
     * that costs a wait and a chunk of the card, and it needs no YouTube and no account.
     */
    val sampleUrl: String?,
) {
    /** How the song would be named on disk, matching the convention the library already uses. */
    val folderName: String get() = "$artist - $title"
}

/** One page of results, and enough context to page through the rest. */
data class SearchPage(
    val songs: List<UsdbSong>,
    val totalResults: Int,
    val totalPages: Int,
    /** Zero-based. */
    val page: Int,
) {
    val hasMore: Boolean get() = page + 1 < totalPages
}

/**
 * Searching USDB.
 *
 * USDB has no API, so this is its own search form posted the way a browser posts it, and its own
 * results table read the way a browser renders it. Both were mapped against the live site on
 * 2026-08-20 rather than guessed, and [parseSearchPage] is tested against a real captured page.
 *
 * `details=1` is always sent. It is what adds the cover and sample columns, and those are the two
 * things that make the results browsable rather than a wall of text — which matters more here
 * than usual, since the people choosing songs may not read quickly yet.
 */
class UsdbSearch(private val session: UsdbSession) {

    /**
     * Fetches one page of results. [page] is zero-based.
     *
     * A [SongFilter.keyword] costs **two** requests rather than one, because USDB's search form
     * has no field that looks at both artist and title — measured against the live form, which
     * offers `interpret` and `title` and nothing spanning them. Each field does match on any part
     * of the value, and the two answers genuinely differ: `interpret=gone` finds one song on the
     * whole site while `title=gone` finds fifty-nine, so running only one of them would quietly
     * lose most of what somebody meant. Searching is not throttled, unlike fetching a chart.
     *
     * Artist matches come first. That is the order somebody typing a band's name expects, and it
     * makes paging predictable: page two continues both lists rather than reshuffling them.
     */
    fun search(filter: SongFilter, page: Int = 0): SearchPage {
        require(page >= 0) { "page must not be negative" }
        if (filter.keyword.isBlank()) return onePage(filter, page)

        val found = keywordSearches(filter)
            .map { onePage(it, page) }
            .reduce { merged, next -> mergePages(merged, next, page) }

        // **The fallbacks only run when the answer would otherwise be nothing.** Each is another
        // request, and USDB's search is cheap rather than free; paying for one on every query to
        // rescue the queries that already work would be the wrong trade.
        if (found.songs.isNotEmpty()) return found
        return lastResorts(filter)
            .map { onePage(it, page) }
            .fold(found) { merged, next -> mergePages(merged, next, page) }
    }

    private fun onePage(filter: SongFilter, page: Int): SearchPage =
        parseSearchPage(session.postForm("?link=list", searchFields(filter, page)), page)
}

/**
 * The searches one keyword becomes.
 *
 * Two always: the whole phrase as an artist, and as a title. USDB has no field that spans both,
 * measured against the live form, and running only one of them loses most of what somebody meant —
 * `interpret=gone` finds one song on the whole site while `title=gone` finds fifty-nine.
 *
 * **A third when the keyword has more than one word**, splitting it at the first space into artist
 * and title. That is the case the two searches above cannot answer at all: USDB matches each field
 * as a *substring*, so "beatles yesterday" is not contained in any artist and not contained in any
 * title, and the search that people type most often was the one that returned nothing. Sending the
 * two halves to the two fields is a real server-side match rather than a guess made here.
 *
 * Only when neither field was set explicitly — with an artist already named, the split would be
 * arguing with what was asked for. Searching is the part of USDB that is not throttled, so the
 * extra request is cheap.
 */
fun keywordSearches(filter: SongFilter): List<SongFilter> {
    val word = filter.keyword.trim()
    val searches = mutableListOf(
        filter.copy(keyword = "", artist = joinTerms(filter.artist, word)),
        filter.copy(keyword = "", title = joinTerms(filter.title, word)),
    )

    val space = word.indexOf(' ')
    if (space > 0 && filter.artist.isBlank() && filter.title.isBlank()) {
        val head = word.substring(0, space)
        val tail = word.substring(space + 1).trim()
        if (tail.isNotEmpty()) {
            searches += filter.copy(keyword = "", artist = head, title = tail)
        }
    }
    return searches
}

private fun joinTerms(existing: String, word: String): String =
    if (existing.isBlank()) word else existing.trim()

/**
 * What to try when the ordinary searches found nothing at all.
 *
 * Both of these were found by somebody typing a real query into the television and getting an
 * empty screen for a song that is unquestionably on USDB.
 *
 *  - **The band's name last.** [keywordSearches] splits at the *first* space, which reads
 *    "beatles yesterday" correctly and reads "god gave kiss" backwards. People type the words they
 *    remember in the order they remember them, and the band is as often last as first, so the
 *    other split is tried too.
 *  - **An acronym written with full stops.** "ymca" finds nothing because the song is filed as
 *    "Y.M.C.A." and USDB matches substrings, so the letters somebody types are never contiguous in
 *    the title. Spelling the query out with stops is a genuine substring of how those titles are
 *    actually written, and it is the whole of that family — D.I.S.C.O., S.O.S., Y.M.C.A.
 *
 * Both are deliberately narrow. The acronym form is only tried for a short single word of letters,
 * because "l.o.v.e" as a rescue for a query that already returned fifty songs would be noise.
 */
internal fun lastResorts(filter: SongFilter): List<SongFilter> {
    if (filter.artist.isNotBlank() || filter.title.isNotBlank()) return emptyList()
    val word = filter.keyword.trim()
    val tries = mutableListOf<SongFilter>()

    val lastSpace = word.lastIndexOf(' ')
    if (lastSpace > 0) {
        val tail = word.substring(lastSpace + 1)
        val head = word.substring(0, lastSpace).trim()
        if (tail.isNotEmpty() && head.isNotEmpty()) {
            tries += filter.copy(keyword = "", artist = tail, title = head)
        }
    }

    dottedAcronym(word)?.let { tries += filter.copy(keyword = "", title = it) }
    return tries
}

/** Shortest and longest a word can be and still plausibly be written with full stops. */
private val ACRONYM_LENGTHS = 2..6

/**
 * "ymca" as "y.m.c.a", or null when the word is not the shape of an acronym.
 *
 * No trailing stop: the title is "Y.M.C.A." and this has to be a *substring* of it, which
 * "y.m.c.a" is and which a trailing stop would still be — but leaving it off also matches a title
 * written without one.
 */
internal fun dottedAcronym(word: String): String? {
    if (word.length !in ACRONYM_LENGTHS) return null
    if (!word.all { it.isLetter() }) return null
    return word.lowercase().toCharArray().joinToString(".")
}

/**
 * Folds two result pages into one, keeping the first page's order and dropping repeats.
 *
 * A song can match on both halves — "Coldplay" as an artist and inside somebody's title — so the
 * count has to lose those or it overstates what is there. It is exact for everything actually
 * loaded and can only overstate the tail, where a repeat has not been seen yet; that needs a song
 * whose artist *and* title both contain the same word, which is rare enough to be worth a simpler
 * rule than a second pass over the whole site would be.
 */
fun mergePages(first: SearchPage, second: SearchPage, page: Int): SearchPage {
    val seen = mutableSetOf<Int>()
    val songs = (first.songs + second.songs).filter { seen.add(it.songId) }
    val repeats = first.songs.size + second.songs.size - songs.size
    return SearchPage(
        songs = songs,
        totalResults = (first.totalResults + second.totalResults - repeats).coerceAtLeast(songs.size),
        totalPages = maxOf(first.totalPages, second.totalPages),
        page = page,
    )
}

/** Builds the form USDB's search expects. */
fun searchFields(filter: SongFilter, page: Int): Map<String, String> {
    val fields = mutableMapOf(
        "interpret" to filter.artist.trim(),
        "title" to filter.title.trim(),
        "edition" to filter.edition.trim(),
        "language" to filter.language.trim(),
        "genre" to filter.genre.trim(),
        "year" to filter.year.trim(),
        "creator" to filter.creator.trim(),
        "user" to "",
        "limit" to filter.pageSize.toString(),
        // Adds the cover and sample columns. See the class doc.
        "details" to "1",
        "start" to (page * filter.pageSize).toString(),
        "order" to filter.order.field,
        "ud" to if (filter.ascending) "asc" else "desc",
    )
    // Checkboxes are absent rather than false in a form post; sending golden=0 would be read as
    // "on" by a `isset()` check, which is how PHP of this vintage reads a checkbox.
    if (filter.goldenNotesOnly) fields["golden"] = "1"
    return fields
}

/**
 * Reads a results page.
 *
 * Columns are located by the `id` on each header cell — `list_artist`, `list_title` and so on —
 * rather than by counting. That is not fussiness: turning `details` on inserts two columns at the
 * *front*, so every fixed position is wrong in one of the two modes, and the header moves with
 * them. Songs are found by the `data-songid` their row carries, which is also what makes a row a
 * row rather than a layout table.
 */
fun parseSearchPage(html: String, page: Int = 0): SearchPage {
    val counts = RESULT_COUNT.find(html)
    val total = counts?.groupValues?.get(1)?.toIntOrNull() ?: 0
    val pages = counts?.groupValues?.get(2)?.toIntOrNull() ?: 0

    val columns = columnIndexes(html)
    val songs = ROW.findAll(html).mapNotNull { match ->
        songFrom(match.groupValues[1].toIntOrNull() ?: return@mapNotNull null, match.groupValues[2], columns)
    }.toList()

    return SearchPage(songs = songs, totalResults = total, totalPages = pages, page = page)
}

/**
 * Maps USDB's header-cell ids to their column positions.
 *
 * Returns an empty map when there is no header, which makes [parseSearchPage] yield no songs
 * rather than confidently reading the wrong columns.
 */
fun columnIndexes(html: String): Map<String, Int> {
    val header = HEADER_ROW.find(html)?.value ?: return emptyMap()
    val indexes = mutableMapOf<String, Int>()
    CELL.findAll(header).forEachIndexed { index, cell ->
        CELL_ID.find(cell.groupValues[1])?.groupValues?.get(1)?.let { id -> indexes[id] = index }
    }
    return indexes
}

private fun songFrom(songId: Int, rowHtml: String, columns: Map<String, Int>): UsdbSong? {
    if (columns.isEmpty()) return null
    val cells = CELL.findAll(rowHtml).map { it.groupValues[1] }.toList()

    fun text(columnId: String): String =
        columns[columnId]?.let { index -> cells.getOrNull(index) }?.let(::htmlToText).orEmpty()

    val ratingCell = columns["list_rating"]?.let { cells.getOrNull(it) }.orEmpty()

    return UsdbSong(
        songId = songId,
        artist = text("list_artist"),
        title = text("list_title"),
        genre = text("list_genre"),
        year = text("list_year"),
        edition = text("list_edition"),
        // USDB writes this as the English word regardless of the rest of the row's language.
        hasGoldenNotes = text("list_gnotes").equals("yes", ignoreCase = true),
        language = text("list_language"),
        creator = text("list_autor"),
        // A filled star is `star.png` and an empty one `star2.png`, so counting every star image
        // would rate every song five.
        rating = FILLED_STAR.findAll(ratingCell).count(),
        views = text("list_views").filter { it.isDigit() }.toIntOrNull() ?: 0,
        coverUrl = COVER.find(rowHtml)?.groupValues?.get(1)?.let { USDB_ROOT + it },
        sampleUrl = SAMPLE.find(rowHtml)?.groupValues?.get(1),
    )
}

/** Strips markup and decodes entities, leaving the text a person would have seen. */
fun htmlToText(html: String): String =
    unescapeHtml(html.replace(TAG, " ")).replace(WHITESPACE, " ").trim()

/**
 * Decodes the HTML entities that turn up in song titles.
 *
 * Not a general decoder — the named set is the handful a title actually contains, plus numeric
 * escapes, which is where anything unusual ends up anyway.
 */
fun unescapeHtml(text: String): String {
    if ('&' !in text) return text
    return ENTITY.replace(text) { match ->
        val body = match.groupValues[1]
        when {
            body.startsWith("#x") || body.startsWith("#X") ->
                body.drop(2).toIntOrNull(16)?.toChar()?.toString() ?: match.value
            body.startsWith("#") -> body.drop(1).toIntOrNull()?.toChar()?.toString() ?: match.value
            else -> NAMED_ENTITIES[body.lowercase()] ?: match.value
        }
    }
}

private val NAMED_ENTITIES = mapOf(
    "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'",
    "nbsp" to " ", "auml" to "ä", "ouml" to "ö", "uuml" to "ü", "szlig" to "ß",
    "eacute" to "é", "egrave" to "è", "agrave" to "à", "ccedil" to "ç", "ntilde" to "ñ",
)

private val RESULT_COUNT = Regex("""There are\s*(\d+)\s*results on\s*(\d+)\s*page""", RegexOption.IGNORE_CASE)
private val HEADER_ROW = Regex("""<tr class="list_head">.*?(?=<tr[ >])""", RegexOption.DOT_MATCHES_ALL)
private val ROW = Regex(
    """<tr class="list_tr\d"[^>]*data-songid="(\d+)"[^>]*>(.*?)(?=<tr[ >]|</table>)""",
    RegexOption.DOT_MATCHES_ALL,
)
private val CELL = Regex("""<td[^>]*>(.*?)</td>""", RegexOption.DOT_MATCHES_ALL)
private val CELL_ID = Regex("""id="([^"]+)"""")
private val COVER = Regex("""<img[^>]*src="(data/cover/[^"]+)"""")
private val SAMPLE = Regex("""<source[^>]*src="([^"]+)"""")
private val FILLED_STAR = Regex("""images/star\.png""")
private val TAG = Regex("""<[^>]*>""")
private val WHITESPACE = Regex("""\s+""")
private val ENTITY = Regex("""&(#[xX]?[0-9a-fA-F]+|[a-zA-Z]+);""")
