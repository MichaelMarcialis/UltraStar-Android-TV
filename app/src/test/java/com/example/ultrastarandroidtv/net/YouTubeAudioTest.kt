package com.example.ultrastarandroidtv.net

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The fixtures below are trimmed copies of real player responses captured from YouTube on
 * 2026-08-20, keeping the fields this code reads and dropping the tracking payload. Written by
 * hand rather than checked in whole because a real response is 75 KB of which about ten fields
 * matter, and a fixture nobody can read is a fixture nobody maintains.
 */
class YouTubeAudioTest {

    // -----------------------------------------------------------------------------------------
    // The session token
    // -----------------------------------------------------------------------------------------

    @Test
    fun `finds visitor data in a page`() {
        val html = """<script>ytcfg.set({"INNERTUBE_CONTEXT":{"client":{""" +
            """"visitorData":"CgtBYkNkRWZHaElqaw%3D","clientName":"WEB"}}});</script>"""
        assertEquals("CgtBYkNkRWZHaElqaw%3D", visitorDataFrom(html))
    }

    /**
     * The token arrives JSON-escaped inside a script body. Taken raw it looks perfectly plausible
     * and is simply wrong, which the player API reports as LOGIN_REQUIRED rather than as a bad
     * token — so this is worth pinning.
     */
    @Test
    fun `unescapes the token`() {
        // A normal string, not a raw one: this has to contain a literal backslash-u-0-0-3-d,
        // which is how the escape actually appears in the page.
        val html = "{\"visitorData\":\"Cgtabc\u003d\u003d\",\"x\":1}"
        assertTrue("fixture must carry a real escape", html.contains("\u003d"))
        assertEquals("Cgtabc==", visitorDataFrom(html))
    }

    @Test
    fun `no visitor data in the page is null, not an empty token`() {
        assertNull(visitorDataFrom("<html><body>nothing here</body></html>"))
        assertNull(visitorDataFrom(""))
    }

    // -----------------------------------------------------------------------------------------
    // Choosing a stream
    // -----------------------------------------------------------------------------------------

    @Test
    fun `maps mime types to file extensions`() {
        assertEquals("m4a", containerFor("""audio/mp4; codecs="mp4a.40.2""""))
        assertEquals("webm", containerFor("""audio/webm; codecs="opus""""))
        assertEquals("mp3", containerFor("audio/mpeg"))
    }

    /**
     * The Shield decodes AAC in hardware and Opus in software, and a song already costs 52-55% of
     * a core with video playing. So the lower-bitrate mp4 wins on purpose -- if this ever flips to
     * "highest bitrate", CPU during playback goes up for a difference nobody can hear over a TV.
     */
    @Test
    fun `prefers aac over a higher bitrate opus stream`() {
        val chosen = pickAudio(
            listOf(
                format(itag = 251, mime = """audio/webm; codecs="opus"""", bitrate = 145_640),
                format(itag = 140, mime = """audio/mp4; codecs="mp4a.40.2"""", bitrate = 130_669),
            ),
        )
        assertEquals(140, chosen?.itag)
    }

    @Test
    fun `takes the best aac when there are several`() {
        val chosen = pickAudio(
            listOf(
                format(itag = 139, mime = "audio/mp4", bitrate = 48_000),
                format(itag = 140, mime = "audio/mp4", bitrate = 130_669),
            ),
        )
        assertEquals(140, chosen?.itag)
    }

    @Test
    fun `falls back to the best stream when nothing is aac`() {
        val chosen = pickAudio(
            listOf(
                format(itag = 250, mime = "audio/webm", bitrate = 76_864),
                format(itag = 251, mime = "audio/webm", bitrate = 145_640),
            ),
        )
        assertEquals(251, chosen?.itag)
    }

    /** A stream with no `url` needs signature work this client cannot do, so it is not a stream. */
    @Test
    fun `ignores streams that carry no url`() {
        val chosen = pickAudio(
            listOf(
                format(itag = 140, mime = "audio/mp4", bitrate = 130_669, url = ""),
                format(itag = 251, mime = "audio/webm", bitrate = 90_000),
            ),
        )
        assertEquals(251, chosen?.itag)
    }

    @Test
    fun `video-only formats are not audio`() {
        val chosen = pickAudio(
            listOf(format(itag = 137, mime = """video/mp4; codecs="avc1.640028"""", bitrate = 4_000_000)),
        )
        assertNull(chosen)
    }

    /**
     * Measured 2026-08-20: the same URL served 31 KB/s plain and 10.1 MB/s with this header.
     * Google throttles whole-file requests to about playback speed, so without it a four-megabyte
     * song takes two minutes and looks exactly like a hang. This is not an optimisation.
     */
    @Test
    fun `a stream is fetched with a range header or it crawls`() {
        assertEquals("bytes=0-", format().fetchHeaders["Range"])
    }

    @Test
    fun `reads the declared codec`() {
        assertEquals("mp4a.40.2", format(mime = """audio/mp4; codecs="mp4a.40.2"""").codec)
        assertEquals("opus", format(mime = """audio/webm; codecs="opus"""").codec)
    }

    // -----------------------------------------------------------------------------------------
    // Reading the answer
    // -----------------------------------------------------------------------------------------

    @Test
    fun `reads a playable response`() {
        when (val answer = readPlayerResponse("yebNIHKAC4A", OK_RESPONSE)) {
            is AudioLookup.Found -> {
                assertEquals("yebNIHKAC4A", answer.media.videoId)
                assertEquals("\"Golden\" Official Lyric Video", answer.media.title)
                assertEquals(199, answer.media.durationSeconds)
                assertEquals(140, answer.media.format.itag)
                assertEquals("m4a", answer.media.format.container)
                assertEquals(3_247_881L, answer.media.format.contentLength)
            }
            is AudioLookup.Refused -> fail("expected Found, got $answer")
        }
    }

    /**
     * The made-for-kids case, which is 6 of the 19 audio-less songs on the card. YouTube still
     * volunteers the title and duration here, and keeping them is what lets a screen say which
     * song it is refusing instead of showing a bare eleven-character id.
     */
    @Test
    fun `a refusal still names the video`() {
        when (val answer = readPlayerResponse("cPAbx5kgCJo", UNPLAYABLE_RESPONSE)) {
            is AudioLookup.Refused -> {
                assertEquals(RefusalKind.UNAVAILABLE, answer.kind)
                assertEquals("UNPLAYABLE", answer.status)
                assertEquals("This video is not available", answer.reason)
                assertEquals("How Far I'll Go", answer.title)
                assertEquals(156, answer.durationSeconds)
            }
            is AudioLookup.Found -> fail("expected Refused, got $answer")
        }
    }

    /** What every lookup returns if the session token is wrong -- the failure mode worth naming. */
    @Test
    fun `login required is its own kind`() {
        val answer = readPlayerResponse("x", """{"playabilityStatus":{"status":"LOGIN_REQUIRED"}}""")
        assertEquals(RefusalKind.NEEDS_SIGN_IN, (answer as AudioLookup.Refused).kind)
    }

    /**
     * Playable, but every stream came back needing signature work. This is the shape the whole
     * file's failure will take the day YouTube closes the visionOS door, so it must not look like
     * a crash or like the video being unavailable.
     */
    @Test
    fun `playable with no readable stream is not the same as unavailable`() {
        val json = """
            {"playabilityStatus":{"status":"OK"},
             "videoDetails":{"title":"Song","lengthSeconds":"200"},
             "streamingData":{"adaptiveFormats":[
               {"itag":140,"mimeType":"audio/mp4","bitrate":130669,"signatureCipher":"s=abc"}]}}
        """.trimIndent()
        val answer = readPlayerResponse("x", json) as AudioLookup.Refused
        assertEquals(RefusalKind.NO_AUDIO_STREAM, answer.kind)
        assertEquals("Song", answer.title)
    }

    @Test
    fun `a video with no audio stream at all is refused`() {
        val json = """
            {"playabilityStatus":{"status":"OK"},
             "videoDetails":{"title":"Silent","lengthSeconds":"10"},
             "streamingData":{"adaptiveFormats":[
               {"itag":137,"url":"https://x/","mimeType":"video/mp4","bitrate":4000000}]}}
        """.trimIndent()
        assertEquals(
            RefusalKind.NO_AUDIO_STREAM,
            (readPlayerResponse("x", json) as AudioLookup.Refused).kind,
        )
    }

    /** The legacy `formats` list is muxed audio+video; taking one downloads a video for its sound. */
    @Test
    fun `ignores the legacy muxed formats list`() {
        val json = """
            {"playabilityStatus":{"status":"OK"},
             "videoDetails":{"title":"Song","lengthSeconds":"200"},
             "streamingData":{"formats":[
               {"itag":18,"url":"https://x/","mimeType":"video/mp4","bitrate":500000}]}}
        """.trimIndent()
        assertEquals(
            RefusalKind.NO_AUDIO_STREAM,
            (readPlayerResponse("x", json) as AudioLookup.Refused).kind,
        )
    }

    @Test
    fun `nonsense from the network is refused rather than thrown`() {
        val answer = readPlayerResponse("x", "<html>502 Bad Gateway</html>")
        assertEquals(RefusalKind.UNKNOWN, (answer as AudioLookup.Refused).kind)
    }

    @Test
    fun `a missing content length is minus one, not zero`() {
        val json = """
            {"playabilityStatus":{"status":"OK"},
             "videoDetails":{"title":"Song","lengthSeconds":"200"},
             "streamingData":{"adaptiveFormats":[
               {"itag":140,"url":"https://x/","mimeType":"audio/mp4","bitrate":130669}]}}
        """.trimIndent()
        val found = readPlayerResponse("x", json) as AudioLookup.Found
        assertEquals(-1L, found.media.format.contentLength)
    }

    @Test
    fun `maps every status this app has seen`() {
        assertEquals(RefusalKind.UNAVAILABLE, refusalKindFor("UNPLAYABLE"))
        assertEquals(RefusalKind.UNAVAILABLE, refusalKindFor("ERROR"))
        assertEquals(RefusalKind.NEEDS_SIGN_IN, refusalKindFor("LOGIN_REQUIRED"))
        assertEquals(RefusalKind.AGE_RESTRICTED, refusalKindFor("AGE_CHECK_REQUIRED"))
        assertEquals(RefusalKind.LIVE, refusalKindFor("LIVE_STREAM_OFFLINE"))
        assertEquals(RefusalKind.UNKNOWN, refusalKindFor("SOMETHING_NEW"))
    }

    // -----------------------------------------------------------------------------------------
    // The request
    // -----------------------------------------------------------------------------------------

    @Test
    fun `builds a player request carrying the client identity`() {
        val body = JSONObject(playerRequestBody(InnertubeClients.VISION_OS, "abc123", "TOKEN"))
        assertEquals("abc123", body.getString("videoId"))
        val client = body.getJSONObject("context").getJSONObject("client")
        assertEquals("VISIONOS", client.getString("clientName"))
        assertEquals("1.02", client.getString("clientVersion"))
        assertEquals("TOKEN", client.getString("visitorData"))
        assertEquals("visionOS", client.getString("osName"))
    }

    @Test
    fun `omits device fields a client does not declare`() {
        val bare = InnertubeClient("WEB", "2.0", 1, "UA")
        val client = JSONObject(playerRequestBody(bare, "v", "T"))
            .getJSONObject("context").getJSONObject("client")
        assertTrue(client.isNull("osName") || !client.has("osName"))
        assertEquals("WEB", client.getString("clientName"))
    }

    // -----------------------------------------------------------------------------------------
    // Putting it together
    // -----------------------------------------------------------------------------------------

    @Test
    fun `resolves a video`() {
        val http = FakeHttp(home = HOME_PAGE, replies = listOf(OK_RESPONSE))
        val answer = YouTubeAudio(http).resolve("yebNIHKAC4A")
        assertEquals(140, (answer as AudioLookup.Found).media.format.itag)
    }

    /** The token is per-session, so a bulk download must not re-fetch the homepage for every song. */
    @Test
    fun `fetches the session token once and reuses it`() {
        val http = FakeHttp(home = HOME_PAGE, replies = listOf(OK_RESPONSE, OK_RESPONSE, OK_RESPONSE))
        val youTube = YouTubeAudio(http)
        repeat(3) { youTube.resolve("abc") }
        assertEquals(1, http.homeRequests)
        assertEquals(3, http.postCount)
    }

    @Test
    fun `forgetting the session re-establishes it`() {
        val http = FakeHttp(home = HOME_PAGE, replies = listOf(OK_RESPONSE, OK_RESPONSE))
        val youTube = YouTubeAudio(http)
        youTube.resolve("abc")
        youTube.forgetSession()
        youTube.resolve("abc")
        assertEquals(2, http.homeRequests)
    }

    @Test
    fun `sends the token in the header as well as the body`() {
        val http = FakeHttp(home = HOME_PAGE, replies = listOf(OK_RESPONSE))
        YouTubeAudio(http).resolve("abc")
        assertEquals("TOKENVALUE", http.lastHeaders["X-Goog-Visitor-Id"])
        assertEquals("101", http.lastHeaders["X-YouTube-Client-Name"])
    }

    /**
     * A homepage that yields no token is a broken *session*, not a video that said no -- so it
     * throws rather than returning a Refused that would tell somebody their song is unavailable.
     */
    @Test
    fun `no session token is a failure, not a refusal`() {
        val http = FakeHttp(home = "<html>nothing</html>", replies = listOf(OK_RESPONSE))
        try {
            YouTubeAudio(http).resolve("abc")
            fail("expected HttpFailure")
        } catch (e: HttpFailure) {
            assertTrue(e.message!!.contains("session token"))
        }
    }

    @Test
    fun `a refusal from the only client is the answer`() {
        val http = FakeHttp(home = HOME_PAGE, replies = listOf(UNPLAYABLE_RESPONSE))
        val answer = YouTubeAudio(http).resolve("cPAbx5kgCJo")
        assertEquals(RefusalKind.UNAVAILABLE, (answer as AudioLookup.Refused).kind)
    }

    /** With more than one client configured, the first that delivers audio wins. */
    @Test
    fun `falls through to a later client that works`() {
        val http = FakeHttp(home = HOME_PAGE, replies = listOf(UNPLAYABLE_RESPONSE, OK_RESPONSE))
        val youTube = YouTubeAudio(
            http,
            clients = listOf(InnertubeClients.VISION_OS, InnertubeClient("ANDROID", "19", 3, "UA")),
        )
        assertTrue(youTube.resolve("abc") is AudioLookup.Found)
        assertEquals(2, http.postCount)
    }

    /** When they all refuse, report the most-preferred client's answer, not the last one tried. */
    @Test
    fun `reports the first refusal when every client refuses`() {
        val second = """{"playabilityStatus":{"status":"LOGIN_REQUIRED","reason":"Sign in"}}"""
        val http = FakeHttp(home = HOME_PAGE, replies = listOf(UNPLAYABLE_RESPONSE, second))
        val youTube = YouTubeAudio(
            http,
            clients = listOf(InnertubeClients.VISION_OS, InnertubeClient("ANDROID", "19", 3, "UA")),
        )
        val answer = youTube.resolve("abc") as AudioLookup.Refused
        assertEquals(RefusalKind.UNAVAILABLE, answer.kind)
    }

    @Test
    fun `a blank video id is a programming error, not a lookup`() {
        try {
            YouTubeAudio(FakeHttp(HOME_PAGE, listOf(OK_RESPONSE))).resolve("  ")
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    // -----------------------------------------------------------------------------------------

    private fun format(
        itag: Int = 140,
        mime: String = "audio/mp4",
        bitrate: Int = 130_669,
        url: String = "https://rr4---sn-example.googlevideo.com/videoplayback?x=1",
    ) = AudioFormat(itag = itag, url = url, mimeType = mime, bitrate = bitrate, contentLength = -1L)

    private class FakeHttp(
        private val home: String,
        private val replies: List<String>,
    ) : Http {
        var homeRequests = 0
        var postCount = 0
        var lastHeaders: Map<String, String> = emptyMap()

        override fun send(request: HttpRequest): HttpReply {
            if (request.method != "POST") {
                homeRequests++
                return HttpReply(200, home, emptyMap())
            }
            lastHeaders = request.headers
            val reply = replies[minOf(postCount, replies.lastIndex)]
            postCount++
            return HttpReply(200, reply, emptyMap())
        }
    }

    private companion object {
        const val HOME_PAGE = """<script>ytcfg.set({"visitorData":"TOKENVALUE"});</script>"""

        val OK_RESPONSE = """
            {"playabilityStatus":{"status":"OK"},
             "videoDetails":{"videoId":"yebNIHKAC4A","title":"\"Golden\" Official Lyric Video",
                             "lengthSeconds":"199","isLiveContent":false},
             "streamingData":{"adaptiveFormats":[
               {"itag":251,"url":"https://rr4/vp?a=1","mimeType":"audio/webm; codecs=\"opus\"",
                "bitrate":145640,"contentLength":"3327876"},
               {"itag":140,"url":"https://rr4/vp?a=2","mimeType":"audio/mp4; codecs=\"mp4a.40.2\"",
                "bitrate":130669,"contentLength":"3247881"},
               {"itag":137,"url":"https://rr4/vp?a=3","mimeType":"video/mp4; codecs=\"avc1.640028\"",
                "bitrate":4000000,"contentLength":"90000000"}]}}
        """.trimIndent()

        val UNPLAYABLE_RESPONSE = """
            {"playabilityStatus":{"status":"UNPLAYABLE","reason":"This video is not available"},
             "videoDetails":{"videoId":"cPAbx5kgCJo","title":"How Far I'll Go",
                             "lengthSeconds":"156","isLiveContent":false}}
        """.trimIndent()
    }

    // -----------------------------------------------------------------------------------------
    // Choosing a picture stream
    // -----------------------------------------------------------------------------------------

    /**
     * The one rule that cannot be got wrong. The Shield's Tegra X1+ has no AV1 decoder, so an AV1
     * stream would be unpacked on the CPU during a song that is already scoring two microphones —
     * and AV1 is usually the *smallest* file at each height, which makes it exactly what a
     * well-meant "take the smallest" would choose.
     */
    @Test
    fun `never takes an AV1 stream, however small`() {
        val chosen = pickVideo(
            listOf(
                video(399, "video/mp4; codecs=\"av01.0.08M.08\"", height = 1080, bytes = 27_000),
                video(136, "video/mp4; codecs=\"avc1.4d401f\"", height = 720, bytes = 41_000),
            ),
        )
        assertEquals(136, chosen?.itag)
    }

    @Test
    fun `takes h264 over VP9 at the same height`() {
        val chosen = pickVideo(
            listOf(
                video(248, "video/webm; codecs=\"vp9\"", height = 1080, bytes = 33_000),
                video(137, "video/mp4; codecs=\"avc1.640028\"", height = 1080, bytes = 58_000),
            ),
        )
        assertEquals(137, chosen?.itag)
        assertEquals("mp4", chosen?.container)
    }

    /** VP9 is the fallback rather than the default: it is still hardware-decoded on this device. */
    @Test
    fun `falls back to VP9 when h264 is not offered`() {
        val chosen = pickVideo(
            listOf(
                video(394, "video/mp4; codecs=\"av01.0.00M.08\"", height = 1080, bytes = 20_000),
                video(248, "video/webm; codecs=\"vp9\"", height = 1080, bytes = 33_000),
            ),
        )
        assertEquals(248, chosen?.itag)
        assertEquals("webm", chosen?.container)
    }

    @Test
    fun `takes the tallest picture within the cap`() {
        val chosen = pickVideo(
            listOf(
                video(134, "video/mp4; codecs=\"avc1.4d401e\"", height = 360, bytes = 7_000),
                video(137, "video/mp4; codecs=\"avc1.640028\"", height = 1080, bytes = 58_000),
                video(135, "video/mp4; codecs=\"avc1.4d401e\"", height = 480, bytes = 13_000),
            ),
        )
        assertEquals(137, chosen?.itag)
    }

    @Test
    fun `refuses anything taller than the cap`() {
        val chosen = pickVideo(
            listOf(
                video(315, "video/webm; codecs=\"vp9\"", height = 2160, bytes = 900_000),
                video(136, "video/mp4; codecs=\"avc1.4d401f\"", height = 720, bytes = 41_000),
            ),
        )
        assertEquals(136, chosen?.itag)
    }

    /** No picture is a perfectly good answer: the visualiser draws instead. */
    @Test
    fun `no usable picture is null rather than a guess`() {
        assertNull(pickVideo(emptyList()))
        assertNull(
            pickVideo(listOf(video(399, "video/mp4; codecs=\"av01.0.08M.08\"", 1080, 27_000))),
        )
        assertNull(
            pickVideo(listOf(video(140, "audio/mp4; codecs=\"mp4a.40.2\"", 0, 4_000))),
        )
    }

    /** A stream with no url needs signature work this client cannot do, so it is not a stream. */
    @Test
    fun `skips a picture with no url`() {
        val chosen = pickVideo(
            listOf(
                VideoFormat(137, "", "video/mp4; codecs=\"avc1.640028\"", 1080, 58_000),
                video(136, "video/mp4; codecs=\"avc1.4d401f\"", height = 720, bytes = 41_000),
            ),
        )
        assertEquals(136, chosen?.itag)
    }

    @Test
    fun `a picture is fetched with the same Range header the audio needs`() {
        assertEquals(
            "bytes=0-",
            video(136, "video/mp4; codecs=\"avc1.4d401f\"", 720, 41_000).fetchHeaders["Range"],
        )
    }

    /** Read from the same response the sound came from, so a video costs no extra request. */
    @Test
    fun `a playable video reports its picture alongside its sound`() {
        val json = """
            {"playabilityStatus":{"status":"OK"},
             "videoDetails":{"title":"Golden","lengthSeconds":"199"},
             "streamingData":{"adaptiveFormats":[
               {"itag":140,"url":"https://rr1.googlevideo.com/a","mimeType":"audio/mp4; codecs=\"mp4a.40.2\"","bitrate":130669,"contentLength":"3000000"},
               {"itag":137,"url":"https://rr1.googlevideo.com/v","mimeType":"video/mp4; codecs=\"avc1.640028\"","height":1080,"contentLength":"58000000"}]}}
        """.trimIndent()
        val found = readPlayerResponse("abc", json) as AudioLookup.Found
        assertEquals(140, found.media.format.itag)
        assertEquals(137, found.media.video?.itag)
        assertEquals(1080, found.media.video?.height)
    }

    @Test
    fun `a video with no picture stream is still found`() {
        val json = """
            {"playabilityStatus":{"status":"OK"},
             "videoDetails":{"title":"Golden","lengthSeconds":"199"},
             "streamingData":{"adaptiveFormats":[
               {"itag":140,"url":"https://rr1.googlevideo.com/a","mimeType":"audio/mp4; codecs=\"mp4a.40.2\"","bitrate":130669,"contentLength":"3000000"}]}}
        """.trimIndent()
        val found = readPlayerResponse("abc", json) as AudioLookup.Found
        assertNull(found.media.video)
    }

    // -----------------------------------------------------------------------------------------
    // Telling a blocked video from a dead one
    // -----------------------------------------------------------------------------------------

    /**
     * Measured on Kelly Clarkson's "Since U Been Gone" (R7UrFYvl5TE), which is alive, popular and
     * answers `UNPLAYABLE` — the same status as a deleted video. The sentence is the only thing
     * that separates them, and the advice differs: waiting will not help, another upload usually
     * does.
     */
    @Test
    fun `a geographic block is not read as a dead video`() {
        assertEquals(
            RefusalKind.REGION_BLOCKED,
            refusalKindFor(
                "UNPLAYABLE",
                "The uploader has not made this video available in your country",
            ),
        )
    }

    @Test
    fun `an ordinary unplayable video stays unavailable`() {
        assertEquals(RefusalKind.UNAVAILABLE, refusalKindFor("UNPLAYABLE", "Video unavailable"))
        assertEquals(RefusalKind.UNAVAILABLE, refusalKindFor("UNPLAYABLE"))
    }

    /** The sentence may only narrow the answer, never widen it: it is localised prose. */
    @Test
    fun `a reason mentioning a country cannot override a different status`() {
        assertEquals(
            RefusalKind.NEEDS_SIGN_IN,
            refusalKindFor("LOGIN_REQUIRED", "not available in your country"),
        )
        assertEquals(RefusalKind.LIVE, refusalKindFor("LIVE_STREAM_OFFLINE", "in your region"))
    }

    private fun video(itag: Int, mimeType: String, height: Int, bytes: Long) =
        VideoFormat(itag, "https://rr1.googlevideo.com/videoplayback?i=$itag", mimeType, height, bytes)

}
