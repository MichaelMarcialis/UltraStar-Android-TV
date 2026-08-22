package com.example.ultrastarandroidtv.net

import org.json.JSONObject

/**
 * Album artwork at a size worth putting on a television.
 *
 * ## Why this exists at all
 *
 * USDB serves exactly one cover per song, at **200x200** (`data/cover/{id}.jpg`), and that is what
 * downloaded songs used to get. Drawn as a card on a 4K set it is visibly soft — the complaint
 * that led here — and it is soft next to the rest of the library, because the desktop tool fetches
 * the full-size original and lands **1000x1000**.
 *
 * ## Why not the source the chart names
 *
 * A USDB chart's `#VIDEO:` header carries a `co=` tag naming the cover its author chose, and where
 * that is a full URL it is the best answer there is — often 1400px or more, and the exact artwork
 * somebody picked for this song. [com.example.ultrastarandroidtv.download.SongDownloader] tries it
 * first for that reason. But measured across the 68 charts on this card, **57 of them name a bare
 * fanart.tv filename rather than a URL**, and fanart.tv sits behind a Cloudflare challenge that
 * answers any plain HTTP client with `403 cf-mitigated: challenge` — verified from two different
 * TLS stacks, so it is not a matter of sending better headers. Those 57 are unreachable.
 *
 * ## Why iTunes is a fair source rather than a guess
 *
 * USDB has already matched every song in its list to an iTunes track — that is where the
 * thirty-second preview on each search row comes from — so the app is asking the same catalogue
 * USDB itself used, and it is not introducing a service the user was not already talking to.
 * Artwork size is a path segment, so `100x100bb.jpg` becomes [ARTWORK_PIXELS] by string surgery.
 *
 * **A wrong cover is worse than a soft one**, so a result is only taken when both halves agree —
 * see [sameTitle] and [sameArtist]. Checked against ten songs from this card: nine matched, and a
 * made-up artist and title matched nothing rather than settling for the closest thing.
 */
class ITunesArtwork(private val http: Http) {

    /** A high-resolution cover URL, or null if nothing matched well enough to trust. */
    fun find(artist: String, title: String): String? = runCatching {
        artworkFrom(http.getText(artworkSearchUrl(artist, title)), artist, title)
    }.getOrNull()
}

/** How wide the cover is asked for. Matches what the rest of the card's covers already are. */
const val ARTWORK_PIXELS = 1000

private const val SEARCH_URL = "https://itunes.apple.com/search"

fun artworkSearchUrl(artist: String, title: String): String = SEARCH_URL + "?" + formEncode(
    mapOf(
        "term" to listOf(withoutTags(artist), withoutTags(title))
            .filter { it.isNotBlank() }
            .joinToString(" "),
        "entity" to "song",
        "limit" to "5",
    ),
)

/**
 * Drops USDB's own annotations from a name before asking iTunes about it.
 *
 * USDB marks a chart's variant in square brackets — `[DUET]`, and others — and those words are
 * about the chart rather than the recording. Left in the query they do real damage, because
 * Apple ranks on the whole phrase: searching "ABBA Gimme! Gimme! Gimme! (A Man After Midnight)
 * [DUET]" returns Olivia Newton-John and a punk covers band, and ABBA appears nowhere in the
 * results. Without the tag it is the first hit.
 *
 * **Round brackets are left alone**, because those usually belong to the song — "(A Man After
 * Midnight)" is part of the title, and stripping it would throw away the most identifying half.
 */
fun withoutTags(name: String): String = SQUARE_TAG.replace(name, " ").replace(SPACES, " ").trim()

/**
 * The first result that is convincingly the song asked for, at [ARTWORK_PIXELS].
 *
 * Walks the top few rather than taking the first, because iTunes will happily lead with a cover
 * version or a tribute album when the exact recording sits a row lower.
 */
fun artworkFrom(json: String, artist: String, title: String): String? {
    val results = runCatching { JSONObject(json).optJSONArray("results") }.getOrNull() ?: return null
    for (index in 0 until results.length()) {
        val entry = results.optJSONObject(index) ?: continue
        val art = entry.optString("artworkUrl100").orEmpty()
        if (art.isBlank()) continue
        if (sameTitle(title, entry.optString("trackName").orEmpty()) &&
            sameArtist(artist, entry.optString("artistName").orEmpty())
        ) {
            return enlargeArtwork(art)
        }
    }
    return null
}

/**
 * Rewrites an iTunes artwork URL to ask for a larger copy.
 *
 * The size is the last path segment — `.../100x100bb.jpg` — and Apple renders whatever is asked
 * for, capped at the original. Asking for more than exists returns the original rather than
 * failing, so there is no need to know how big it is first.
 */
fun enlargeArtwork(artworkUrl: String, pixels: Int = ARTWORK_PIXELS): String =
    ARTWORK_SIZE.replace(artworkUrl, "${pixels}x${pixels}bb.jpg")

/** Titles agree when either contains the other, ignoring case, spacing and punctuation. */
fun sameTitle(wanted: String, found: String): Boolean {
    val a = squash(wanted)
    val b = squash(found)
    return a.isNotEmpty() && b.isNotEmpty() && (a in b || b in a)
}

/**
 * Artists agree when they share a real word.
 *
 * Deliberately looser than [sameTitle], because a soundtrack credit rarely matches a chart's idea
 * of the artist: this card's "KPop Demon Hunters (Huntr/x)" is billed on iTunes as "HUNTR/X, EJAE,
 * AUDREY NUNA, REI AMI & KPop Demon Hunters Cast". Containment rejects that correctly-matched
 * cover; a shared word accepts it while still rejecting Lionel Richie's "Hello" for Adele's.
 *
 * Short names have no word long enough to be evidence — "U2", "a-ha" — so those fall back to
 * containment, which is what they need.
 *
 * Requiring *every* word instead was tried and is worse: run against all 77 folders on this card
 * it lost seven of them, and all seven were soundtracks — "Disney's Moana (Dwayne Johnson)" is
 * billed by iTunes as "Dwayne Johnson", so the film's name is in the chart and nowhere in the
 * credit. What keeps the loose rule honest is that it never stands alone: the title has to match
 * too, and iTunes has already ranked the results against the artist and title together.
 */
fun sameArtist(wanted: String, found: String): Boolean {
    val words = squashedWords(wanted)
    if (words.isEmpty()) {
        val a = squash(wanted)
        val b = squash(found)
        return a.isNotEmpty() && b.isNotEmpty() && (a in b || b in a)
    }
    val haystack = squash(found)
    return words.any { it in haystack }
}

private const val MIN_EVIDENCE_LETTERS = 3
private val ARTWORK_SIZE = Regex("""\d+x\d+bb\.jpg$""")
private val SQUARE_TAG = Regex("""\[[^\]]*\]""")
private val SPACES = Regex("""\s+""")
private val NOT_ALPHANUMERIC = Regex("""[^a-z0-9]+""")

private fun squash(text: String): String = NOT_ALPHANUMERIC.replace(text.lowercase(), "")

private fun squashedWords(text: String): List<String> =
    NOT_ALPHANUMERIC.split(text.lowercase()).filter { it.length >= MIN_EVIDENCE_LETTERS }
