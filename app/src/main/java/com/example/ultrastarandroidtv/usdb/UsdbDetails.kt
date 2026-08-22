package com.example.ultrastarandroidtv.usdb

/** The little of a song's USDB page this app reads. */
data class SongDetails(
    val songId: Int,
    /** The YouTube video its media comes from, or null if the page names none. */
    val videoId: String?,
)

/**
 * A song's own page on USDB — which, unlike its chart, costs nothing to fetch.
 *
 * **This exists to avoid spending USDB's throttle on a song that cannot be downloaded.** Getting a
 * chart means asking, waiting 24 seconds, and asking again; only then does the chart reveal which
 * YouTube video the media comes from, and only then can that video be checked. A song whose video
 * has been removed therefore failed *after* the full wait — twenty-seven seconds to be told no.
 *
 * The detail page carries the same YouTube link with **no wait attached**, so the check can happen
 * first and the refusal arrive in about two seconds.
 *
 * **The two ids are the same id, which is what makes this trustworthy rather than a guess.** A
 * chart names its media with either `v=` (a video) or `a=` (audio taken from elsewhere), and the
 * worry was that the page might show `v=` while the download used a different `a=`, so a good song
 * could be refused on the strength of the wrong video. Measured across the 68 charts on this card
 * (2026-08-20): `a=` and `v=` are **never both present** — they are alternatives, and 6 of the 68
 * use `a=` alone. Four songs were then checked against their live pages, three of them `a=`-only,
 * and the page named exactly the id the chart uses every time.
 *
 * If that ever stops being true the failure is a false refusal, so [com.example.ultrastarandroidtv
 * .download.SongDownloader] re-checks against the chart's own id once it has it, and only skips
 * that second lookup when the two agree.
 */
class UsdbDetails(private val session: UsdbSession) {

    fun fetch(songId: Int): SongDetails =
        SongDetails(songId, videoIdFrom(session.get("?link=detail&id=$songId")))
}

/**
 * Finds the YouTube video id on a song's page.
 *
 * USDB writes the link as an embed, and has used a watch link before; both shapes are read, along
 * with the short form, because the cost of accepting all three is one alternation.
 */
fun videoIdFrom(html: String): String? =
    YOUTUBE_LINK.find(html)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }

private val YOUTUBE_LINK = Regex(
    """(?:youtube(?:-nocookie)?\.com/(?:embed/|watch\?(?:[^"'\s]*&(?:amp;)?)?v=)|youtu\.be/)([A-Za-z0-9_-]{11})""",
    RegexOption.IGNORE_CASE,
)
