package com.example.ultrastarandroidtv.net

import java.io.IOException
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
 * Whole bodies, held in memory. Songs are a few megabytes and [UrlHttp] refuses anything much
 * larger, so streaming to disk would buy nothing but a partly-written file to clean up after —
 * and the download layer wants all-or-nothing anyway, since a half-downloaded song is exactly
 * the broken-folder problem this feature exists to stop making worse.
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
        val detail = body.take(400).trim()
        throw HttpFailure(
            "HTTP $status from ${hostOf(url)}" + if (detail.isEmpty()) "" else ": $detail",
            status,
        )
    }
    return this
}

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
