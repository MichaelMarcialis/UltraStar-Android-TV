package com.example.ultrastarandroidtv.download

import com.example.ultrastarandroidtv.library.ScannedSong
import com.example.ultrastarandroidtv.song.SongMetadata
import com.example.ultrastarandroidtv.song.UltraStarSong
import com.example.ultrastarandroidtv.song.VoicePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Songs waiting to be filled in.
 *
 * The same ground [DownloadQueueTest] covers for downloads, because the two queues are separate on
 * purpose and a rule proved for one says nothing about the other.
 */
class RepairQueueTest {

    // -------------------------------------------------------------------------------------
    // The queue
    // -------------------------------------------------------------------------------------

    @Test
    fun `keeps what was asked for, in order`() {
        val queue = RepairQueue()
        queue.add(song("First"), plan())
        queue.add(song("Second"), plan())

        assertEquals(listOf("First", "Second"), queue.jobs.map { it.song.folderName })
    }

    /** Pressing a song twice and seeing nothing happen reads as a broken button. */
    @Test
    fun `refuses a song already queued`() {
        val queue = RepairQueue()

        assertTrue(queue.add(song("First"), plan()))
        assertFalse(queue.add(song("First"), plan()))
        assertEquals(1, queue.jobs.size)
    }

    /** Asking again after a failure is a retry, and a reasonable thing to want. */
    @Test
    fun `a failed song can be asked for again, replacing the old attempt`() {
        val queue = RepairQueue()
        queue.add(song("First"), plan())
        queue.jobs.first().status = RepairStatus.Failed("no internet")

        assertTrue(queue.add(song("First"), plan()))
        assertEquals(1, queue.jobs.size)
        assertEquals(RepairStatus.Queued, queue.jobs.first().status)
    }

    /**
     * A partial repair leaves a plan behind, and it has to be reachable again — but only once
     * somebody has looked at the card.
     *
     * Both halves matter, and getting either wrong is its own bug. Refusing forever meant the rest
     * of the plan could never be attempted for the whole session. Allowing it immediately meant the
     * *stale* plan could run: the screen keeps its plans until the batch ends and rescans, so
     * pressing Repair mid-batch would fetch the music again, be handed a suffixed copy by SAF, and
     * point the chart at that.
     */
    @Test
    fun `a done song is held until the card has been read again`() {
        val queue = RepairQueue()
        queue.add(song("First"), plan(scan = 7))
        queue.jobs.first().status = RepairStatus.Done("Got the music")

        assertTrue(
            "the plan it ran is the only plan anybody has, and it is now untrue",
            queue.holds("text-First", scan = 7),
        )
        assertFalse(
            "a rescan makes whatever it asks for next a fresh question",
            queue.holds("text-First", scan = 8),
        )
    }

    @Test
    fun `a stale plan cannot be queued a second time`() {
        val queue = RepairQueue()
        queue.add(song("First"), plan(scan = 7))
        queue.jobs.first().status = RepairStatus.Done("Got the music")

        assertFalse("the same scan asks the same question", queue.add(song("First"), plan(scan = 7)))
        assertTrue(queue.add(song("First"), plan(audio = false, cover = true, scan = 8)))
        assertEquals(1, queue.jobs.size)
        assertEquals(RepairStatus.Queued, queue.jobs.first().status)
    }

    /** While it is actually on its way, no scan makes asking twice reasonable. */
    @Test
    fun `a song being worked on is not re-queued`() {
        val queue = RepairQueue()
        queue.add(song("First"), plan(scan = 7))
        queue.jobs.first().status = RepairStatus.Working(RepairStage.Music)

        assertTrue(queue.holds("text-First", scan = 99))
        assertFalse(queue.add(song("First"), plan(scan = 99)))
    }

    /** A failure wrote nothing, so nothing can be written twice: a retry is always reasonable. */
    @Test
    fun `a failure is never in the way, whatever the scan`() {
        val queue = RepairQueue()
        queue.add(song("First"), plan(scan = 7))
        queue.jobs.first().status = RepairStatus.Failed("no internet")

        assertFalse(queue.holds("text-First", scan = 7))
        assertTrue(queue.add(song("First"), plan(scan = 7)))
    }

    @Test
    fun `holds while a song is queued, and says nothing about one it has never seen`() {
        val queue = RepairQueue()
        queue.add(song("First"), plan(scan = 1))

        assertTrue(queue.holds("text-First", scan = 1))
        assertFalse(queue.holds("text-Nothing", scan = 1))
    }

    @Test
    fun `works through the pending jobs and then reports nothing left`() {
        val queue = RepairQueue()
        queue.add(song("First"), plan())
        queue.add(song("Second"), plan())

        val first = queue.nextPending()!!
        assertEquals("First", first.song.folderName)
        first.status = RepairStatus.Done("Got the music")

        assertEquals("Second", queue.nextPending()!!.song.folderName)
        queue.jobs[1].status = RepairStatus.Done("Got the music")

        assertNull(queue.nextPending())
        assertFalse(queue.isBusy)
    }

    @Test
    fun `clearing keeps whatever is still in flight`() {
        val queue = RepairQueue()
        queue.add(song("Done"), plan())
        queue.add(song("Waiting"), plan())
        queue.jobs.first().status = RepairStatus.Done("Got the music")

        queue.clearFinished()

        assertEquals(listOf("Waiting"), queue.jobs.map { it.song.folderName })
    }

    /**
     * A job carries document ids, and a document id only means anything inside the tree it came
     * from. Changing the song folder while a batch is queued would apply the old ids to the new
     * tree: at best the rest of the batch fails, at worst an id that exists under both grants
     * points at a different song and the repair writes into it.
     */
    @Test
    fun `a job remembers which folder it was queued from`() {
        val queue = RepairQueue()

        queue.add(song("First"), plan(), tree = "content://card/UltraStar")

        assertEquals("content://card/UltraStar", queue.jobs.first().tree)
    }

    /** Nothing recorded means nothing to check, which is what the queue's own tests want. */
    @Test
    fun `a job queued without a folder records none`() {
        val queue = RepairQueue()

        queue.add(song("First"), plan())

        assertNull(queue.jobs.first().tree)
    }

    // -------------------------------------------------------------------------------------
    // One run at a time
    // -------------------------------------------------------------------------------------

    /**
     * The bug this exists to stop: a bar that opens nearly full.
     *
     * Finished jobs are kept, because a song's own screen shows what its last repair did. Counting
     * them into the *bar* meant that repairing one song after a batch of twenty-three opened at
     * 96%, twenty-three of the twenty-four jobs being already complete.
     */
    @Test
    fun `a new run starts its bar at the beginning`() {
        val queue = RepairQueue()
        repeat(5) { queue.add(song("Old$it"), plan()) }
        queue.jobs.forEach { it.status = RepairStatus.Done("Got the music") }
        assertEquals(1f, repairQueueProgress(queue)!!, 0.001f)

        queue.add(song("New"), plan())

        assertEquals(0f, repairQueueProgress(queue)!!, 0.001f)
        assertEquals("only the new one is being waited for", 1, queue.current.size)
    }

    /** And the count beside it is about this run, not everything done this session. */
    @Test
    fun `what has been fixed is counted per run`() {
        val queue = RepairQueue()
        queue.add(song("Old"), plan())
        queue.jobs.first().status = RepairStatus.Done("Got the music")
        assertEquals(1, queue.fixedCount)

        queue.add(song("New"), plan())

        assertEquals(0, queue.fixedCount)
    }

    /** Adding while a run is still going joins it rather than starting another. */
    @Test
    fun `adding to a busy queue joins the same run`() {
        val queue = RepairQueue()
        queue.add(song("First"), plan())
        queue.add(song("Second"), plan())

        assertEquals(2, queue.current.size)
        assertEquals(0.5f, run { queue.jobs.first().status = RepairStatus.Done("x"); repairQueueProgress(queue)!! }, 0.001f)
    }

    @Test
    fun `nothing queued means no bar at all`() {
        assertNull(repairQueueProgress(RepairQueue()))
    }

    // -------------------------------------------------------------------------------------
    // Progress within one job
    // -------------------------------------------------------------------------------------

    @Test
    fun `progress climbs through the stages and never falls back`() {
        val job = RepairJob(song("First"), plan())
        val seen = mutableListOf<Float>()
        for (stage in listOf(
            RepairStage.Looking,
            RepairStage.Music,
            RepairStage.Artwork,
            RepairStage.Video(0),
            RepairStage.Video(100),
        )) {
            job.status = RepairStatus.Working(stage)
            seen += job.progress
        }
        job.status = RepairStatus.Done("Got the music, video and artwork")

        assertEquals("must only ever climb", seen.sorted(), seen)
        assertEquals(1f, job.progress, 0f)
    }

    /** Both endings fill the bar: a failure is finished with, not stuck. */
    @Test
    fun `a failed job counts as finished`() {
        val job = RepairJob(song("First"), plan())
        job.status = RepairStatus.Working(RepairStage.Music)
        job.status = RepairStatus.Failed("its music has gone")

        assertEquals(1f, job.progress, 0f)
        assertTrue(job.isFinished)
    }

    /** A repair fetching one thing must not sit at a third because it is not fetching three. */
    @Test
    fun `a job only reaches through the stages it actually has`() {
        val coverOnly = RepairJob(song("First"), plan(audio = false, video = false, cover = true))
        coverOnly.status = RepairStatus.Working(RepairStage.Artwork)

        val everything = RepairJob(song("Second"), plan(audio = true, video = true, cover = true))
        everything.status = RepairStatus.Working(RepairStage.Artwork)

        assertTrue(
            "the same stage is further along when there is less to do",
            coverOnly.progress > everything.progress,
        )
    }

    // -------------------------------------------------------------------------------------
    // The words
    // -------------------------------------------------------------------------------------

    @Test
    fun `says how many are still to come, and then what was done`() {
        val queue = RepairQueue()
        queue.add(song("First"), plan())
        queue.add(song("Second"), plan())
        queue.jobs.first().status = RepairStatus.Working(RepairStage.Music)

        assertTrue(repairSummary(queue)!!.contains("1 more waiting"))

        queue.jobs.first().status = RepairStatus.Done("Got the music")
        queue.jobs[1].status = RepairStatus.Failed("its music has gone")

        val finished = repairSummary(queue)!!
        assertTrue("one succeeded, so say so", finished.contains("1 song"))
        assertTrue(finished.contains("repaired"))
    }

    @Test
    fun `an untouched queue has nothing to say`() {
        assertNull(repairSummary(RepairQueue()))
    }

    // -------------------------------------------------------------------------------------

    private fun plan(
        audio: Boolean = true,
        video: Boolean = true,
        cover: Boolean = true,
        scan: Int = 0,
    ) = RepairPlan(
            videoId = "VIDEO123",
            needsAudio = audio,
            needsVideo = video,
            needsCover = cover,
            coverUrl = null,
            scan = scan,
        )

    private fun song(name: String) = ScannedSong(
        song = UltraStarSong(
            metadata = SongMetadata(title = name, artist = "An Artist", mp3 = null, bpm = 120.0),
            voiceParts = listOf(VoicePart(label = null, lines = emptyList())),
        ),
        folderId = "folder-$name",
        textId = "text-$name",
        folderName = name,
        audioId = null,
        videoId = null,
        coverId = null,
        backgroundId = null,
    )
}
