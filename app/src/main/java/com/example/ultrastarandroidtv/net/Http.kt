package com.example.ultrastarandroidtv.net

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * An exchange that did not produce a usable answer.
 *
 * [status] is the HTTP status when there was one and `0` when the request never got that far —
 * no route to the host, DNS failure, a timeout. The distinction matters to a screen that has to
 * tell somebody whether the internet is down or YouTube said no.
 */
class HttpFailure(message: String, val status: Int = 0) : IOException(message)

/**
 * The little of HTTP this app needs.
 *
 * Narrow for the same reason [com.example.ultrastarandroidtv.library.DocumentTree] is narrow:
 * the interesting part of fetching a song is the decisions — which client to ask, which audio
 * format to take, what to tell the user when the answer is no — and none of that should need a
 * network, a device, or YouTube to be having a good day in order to be tested. Against this
 * interface those decisions are ordinary logic over canned responses.
 *
 * Text only. Downloading the media itself streams to disk with progress and belongs to the
 * download layer, not here.
 */
interface Http {
    /** Body of a GET as text. Throws [HttpFailure] on transport failure or a non-2xx status. */
    fun getText(url: String, headers: Map<String, String> = emptyMap()): String

    /** Body of a POST of [body] as `application/json`, as text. Same failure rules as [getText]. */
    fun postJson(url: String, body: String, headers: Map<String, String> = emptyMap()): String
}

/**
 * [Http] over `HttpURLConnection`, which on Android is OkHttp underneath — so this is a thin
 * shim rather than a hand-rolled client.
 *
 * Deliberately does *not* set `Accept-Encoding`. Left alone, `HttpURLConnection` adds gzip
 * itself and transparently decompresses the reply; set it by hand and that transparency switches
 * off, leaving the caller holding compressed bytes it did not ask to deal with.
 */
class UrlHttp(
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 30_000,
) : Http {

    override fun getText(url: String, headers: Map<String, String>): String =
        exchange(url, headers, body = null)

    override fun postJson(url: String, body: String, headers: Map<String, String>): String =
        exchange(url, headers + ("Content-Type" to "application/json"), body = body)

    private fun exchange(url: String, headers: Map<String, String>, body: String?): String {
        val connection = try {
            URL(url).openConnection() as HttpURLConnection
        } catch (e: IOException) {
            throw HttpFailure("Could not reach ${hostOf(url)}: ${e.message}")
        }
        try {
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.instanceFollowRedirects = true
            headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }

            if (body != null) {
                connection.requestMethod = "POST"
                connection.doOutput = true
                val bytes = body.toByteArray(Charsets.UTF_8)
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
            }

            val status = try {
                connection.responseCode
            } catch (e: IOException) {
                throw HttpFailure("Could not reach ${hostOf(url)}: ${e.message}")
            }
            if (status !in 200..299) {
                // The error stream carries the server's explanation; a bare status code is a
                // worse thing to put in front of somebody than the sentence YouTube wrote.
                val detail = connection.errorStream?.use { it.readBytes().decodeToString() }
                    ?.take(400).orEmpty()
                throw HttpFailure("HTTP $status from ${hostOf(url)}${detail.prefixed()}", status)
            }
            return connection.inputStream.use { it.readBytes() }.decodeToString()
        } finally {
            connection.disconnect()
        }
    }

    private fun String.prefixed(): String = if (isBlank()) "" else ": $this"

    private fun hostOf(url: String): String =
        runCatching { URL(url).host }.getOrNull() ?: url
}
