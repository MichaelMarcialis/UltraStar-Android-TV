package com.example.ultrastarandroidtv.library

/** Which name a library is arranged by. */
enum class SongSort(val label: String) {
    Title("Title"),
    Artist("Artist"),
}

/**
 * Which songs a library shows.
 *
 * **Every one of these is a state the scanner already knows**, which is the rule that decided the
 * list. "Has an update waiting" is the obvious missing entry and is deliberately absent: nothing
 * in this app can tell yet whether a chart has changed on USDB, and a filter that quietly always
 * matches nothing is worse than one that is not there.
 */
enum class SongFilterState(val label: String) {
    All("All"),
    Ready("Ready"),
    MissingMusic("No music"),
    MissingVideo("No video"),
    MissingArtwork("No artwork"),
}

/**
 * Words a name is filed *past* rather than under.
 *
 * Every record shop, library catalogue and music app does this, and the reason is plain once a
 * shelf is big enough: without it "The Beatles", "The Cure", "The Monkees" and "The Weeknd" all
 * queue up under T, which tells you nothing about any of them and buries three quarters of the
 * bands somebody actually wants under one letter.
 *
 * **English only, deliberately.** German files "Die Ärzte" under Ä by exactly the same convention,
 * and USDB is a German site — but this app's own library is English, and a rule that quietly
 * re-files somebody's German songs is worse than one that leaves them where they were put. Adding
 * a language means adding a line here.
 */
private val ARTICLES = setOf("the", "a", "an")

/**
 * The name a song is filed under: its own, with any leading article dropped.
 *
 * Only when the article is a whole word followed by a space — which is what keeps **a-ha** filed
 * under A instead of being read as "a" plus "ha", and what stops "Anna" losing its first letter.
 * A name that is *only* an article keeps it, because filing something under nothing is not filing.
 */
fun filingKey(name: String): String {
    val trimmed = name.trim()
    val space = trimmed.indexOf(' ')
    if (space <= 0) return trimmed
    if (trimmed.substring(0, space).lowercase() !in ARTICLES) return trimmed
    return trimmed.substring(space + 1).trimStart().ifBlank { trimmed }
}

/** What the library is sorted and indexed on. */
fun sortKeyOf(song: ScannedSong, sort: SongSort): String {
    val metadata = song.song.metadata
    val key = when (sort) {
        SongSort.Title -> metadata.title
        SongSort.Artist -> metadata.artist
    }
    return filingKey(key.ifBlank { song.folderName })
}

/**
 * How a library is ordered, shared by every screen that shows one.
 *
 * One comparator rather than three `sortedBy` calls, because the picker, the management grid and
 * the scan that fills them both must agree: a song filed under M on one screen and T on another is
 * a song somebody cannot find twice in a row.
 */
fun songOrder(sort: SongSort): Comparator<ScannedSong> = compareBy(
    { sortKeyOf(it, sort).lowercase() },
    { sortKeyOf(it, if (sort == SongSort.Title) SongSort.Artist else SongSort.Title).lowercase() },
    // Total, not merely sorted. Title and artist are not enough to separate two songs: a duet
    // arrangement sits beside its original sharing both, and the scanner supports exactly that.
    // Left tied they keep whatever order the card was walked in, which is not the same order
    // twice, so the pair would silently swap places between rescans. The folder and then the
    // chart's own document id break it, and both are as stable as the files themselves.
    { it.folderName.lowercase() },
    { it.textId },
)

fun matches(song: ScannedSong, filter: SongFilterState): Boolean = when (filter) {
    SongFilterState.All -> true
    SongFilterState.Ready -> song.isPlayable
    SongFilterState.MissingMusic -> song.audioId == null
    SongFilterState.MissingVideo -> song.videoId == null
    SongFilterState.MissingArtwork -> song.coverId == null
}

/**
 * The library as it should appear: filtered, then sorted by the chosen name.
 *
 * Sorted case-insensitively and with the *other* name as a tie-break, so arranging by artist puts
 * that artist's songs in a stable order rather than whatever order the card happened to be walked
 * in — which changes between scans and makes a list look like it is shuffling itself.
 */
fun arrange(
    songs: List<ScannedSong>,
    sort: SongSort,
    filter: SongFilterState,
): List<ScannedSong> = songs
    .filter { matches(it, filter) }
    .sortedWith(songOrder(sort))

/**
 * The letter a name is filed under.
 *
 * Anything not starting with a letter files under `#`, which is what every music library does and
 * is the honest answer for "9 To 5", "'74–'75" and the handful of songs whose titles begin with a
 * quotation mark.
 */
fun indexLetterOf(name: String): Char {
    val first = name.trimStart().firstOrNull() ?: return '#'
    val upper = first.uppercaseChar()
    return if (upper in 'A'..'Z') upper else '#'
}

/**
 * The letters worth offering, in order, for a library of this size.
 *
 * **Only the letters that are actually there.** A full A-Z rail on a fifty-song library is mostly
 * dead ends, and on a remote a dead end costs a press to discover and a press to leave. `#` sorts
 * first, as it does in the list itself.
 */
fun indexLetters(songs: List<ScannedSong>, sort: SongSort): List<Char> =
    songs.map { indexLetterOf(sortKeyOf(it, sort)) }
        .distinct()
        .sortedWith(compareBy({ it != '#' }, { it }))

/** Where [letter] starts in an already-arranged list, or -1 when nothing files under it. */
fun firstIndexUnder(arranged: List<ScannedSong>, sort: SongSort, letter: Char): Int =
    arranged.indexOfFirst { indexLetterOf(sortKeyOf(it, sort)) == letter }

/**
 * How two singers will be marked against each other, as something to filter on.
 *
 * Only ever asked with two people in the room: on your own a duet collapses to a single line, so
 * the distinction has nothing to say and the control is not shown at all.
 */
enum class SongMode(val label: String) {
    Any("Any"),
    Duet("Duet"),
    Versus("Versus"),
}

/** True when the chart deals its lines out to two parts rather than one. */
fun isDuetChart(song: ScannedSong): Boolean = song.song.voiceParts.size >= 2

/**
 * Compares two genre names as the same thing without merging genres that are not.
 *
 * Real charts write the field by hand, so this library holds "Pop Rock" and "Pop-Rock" as separate
 * spellings of one genre. Folding a hyphen into a space fixes that and cannot fold "Rock" into
 * "Pop Rock", which is the mistake a looser rule would make. Nothing more clever is attempted:
 * deciding that "Alternative" and "Alternative Rock" are the same genre is an opinion, and one the
 * person who wrote the chart did not share.
 */
private fun genreKey(genre: String): String =
    genre.lowercase()
        .replace('-', ' ')
        .split(' ')
        .filter { it.isNotEmpty() }
        .joinToString(" ")

/**
 * The genres a song claims, which is very often more than one.
 *
 * `#GENRE` is free text and this card uses it as a list: "Soundtrack, K-Pop" is four songs here,
 * and reading it as a single genre would file them under a name nothing else shares. Splitting is
 * therefore not a nicety — without it the commonest real combination becomes its own dead end.
 */
fun genresOf(song: ScannedSong): List<String> =
    song.song.metadata.genre
        ?.split(',', ';', '/')
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        .orEmpty()

/** The decade a song belongs to, or null when the chart does not say what year it is from. */
fun decadeOf(song: ScannedSong): Int? =
    song.song.metadata.year?.let { if (it in 1000..9999) it / 10 * 10 else null }

/**
 * Everything the library row is narrowed by at once.
 *
 * One object rather than four pieces of screen state because it is also what gets *cleared*: a
 * "Clear" that had to remember to reset four separate things would eventually forget one, and a
 * filter still quietly in force is indistinguishable from a library that has lost songs.
 */
data class LibraryFilter(
    val query: String = "",
    val genre: String? = null,
    val decade: Int? = null,
    val mode: SongMode = SongMode.Any,
) {
    /** True when nothing is being hidden, which is what decides whether to offer a way out. */
    val isEmpty: Boolean
        get() = query.isBlank() && genre == null && decade == null && mode == SongMode.Any
}

/**
 * Whether a song answers to what somebody typed.
 *
 * Title **and** artist, because half of remembering a song is remembering who sang it, and on a
 * remote nobody wants to be told which of the two fields they are in.
 *
 * **Matched forgivingly** — see [fuzzyMatches] for the four rules. It used to be plain containment,
 * on the grounds that a query arriving one directional-pad press per letter is short and a fuzzy
 * match on two letters returns the library. The second half of that is true and is now handled
 * where it belongs, by [FUZZY_MIN_TOKEN]: a short query still only matches by containment. What
 * the old rule could not do was find "Guns N' Roses" for somebody who typed "guns n roses", or the
 * song for somebody who typed the artist and the title together — both of which are what people
 * actually type, and both of which used to come back empty.
 *
 * Artist and title are matched **together as one string** as well as separately, so a query naming
 * both finds the song. Separately as well, because otherwise a query would have to be in the right
 * order.
 */
fun matchesQuery(song: ScannedSong, query: String): Boolean {
    val needle = query.trim()
    if (needle.isEmpty()) return true
    val metadata = song.song.metadata
    return fuzzyMatches("${metadata.artist} ${metadata.title}", needle) ||
        fuzzyMatches("${metadata.title} ${metadata.artist}", needle)
}

fun matchesFilter(song: ScannedSong, filter: LibraryFilter): Boolean {
    if (!matchesQuery(song, filter.query)) return false
    filter.genre?.let { wanted ->
        if (genresOf(song).none { genreKey(it) == genreKey(wanted) }) return false
    }
    filter.decade?.let { if (decadeOf(song) != it) return false }
    return when (filter.mode) {
        SongMode.Any -> true
        SongMode.Duet -> isDuetChart(song)
        SongMode.Versus -> !isDuetChart(song)
    }
}

/** The library as the picker should show it: narrowed, then filed under the chosen name. */
fun browse(
    songs: List<ScannedSong>,
    sort: SongSort,
    filter: LibraryFilter,
): List<ScannedSong> = songs
    .filter { matchesFilter(it, filter) }
    .sortedWith(songOrder(sort))

/**
 * The genres worth offering, commonest first.
 *
 * **Built from the library rather than from a fixed list**, which is the opposite of the call the
 * add-songs screen made — and for the opposite reason. That list has to keep its shape while
 * search results arrive underneath it, so it cannot be derived from them; this one describes a
 * library that only changes on a rescan, and a fixed list would offer genres nobody here has while
 * hiding the ones they do. Commonest first because a remote pays per press.
 *
 * Case and hyphens are folded, and the spelling kept is the one used most.
 */
fun genreChoices(songs: List<ScannedSong>): List<String> {
    val counts = LinkedHashMap<String, MutableMap<String, Int>>()
    for (song in songs) {
        for (genre in genresOf(song)) {
            counts.getOrPut(genreKey(genre)) { LinkedHashMap() }
                .merge(genre, 1, Int::plus)
        }
    }
    return counts.values
        .map { spellings -> spellings.maxBy { it.value }.key to spellings.values.sum() }
        .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first.lowercase() })
        .map { it.first }
}

/** The decades this library actually covers, oldest first. Songs with no year are not one. */
fun decadeChoices(songs: List<ScannedSong>): List<Int> =
    songs.mapNotNull(::decadeOf).distinct().sorted()

/** How a decade is written on a chip. */
fun decadeLabel(decade: Int): String = "${decade}s"
