package com.example.ultrastarandroidtv.net

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Finds the audio stream behind a YouTube video id.
 *
 * ## Why this exists rather than a library
 *
 * UltraStar charts from USDB carry no media — they name a YouTube video and leave fetching it to
 * the tool. On the desktop that tool is yt-dlp, and on 2026-08-18 shipping yt-dlp onto the Shield
 * looked like it meant a Python runtime *plus* a JavaScript engine *plus* a PO-token attestation
 * service *plus* the family's Google cookies sitting on a living-room device. That was the reason
 * in-app downloading was researched and parked.
 *
 * Re-measured on 2026-08-20, it is none of those things. What yt-dlp does for a video whose
 * client does not require the JS player is three plain HTTP calls:
 *
 *  1. `GET https://www.youtube.com/` and scrape `visitorData` — an anonymous session token.
 *  2. `POST /youtubei/v1/player` with a client context and that token, which answers with JSON
 *     containing direct, already-signed stream URLs.
 *  3. `GET` the URL, which is an ordinary ranged download.
 *
 * No Python, no JavaScript, no cookies, no PO token, no signature deciphering — verified end to
 * end by hand before a line of this was written, and again by [YouTubeAudioTest] against captured
 * responses. That is why this is a few hundred lines of Kotlin with no new runtime dependency,
 * in the same spirit as the project's own YIN, FFT and UltraStar parser.
 *
 * ## What is load-bearing, and what will break first
 *
 * The whole thing rests on a client that declares it needs no JS player. Today that is
 * [InnertubeClients.VISION_OS]. **When YouTube closes that door this file stops working**, and the
 * repair is to add a client to [InnertubeClients.preferred] — a *data* edit, not a rewrite, which
 * is exactly why the client is a value and not a hard-coded request. The failure is loud and
 * specific: every lookup starts returning [RefusalKind.NEEDS_SIGN_IN] or [RefusalKind.NO_AUDIO_STREAM].
 *
 * ## The known hole: "made for kids"
 *
 * The visionOS client cannot see videos flagged made-for-kids — it answers `UNPLAYABLE` with
 * *"This video is not available"*, while still returning the title and duration. Measured on this
 * library: of 19 charts with no audio, 13 resolve and the 6 that do not are all Disney/Trolls
 * uploads, alive and readable by the `android` client, whose *streams* then demand a PO token.
 * So those are genuinely out of reach here, and the practical fix is the one the Add-songs screen
 * offers anyway — search for a different upload of the same song.
 *
 * Refusals are values, not exceptions, because "this video says no" is a normal answer a screen
 * has to render. Exceptions are reserved for the network or YouTube itself being broken, which is
 * a different sentence to put in front of somebody.
 */
class YouTubeAudio(
    private val http: Http,
    private val clients: List<InnertubeClient> = InnertubeClients.preferred,
) {

    /**
     * Cached for the life of the instance. The token is per-session rather than per-video, and
     * fetching the homepage once per song would triple the requests a bulk download makes for
     * nothing.
     */
    private var cachedVisitorData: String? = null

    /**
     * Looks [videoId] up, trying each client in order until one produces audio.
     *
     * Returns [AudioLookup.Found] or [AudioLookup.Refused]; throws [HttpFailure] if the network
     * or YouTube is unreachable. When several clients refuse, the *first* refusal is reported —
     * it came from the most-preferred client and is the closest thing to an authoritative answer.
     */
    fun resolve(videoId: String): AudioLookup {
        require(videoId.isNotBlank()) { "videoId must not be blank" }
        val visitor = visitorData()
        var firstRefusal: AudioLookup.Refused? = null
        for (client in clients) {
            val reply = http.postJson(
                url = PLAYER_URL,
                body = playerRequestBody(client, videoId, visitor),
                headers = headersFor(client, visitor),
            )
            when (val answer = readPlayerResponse(videoId, reply)) {
                is AudioLookup.Found -> return answer
                is AudioLookup.Refused -> if (firstRefusal == null) firstRefusal = answer
            }
        }
        return firstRefusal ?: AudioLookup.Refused(
            videoId = videoId,
            kind = RefusalKind.UNKNOWN,
            status = "",
            reason = "No client was asked.",
            title = null,
            durationSeconds = null,
        )
    }

    /** Forgets the session token, so the next lookup establishes a fresh one. */
    fun forgetSession() {
        cachedVisitorData = null
    }

    private fun visitorData(): String {
        cachedVisitorData?.let { return it }
        val home = http.getText(HOME_URL, mapOf("User-Agent" to clients.first().userAgent))
        val token = visitorDataFrom(home)
            ?: throw HttpFailure("YouTube did not hand out a session token.")
        cachedVisitorData = token
        return token
    }

    private fun headersFor(client: InnertubeClient, visitorData: String): Map<String, String> =
        mapOf(
            "User-Agent" to client.userAgent,
            "X-YouTube-Client-Name" to client.clientId.toString(),
            "X-YouTube-Client-Version" to client.clientVersion,
            "X-Goog-Visitor-Id" to visitorData,
            "Origin" to "https://www.youtube.com",
            "Referer" to "https://www.youtube.com/",
        )

    private companion object {
        const val HOME_URL = "https://www.youtube.com/"
        const val PLAYER_URL = "https://www.youtube.com/youtubei/v1/player"
    }
}

/**
 * One of YouTube's own client identities, as sent in an InnerTube request.
 *
 * A value rather than a hard-coded request because which client works is the part that changes:
 * see the class doc on [YouTubeAudio]. The fields mirror what the API expects verbatim, so a new
 * client can be transcribed from yt-dlp's table without touching any logic.
 */
data class InnertubeClient(
    val clientName: String,
    val clientVersion: String,
    /** The `X-YouTube-Client-Name` number YouTube assigns this client. */
    val clientId: Int,
    val userAgent: String,
    val deviceMake: String? = null,
    val deviceModel: String? = null,
    val osName: String? = null,
    val osVersion: String? = null,
)

object InnertubeClients {

    /**
     * The one that works without a JavaScript runtime, which is the whole reason this file can be
     * pure Kotlin. Cannot see made-for-kids videos — see the note on [YouTubeAudio].
     */
    val VISION_OS = InnertubeClient(
        clientName = "VISIONOS",
        clientVersion = "1.02",
        clientId = 101,
        userAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) AppleWebKit/605.1.15 " +
            "(KHTML, like Gecko) Version/26.0 Safari/605.1.15",
        deviceMake = "Apple",
        deviceModel = "RealityDevice17,1",
        osName = "visionOS",
        osVersion = "26.5.23O471",
    )

    /**
     * Tried in order. One entry today on purpose: every other client measured on 2026-08-20 either
     * returned no formats or demanded a PO token for the stream, and a client that cannot deliver
     * bytes only adds a request and a delay to every failure. Add to this list when a new one is
     * shown to work, not in anticipation.
     */
    val preferred: List<InnertubeClient> = listOf(VISION_OS)
}

/** One downloadable audio stream. */
data class AudioFormat(
    val itag: Int,
    val url: String,
    /** As YouTube reports it, e.g. `audio/mp4; codecs="mp4a.40.2"`. */
    val mimeType: String,
    val bitrate: Int,
    /** Bytes, or `-1` when YouTube did not say. */
    val contentLength: Long,
) {
    /** File extension to save this as: `m4a`, `webm`, … */
    val container: String get() = containerFor(mimeType)

    /** Codec string YouTube declared, e.g. `mp4a.40.2` or `opus`. Empty when it did not say. */
    val codec: String
        get() = mimeType.substringAfter("codecs=", "").trim('"', ' ').substringBefore('"')
}

/** A video whose audio can be fetched. */
data class ResolvedAudio(
    val videoId: String,
    val title: String,
    /** From YouTube, so it is the *video's* length — which is not always the song's. */
    val durationSeconds: Int,
    val format: AudioFormat,
)

/** Why a video could not be fetched, in terms a screen can act on without reading YouTube's prose. */
enum class RefusalKind {
    /** Removed, private, region-blocked, or invisible to this client (the made-for-kids case). */
    UNAVAILABLE,

    /** YouTube wants an account. With no session token this is what every lookup returns. */
    NEEDS_SIGN_IN,

    /** Age or content gate. */
    AGE_RESTRICTED,

    /** A live stream, which has no fixed file to download. */
    LIVE,

    /** Playable, but every audio stream came back needing signature work this client cannot do. */
    NO_AUDIO_STREAM,

    UNKNOWN,
}

/** The answer to "can this video's audio be fetched". */
sealed interface AudioLookup {

    data class Found(val audio: ResolvedAudio) : AudioLookup

    /**
     * [title] and [durationSeconds] are populated whenever YouTube volunteered them, which it
     * does even for `UNPLAYABLE` — so a screen can name the song it is refusing rather than
     * showing a bare id.
     */
    data class Refused(
        val videoId: String,
        val kind: RefusalKind,
        /** YouTube's own status, kept verbatim for diagnosis. */
        val status: String,
        /** YouTube's own sentence, which is localised and changes; classify with [kind] instead. */
        val reason: String,
        val title: String?,
        val durationSeconds: Int?,
    ) : AudioLookup
}

// ---------------------------------------------------------------------------------------------
// The rules, as free functions: pure, and therefore testable without a network or a device.
// ---------------------------------------------------------------------------------------------

/**
 * Digs the anonymous session token out of the YouTube homepage.
 *
 * This is the one scrape in the file and so the most brittle line in it — but there is no
 * documented endpoint that hands the token over, and without it the player API answers
 * `LOGIN_REQUIRED` to everything (measured). The value is JSON-escaped inside the page, so `=`
 * commonly arrives as `=`; taking it raw yields a token that looks right and is not.
 */
fun visitorDataFrom(html: String): String? {
    val match = VISITOR_DATA.find(html) ?: return null
    val raw = match.groupValues[1]
    return unescapeJsonText(raw).takeIf { it.isNotBlank() }
}

private val VISITOR_DATA = Regex("\"visitorData\"\\s*:\\s*\"([^\"]+)\"")

/** Maps a MIME type to the extension the file should be saved with. */
fun containerFor(mimeType: String): String {
    val type = mimeType.substringBefore(';').trim().lowercase()
    return when (type) {
        "audio/mp4" -> "m4a"
        "audio/webm" -> "webm"
        "audio/mpeg" -> "mp3"
        "audio/ogg" -> "ogg"
        else -> type.substringAfter('/', "bin").ifBlank { "bin" }
    }
}

/**
 * Chooses which stream to take.
 *
 * Prefers AAC-in-mp4 over Opus-in-WebM even when the Opus stream carries a higher bitrate,
 * because the Shield decodes AAC in hardware and Opus in software. This app already spends
 * 52–55% of a core during a song with video playing, and the difference between the two formats
 * at these bitrates is inaudible over a television with two people singing over it — so the
 * cheaper one wins. Streams with no `url` are skipped: those need signature work this client
 * cannot do, and a URL that has to be deciphered is the same as no URL here.
 */
fun pickAudio(formats: List<AudioFormat>): AudioFormat? {
    val usable = formats.filter { it.mimeType.startsWith("audio/") && it.url.isNotBlank() }
    if (usable.isEmpty()) return null
    val aac = usable.filter { it.container == "m4a" }
    return (if (aac.isNotEmpty()) aac else usable).maxByOrNull { it.bitrate }
}

/** Builds the InnerTube player request. */
fun playerRequestBody(
    client: InnertubeClient,
    videoId: String,
    visitorData: String,
): String {
    val context = JSONObject()
        .put("clientName", client.clientName)
        .put("clientVersion", client.clientVersion)
        .put("userAgent", client.userAgent)
        .put("hl", "en")
        .put("gl", "US")
        .put("visitorData", visitorData)
    client.deviceMake?.let { context.put("deviceMake", it) }
    client.deviceModel?.let { context.put("deviceModel", it) }
    client.osName?.let { context.put("osName", it) }
    client.osVersion?.let { context.put("osVersion", it) }

    return JSONObject()
        .put("context", JSONObject().put("client", context))
        .put("videoId", videoId)
        .put("contentCheckOk", true)
        .put("racyCheckOk", true)
        .toString()
}

/**
 * Turns a player response into an answer.
 *
 * Reads `videoDetails` before deciding, so a refusal can still name the video: YouTube returns
 * the title and length even when it will not hand over a stream.
 */
fun readPlayerResponse(videoId: String, json: String): AudioLookup {
    val root = try {
        JSONObject(json)
    } catch (e: JSONException) {
        return AudioLookup.Refused(
            videoId = videoId,
            kind = RefusalKind.UNKNOWN,
            status = "",
            reason = "YouTube sent something that was not a player response.",
            title = null,
            durationSeconds = null,
        )
    }

    val details = root.optJSONObject("videoDetails")
    val title = details?.optString("title")?.takeIf { it.isNotBlank() }
    val duration = details?.optString("lengthSeconds")?.toIntOrNull()

    val playability = root.optJSONObject("playabilityStatus")
    val status = playability?.optString("status").orEmpty()
    val reason = playability?.optString("reason").orEmpty()

    fun refuse(kind: RefusalKind, why: String = reason) = AudioLookup.Refused(
        videoId = videoId,
        kind = kind,
        status = status,
        reason = why.ifBlank { "YouTube gave no reason." },
        title = title,
        durationSeconds = duration,
    )

    if (status != "OK") return refuse(refusalKindFor(status))

    val formats = adaptiveFormatsFrom(root)
    val best = pickAudio(formats)
        ?: return refuse(
            RefusalKind.NO_AUDIO_STREAM,
            "No audio stream this app can read. YouTube may have changed how streams are signed.",
        )

    return AudioLookup.Found(
        ResolvedAudio(
            videoId = videoId,
            title = title ?: videoId,
            durationSeconds = duration ?: 0,
            format = best,
        ),
    )
}

/** Maps YouTube's `playabilityStatus.status` onto something a screen can branch on. */
fun refusalKindFor(status: String): RefusalKind = when (status.uppercase()) {
    "UNPLAYABLE", "ERROR" -> RefusalKind.UNAVAILABLE
    "LOGIN_REQUIRED" -> RefusalKind.NEEDS_SIGN_IN
    "AGE_CHECK_REQUIRED", "CONTENT_CHECK_REQUIRED" -> RefusalKind.AGE_RESTRICTED
    "LIVE_STREAM_OFFLINE" -> RefusalKind.LIVE
    else -> RefusalKind.UNKNOWN
}

/**
 * Reads `streamingData.adaptiveFormats`.
 *
 * Only the adaptive list: the legacy `formats` list holds muxed audio+video, so taking one would
 * download a whole video to keep its soundtrack.
 */
private fun adaptiveFormatsFrom(root: JSONObject): List<AudioFormat> {
    val streaming = root.optJSONObject("streamingData") ?: return emptyList()
    val adaptive: JSONArray = streaming.optJSONArray("adaptiveFormats") ?: return emptyList()
    return (0 until adaptive.length()).mapNotNull { index ->
        val entry = adaptive.optJSONObject(index) ?: return@mapNotNull null
        AudioFormat(
            itag = entry.optInt("itag", -1),
            url = entry.optString("url").orEmpty(),
            mimeType = entry.optString("mimeType").orEmpty(),
            bitrate = entry.optInt("bitrate", 0),
            // A string in the JSON, and absent altogether on some streams.
            contentLength = entry.optString("contentLength").toLongOrNull() ?: -1L,
        )
    }
}

/**
 * Undoes JSON string escaping on a value lifted out of a page by regex.
 *
 * Only the escapes that actually appear in these tokens. A full JSON parse is the right tool for
 * a JSON document, but this value is fished out of a `<script>` body, so there is no document to
 * hand to a parser.
 */
private fun unescapeJsonText(raw: String): String {
    if ('\\' !in raw) return raw
    val out = StringBuilder(raw.length)
    var i = 0
    while (i < raw.length) {
        val c = raw[i]
        if (c != '\\' || i == raw.lastIndex) {
            out.append(c)
            i++
            continue
        }
        when (val escape = raw[i + 1]) {
            'u' -> {
                val hex = raw.substring(i + 2, minOf(i + 6, raw.length))
                val code = hex.toIntOrNull(16)
                if (hex.length == 4 && code != null) {
                    out.append(code.toChar())
                    i += 6
                } else {
                    out.append(c)
                    i++
                }
            }
            'n' -> { out.append('\n'); i += 2 }
            'r' -> { out.append('\r'); i += 2 }
            't' -> { out.append('\t'); i += 2 }
            'b' -> { out.append('\b'); i += 2 }
            '"', '\\', '/', '\'' -> { out.append(escape); i += 2 }
            else -> { out.append(escape); i += 2 }
        }
    }
    return out.toString()
}
