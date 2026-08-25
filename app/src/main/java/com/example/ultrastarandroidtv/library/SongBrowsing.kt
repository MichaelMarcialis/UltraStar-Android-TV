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
