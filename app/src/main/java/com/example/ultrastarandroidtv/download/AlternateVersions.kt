package com.example.ultrastarandroidtv.download

import com.example.ultrastarandroidtv.net.AudioLookup
import com.example.ultrastarandroidtv.net.YouTubeAudio
import com.example.ultrastarandroidtv.usdb.SongFilter
import com.example.ultrastarandroidtv.usdb.UsdbDetails
import com.example.ultrastarandroidtv.usdb.UsdbSearch
import com.example.ultrastarandroidtv.usdb.UsdbSong

/** How many other charts of the same song to check before giving up. */
private const val MAX_CANDIDATES = 6

/**
 * When one chart's music has gone, finds another chart of the same song whose music has not.
 *
 * ## Why this searches USDB rather than YouTube
 *
 * The failure this answers is a specific one: the chart is fine, the singer wants the song, and
 * the *upload it happens to name* has been taken down or blocked. Kelly Clarkson's "Since U Been
 * Gone" is the case that prompted it — YouTube answers `UNPLAYABLE` with "the uploader has not made
 * this video available in your country", which no amount of retrying will change.
 *
 * The tempting fix is to search YouTube for another copy. It is the wrong one. Matching an
 * arbitrary YouTube result to a chart is exactly where the Thriller problem lives: that song
 * resolves to a 13:42 video, because the famous upload is the long-form horror film, and a chart
 * timed against the six-minute single will not fit it. A chart's timing belongs to one specific
 * recording, and only whoever made the chart knows which.
 *
 * USDB does know. A popular song usually has several charts on it, each made by a different person
 * against a different upload — so another chart is another *matched pair* of notes and audio,
 * rather than a guess. That is the whole argument for this file.
 *
 * ## It costs nothing to look
 *
 * Searching USDB is not throttled, and a song's detail page names its video with no wait attached.
 * So checking six alternatives is about a dozen seconds of ordinary requests, against the
 * twenty-four-second throttle that fetching even one chart would cost. Nothing here downloads a
 * chart; it only finds the one worth asking for.
 */
class AlternateVersions(
    private val search: UsdbSearch,
    private val details: UsdbDetails,
    private val youTube: YouTubeAudio,
) {

    /**
     * Another chart of the same song whose music can actually be fetched, or null.
     *
     * @param alreadyHave folder names already on the card, so a version that would collide with
     *   something is never offered — two folders for one song is worse than not downloading.
     */
    fun find(song: UsdbSong, alreadyHave: Set<String> = emptySet()): UsdbSong? {
        val page = runCatching {
            search.search(SongFilter(artist = song.artist, title = song.title))
        }.getOrNull() ?: return null

        val candidates = sameSongAs(song, page.songs)
            .filterNot { safeFileName(it.folderName).lowercase() in alreadyHave }
            .take(MAX_CANDIDATES)

        return candidates.firstOrNull { canFetch(it) }
    }

    private fun canFetch(candidate: UsdbSong): Boolean = runCatching {
        val videoId = details.fetch(candidate.songId).videoId ?: return false
        youTube.resolve(videoId) is AudioLookup.Found
    }.getOrDefault(false)
}

/**
 * The other charts on USDB that are the same song as [song].
 *
 * **Compared loosely on purpose.** Charts are typed by different people over twenty years, so the
 * same song appears as "Kelly Clarkson" and "Kelly  Clarkson", with and without a `[DUET]` tag,
 * with and without punctuation. Comparing strictly would find nothing in exactly the cases this is
 * for. Comparing on *containment* rather than equality is what lets "Since U Been Gone" match
 * "Since U Been Gone [DUET]" without also matching some other song.
 *
 * The original is always excluded — it is the one that just failed.
 */
fun sameSongAs(song: UsdbSong, found: List<UsdbSong>): List<UsdbSong> {
    val artist = loosely(song.artist)
    val title = loosely(song.title)
    if (artist.isEmpty() || title.isEmpty()) return emptyList()

    return found.filter { other ->
        other.songId != song.songId &&
            sameArtist(loosely(other.artist), artist) &&
            oneContainsTheOther(loosely(other.title), title)
    }
}

/** Lower case, no punctuation, single spaces — enough to survive twenty years of typing. */
private fun loosely(text: String): String = text
    .lowercase()
    .map { if (it.isLetterOrDigit() || it.isWhitespace()) it else ' ' }
    .joinToString("")
    .split(' ')
    .filter { it.isNotBlank() }
    .joinToString(" ")

private fun oneContainsTheOther(a: String, b: String): Boolean =
    a.isNotEmpty() && b.isNotEmpty() && (a.contains(b) || b.contains(a))

/**
 * Artists are compared with their spaces taken out as well as their punctuation.
 *
 * Two charts of one song rarely agree on how an artist is written: "a-ha" and "aha", "Guns N'
 * Roses" and "Guns N Roses", "Disney's Moana" and "Moana". Comparing the letters alone, and asking
 * only that one name contains the other, survives all of those.
 *
 * A shared *word* was tried first and is wrong in a way worth recording: "The Monkees" and "The
 * Beatles" share "the", which would have matched two entirely different bands on the strength of a
 * definite article. Containment cannot make that mistake — neither of those names is inside the
 * other.
 */
private fun sameArtist(a: String, b: String): Boolean {
    val left = a.replace(" ", "")
    val right = b.replace(" ", "")
    if (left.length < 3 || right.length < 3) return left.isNotEmpty() && left == right
    return left.contains(right) || right.contains(left)
}
