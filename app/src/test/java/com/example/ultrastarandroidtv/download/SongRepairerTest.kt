package com.example.ultrastarandroidtv.download

import com.example.ultrastarandroidtv.library.DocumentTree
import com.example.ultrastarandroidtv.library.DocumentWriter
import com.example.ultrastarandroidtv.library.ScannedSong
import com.example.ultrastarandroidtv.library.TreeEntry
import com.example.ultrastarandroidtv.net.Http
import com.example.ultrastarandroidtv.net.HttpReply
import com.example.ultrastarandroidtv.net.HttpRequest
import com.example.ultrastarandroidtv.net.ITunesArtwork
import com.example.ultrastarandroidtv.net.YouTubeAudio
import com.example.ultrastarandroidtv.song.UltraStarSongParser
import com.example.ultrastarandroidtv.usdb.readUsdbSidecar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Filling in what a song on the card is missing.
 *
 * The case that matters is the one the real card is full of: nineteen folders holding a chart,
 * artwork and no music, made by a desktop tool that created the folder before it knew the download
 * would work. Those songs are not rubbish — the chart is fine and the music is still on YouTube —
 * and every one of them names its video in a `.usdb` file that the chart itself does not mention.
 */
class SongRepairerTest {

    // -------------------------------------------------------------------------------------
    // Working out whether there is anything to do
    // -------------------------------------------------------------------------------------

    /**
     * The load-bearing case. USDB Syncer moves the meta tags *out* of the chart and into its own
     * file, so a broken folder's `.txt` has no `#VIDEO:` line at all and the sidecar is the only
     * record of where the music came from.
     */
    @Test
    fun `finds the video in the sidecar when the chart does not name one`() {
        val card = FakeFolder(chart = CHART_WITHOUT_VIDEO, sidecar = SIDECAR)

        val plan = repairerFor(card).plan(card.song(audio = null))

        assertEquals("Qn-8ieevpkA", plan?.videoId)
        assertTrue(plan!!.needsAudio)
    }

    /** A chart that does name one is believed over the sidecar: it is the file this app writes. */
    @Test
    fun `prefers the chart's own meta tags`() {
        val card = FakeFolder(chart = CHART_WITH_VIDEO, sidecar = SIDECAR)

        assertEquals("FROMCHART01", repairerFor(card).plan(card.song(audio = null))?.videoId)
    }

    /** `a=` is the audio source when it is there; `v=` is the ordinary case where one is both. */
    @Test
    fun `takes the audio source over the video when they differ`() {
        val card = FakeFolder(
            chart = "#TITLE:T\n#ARTIST:A\n#BPM:200\n#VIDEO:a=SOUNDONLY01,v=PICTURE0001\n: 0 3 31 la\n- 20\nE",
            sidecar = null,
        )

        assertEquals("SOUNDONLY01", repairerFor(card).plan(card.song(audio = null))?.videoId)
    }

    /**
     * No offer when there is nothing to offer. A Repair button that answers "there is nothing I
     * can do about this" is worse than no button — it invites a press and then a telling-off.
     *
     * With no media source the *music* is beyond reach, and the plan has to say so rather than
     * promising a repair it cannot perform. What it may still offer is artwork, which comes from
     * the song's own name — see the cover-only test below.
     */
    @Test
    fun `a folder that does not say where its media came from cannot get its music back`() {
        val card = FakeFolder(chart = CHART_WITHOUT_VIDEO, sidecar = null)

        val plan = repairerFor(card).plan(card.song(audio = null))

        assertFalse("music cannot be promised without knowing where it lives", plan!!.needsAudio)
        assertFalse(plan.needsVideo)
    }

    /** Nothing missing and nothing fetchable means no button at all. */
    @Test
    fun `offers nothing when there is nothing left to get`() {
        val card = FakeFolder(chart = CHART_WITHOUT_VIDEO, sidecar = null)

        assertNull(repairerFor(card).plan(card.song(audio = "a", video = "v", cover = "c")))
    }

    @Test
    fun `offers nothing for a song that is already complete`() {
        val card = FakeFolder(chart = CHART_WITH_VIDEO, sidecar = null)

        val complete = card.song(audio = "audio", video = "video", cover = "cover")

        assertNull(repairerFor(card).plan(complete))
    }

    @Test
    fun `notices a missing video or cover on a song that plays`() {
        val card = FakeFolder(chart = CHART_WITH_VIDEO, sidecar = null)

        val plan = repairerFor(card).plan(card.song(audio = "audio"))!!

        assertFalse(plan.needsAudio)
        assertTrue(plan.needsVideo)
        assertTrue(plan.needsCover)
    }

    // -------------------------------------------------------------------------------------
    // Doing it
    // -------------------------------------------------------------------------------------

    @Test
    fun `saves the music and points the chart at it`() {
        val card = FakeFolder(chart = CHART_WITHOUT_VIDEO, sidecar = SIDECAR)
        val song = card.song(audio = null)
        val repairer = repairerFor(card)

        val outcome = repairer.repair(song, repairer.plan(song)!!) as RepairOutcome.Repaired

        assertTrue(outcome.audio)
        assertTrue("David Bowie - Golden Years.m4a" in card.names())
        assertTrue(card.chartText().contains("#MP3:David Bowie - Golden Years.m4a"))
    }

    /**
     * The chart must be *replaced*, not written again by name. The Storage Access Framework
     * resolves a clash by suffixing, so a second `.txt` would appear beside the first — and the
     * scanner reads each `.txt` as its own song, so the picker would then offer the song twice.
     */
    @Test
    fun `replaces the chart rather than writing a second one`() {
        val card = FakeFolder(chart = CHART_WITHOUT_VIDEO, sidecar = SIDECAR)
        val song = card.song(audio = null)
        val repairer = repairerFor(card)

        repairer.repair(song, repairer.plan(song)!!)

        assertEquals(1, card.names().count { it.endsWith(".txt") })
    }

    /**
     * A chart is not only its headers. Note lines carry meaning in their trailing spaces — the
     * only marker of a word ending in this format — and rebuilding one from parsed data is how
     * every syllable in the library ended up hyphenated once already.
     */
    @Test
    fun `leaves the notes exactly as they were`() {
        val card = FakeFolder(chart = CHART_WITH_NOTES, sidecar = SIDECAR)
        val song = card.song(audio = null)
        val repairer = repairerFor(card)

        repairer.repair(song, repairer.plan(song)!!)

        assertTrue(card.chartText().contains(": 0 3 31 Gol"))
        assertTrue("the trailing space is data", card.chartText().contains(": 8 3 28 den \n"))
    }

    /** Nothing here may make a folder worse than it found it. */
    @Test
    fun `a video that has gone changes nothing on the card`() {
        val card = FakeFolder(chart = CHART_WITHOUT_VIDEO, sidecar = SIDECAR)
        val song = card.song(audio = null)
        val repairer = repairerFor(card, net = FakeNet(playable = false))
        val before = card.names()

        val outcome = repairer.repair(song, repairer.plan(song)!!)

        assertEquals(
            DownloadProblem.AUDIO_UNAVAILABLE,
            (outcome as RepairOutcome.Failed).problem,
        )
        assertEquals(before, card.names())
    }

    /** Artwork is a nicety, and a song that got its music back is a success either way. */
    @Test
    fun `artwork that will not download does not fail the repair`() {
        val card = FakeFolder(chart = CHART_WITHOUT_VIDEO, sidecar = SIDECAR)
        val song = card.song(audio = null)
        val repairer = repairerFor(card, net = FakeNet(coverFails = true))

        val outcome = repairer.repair(song, repairer.plan(song)!!) as RepairOutcome.Repaired

        assertTrue(outcome.audio)
        assertFalse(outcome.cover)
        assertFalse("no cover header pointing at nothing", card.chartText().contains("#COVER:"))
    }

    /** Fetching only the artwork must not touch the header naming the music. */
    @Test
    fun `repairing a cover leaves the audio header alone`() {
        val card = FakeFolder(chart = CHART_PLAYABLE, sidecar = SIDECAR)
        val song = card.song(audio = "audio", video = "video")
        val repairer = repairerFor(card)

        val outcome = repairer.repair(song, repairer.plan(song)!!) as RepairOutcome.Repaired

        assertTrue(outcome.cover)
        assertTrue(card.chartText().contains("#MP3:David Bowie - Golden Years.mp3"))
    }

    /**
     * Artwork does not come from YouTube, so a missing video id must not cancel fetching it.
     *
     * A playable song wearing a thumbnail, whose chart names no video and which has no sidecar, can
     * still have a proper cover found from its artist and title alone. Refusing to offer that
     * because of an unrelated missing id was refusing the one repair that was possible.
     */
    @Test
    fun `a cover can be repaired without knowing where the media came from`() {
        val card = FakeFolder(chart = CHART_WITHOUT_VIDEO, sidecar = null)
        val song = card.song(audio = "audio", video = "video")

        val plan = repairerFor(card).plan(song)!!

        assertNull("nothing says where media would come from", plan.videoId)
        assertTrue(plan.needsCover)
        assertFalse("and so neither of those can be attempted", plan.needsAudio)
        assertFalse(plan.needsVideo)
    }

    /** A cover-only repair must not be blocked by a video that has since been taken down. */
    @Test
    fun `a removed video does not stop the artwork being fetched`() {
        val card = FakeFolder(chart = CHART_PLAYABLE, sidecar = null)
        val song = card.song(audio = "audio", video = "video")
        val repairer = repairerFor(card, net = FakeNet(playable = false))

        val outcome = repairer.repair(song, repairer.plan(song)!!) as RepairOutcome.Repaired

        assertTrue(outcome.cover)
    }

    /**
     * A file the chart does not name is litter, and SAF *suffixes* a name that is already taken —
     * so retrying would leave one more orphan each time while the song still would not play.
     */
    @Test
    fun `music that cannot be pointed at is removed again`() {
        val card = FakeFolder(chart = CHART_WITHOUT_VIDEO, sidecar = SIDECAR, refuseChartRewrite = true)
        val song = card.song(audio = null)
        val repairer = repairerFor(card)

        val outcome = repairer.repair(song, repairer.plan(song)!!)

        assertTrue(outcome is RepairOutcome.Failed)
        assertTrue(
            "no orphaned audio may be left behind",
            card.names().none { it.endsWith(".m4a") },
        )
    }

    /**
     * A repair that got none of what it went for is a failure, not a quiet success.
     *
     * Reported as `Repaired` it was counted among the songs fixed, marked the library scan out of
     * date, and put a success-coloured "Nothing could be got" in front of somebody — three
     * statements that were all untrue at once.
     */
    @Test
    fun `fetching none of it is a failure`() {
        val card = FakeFolder(chart = CHART_PLAYABLE, sidecar = null)
        val song = card.song(audio = "audio", video = "video")
        val repairer = repairerFor(card, net = FakeNet(coverFails = true, itunesFinds = false))

        val outcome = repairer.repair(song, repairer.plan(song)!!)

        assertEquals(
            DownloadProblem.NOTHING_FETCHED,
            (outcome as RepairOutcome.Failed).problem,
        )
    }

    /**
     * And it must not be reported as the music being unavailable, which is the reason that sends
     * `Downloads` off to replace the entire song with a different chart. A cover iTunes happened
     * not to have is nowhere near grounds for that.
     */
    @Test
    fun `a missing cover is never mistaken for missing music`() {
        val card = FakeFolder(chart = CHART_PLAYABLE, sidecar = null)
        val song = card.song(audio = "audio", video = "video")
        val repairer = repairerFor(card, net = FakeNet(coverFails = true, itunesFinds = false))

        val outcome = repairer.repair(song, repairer.plan(song)!!) as RepairOutcome.Failed

        assertNotEquals(DownloadProblem.AUDIO_UNAVAILABLE, outcome.problem)
    }

    /** Getting one of three is still worth having, and still counts. */
    @Test
    fun `partial success is still success`() {
        val card = FakeFolder(chart = CHART_WITHOUT_VIDEO, sidecar = SIDECAR)
        val song = card.song(audio = null)
        val repairer = repairerFor(card, net = FakeNet(coverFails = true, itunesFinds = false))

        val outcome = repairer.repair(song, repairer.plan(song)!!) as RepairOutcome.Repaired

        assertTrue(outcome.audio)
        assertFalse(outcome.cover)
    }

    // -------------------------------------------------------------------------------------
    // The sidecar itself
    // -------------------------------------------------------------------------------------

    @Test
    fun `reads a real sidecar`() {
        val read = readUsdbSidecar(SIDECAR)!!

        assertEquals(18117, read.songId)
        assertEquals("Qn-8ieevpkA", read.metaTags.audioSource)
    }

    /** The format belongs to another program, which is free to change it. */
    @Test
    fun `anything that is not a sidecar is simply not one`() {
        assertNull(readUsdbSidecar("not json at all"))
        assertNull(readUsdbSidecar("{}"))
        assertNull(readUsdbSidecar("""{"pinned": false}"""))
    }

    @Test
    fun `a sidecar with an id and no tags still gives up its id`() {
        val read = readUsdbSidecar("""{"song_id": 42}""")!!

        assertEquals(42, read.songId)
        assertNull(read.metaTags.audioSource)
    }

    // -------------------------------------------------------------------------------------

    private fun repairerFor(card: FakeFolder, net: FakeNet = FakeNet()) = SongRepairer(
        youTube = YouTubeAudio(net),
        artwork = ITunesArtwork(net),
        http = net,
        tree = card,
        writer = card,
    )

    /** One song's folder, with whatever is in it. */
    private class FakeFolder(
        chart: String,
        sidecar: String?,
        private val refuseChartRewrite: Boolean = false,
    ) : DocumentTree, DocumentWriter {

        override val rootId = "root"
        private val entries = mutableListOf<TreeEntry>()
        private val contents = mutableMapOf<String, ByteArray>()
        private var next = 0
        private val chartText: String = chart

        init {
            put("David Bowie - Golden Years.txt", chart.toByteArray())
            sidecar?.let { put("R0V2tgk2txI.usdb", it.toByteArray()) }
        }

        private fun put(name: String, bytes: ByteArray): String {
            val id = "file-${next++}"
            entries += TreeEntry(id, name, false)
            contents[id] = bytes
            return id
        }

        fun song(audio: String?, video: String? = null, cover: String? = null) = ScannedSong(
            song = UltraStarSongParser.parse(chartText),
            folderId = "root",
            textId = entries.first { it.name.endsWith(".txt") }.id,
            folderName = "David Bowie - Golden Years",
            audioId = audio,
            videoId = video,
            coverId = cover,
            backgroundId = null,
            sidecarId = entries.firstOrNull { it.name.endsWith(".usdb") }?.id,
        )

        fun names(): List<String> = entries.map { it.name }.sorted()

        fun chartText(): String =
            contents[entries.first { it.name.endsWith(".txt") }.id]!!.decodeToString()

        override fun list(directoryId: String) = entries.toList()

        override fun readBytes(fileId: String) = contents[fileId] ?: ByteArray(0)

        override fun createFolder(parentId: String, name: String): String? = null

        override fun writeFile(
            parentId: String,
            name: String,
            mimeType: String,
            bytes: ByteArray,
        ): String = put(name, bytes)

        override fun overwrite(documentId: String, bytes: ByteArray): Boolean {
            if (refuseChartRewrite) return false
            if (documentId !in contents) return false
            contents[documentId] = bytes
            return true
        }

        override fun delete(documentId: String): Boolean {
            entries.removeAll { it.id == documentId }
            return contents.remove(documentId) != null
        }
    }

    private class FakeNet(
        private val playable: Boolean = true,
        private val coverFails: Boolean = false,
        private val itunesFinds: Boolean = true,
    ) : Http {
        override fun send(request: HttpRequest): HttpReply {
            val url = request.url
            return when {
                url.contains("youtubei/v1/player") ->
                    HttpReply(200, if (playable) PLAYABLE else UNPLAYABLE, NO_HEADERS)
                url.startsWith("https://www.youtube.com/") -> HttpReply(200, YT_HOME, NO_HEADERS)
                url.contains("googlevideo") -> ranged(ByteArray(2048) { 7 }, request)
                url.startsWith("https://itunes.apple.com/search") ->
                    HttpReply(200, if (itunesFinds) ITUNES_HIT else ITUNES_MISS, NO_HEADERS)
                url.startsWith("https://is1-ssl.mzstatic.com") ->
                    if (coverFails) HttpReply(500, "", NO_HEADERS)
                    else HttpReply(200, ByteArray(128) { 22 }, NO_HEADERS)
                else -> HttpReply(404, "", NO_HEADERS)
            }
        }

        private fun ranged(body: ByteArray, request: HttpRequest): HttpReply {
            val spec = request.headers["Range"]?.removePrefix("bytes=")
                ?: return HttpReply(200, body, NO_HEADERS)
            val start = spec.substringBefore('-').toInt()
            val end = spec.substringAfter('-').toIntOrNull() ?: (body.size - 1)
            if (start >= body.size) return HttpReply(416, "", NO_HEADERS)
            return HttpReply(206, body.copyOfRange(start, minOf(end + 1, body.size)), NO_HEADERS)
        }

        private val NO_HEADERS = emptyMap<String, List<String>>()
    }

    private companion object {
        const val CHART_WITHOUT_VIDEO = "#TITLE:Golden Years\n#ARTIST:David Bowie\n#BPM:435.04\n: 0 3 31 la\n- 20\nE"

        const val CHART_WITH_VIDEO =
            "#TITLE:Golden Years\n#ARTIST:David Bowie\n#BPM:435.04\n#VIDEO:v=FROMCHART01\n: 0 3 31 la\n- 20\nE"

        const val CHART_PLAYABLE = "#TITLE:Golden Years\n#ARTIST:David Bowie\n#BPM:435.04\n" +
            "#MP3:David Bowie - Golden Years.mp3\n#VIDEO:v=FROMCHART01\n: 0 3 31 la\n- 20\nE"

        val CHART_WITH_NOTES = "#TITLE:Golden Years\n#ARTIST:David Bowie\n#BPM:435.04\n" +
            ": 0 3 31 Gol\n" + ": 8 3 28 den \n" + "- 20\nE"

        /** Trimmed from a real one off the card, which is what makes it worth having. */
        val SIDECAR = """
            {
                "song_id": 18117,
                "usdb_mtime": 1421149101,
                "meta_tags": "a=Qn-8ieevpkA,co=golden-years.jpg,bg=bowie-david.jpg",
                "pinned": false,
                "audio": { "status": "failure" }
            }
        """.trimIndent()

        val YT_HOME = """<script>ytcfg.set({"visitorData":"TOKEN"});</script>"""

        val PLAYABLE = """
            {"playabilityStatus":{"status":"OK"},
             "videoDetails":{"title":"Golden Years","lengthSeconds":"240"},
             "streamingData":{"adaptiveFormats":[
               {"itag":140,"url":"https://rr1.googlevideo.com/videoplayback?a=1",
                "mimeType":"audio/mp4; codecs=\"mp4a.40.2\"","bitrate":130669,
                "contentLength":"2048"}]}}
        """.trimIndent()

        val UNPLAYABLE = """
            {"playabilityStatus":{"status":"UNPLAYABLE","reason":"This video is not available"},
             "videoDetails":{"title":"Golden Years","lengthSeconds":"240"}}
        """.trimIndent()

        val ITUNES_MISS = """{"resultCount":0,"results":[]}"""

        val ITUNES_HIT = """
            {"resultCount":1,"results":[{"artistName":"David Bowie","trackName":"Golden Years",
             "artworkUrl100":"https://is1-ssl.mzstatic.com/image/thumb/a/b/100x100bb.jpg"}]}
        """.trimIndent()
    }
}
