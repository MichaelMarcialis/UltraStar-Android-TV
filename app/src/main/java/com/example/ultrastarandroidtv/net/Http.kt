package com.example.ultrastarandroidtv.net

import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * An exchange that did not produce a usable answer.
 *
 * [status] is the HTTP status when there was one and `0` when the request never got that far —
 * no route to the host, DNS failure, a timeout. The distinction matters to a screen that has to
 * tell somebody whether the internet is down or the far end said no.
 */
class HttpFailure(message: String, val status: Int = 0) : IOException(message)

/**
 * What came back. Header names are matched without regard to case, as HTTP requires.
 *
 * Carries **bytes**, with [body] a text view of them, because the same client fetches HTML pages
 * and MP4 audio. Decoding every reply to text up front would corrupt the audio; keeping two
 * parallel methods would let a caller pick the wrong one.
 */
class HttpReply(
    val status: Int,
    val bytes: ByteArray,
    headers: Map<String, List<String>>,
) {
    constructor(status: Int, body: String, headers: Map<String, List<String>>) :
        this(status, body.toByteArray(Charsets.UTF_8), headers)

    /** The reply decoded as UTF-8 text. Meaningless for media, which is what [bytes] is for. */
    val body: String by lazy(LazyThreadSafetyMode.NONE) { bytes.decodeToString() }

    private val byLowerName: Map<String, List<String>> =
        headers.entries.associate { (name, values) -> name.lowercase() to values }

    fun header(name: String): String? = byLowerName[name.lowercase()]?.firstOrNull()

    fun headers(name: String): List<String> = byLowerName[name.lowercase()].orEmpty()

    val ok: Boolean get() = status in 200..299
}

/**
 * A body being read as it arrives rather than held whole.
 *
 * [declaredLength] is the `Content-Length` the server gave, or `-1` when it gave none — worth
 * having so a caller can refuse something absurd before spending the bandwidth rather than after.
 * Closing this closes the connection underneath it.
 */
class HttpStream(
    val stream: InputStream,
    val declaredLength: Long,
    /**
     * The status that came back — `206` for a range that was honoured, `200` for a whole body.
     *
     * Kept because a chunked download has to know the difference. A server, a proxy or a
     * redirect that ignores `Range` answers `200` with the entire file, and appending that to
     * bytes already collected produces a file with its beginning written twice.
     */
    val status: Int = 200,
    /** The raw `Content-Range` header, or null — the reply's own account of what it sent. */
    val contentRange: String? = null,
    private val onClose: () -> Unit = {},
) : Closeable {

    /**
     * Where this reply says its bytes start, or null when it did not say.
     *
     * `Content-Range: bytes 5242880-10485759/77000000` — the first number is the only part
     * worth reading here, because the only question is whether the server sent the piece that
     * was asked for or some other piece.
     */
    val rangeStart: Long?
        get() = contentRange
            ?.substringAfter("bytes ", "")
            ?.substringBefore('-', "")
            ?.trim()
            ?.toLongOrNull()

    override fun close() {
        runCatching { stream.close() }
        onClose()
    }
}

/** One request. [body] is already encoded; [contentType] says how. */
class HttpRequest(
    val url: String,
    val method: String = "GET",
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray? = null,
    val contentType: String? = null,
    /**
     * Whether to follow 3xx automatically. Off for anything that signs in: a login's redirect is
     * how it reports success, and following it discards the `Set-Cookie` that came with it.
     */
    val followRedirects: Boolean = true,
)

/**
 * The little of HTTP this app needs — one method, plus three conveniences over it.
 *
 * Narrow on purpose, for the same reason [com.example.ultrastarandroidtv.library.DocumentTree] is
 * narrow: the interesting part of fetching a song is the decisions, and none of them should need
 * a network, a device, or a website to be having a good day in order to be tested. Against this
 * interface those decisions are ordinary logic over canned replies.
 *
 * Bodies come back whole and in memory, which is right for everything that has to be
 * all-or-nothing: a song is a few megabytes, [UrlHttp] refuses anything much larger, and a
 * half-written chart is exactly the broken-folder problem this feature exists to stop making.
 *
 * [openStream] is the exception, and it exists for one reason: **a music video does not fit that
 * rule.** This app's heap is capped at 192 MB (`dalvik.vm.heapgrowthlimit` on the Shield), and a
 * three-minute 1080p video runs to 60-70 MB — a single allocation of that size, next to Compose
 * and a decoder, is how an out-of-memory kill happens. A video is also a nicety rather than part
 * of the song, so a failed one costs nothing and a partly-written one is simply deleted.
 */
interface Http {

    /** Performs [request]. Throws [HttpFailure] only if it never completed; a 404 is a reply. */
    fun send(request: HttpRequest): HttpReply

    /** Body of a GET. Throws [HttpFailure] on a non-2xx status. */
    fun getText(url: String, headers: Map<String, String> = emptyMap()): String =
        send(HttpRequest(url, headers = headers)).orThrow(url).body

    /** POSTs [body] as `application/json`. Throws [HttpFailure] on a non-2xx status. */
    fun postJson(url: String, body: String, headers: Map<String, String> = emptyMap()): String =
        send(
            HttpRequest(
                url = url,
                method = "POST",
                headers = headers,
                body = body.toByteArray(Charsets.UTF_8),
                contentType = "application/json",
            ),
        ).orThrow(url).body

    /** Whole body of a GET as bytes, for media. Throws [HttpFailure] on a non-2xx status. */
    fun getBytes(url: String, headers: Map<String, String> = emptyMap()): ByteArray =
        send(HttpRequest(url, headers = headers)).orThrow(url).bytes

    /**
     * A GET whose body is read as it arrives, for something too big to hold. Close the result.
     *
     * The default here simply fetches the whole body first, which is what a test fake wants and
     * is why implementing [send] is still enough to be an [Http]. [UrlHttp] overrides it with a
     * connection that stays open, which is the version that actually saves the memory.
     */
    fun openStream(url: String, headers: Map<String, String> = emptyMap()): HttpStream {
        val reply = send(HttpRequest(url, headers = headers)).orThrow(url)
        return HttpStream(
            stream = ByteArrayInputStream(reply.bytes),
            declaredLength = reply.bytes.size.toLong(),
            status = reply.status,
            contentRange = reply.header("Content-Range"),
        )
    }

    /**
     * POSTs [fields] as an HTML form. Returns the whole reply rather than the body, because the
     * sites this talks to report a sign-in through a redirect and a cookie rather than through
     * the page they hand back.
     */
    fun postForm(
        url: String,
        fields: Map<String, String>,
        headers: Map<String, String> = emptyMap(),
        followRedirects: Boolean = true,
    ): HttpReply = send(
        HttpRequest(
            url = url,
            method = "POST",
            headers = headers,
            body = formEncode(fields).toByteArray(Charsets.UTF_8),
            contentType = "application/x-www-form-urlencoded",
            followRedirects = followRedirects,
        ),
    )
}

private fun HttpReply.orThrow(url: String): HttpReply {
    if (!ok) {
        val detail = summarise(body)
        throw HttpFailure(
            "HTTP $status from ${hostOf(url)}" + if (detail.isEmpty()) "" else ": $detail",
            status,
        )
    }
    return this
}

/**
 * A server's own explanation, cut down to something that can be shown to a person.
 *
 * This message ends up on a television, inside a list row, and the raw version is unusable there:
 * an error page is mostly markup, and 400 characters of it once stretched a single row of the
 * download list to the full height of the screen. Tags go, runs of whitespace collapse, and what
 * is left is capped at a sentence's worth — enough to say what went wrong, never enough to break
 * a layout.
 */
internal fun summarise(body: String): String =
    body.replace(SCRIPT_OR_STYLE, " ")
        .replace(HTML_TAG, " ")
        .replace(WHITESPACE_RUN, " ")
        .trim()
        .take(MAX_DETAIL_CHARS)
        .trim()

private const val MAX_DETAIL_CHARS = 120
private val SCRIPT_OR_STYLE = Regex(
    """<(?:script|style)\b[^>]*>.*?</(?:script|style)>""",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
)
private val HTML_TAG = Regex("""<[^>]*>""")
private val WHITESPACE_RUN = Regex("""\s+""")

/** Percent-encodes [fields] the way a browser posts a form. */
fun formEncode(fields: Map<String, String>): String =
    fields.entries.joinToString("&") { (name, value) ->
        "${URLEncoder.encode(name, "UTF-8")}=${URLEncoder.encode(value, "UTF-8")}"
    }

private fun hostOf(url: String): String = runCatching { URL(url).host }.getOrNull() ?: url

/**
 * [Http] over `HttpURLConnection`, which on Android is OkHttp underneath — so this is a thin shim
 * rather than a hand-rolled client.
 *
 * Deliberately does *not* set `Accept-Encoding`. Left alone, `HttpURLConnection` adds gzip itself
 * and transparently decompresses the reply; set it by hand and that transparency switches off,
 * leaving the caller holding compressed bytes it did not ask to deal with.
 */
class UrlHttp(
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 30_000,
    /** Refuse a body larger than this. Generous for a song, far below anything alarming. */
    private val maxBytes: Long = 64L * 1024 * 1024,
) : Http {

    /**
     * Reads a whole body, refusing anything past [maxBytes].
     *
     * A cap rather than trust: a redirect to something enormous, or a stream URL that turns out
     * to be a video, would otherwise fill a card quietly. Songs run to a few megabytes.
     */
    private fun readCapped(stream: java.io.InputStream, url: String): ByteArray {
        val out = java.io.ByteArrayOutputStream(DEFAULT_BUFFER_SIZE)
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            total += read
            if (total > maxBytes) {
                throw HttpFailure("Reply from ${hostOf(url)} is larger than ${maxBytes / (1024 * 1024)} MB")
            }
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    /**
     * Opens a GET and hands back the live stream, leaving the connection open until it is closed.
     *
     * Deliberately does not go through [send], which reads the whole body and disconnects in a
     * `finally` — the two things this method exists to avoid.
     */
    override fun openStream(url: String, headers: Map<String, String>): HttpStream {
        val connection = try {
            (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                headers.forEach { (name, value) -> setRequestProperty(name, value) }
            }
        } catch (e: IOException) {
            throw HttpFailure("Could not reach ${hostOf(url)}: ${e.message}")
        }

        val status = try {
            connection.responseCode
        } catch (e: IOException) {
            connection.disconnect()
            throw HttpFailure("Could not reach ${hostOf(url)}: ${e.message}")
        }

        // A partial reply is the *expected* success here: every media URL is asked for with a
        // Range header, and the answer to that is 206 rather than 200.
        if (status !in 200..299) {
            connection.disconnect()
            throw HttpFailure("HTTP $status from ${hostOf(url)}", status)
        }

        val stream = connection.inputStream
            ?: run {
                connection.disconnect()
                throw HttpFailure("${hostOf(url)} sent no body.")
            }
        return HttpStream(
            stream = stream,
            declaredLength = connection.contentLengthLong,
            status = status,
            contentRange = connection.getHeaderField("Content-Range"),
        ) { connection.disconnect() }
    }

    override fun send(request: HttpRequest): HttpReply {
        val connection = try {
            URL(request.url).openConnection() as HttpURLConnection
        } catch (e: IOException) {
            throw HttpFailure("Could not reach ${hostOf(request.url)}: ${e.message}")
        }
        try {
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.instanceFollowRedirects = request.followRedirects
            connection.requestMethod = request.method
            request.contentType?.let { connection.setRequestProperty("Content-Type", it) }
            request.headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }

            request.body?.let { bytes ->
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
            }

            val status = try {
                connection.responseCode
            } catch (e: IOException) {
                throw HttpFailure("Could not reach ${hostOf(request.url)}: ${e.message}")
            }
            // The error stream carries the server's own explanation, which is a better thing to
            // put in front of somebody than a bare status number.
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val bytes = stream?.use { readCapped(it, request.url) } ?: ByteArray(0)
            return HttpReply(status, bytes, connection.headerFields.orEmpty().filterKeys { it != null })
        } finally {
            connection.disconnect()
        }
    }
}
