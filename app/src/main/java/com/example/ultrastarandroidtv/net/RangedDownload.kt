package com.example.ultrastarandroidtv.net

import java.io.ByteArrayOutputStream
import java.io.OutputStream

/**
 * The most YouTube will hand over in one response before it throttles the connection.
 *
 * **Measured on 2026-08-23, against one 77 MB video, one request per size, each from a different
 * offset so nothing came from the last test's cache:**
 *
 * | asked for in one request | rate |
 * |---|---|
 * | 8 MB | 49.9 MB/s |
 * | 10 MB | 66.3 MB/s |
 * | 12 MB | **0.72 MB/s** |
 * | 14 MB | **0.72 MB/s** |
 * | 16 MB | **0.72 MB/s** |
 *
 * The knee sits at exactly 10 MiB, and past it the rate collapses by a factor of seventy.
 */
const val UNTHROTTLED_RESPONSE_BYTES: Long = 10L * 1024 * 1024

/**
 * How much to ask for at a time: half the limit, so there is room for the rule to move.
 *
 * A typical music video is 60-70 MB, so this is a dozen requests — and each one completed in
 * about 0.2 s in the measurement above, which makes the extra round trips free next to what they
 * save. Smaller would be safer still and is the direction to move if this ever regresses.
 */
const val DOWNLOAD_CHUNK_BYTES: Int = 5 * 1024 * 1024

/** Big enough that a slow 60 MB video is not also a million tiny writes to an SD card. */
private const val COPY_BUFFER_BYTES = 64 * 1024

/**
 * Fetches [url] into [out] as a series of bounded ranges, rather than as one long download.
 *
 * ## Why this exists, and why the obvious simplification is wrong
 *
 * Every media URL YouTube hands out **must** be fetched with a `Range` header — a plain `GET`
 * comes back at about 31 KB/s. That much was known, and it is why a four-megabyte song stopped
 * taking two minutes. What was *not* known is that the header alone is not enough: what YouTube
 * actually meters is **how many bytes a single response carries**, and anything over
 * [UNTHROTTLED_RESPONSE_BYTES] is served at roughly 0.72 MB/s no matter how it was asked for.
 *
 * So `Range: bytes=0-` looked like the fix only because it was measured against a 3 MB song,
 * which fits under the limit. A 77 MB video does not, and it crawled: **40 MB took 55.4 s as one
 * request and 0.8 s as 10 MB chunks — a seventy-one-fold difference**, with the slow case running
 * at a flat 0.72 MB/s from its very first megabyte rather than starting fast and clamping.
 *
 * An explicit end does not help either: one request for `bytes=0-20971519` was throttled exactly
 * as hard as `bytes=0-`. **The size of the response is the only thing that matters**, which is why
 * this asks for a bounded amount at a time and there is no single-request path left to fall back
 * to. It is also, incidentally, how every video player on the internet fetches a file.
 *
 * This is not the same thing as the sub-256 KB chunking that was deliberately *not* built for
 * made-for-kids videos. That was a way of slipping under a cap on an un-attested client, which is
 * an attestation bypass. This is ordinary ranged HTTP against a stream we are entitled to fetch.
 *
 * @param declaredLength what the stream said it was, or `-1` when unknown — in which case
 *   fetching stops at the first range that comes back empty.
 * @param onProgress called with the running total and [declaredLength], often. Gate it on
 *   something coarse before putting it on screen.
 * @return how many bytes were written.
 */
fun downloadInChunks(
    http: Http,
    url: String,
    out: OutputStream,
    headers: Map<String, String> = emptyMap(),
    declaredLength: Long = -1L,
    chunkBytes: Int = DOWNLOAD_CHUNK_BYTES,
    onProgress: (written: Long, total: Long) -> Unit = { _, _ -> },
): Long {
    require(chunkBytes > 0) { "chunkBytes must be positive" }

    val buffer = ByteArray(COPY_BUFFER_BYTES)
    var written = 0L

    while (declaredLength <= 0L || written < declaredLength) {
        val wanted = written + chunkBytes - 1
        val end = if (declaredLength > 0L) minOf(wanted, declaredLength - 1) else wanted
        var got = 0L

        try {
            http.openStream(url, headers + ("Range" to "bytes=$written-$end")).use { source ->
                while (true) {
                    val read = source.stream.read(buffer)
                    if (read < 0) break
                    out.write(buffer, 0, read)
                    written += read
                    got += read
                    onProgress(written, declaredLength)
                }
            }
        } catch (e: HttpFailure) {
            // Asking past the end of a file whose length nobody declared is how this loop finds
            // that end. Anything else is a real failure and belongs to the caller.
            if (e.status == RANGE_NOT_SATISFIABLE && declaredLength <= 0L) break
            throw e
        }

        // A range that returned nothing is the end of the file -- but only when nobody said
        // how long the file was. With a declared length still unmet, an empty 2xx is a
        // *short read*, and treating it as the end would write a truncated song and call it
        // finished. That is the same corruption a declared-length 416 is already rejected
        // for, and it has to be rejected the same way rather than saved silently.
        if (got == 0L) {
            if (declaredLength > 0L && written < declaredLength) {
                throw HttpFailure(
                    "The download stopped after $written of $declaredLength bytes.",
                )
            }
            break
        }
    }

    return written
}

/**
 * The same, for something small enough to hold: covers, charts, and a song's audio.
 *
 * Audio is usually 3-5 MB and so would never be throttled anyway, but a long recording can pass
 * [UNTHROTTLED_RESPONSE_BYTES] — a thirteen-minute video's soundtrack does — and the failure is
 * silent and slow rather than loud. One rule for both is cheaper than remembering which files are
 * allowed to be big.
 */
fun fetchInChunks(
    http: Http,
    url: String,
    headers: Map<String, String> = emptyMap(),
    declaredLength: Long = -1L,
    chunkBytes: Int = DOWNLOAD_CHUNK_BYTES,
    maxBytes: Long = MAX_IN_MEMORY_BYTES,
): ByteArray {
    // A declared length is somebody else's number, and using it as an allocation size hands
    // a remote server the size of this app's heap -- 192 MB on this device, of which
    // `toByteArray` then wants a second copy. A length above two gigabytes is worse than
    // that: it truncates to a *negative* int and throws before a byte has been fetched.
    val expected =
        if (declaredLength in 1..maxBytes) declaredLength.toInt() else DEFAULT_BUFFER_SIZE
    val out = object : ByteArrayOutputStream(expected) {
        override fun write(source: ByteArray, offset: Int, length: Int) {
            if (count.toLong() + length > maxBytes) {
                throw HttpFailure("That file is larger than ${maxBytes / (1024 * 1024)} MB.")
            }
            super.write(source, offset, length)
        }
    }
    downloadInChunks(http, url, out, headers, declaredLength, chunkBytes)
    return out.toByteArray()
}

/**
 * The most any [fetchInChunks] caller may hold in memory at once.
 *
 * The same cap [UrlHttp] already applies to a whole-body read, which fetching in chunks was
 * otherwise quietly routing around. Everything that comes through here is a song's audio or a
 * cover -- a few megabytes -- and anything claiming to be sixty-four times that is a mistake
 * worth refusing rather than a file worth having. A music video never comes through here at
 * all: it is streamed straight to the card by [downloadInChunks].
 */
const val MAX_IN_MEMORY_BYTES: Long = 64L * 1024 * 1024

private const val RANGE_NOT_SATISFIABLE = 416
