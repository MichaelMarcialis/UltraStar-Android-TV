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
    fun find(song: UsdbSong, alreadyHave: Set<String> = emptySet()): UsdbSong? =
        findFor(song.artist, song.title, song.songId, alreadyHave)

    /**
     * The same, for a song that is already on the card rather than one in a list of results.
     *
     * @param differentFolderFrom refuse a version whose folder would have the same name. A repair
     *   uses this: a replacement that lands in the folder it is replacing cannot be written until
     *   the old one is gone, and deleting first would mean a failure left nothing at all.
     */
    fun findFor(
        artist: String,
        title: String,
        excludeSongId: Int? = null,
        alreadyHave: Set<String> = emptySet(),
        differentFolderFrom: String? = null,
    ): UsdbSong? {
        if (artist.isBlank() || title.isBlank()) return null

        // Searched by **title alone**, and the artist judged here.
        //
        // Sending the artist to USDB as well undoes the whole point of the loose matcher
        // below: `interpret=Disney's Moana (Auli'i Cravalho)` cannot match the chart credited
        // to Alessia Cara, so the one real-library case this exists for was being filtered out
        // on the server before [sameSongAs] ever saw it. A title search is wider and the
        // widening is free -- USDB's search is the part that is not throttled.
        val page = runCatching {
            search.search(SongFilter(title = withoutArrangementTag(title).trim()))
        }.getOrNull() ?: return null

        val same = differentFolderFrom?.let { safeFileName(it).lowercase() }
        val candidates = sameSongAs(artist, title, excludeSongId, page.songs)
            .map { it to safeFileName(it.folderName).lowercase() }
            .filterNot { (_, folder) -> folder in alreadyHave || folder == same }
            .map { (song, _) -> song }
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
fun sameSongAs(song: UsdbSong, found: List<UsdbSong>): List<UsdbSong> =
    sameSongAs(song.artist, song.title, song.songId, found)

fun sameSongAs(
    artist: String,
    title: String,
    excludeSongId: Int?,
    found: List<UsdbSong>,
): List<UsdbSong> {
    val wantedTitle = loosely(withoutArrangementTag(title))
    if (artist.isBlank() || wantedTitle.isEmpty()) return emptyList()

    return found.filter { other ->
        other.songId != excludeSongId &&
            sameArtist(other.artist, artist) &&
            loosely(withoutArrangementTag(other.title)) == wantedTitle
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

/**
 * A title with USDB's own arrangement tag taken off, so `[DUET]` is not part of the name.
 *
 * Titles are then compared for **equality**, not containment. Containment was the first rule
 * and it is dangerous in one specific way: "Hello" is contained in "Hello Again", so a song
 * whose music had gone could be replaced by a different song with a longer name -- and a wrong
 * song on the card is the failure nobody notices until they press play on it.
 *
 * **Square brackets only.** A round bracket in a title is usually part of the song -- "(A Man
 * After Midnight)" -- or marks a genuinely different recording -- "(Hardstyle)" -- and folding
 * those together is the same mistake by another road. The same distinction the iTunes cover
 * search draws, for the same reason.
 */
private fun withoutArrangementTag(title: String): String = title.replace(ARRANGEMENT_TAG, " ")

private val ARRANGEMENT_TAG = Regex("""\[[^\]]*\]""")

/**
 * Whether two artist strings name the same act.
 *
 * Compared on the **letters alone**, with spaces and punctuation gone, and then again with any
 * bracketed credit removed. Two charts of one song rarely agree on how an artist is written, and
 * that much survives "a-ha"/"aha", "Guns N' Roses"/"Guns N Roses", and the case measured on the
 * real library: "How Far I'll Go" is on USDB as both *Disney's Moana (Auli'i Cravalho)* and
 * *Disney's Moana (Alessia Cara)* — one song from one film, billed to two singers. A bracket in
 * an *artist* is a performer credit, where a bracket in a **title** is usually part of the song,
 * which is why this rule lives here and not in the title comparison.
 *
 * **Equality, not containment**, and that is the whole of the safety here. Containment was tried
 * and it merges names that happen to sit inside one another: "Queen" is inside "Queens of the
 * Stone Age". With a shared title that would have downloaded the other act's chart *and deleted
 * the original folder*, which is the worst thing in this file. The cost is a genuine miss — a
 * soundtrack billed as plain "Moana" against another's "Disney's Moana" is not matched — and
 * that is the right way round: a missed alternative is a song somebody searches for by hand,
 * where a wrong one is a song that quietly disappears.
 *
 * A shared *word* was tried first and is wrong in its own way: "The Monkees" and "The Beatles"
 * share "the", which matched two entirely different bands on a definite article.
 */
private fun sameArtist(a: String, b: String): Boolean =
    sameLetters(letters(a), letters(b)) ||
        sameLetters(letters(withoutQualifier(a)), letters(withoutQualifier(b)))

private fun withoutQualifier(name: String): String = name.replace(QUALIFIER, " ")

private val QUALIFIER = Regex("""\([^)]*\)""")

private fun letters(name: String): String = loosely(name).replace(" ", "")

private fun sameLetters(a: String, b: String): Boolean = a.isNotEmpty() && a == b
