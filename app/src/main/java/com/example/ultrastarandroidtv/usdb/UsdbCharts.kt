package com.example.ultrastarandroidtv.usdb

/** The answer to asking USDB for a chart. */
sealed interface ChartFetch {

    /** The chart text, exactly as USDB stores it. */
    data class Ready(val text: String) : ChartFetch

    /** USDB wants [seconds] to pass before it will hand the file over. */
    data class Waiting(val seconds: Int) : ChartFetch
}

/**
 * Downloading a chart from USDB.
 *
 * **USDB throttles this deliberately**, and the throttle is the shape of the whole feature. Asking
 * for a chart returns a page that says *"Please wait 24 seconds. After this period you can download
 * the TXT file. Your waiting time can be reduced by uploading some of your own songs to our
 * database."* — the wait is the site asking to be treated gently by people who take more than they
 * give, and it is entirely reasonable for a free community database.
 *
 * So: the wait is **honoured, not worked around**. Two reasons, and the second is the one that
 * matters. It is their site and their bandwidth. And this app may be used by people other than the
 * person who built it, each signed in as themselves — an app that hammered USDB would get *their*
 * accounts blocked, for something they did not do and cannot see.
 *
 * The consequence for the screen above this is real and should not be hidden: **a song takes about
 * half a minute to arrive**, and ten songs take five minutes. That is worth saying plainly on
 * screen rather than disguising with a spinner, because a progress bar that appears stuck is worse
 * than a countdown that is honest.
 *
 * The exchange, mapped against the live site on 2026-08-20:
 *
 *  1. `GET ?link=gettxt&id=N` → a page carrying `time = 24;` and a form holding `wd=1`.
 *  2. wait.
 *  3. `POST wd=1` to the same address → the same page shape, now with the chart in a `<textarea>`.
 */
class UsdbCharts(private val session: UsdbSession) {

    /**
     * Asks for a chart, which starts USDB's clock.
     *
     * Almost always answers [ChartFetch.Waiting]. It can answer [ChartFetch.Ready] outright —
     * USDB reduces the wait for people who upload — so the caller must handle both rather than
     * assuming a delay it can skip past.
     */
    fun beginChart(songId: Int): ChartFetch = read(session.get("?link=gettxt&id=$songId"))

    /** Collects the chart once the wait has passed. */
    fun collectChart(songId: Int): ChartFetch =
        read(session.postForm("?link=gettxt&id=$songId", mapOf("wd" to "1")))

    private fun read(html: String): ChartFetch =
        chartFrom(html)?.let { ChartFetch.Ready(it) } ?: ChartFetch.Waiting(waitSecondsFrom(html))
}

// ---------------------------------------------------------------------------------------------
// The rules, as free functions: pure, and so testable without a network or an account.
// ---------------------------------------------------------------------------------------------

/**
 * Pulls the chart out of the page USDB wraps it in.
 *
 * Null when the page holds no chart, which is how "still waiting" is told from "here it is" —
 * the two are otherwise the same page. A chart must start with a `#` header to count, so a page
 * carrying an empty textarea for some other reason cannot be mistaken for a song.
 */
fun chartFrom(html: String): String? {
    val body = TEXTAREA.find(html)?.groupValues?.get(1) ?: return null
    val text = unescapeHtml(body).trim('\r', '\n')
    return text.takeIf { it.trimStart().startsWith("#") }
}

/**
 * Reads how long USDB wants us to wait, from the countdown its own page runs.
 *
 * Falls back to [DEFAULT_WAIT_SECONDS] rather than to zero when the number cannot be found. If
 * this ever stops matching, the failure should be waiting too long — not hammering a community
 * database on every download because a regex missed.
 */
fun waitSecondsFrom(html: String): Int {
    val exact = WAIT_BEFORE_FUNCTION.find(html)?.groupValues?.get(1)?.toIntOrNull()
    if (exact != null) return exact
    return LOOSE_WAIT.find(html)?.groupValues?.get(1)?.toIntOrNull() ?: DEFAULT_WAIT_SECONDS
}

/** What to wait when USDB's page cannot be read. Its own value was 24 seconds on 2026-08-20. */
const val DEFAULT_WAIT_SECONDS = 30

/**
 * The extra information USDB Syncer packs into a chart's `#VIDEO:` header.
 *
 * Not part of the UltraStar format — it is a convention the desktop tool writes and this app
 * reads, which is what lets a chart say where its own media came from. Charts on the card carry
 * it too, e.g. `v=yebNIHKAC4A,co=golden-6a05f7ad86d83.jpg,bg=...,medley=504-1253`.
 */
data class UsdbMetaTags(
    /** `a=`, when the audio was taken from a different video than the picture. */
    val audioVideoId: String? = null,
    /** `v=`, the video itself. */
    val videoId: String? = null,
    val coverFile: String? = null,
    val backgroundFile: String? = null,
) {
    /**
     * Which video to take the *sound* from. `a=` wins when present, because that is exactly what
     * it means; falling back to `v=` covers the ordinary case where one video is both.
     */
    val audioSource: String? get() = audioVideoId ?: videoId
}

/** Reads the comma-separated `key=value` list out of a `#VIDEO:` header value. */
fun metaTagsFrom(videoHeader: String): UsdbMetaTags {
    val pairs = videoHeader.split(',')
        .mapNotNull { part ->
            val key = part.substringBefore('=', "").trim()
            val value = part.substringAfter('=', "").trim()
            if (key.isEmpty() || value.isEmpty()) null else key.lowercase() to value
        }
        .toMap()
    return UsdbMetaTags(
        audioVideoId = pairs["a"],
        videoId = pairs["v"],
        coverFile = pairs["co"],
        backgroundFile = pairs["bg"],
    )
}

/** Finds the `#VIDEO:` header in a chart and reads its meta tags. */
fun metaTagsOf(chartText: String): UsdbMetaTags {
    val line = chartText.lineSequence()
        .firstOrNull { it.trimStart().startsWith("#VIDEO:", ignoreCase = true) }
        ?: return UsdbMetaTags()
    return metaTagsFrom(line.substringAfter(':', "").trim())
}

private val TEXTAREA = Regex("""<textarea[^>]*>(.*?)</textarea>""", RegexOption.DOT_MATCHES_ALL)
private val WAIT_BEFORE_FUNCTION = Regex(
    """time\s*=\s*(\d+)\s*;\s*function\s+wait""",
    RegexOption.DOT_MATCHES_ALL,
)
private val LOOSE_WAIT = Regex("""\btime\s*=\s*(\d+)\s*;""")
