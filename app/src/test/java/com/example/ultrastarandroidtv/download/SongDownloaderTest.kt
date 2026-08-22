package com.example.ultrastarandroidtv.download

import com.example.ultrastarandroidtv.library.DocumentTree
import com.example.ultrastarandroidtv.library.DocumentWriter
import com.example.ultrastarandroidtv.library.TreeEntry
import com.example.ultrastarandroidtv.net.Http
import com.example.ultrastarandroidtv.net.ITunesArtwork
import com.example.ultrastarandroidtv.net.HttpReply
import com.example.ultrastarandroidtv.net.HttpRequest
import com.example.ultrastarandroidtv.net.YouTubeAudio
import com.example.ultrastarandroidtv.usdb.UsdbCharts
import com.example.ultrastarandroidtv.usdb.UsdbDetails
import com.example.ultrastarandroidtv.usdb.UsdbSession
import com.example.ultrastarandroidtv.usdb.UsdbSong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SongDownloaderTest {

    // -----------------------------------------------------------------------------------------
    // A whole download
    // -----------------------------------------------------------------------------------------

    @Test
    fun `saves the chart, the music and the cover`() {
        val card = FakeCard()
        val outcome = downloaderFor(FakeNet(), card).download(bowie)

        val saved = outcome as DownloadOutcome.Saved
        assertEquals("David Bowie - China Girl", saved.folderName)
        assertEquals("David Bowie - China Girl.m4a", saved.audioFile)
        assertEquals("David Bowie - China Girl.txt", saved.chartFile)
        assertTrue(saved.coverSaved)

        assertEquals(
            listOf(
                "David Bowie - China Girl [CO].jpg",
                "David Bowie - China Girl.m4a",
                "David Bowie - China Girl.txt",
            ),
            card.namesIn("David Bowie - China Girl").sorted(),
        )
    }

    /**
     * USDB's chart names an mp3 that has never existed. Saving what YouTube served without
     * correcting the header produces a song the scanner cannot resolve -- which looks exactly
     * like the broken folders this feature is meant to stop creating.
     */
    @Test
    fun `points the chart at the file that was actually saved`() {
        val card = FakeCard()
        downloaderFor(FakeNet(), card).download(bowie)
        val chart = card.textOf("David Bowie - China Girl", "David Bowie - China Girl.txt")

        assertTrue(chart.contains("#MP3:David Bowie - China Girl.m4a"))
        assertFalse("the original mp3 name must be gone", chart.contains("#MP3:David Bowie - China Girl.mp3"))
        assertTrue(chart.contains("#COVER:David Bowie - China Girl [CO].jpg"))
    }

    /**
     * The Storage Access Framework may rename what it creates -- a clash gets a suffix, and some
     * providers correct an extension. Guessing the name instead of reading it back is a song that
     * silently will not play.
     */
    @Test
    fun `follows the provider when it renames a file`() {
        val card = FakeCard(renameTo = { name -> name.replace(".m4a", " (1).m4a") })
        val saved = downloaderFor(FakeNet(), card).download(bowie) as DownloadOutcome.Saved

        assertEquals("David Bowie - China Girl (1).m4a", saved.audioFile)
        val chart = card.textOf("David Bowie - China Girl", "David Bowie - China Girl.txt")
        assertTrue(chart.contains("#MP3:David Bowie - China Girl (1).m4a"))
    }

    /**
     * Without a Range header Google serves the file at roughly playback speed -- measured at
     * 31 KB/s against 10.1 MB/s with it. The download still works, so nothing fails; it just
     * takes two minutes and reads as a hang. That is exactly the kind of thing a later tidy-up
     * removes as a no-op, so it is pinned here.
     */
    @Test
    fun `asks for the music as a range`() {
        val net = FakeNet()
        downloaderFor(net, FakeCard()).download(bowie)
        assertEquals("bytes=0-", net.audioRequestHeaders["Range"])
    }

    @Test
    fun `waits the time usdb asks for and says so`() {
        val net = FakeNet(waitSeconds = 3)
        val card = FakeCard()
        val slept = mutableListOf<Long>()
        val stages = mutableListOf<DownloadStage>()

        SongDownloader(
            charts = UsdbCharts(UsdbSession(net, BASE)),
            details = UsdbDetails(UsdbSession(net, BASE)),
            youTube = YouTubeAudio(net),
            artwork = ITunesArtwork(net),
            http = net,
            tree = card,
            writer = card,
            sleepMillis = { slept += it },
        ).download(bowie) { stages += it }

        assertEquals(listOf(1_000L, 1_000L, 1_000L), slept)
        assertEquals(
            listOf(3, 2, 1),
            stages.filterIsInstance<DownloadStage.WaitingForUsdb>().map { it.secondsLeft },
        )
        assertTrue(stages.any { it is DownloadStage.DownloadingAudio })
        assertTrue(stages.last() is DownloadStage.Saving)
    }

    // -----------------------------------------------------------------------------------------
    // Nothing is written unless everything worked
    // -----------------------------------------------------------------------------------------

    /**
     * The load-bearing test. 19 folders on the real card hold a chart and no music because the
     * desktop tool wrote the folder before it knew the download would work.
     */
    @Test
    fun `a video that cannot be downloaded leaves nothing behind`() {
        val card = FakeCard()
        val outcome = downloaderFor(FakeNet(youTubeReply = UNPLAYABLE), card).download(bowie)

        val failed = outcome as DownloadOutcome.Failed
        assertEquals(DownloadProblem.AUDIO_UNAVAILABLE, failed.problem)
        assertTrue(card.isEmpty)
    }

    @Test
    fun `a chart that names no video leaves nothing behind`() {
        val card = FakeCard()
        val net = FakeNet(chart = "#ARTIST:X\n#TITLE:Y\n#MP3:X - Y.mp3\n: 0 1 2 la\nE")
        val outcome = downloaderFor(net, card).download(bowie)

        assertEquals(DownloadProblem.CHART_NAMES_NO_VIDEO, (outcome as DownloadOutcome.Failed).problem)
        assertTrue(card.isEmpty)
    }

    @Test
    fun `music that will not save takes the folder with it`() {
        val card = FakeCard(refuseNames = setOf("David Bowie - China Girl.m4a"))
        val outcome = downloaderFor(FakeNet(), card).download(bowie)

        assertEquals(DownloadProblem.COULD_NOT_WRITE, (outcome as DownloadOutcome.Failed).problem)
        assertTrue("a folder with no music must not survive", card.isEmpty)
    }

    @Test
    fun `a chart that will not save takes the folder with it`() {
        val card = FakeCard(refuseNames = setOf("David Bowie - China Girl.txt"))
        val outcome = downloaderFor(FakeNet(), card).download(bowie)

        assertEquals(DownloadProblem.COULD_NOT_WRITE, (outcome as DownloadOutcome.Failed).problem)
        assertTrue(card.isEmpty)
    }

    /** A cover is a nicety. Losing one must not throw away music that downloaded perfectly. */
    @Test
    fun `artwork that fails does not fail the song`() {
        val card = FakeCard()
        val saved = downloaderFor(FakeNet(coverFails = true), card)
            .download(bowie) as DownloadOutcome.Saved

        assertFalse(saved.coverSaved)
        assertEquals(
            listOf("David Bowie - China Girl.m4a", "David Bowie - China Girl.txt"),
            card.namesIn("David Bowie - China Girl").sorted(),
        )
        val chart = card.textOf("David Bowie - China Girl", "David Bowie - China Girl.txt")
        assertFalse("no cover header pointing at a file that is not there", chart.contains("#COVER:"))
    }

    @Test
    fun `refuses a song already in the library without touching the network`() {
        val card = FakeCard().also { it.addFolder("David Bowie - China Girl") }
        val net = FakeNet()
        val outcome = downloaderFor(net, card).download(bowie)

        assertEquals(DownloadProblem.ALREADY_HAVE_IT, (outcome as DownloadOutcome.Failed).problem)
        assertEquals("nothing should have been requested", 0, net.requests)
    }

    // -----------------------------------------------------------------------------------------
    // Naming
    // -----------------------------------------------------------------------------------------

    /** The card stores "KPop Demon Hunters (Huntr/x)" as "Huntr-x", not "Huntrx". */
    @Test
    fun `a slash becomes a hyphen rather than vanishing`() {
        assertEquals("KPop Demon Hunters (Huntr-x)", safeFileName("KPop Demon Hunters (Huntr/x)"))
        assertEquals("AC-DC", safeFileName("AC\\DC"))
    }

    @Test
    fun `removes what a file system will not take`() {
        assertEquals("Song Title", safeFileName("Song: *Title*?"))
        assertEquals("Quote", safeFileName("\"Quote\""))
    }

    /** FAT accepts a trailing dot at creation and then cannot reliably open the file again. */
    @Test
    fun `no trailing dots or spaces`() {
        assertEquals("Etc", safeFileName("Etc..."))
        assertEquals("Name", safeFileName("  Name  "))
    }

    @Test
    fun `collapses runs of whitespace`() {
        assertEquals("A B", safeFileName("A \t\n B"))
    }

    @Test
    fun `a name that is only punctuation comes back blank rather than broken`() {
        assertEquals("", safeFileName(":*?"))
    }

    // -----------------------------------------------------------------------------------------
    // Rewriting the chart
    // -----------------------------------------------------------------------------------------

    @Test
    fun `replaces the media headers`() {
        val out = retargetChart("#ARTIST:A\n#MP3:old.mp3\n#BPM:120\n: 0 1 2 la\nE", "new.m4a", "art.jpg")
        assertTrue(out.contains("#MP3:new.m4a"))
        assertFalse(out.contains("old.mp3"))
        assertTrue(out.contains("#COVER:art.jpg"))
    }

    @Test
    fun `inserts a cover header among the other headers, not at the end`() {
        val out = retargetChart("#ARTIST:A\n#MP3:old.mp3\n: 0 1 2 la\nE", "new.m4a", "art.jpg")
        val lines = out.split("\n")
        assertTrue(lines.indexOf("#COVER:art.jpg") < lines.indexOf(": 0 1 2 la"))
    }

    /**
     * A syllable's trailing space is the only thing marking the end of a word in this format.
     * Trimming the file once turned every syllable in the library into a hyphenated fragment.
     */
    @Test
    fun `never touches a note line, trailing spaces included`() {
        val chart = "#ARTIST:A\n#MP3:old.mp3\n: 0 3 31 some\n: 8 3 28 thing \n- 40\nE"
        val out = retargetChart(chart, "new.m4a", null)
        assertTrue(out.contains(": 8 3 28 thing \n"))
        assertTrue(out.contains(": 0 3 31 some\n"))
    }

    @Test
    fun `keeps windows line endings`() {
        val out = retargetChart("#ARTIST:A\r\n#MP3:old.mp3\r\n: 0 1 2 la \r\nE\r\n", "new.m4a", "c.jpg")
        assertTrue(out.contains("#MP3:new.m4a\r\n"))
        assertTrue(out.contains("#COVER:c.jpg\r\n"))
        assertTrue(out.contains(": 0 1 2 la \r\n"))
    }

    @Test
    fun `matches a header whatever case it is written in`() {
        val out = retargetChart("#artist:A\n#Mp3:old.mp3\nE", "new.m4a", null)
        assertTrue(out.contains("#MP3:new.m4a"))
        assertFalse(out.contains("old.mp3"))
    }

    @Test
    fun `adds an mp3 header to a chart that somehow lacks one`() {
        val out = retargetChart("#ARTIST:A\n#TITLE:B\n: 0 1 2 la\nE", "new.m4a", null)
        assertTrue(out.contains("#MP3:new.m4a"))
        assertTrue(out.split("\n").indexOf("#MP3:new.m4a") <= 2)
    }

    @Test
    fun `leaves the video meta tags alone`() {
        val chart = "#MP3:old.mp3\n#VIDEO:v=abc123,co=x.jpg\nE"
        assertTrue(retargetChart(chart, "new.m4a", null).contains("#VIDEO:v=abc123,co=x.jpg"))
    }

    @Test
    fun `reads header keys and knows what is not one`() {
        assertEquals("MP3", headerKeyOf("#MP3:file.mp3"))
        assertEquals("TITLE", headerKeyOf("﻿#TITLE:Song"))
        assertEquals("", headerKeyOf(": 0 3 31 Oh, "))
        assertEquals("", headerKeyOf("- 40"))
        assertEquals("", headerKeyOf("E"))
        assertEquals("", headerKeyOf(""))
    }

    // -----------------------------------------------------------------------------------------

    private val bowie = UsdbSong(
        songId = 17720,
        artist = "David Bowie",
        title = "China Girl",
        genre = "",
        year = "1983",
        edition = "",
        hasGoldenNotes = true,
        language = "English",
        creator = "thursday",
        rating = 0,
        views = 584,
        coverUrl = "https://usdb.test/data/cover/17720.jpg",
        sampleUrl = null,
    )

    private fun downloaderFor(net: FakeNet, card: FakeCard) = SongDownloader(
        charts = UsdbCharts(UsdbSession(net, BASE)),
        details = UsdbDetails(UsdbSession(net, BASE)),
        youTube = YouTubeAudio(net),
        artwork = ITunesArtwork(net),
        http = net,
        tree = card,
        writer = card,
        sleepMillis = { },
    )


    // -----------------------------------------------------------------------------------------
    // The pre-check: refusing before USDB's throttle rather than after it
    // -----------------------------------------------------------------------------------------

    /**
     * The whole point of the pre-check. Twenty-four seconds is a long time to wait to be told no,
     * and the detail page answers the same question for free.
     */
    @Test
    fun `a song whose video is gone never reaches USDB's wait`() {
        val net = FakeNet(youTubeReply = UNPLAYABLE)
        val card = FakeCard()
        val slept = mutableListOf<Long>()

        val outcome = SongDownloader(
            charts = UsdbCharts(UsdbSession(net, BASE)),
            details = UsdbDetails(UsdbSession(net, BASE)),
            youTube = YouTubeAudio(net),
            artwork = ITunesArtwork(net),
            http = net,
            tree = card,
            writer = card,
            sleepMillis = { slept += it },
        ).download(bowie)

        assertTrue(outcome is DownloadOutcome.Failed)
        assertEquals(DownloadProblem.AUDIO_UNAVAILABLE, (outcome as DownloadOutcome.Failed).problem)
        assertTrue("must not have waited on USDB", slept.isEmpty())
        assertTrue("must leave nothing behind", card.isEmpty)
    }

    /**
     * The chart has the last word, and this is the failure that rule guards against: if the page
     * ever named a different video than the chart, trusting the page would refuse a good song --
     * or worse, download the wrong music.
     */
    @Test
    fun `a chart naming a different video is looked up again rather than trusted`() {
        val net = FakeNet(chart = CHART.replace("v=_YC3sTbAPcU", "v=DIFFERENT01x"))
        val card = FakeCard()

        val outcome = downloaderFor(net, card).download(bowie)

        assertTrue(outcome is DownloadOutcome.Saved)
        assertEquals(
            "the page's id first, then the chart's own",
            listOf("_YC3sTbAPcU", "DIFFERENT01x"),
            net.playerVideoIds,
        )
    }

    @Test
    fun `a video both agree on is only looked up once`() {
        val net = FakeNet()
        downloaderFor(net, FakeCard()).download(bowie)
        assertEquals(listOf("_YC3sTbAPcU"), net.playerVideoIds)
    }

    // -----------------------------------------------------------------------------------------
    // The music video
    // -----------------------------------------------------------------------------------------

    @Test
    fun `saves the music video beside the song`() {
        val net = FakeNet(youTubeReply = PLAYABLE_WITH_VIDEO)
        val card = FakeCard()

        val outcome = downloaderFor(net, card).download(bowie) as DownloadOutcome.Saved

        assertTrue(outcome.videoSaved)
        assertTrue(
            "the scanner finds a video by its extension, so the name is what matters",
            "David Bowie - China Girl.mp4" in card.namesIn("David Bowie - China Girl"),
        )
    }

    /** A video is a nicety. Losing one must not cost a song that is otherwise complete. */
    @Test
    fun `a video that will not download still leaves a complete song`() {
        val net = FakeNet(youTubeReply = PLAYABLE_WITH_VIDEO, videoFails = true)
        val card = FakeCard()

        val outcome = downloaderFor(net, card).download(bowie) as DownloadOutcome.Saved

        assertFalse(outcome.videoSaved)
        val names = card.namesIn("David Bowie - China Girl")
        assertTrue("David Bowie - China Girl.m4a" in names)
        assertTrue("David Bowie - China Girl.txt" in names)
        assertTrue("no half-written video left behind", names.none { it.endsWith(".mp4") })
    }

    /**
     * `#VIDEO:` keeps USDB's meta tags, which record where the media came from. The scanner finds
     * the file by looking in the folder, so there is nothing to rewrite and a good reason not to.
     */
    @Test
    fun `saving a video does not rewrite the VIDEO header`() {
        val net = FakeNet(youTubeReply = PLAYABLE_WITH_VIDEO)
        val card = FakeCard()

        downloaderFor(net, card).download(bowie)

        val text = card.textOf("David Bowie - China Girl", "David Bowie - China Girl.txt")
        assertTrue(text.contains("#VIDEO:v=_YC3sTbAPcU,co=china-girl.jpg"))
    }

    @Test
    fun `the video is fetched with the Range header too`() {
        val net = FakeNet(youTubeReply = PLAYABLE_WITH_VIDEO)
        downloaderFor(net, FakeCard()).download(bowie)
        assertEquals("bytes=0-", net.videoRequestHeaders["Range"])
    }


    /**
     * A hundred seconds of silence reads as a hang. Measured: YouTube serves a video at about the
     * rate it plays, so this stage runs for roughly the length of the song whatever quality is
     * chosen -- by far the longest part of a download once USDB's wait is over.
     */
    @Test
    fun `the video reports how far along it is`() {
        val net = FakeNet(youTubeReply = PLAYABLE_WITH_VIDEO)
        val stages = mutableListOf<DownloadStage>()

        downloaderFor(net, FakeCard()).download(bowie) { stages += it }

        val reported = stages.filterIsInstance<DownloadStage.DownloadingVideo>().map { it.percent }
        assertEquals("must start at nothing", 0, reported.first())
        assertEquals("must finish at everything", 100, reported.last())
        assertEquals("must never go backwards", reported.sorted(), reported)
    }

    // -----------------------------------------------------------------------------------------
    // Where the cover comes from
    // -----------------------------------------------------------------------------------------

    /** The chart's author picked it, and it is a full-size original rather than a thumbnail. */
    @Test
    fun `prefers the cover the chart names over anything else`() {
        val net = FakeNet(chart = CHART.replace("co=china-girl.jpg", "co=https://covers.test/a.jpg"))
        val card = FakeCard()

        val outcome = downloaderFor(net, card).download(bowie) as DownloadOutcome.Saved

        assertTrue(outcome.coverSaved)
        assertEquals(CHART_COVER_BYTE, card.bytesOf("David Bowie - China Girl", "David Bowie - China Girl [CO].jpg")[0])
        assertEquals("iTunes should not have been asked", 0, net.itunesRequests)
    }

    /**
     * The ordinary case: 57 of the 68 charts on this card name a bare fanart.tv filename, which
     * no plain HTTP client can fetch, so the chart offers nothing usable and iTunes answers.
     */
    @Test
    fun `falls back to iTunes when the chart names no fetchable cover`() {
        val net = FakeNet()
        val card = FakeCard()

        val outcome = downloaderFor(net, card).download(bowie) as DownloadOutcome.Saved

        assertTrue(outcome.coverSaved)
        assertEquals(1, net.itunesRequests)
        assertEquals(ITUNES_COVER_BYTE, card.bytesOf("David Bowie - China Girl", "David Bowie - China Girl [CO].jpg")[0])
    }

    /** USDB's own 200x200 is the last resort rather than the first, but it is still a resort. */
    @Test
    fun `falls back to USDB's thumbnail when iTunes has nothing`() {
        val net = FakeNet(itunesFinds = false)
        val card = FakeCard()

        val outcome = downloaderFor(net, card).download(bowie) as DownloadOutcome.Saved

        assertTrue(outcome.coverSaved)
        assertEquals(USDB_COVER_BYTE, card.bytesOf("David Bowie - China Girl", "David Bowie - China Girl [CO].jpg")[0])
    }

    /** Serves USDB, YouTube and a CDN off one fake, dispatching on the URL like the real net does. */
    private class FakeNet(
        private val waitSeconds: Int = 24,
        private val chart: String = CHART,
        private val youTubeReply: String = PLAYABLE,
        private val coverFails: Boolean = false,
        private val videoFails: Boolean = false,
        private val itunesFinds: Boolean = true,
    ) : Http {
        var requests = 0
        var itunesRequests = 0
        var audioRequestHeaders: Map<String, String> = emptyMap()
        var videoRequestHeaders: Map<String, String> = emptyMap()

        /** Every video the player API was asked about, in order. */
        val playerVideoIds = mutableListOf<String>()

        override fun send(request: HttpRequest): HttpReply {
            requests++
            val url = request.url
            val isVideoStream = url.contains("googlevideo") && url.contains("vid=1")
            if (url.contains("googlevideo")) {
                if (isVideoStream) videoRequestHeaders = request.headers
                else audioRequestHeaders = request.headers
            }
            if (url.contains("youtubei/v1/player")) {
                videoIdIn(request.body?.decodeToString().orEmpty())
                    ?.let { playerVideoIds += it }
            }
            if (url.startsWith(ITUNES)) itunesRequests++
            return when {
                url.contains("youtubei/v1/player") -> HttpReply(200, youTubeReply, NO_HEADERS)
                url.startsWith("https://www.youtube.com/") -> HttpReply(200, YT_HOME, NO_HEADERS)
                isVideoStream ->
                    if (videoFails) HttpReply(500, "", NO_HEADERS)
                    else HttpReply(200, ByteArray(4096) { 9 }, NO_HEADERS)
                url.contains("googlevideo") -> HttpReply(200, ByteArray(2048) { 7 }, NO_HEADERS)
                url.startsWith(ITUNES) ->
                    HttpReply(200, if (itunesFinds) ITUNES_HIT else ITUNES_MISS, NO_HEADERS)
                // coverFails turns off *every* source, not just USDB's: with three of them, a
                // single one failing no longer means the song ends up without a cover.
                url.startsWith("https://covers.test") ->
                    if (coverFails) HttpReply(500, "", NO_HEADERS)
                    else HttpReply(200, ByteArray(96) { CHART_COVER_BYTE }, NO_HEADERS)
                url.startsWith("https://is1-ssl.mzstatic.com") ->
                    if (coverFails) HttpReply(500, "", NO_HEADERS)
                    else HttpReply(200, ByteArray(128) { ITUNES_COVER_BYTE }, NO_HEADERS)
                url.contains("data/cover") ->
                    if (coverFails) HttpReply(500, "", NO_HEADERS)
                    else HttpReply(200, ByteArray(64) { USDB_COVER_BYTE }, NO_HEADERS)
                url.contains("link=detail") ->
                    HttpReply(200, DETAIL_PAGE, NO_HEADERS)
                url.contains("link=gettxt") ->
                    if (request.method == "POST") HttpReply(200, chartPage(chart), NO_HEADERS)
                    else HttpReply(200, waitPage(waitSeconds), NO_HEADERS)
                else -> HttpReply(404, "", NO_HEADERS)
            }
        }
    }

    /** An in-memory card that can be told to rename or refuse, the way a real provider can. */
    private class FakeCard(
        private val renameTo: (String) -> String = { it },
        private val refuseNames: Set<String> = emptySet(),
    ) : DocumentTree, DocumentWriter {

        override val rootId = "root"
        private val children = mutableMapOf("root" to mutableListOf<TreeEntry>())
        private val contents = mutableMapOf<String, ByteArray>()
        private var next = 0

        val isEmpty: Boolean get() = children["root"].orEmpty().isEmpty()

        override fun list(directoryId: String) = children[directoryId].orEmpty().toList()

        override fun readBytes(fileId: String) = contents[fileId] ?: ByteArray(0)

        override fun createFolder(parentId: String, name: String): String? {
            if (children[parentId].orEmpty().any { it.name == name }) return null
            val id = "dir-${next++}"
            children.getOrPut(parentId) { mutableListOf() } += TreeEntry(id, name, true)
            children[id] = mutableListOf()
            return id
        }

        override fun writeFile(
            parentId: String,
            name: String,
            mimeType: String,
            bytes: ByteArray,
        ): String? {
            if (name in refuseNames) return null
            val id = "file-${next++}"
            children.getOrPut(parentId) { mutableListOf() } += TreeEntry(id, renameTo(name), false)
            contents[id] = bytes
            return id
        }

        override fun delete(documentId: String): Boolean {
            children.values.forEach { list -> list.removeAll { it.id == documentId } }
            children.remove(documentId)
            return true
        }

        fun addFolder(name: String) {
            createFolder("root", name)
        }

        fun namesIn(folder: String): List<String> {
            val id = children["root"]!!.first { it.name == folder }.id
            return children[id]!!.map { it.name }
        }

        fun bytesOf(folder: String, file: String): ByteArray {
            val folderId = children["root"]!!.first { it.name == folder }.id
            val fileId = children[folderId]!!.first { it.name == file }.id
            return contents[fileId]!!
        }

        fun textOf(folder: String, file: String): String {
            val folderId = children["root"]!!.first { it.name == folder }.id
            val fileId = children[folderId]!!.first { it.name == file }.id
            return contents[fileId]!!.decodeToString()
        }
    }

    private companion object {
        const val BASE = "https://usdb.test/index.php"
        val NO_HEADERS = emptyMap<String, List<String>>()

        const val CHART = "#ARTIST:David Bowie\n#TITLE:China Girl\n" +
            "#MP3:David Bowie - China Girl.mp3\n#BPM:269.14\n#GAP:11030\n" +
            "#VIDEO:v=_YC3sTbAPcU,co=china-girl.jpg\n: 0 3 31 Oh, \n- 40\nE"

        const val DETAIL_PAGE =
            """<html><a href="https://www.youtube.com/embed/_YC3sTbAPcU">Youtube-Link</a></html>"""

        const val YT_HOME = """<script>ytcfg.set({"visitorData":"TOKEN"});</script>"""

        val PLAYABLE = """
            {"playabilityStatus":{"status":"OK"},
             "videoDetails":{"title":"China Girl","lengthSeconds":"310"},
             "streamingData":{"adaptiveFormats":[
               {"itag":140,"url":"https://rr1.googlevideo.com/videoplayback?a=1",
                "mimeType":"audio/mp4; codecs=\"mp4a.40.2\"","bitrate":130669,
                "contentLength":"2048"}]}}
        """.trimIndent()

        val UNPLAYABLE = """
            {"playabilityStatus":{"status":"UNPLAYABLE","reason":"This video is not available"},
             "videoDetails":{"title":"China Girl","lengthSeconds":"310"}}
        """.trimIndent()

        fun waitPage(seconds: Int) = """
            <html><body>Please wait <span id="timeleft">x</span> seconds.
            <form id="timeform" method="post"><input type="hidden" name="wd" value="1"></form>
            <script>time = $seconds;
            function wait() { }</script></body></html>
        """.trimIndent()

        const val ITUNES = "https://itunes.apple.com/search"

        /** Which byte each cover source fills its file with, so a test can say where one came from. */
        const val CHART_COVER_BYTE: Byte = 11
        const val ITUNES_COVER_BYTE: Byte = 22
        const val USDB_COVER_BYTE: Byte = 33

        /** Pulls the id out of a player request body without needing a regex full of escapes. */
        fun videoIdIn(body: String): String? = body
            .substringAfter("\"videoId\"", "")
            .substringAfter('"', "")
            .substringBefore('"', "")
            .takeIf { it.isNotBlank() }

        val ITUNES_HIT = """
            {"resultCount":1,"results":[{"artistName":"David Bowie","trackName":"China Girl",
             "artworkUrl100":"https://is1-ssl.mzstatic.com/image/thumb/a/b/100x100bb.jpg"}]}
        """.trimIndent()

        val ITUNES_MISS = """{"resultCount":0,"results":[]}"""

        val PLAYABLE_WITH_VIDEO = """
            {"playabilityStatus":{"status":"OK"},
             "videoDetails":{"title":"China Girl","lengthSeconds":"310"},
             "streamingData":{"adaptiveFormats":[
               {"itag":140,"url":"https://rr1.googlevideo.com/videoplayback?a=1",
                "mimeType":"audio/mp4; codecs=\"mp4a.40.2\"","bitrate":130669,
                "contentLength":"2048"},
               {"itag":399,"url":"https://rr1.googlevideo.com/videoplayback?vid=1&av1=1",
                "mimeType":"video/mp4; codecs=\"av01.0.08M.08\"","height":1080,
                "contentLength":"27000"},
               {"itag":136,"url":"https://rr1.googlevideo.com/videoplayback?vid=1",
                "mimeType":"video/mp4; codecs=\"avc1.4d401f\"","height":720,
                "contentLength":"4096"}]}}
        """.trimIndent()

        fun chartPage(chart: String) = "<html><textarea name=\"txt\">$chart</textarea></html>"
    }
}
