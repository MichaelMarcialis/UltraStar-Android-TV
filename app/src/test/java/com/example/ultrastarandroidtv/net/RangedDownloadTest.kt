package com.example.ultrastarandroidtv.net

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fetching in bounded ranges, which is the difference between a video download taking three
 * seconds and taking a hundred.
 *
 * The measurement behind it is on [downloadInChunks] itself. What these pin is the behaviour that
 * measurement demands: that every request is bounded, that none of them exceeds the size YouTube
 * throttles at, and that the pieces come back out in the right order — because a chunked download
 * that silently reassembles wrongly produces a file that is the right length and will not play.
 */
class RangedDownloadTest {

    // -------------------------------------------------------------------------------------
    // The rule the whole thing exists for
    // -------------------------------------------------------------------------------------

    /**
     * The load-bearing test. Measured against a real stream: a single request for more than
     * 10 MiB is served at 0.72 MB/s, and one for less at 50-66 MB/s. Nothing here may ever ask
     * for more than that in one go, however the chunk size is set.
     */
    @Test
    fun `never asks for more than YouTube will serve unthrottled`() {
        val net = FakeRanges(ByteArray(40 * 1024 * 1024))

        downloadInChunks(net, URL, ByteArrayOutputStream(), declaredLength = net.body.size.toLong())

        assertTrue("expected several requests", net.ranges.size > 1)
        for ((start, end) in net.ranges) {
            val asked = end - start + 1
            assertTrue(
                "asked for $asked bytes in one request, which YouTube throttles",
                asked <= UNTHROTTLED_RESPONSE_BYTES,
            )
        }
    }

    @Test
    fun `the chunk size stays below the throttle with room to spare`() {
        assertTrue(DOWNLOAD_CHUNK_BYTES < UNTHROTTLED_RESPONSE_BYTES)
    }

    // -------------------------------------------------------------------------------------
    // Putting it back together
    // -------------------------------------------------------------------------------------

    @Test
    fun `the pieces reassemble into exactly the original`() {
        val body = ByteArray(2_500) { (it % 251).toByte() }
        val net = FakeRanges(body)
        val out = ByteArrayOutputStream()

        val written = downloadInChunks(
            net, URL, out, declaredLength = body.size.toLong(), chunkBytes = 400,
        )

        assertEquals(body.size.toLong(), written)
        assertArrayEquals(body, out.toByteArray())
    }

    @Test
    fun `asks for contiguous ranges, in order, starting at zero`() {
        val net = FakeRanges(ByteArray(1_000))

        downloadInChunks(net, URL, ByteArrayOutputStream(), declaredLength = 1_000, chunkBytes = 300)

        assertEquals(
            listOf(0 to 299, 300 to 599, 600 to 899, 900 to 999),
            net.ranges,
        )
    }

    /** A declared length is a promise not to ask past the end, so no request may exceed it. */
    @Test
    fun `the last range stops at the declared end rather than overshooting`() {
        val net = FakeRanges(ByteArray(700))

        downloadInChunks(net, URL, ByteArrayOutputStream(), declaredLength = 700, chunkBytes = 300)

        assertEquals(699, net.ranges.last().second)
    }

    @Test
    fun `one request is enough for something smaller than a chunk`() {
        val net = FakeRanges(ByteArray(100))

        downloadInChunks(net, URL, ByteArrayOutputStream(), declaredLength = 100, chunkBytes = 5_000)

        assertEquals(listOf(0 to 99), net.ranges)
    }

    // -------------------------------------------------------------------------------------
    // Not knowing how long it is
    // -------------------------------------------------------------------------------------

    /** YouTube declares a length for every stream, but nothing here may depend on that. */
    @Test
    fun `an undeclared length reads until a range comes back empty`() {
        val body = ByteArray(750) { (it % 97).toByte() }
        val net = FakeRanges(body)
        val out = ByteArrayOutputStream()

        val written = downloadInChunks(net, URL, out, declaredLength = -1, chunkBytes = 300)

        assertEquals(750L, written)
        assertArrayEquals(body, out.toByteArray())
    }

    /**
     * A server that answers 416 rather than an empty body is saying the same thing — but only
     * when nobody declared a length. With one declared, a 416 means something went wrong and
     * swallowing it would write a truncated file and call it a success.
     */
    @Test
    fun `a 416 ends an undeclared read and fails a declared one`() {
        val ends = FakeRanges(ByteArray(600), refuseBeyondEnd = true)
        assertEquals(
            600L,
            downloadInChunks(ends, URL, ByteArrayOutputStream(), declaredLength = -1, chunkBytes = 300),
        )

        val declaresTooMuch = FakeRanges(ByteArray(600), refuseBeyondEnd = true)
        val thrown = runCatching {
            downloadInChunks(
                declaresTooMuch, URL, ByteArrayOutputStream(),
                declaredLength = 900, chunkBytes = 300,
            )
        }.exceptionOrNull()
        assertTrue("a short file must not pass as complete", thrown is HttpFailure)
    }

    /** Any other failure is the caller's to report — a network drop is not an end of file. */
    @Test
    fun `a real failure is not mistaken for the end`() {
        val net = FakeRanges(ByteArray(900), failAfterRequests = 2)

        val thrown = runCatching {
            downloadInChunks(net, URL, ByteArrayOutputStream(), declaredLength = 900, chunkBytes = 300)
        }.exceptionOrNull()

        assertTrue(thrown is HttpFailure)
        assertEquals(500, (thrown as HttpFailure).status)
    }

    /**
     * A short read must fail rather than pass as the end of the file.
     *
     * An empty 2xx in the middle of a download used to break the loop and return what had been
     * collected, which wrote a truncated song and reported success -- the same corruption a
     * declared-length 416 was already being rejected for, arrived at through a different door.
     */
    @Test
    fun `a range that stops early is a failure, not an ending`() {
        val net = FakeRanges(ByteArray(600))

        val thrown = runCatching {
            downloadInChunks(net, URL, ByteArrayOutputStream(), declaredLength = 900, chunkBytes = 300)
        }.exceptionOrNull()

        assertTrue("a truncated file must not pass as complete", thrown is HttpFailure)
    }

    /** With no declared length, the same empty range is simply where the file ends. */
    @Test
    fun `an empty range still ends an undeclared read`() {
        val net = FakeRanges(ByteArray(600))

        assertEquals(
            600L,
            downloadInChunks(net, URL, ByteArrayOutputStream(), declaredLength = -1, chunkBytes = 300),
        )
    }

    // -------------------------------------------------------------------------------------
    // What may be held in memory
    // -------------------------------------------------------------------------------------

    /**
     * A declared length is somebody else's number, and it used to be handed straight to an
     * allocation. This app's heap is 192 MB and `toByteArray` wants a second copy of whatever is
     * collected, so a wrong or hostile length could take the process down before a byte arrived.
     */
    @Test
    fun `refuses to collect more than the cap allows`() {
        val net = FakeRanges(ByteArray(5_000))

        val thrown = runCatching {
            fetchInChunks(net, URL, declaredLength = 5_000, chunkBytes = 1_000, maxBytes = 2_000)
        }.exceptionOrNull()

        assertTrue(thrown is HttpFailure)
    }

    /**
     * A length beyond two gigabytes truncates to a *negative* int, and
     * `ByteArrayOutputStream(-1)` throws before a single byte is fetched.
     *
     * The download still fails — a declared length that nothing matches is bogus data and a short
     * read is refused on purpose — but it has to fail as a *download*, having actually tried,
     * rather than as an allocation error thrown at the door.
     */
    @Test
    fun `an absurd declared length does not become an allocation`() {
        val net = FakeRanges(ByteArray(400))

        val thrown = runCatching {
            fetchInChunks(net, URL, declaredLength = Long.MAX_VALUE, chunkBytes = 200)
        }.exceptionOrNull()

        assertTrue("must not be an allocation failure", thrown is HttpFailure)
        assertTrue("and it must have got as far as asking", net.ranges.isNotEmpty())
    }

    @Test
    fun `the cap is the same one a whole-body read already had`() {
        assertEquals(64L * 1024 * 1024, MAX_IN_MEMORY_BYTES)
    }

    // -------------------------------------------------------------------------------------
    // A reply that is not the range that was asked for
    // -------------------------------------------------------------------------------------

    /**
     * A whole body is a perfectly good answer to the *first* request, and nothing afterwards.
     *
     * A server, proxy or redirect that ignores `Range` answers 200 with the entire file. Appended
     * to bytes already collected that writes the beginning of the file twice — and with no declared
     * length to stop it, every pass appends the whole body again and the loop never ends.
     */
    @Test
    fun `a server that ignores the range is accepted once and then done`() {
        val body = ByteArray(900) { (it % 31).toByte() }
        val net = FakeRanges(body, ignoreRange = true)
        val out = ByteArrayOutputStream()

        val written = downloadInChunks(net, URL, out, declaredLength = 900, chunkBytes = 300)

        assertEquals(900L, written)
        assertArrayEquals("the file must not have its start written twice", body, out.toByteArray())
        assertEquals("one request was enough", 1, net.ranges.size)
    }

    /** The unbounded case: without a declared length this used to loop for ever. */
    @Test
    fun `an ignored range does not loop for ever when the length is unknown`() {
        val body = ByteArray(900)
        val net = FakeRanges(body, ignoreRange = true)

        val written = downloadInChunks(
            net, URL, ByteArrayOutputStream(), declaredLength = -1, chunkBytes = 300,
        )

        assertEquals(900L, written)
        assertEquals(1, net.ranges.size)
    }

    /** Part way through, the same reply is a duplicate rather than an answer. */
    @Test
    fun `a whole body part way through a download is refused`() {
        val net = FakeRanges(ByteArray(900), ignoreRangeAfterRequests = 1)

        val thrown = runCatching {
            downloadInChunks(net, URL, ByteArrayOutputStream(), declaredLength = 900, chunkBytes = 300)
        }.exceptionOrNull()

        assertTrue(thrown is HttpFailure)
    }

    /**
     * A whole body is only an answer if it is the *whole* file.
     *
     * Accepting a 200 as "nothing left to ask for" walked straight around the short-read
     * guard: a server that ignored the range and returned a truncated body ended the loop
     * with a success, saving corrupt media. The two exits have to account for the same bytes.
     */
    @Test
    fun `a whole body shorter than the declared length is refused`() {
        val net = FakeRanges(ByteArray(400), ignoreRange = true)

        val thrown = runCatching {
            downloadInChunks(net, URL, ByteArrayOutputStream(), declaredLength = 900, chunkBytes = 300)
        }.exceptionOrNull()

        assertTrue("a truncated file must not pass as complete", thrown is HttpFailure)
    }

    /** And a body longer than declared is not this file either. */
    @Test
    fun `a whole body longer than the declared length is refused`() {
        val net = FakeRanges(ByteArray(1_500), ignoreRange = true)

        val thrown = runCatching {
            downloadInChunks(net, URL, ByteArrayOutputStream(), declaredLength = 900, chunkBytes = 300)
        }.exceptionOrNull()

        assertTrue(thrown is HttpFailure)
    }

    /** And a 206 for the wrong offset is a different piece of the file, not this one. */
    @Test
    fun `a range starting somewhere else is refused`() {
        val net = FakeRanges(ByteArray(900), answerFromOffset = 600)

        val thrown = runCatching {
            downloadInChunks(net, URL, ByteArrayOutputStream(), declaredLength = 900, chunkBytes = 300)
        }.exceptionOrNull()

        assertTrue(thrown is HttpFailure)
    }

    // -------------------------------------------------------------------------------------
    // Talking to a screen
    // -------------------------------------------------------------------------------------

    @Test
    fun `progress runs from nothing to everything and never backwards`() {
        val net = FakeRanges(ByteArray(1_000))
        val seen = mutableListOf<Long>()

        downloadInChunks(net, URL, ByteArrayOutputStream(), declaredLength = 1_000, chunkBytes = 300) {
            written, total ->
            assertEquals(1_000L, total)
            seen += written
        }

        assertEquals(seen.sorted(), seen)
        assertEquals(1_000L, seen.last())
    }

    // -------------------------------------------------------------------------------------
    // The bytes-in-hand version
    // -------------------------------------------------------------------------------------

    @Test
    fun `fetchInChunks returns the whole thing`() {
        val body = ByteArray(1_100) { (it % 13).toByte() }
        val net = FakeRanges(body)

        val got = fetchInChunks(net, URL, declaredLength = body.size.toLong(), chunkBytes = 250)

        assertArrayEquals(body, got)
    }

    /** Headers the caller asked for still go out; only `Range` is the chunker's to set. */
    @Test
    fun `keeps the caller's headers and supplies the range itself`() {
        val net = FakeRanges(ByteArray(400))

        fetchInChunks(net, URL, headers = mapOf("User-Agent" to "test"), declaredLength = 400)

        assertEquals("test", net.lastHeaders["User-Agent"])
        assertEquals("bytes=0-399", net.lastHeaders["Range"])
    }

    // -------------------------------------------------------------------------------------

    private companion object {
        const val URL = "https://rr1.googlevideo.com/videoplayback?a=1"
    }

    /** A CDN that honours `Range`, which is the only kind this code works against. */
    private class FakeRanges(
        val body: ByteArray,
        private val refuseBeyondEnd: Boolean = false,
        private val failAfterRequests: Int = Int.MAX_VALUE,
        /** Answers every request with the whole file and a 200, as a careless proxy would. */
        private val ignoreRange: Boolean = false,
        /** The same, but only once this many ranges have been served properly. */
        private val ignoreRangeAfterRequests: Int = Int.MAX_VALUE,
        /** Answers with the right length from the wrong place. */
        private val answerFromOffset: Int? = null,
    ) : Http {
        val ranges = mutableListOf<Pair<Int, Int>>()
        var lastHeaders: Map<String, String> = emptyMap()

        override fun send(request: HttpRequest): HttpReply {
            lastHeaders = request.headers
            if (ranges.size >= failAfterRequests) return HttpReply(500, "", NO_HEADERS)

            val spec = request.headers["Range"]?.removePrefix("bytes=")
                ?: return HttpReply(200, body, NO_HEADERS)
            val start = spec.substringBefore('-').toInt()
            val end = spec.substringAfter('-').toIntOrNull() ?: (body.size - 1)
            ranges += start to end

            if (ignoreRange || ranges.size > ignoreRangeAfterRequests) {
                return HttpReply(200, body, NO_HEADERS)
            }
            answerFromOffset?.let { from ->
                val length = minOf(end + 1, body.size) - start
                return HttpReply(
                    206,
                    body.copyOfRange(from, minOf(from + length, body.size)),
                    mapOf("Content-Range" to listOf("bytes $from-${from + length - 1}/${body.size}")),
                )
            }

            if (start >= body.size) {
                return if (refuseBeyondEnd) HttpReply(416, "", NO_HEADERS)
                else HttpReply(206, ByteArray(0), NO_HEADERS)
            }
            return HttpReply(206, body.copyOfRange(start, minOf(end + 1, body.size)), NO_HEADERS)
        }

        private val NO_HEADERS = emptyMap<String, List<String>>()
    }
}
