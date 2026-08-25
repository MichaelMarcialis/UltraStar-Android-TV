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

/** What the library is sorted and indexed on. */
fun sortKeyOf(song: ScannedSong, sort: SongSort): String {
    val metadata = song.song.metadata
    val key = when (sort) {
        SongSort.Title -> metadata.title
        SongSort.Artist -> metadata.artist
    }
    return key.ifBlank { song.folderName }.trim()
}

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
    .sortedWith(
        compareBy(
            { sortKeyOf(it, sort).lowercase() },
            { sortKeyOf(it, if (sort == SongSort.Title) SongSort.Artist else SongSort.Title).lowercase() },
        ),
    )

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
