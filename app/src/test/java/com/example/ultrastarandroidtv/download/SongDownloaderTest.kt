package com.example.ultrastarandroidtv.download

import com.example.ultrastarandroidtv.library.DocumentTree
import com.example.ultrastarandroidtv.library.DocumentWriter
import com.example.ultrastarandroidtv.library.TreeEntry
import com.example.ultrastarandroidtv.net.Http
import com.example.ultrastarandroidtv.net.HttpReply
import com.example.ultrastarandroidtv.net.HttpRequest
import com.example.ultrastarandroidtv.net.YouTubeAudio
import com.example.ultrastarandroidtv.usdb.UsdbCharts
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
            youTube = YouTubeAudio(net),
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
        youTube = YouTubeAudio(net),
        http = net,
        tree = card,
        writer = card,
        sleepMillis = { },
    )

    /** Serves USDB, YouTube and a CDN off one fake, dispatching on the URL like the real net does. */
    private class FakeNet(
        private val waitSeconds: Int = 24,
        private val chart: String = CHART,
        private val youTubeReply: String = PLAYABLE,
        private val coverFails: Boolean = false,
    ) : Http {
        var requests = 0
        var audioRequestHeaders: Map<String, String> = emptyMap()

        override fun send(request: HttpRequest): HttpReply {
            requests++
            val url = request.url
            if (url.contains("googlevideo")) audioRequestHeaders = request.headers
            return when {
                url.contains("youtubei/v1/player") -> HttpReply(200, youTubeReply, NO_HEADERS)
                url.startsWith("https://www.youtube.com/") -> HttpReply(200, YT_HOME, NO_HEADERS)
                url.contains("googlevideo") -> HttpReply(200, ByteArray(2048) { 7 }, NO_HEADERS)
                url.contains("data/cover") ->
                    if (coverFails) HttpReply(500, "", NO_HEADERS)
                    else HttpReply(200, ByteArray(64) { 1 }, NO_HEADERS)
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

        fun chartPage(chart: String) = "<html><textarea name=\"txt\">$chart</textarea></html>"
    }
}
