package com.example.ultrastarandroidtv.download

import com.example.ultrastarandroidtv.usdb.UsdbSong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadQueueTest {

    // -----------------------------------------------------------------------------------------
    // The queue
    // -----------------------------------------------------------------------------------------

    @Test
    fun `keeps what was asked for, in order`() {
        val queue = DownloadQueue()
        queue.add(song(1, "First"))
        queue.add(song(2, "Second"))
        assertEquals(listOf("First", "Second"), queue.entries.map { it.song.title })
    }

    /** Pressing a song twice and seeing nothing happen reads as a broken button. */
    @Test
    fun `refuses a song already queued, and says so`() {
        val queue = DownloadQueue()
        assertTrue(queue.add(song(1, "First")))
        assertFalse(queue.add(song(1, "First")))
        assertEquals(1, queue.entries.size)
    }

    /** Asking again after a failure is a retry, which is a reasonable thing to want. */
    @Test
    fun `a failed song can be asked for again`() {
        val queue = DownloadQueue()
        queue.add(song(1, "First"))
        queue.entries.first().status = QueueStatus.Failed(DownloadProblem.NETWORK, "no internet")

        assertTrue(queue.add(song(1, "First")))
        assertEquals(1, queue.entries.size)
        assertEquals(QueueStatus.Queued, queue.entries.first().status)
    }

    @Test
    fun `a finished song is not offered again`() {
        val queue = DownloadQueue()
        queue.add(song(1, "First"))
        queue.entries.first().status = QueueStatus.Done("A - First")
        assertFalse(queue.add(song(1, "First")))
    }

    @Test
    fun `works through the queue oldest first`() {
        val queue = DownloadQueue()
        queue.add(song(1, "First"))
        queue.add(song(2, "Second"))

        assertEquals("First", queue.nextPending()?.song?.title)
        queue.entries.first().status = QueueStatus.Done("x")
        assertEquals("Second", queue.nextPending()?.song?.title)
        queue.entries[1].status = QueueStatus.Done("y")
        assertNull(queue.nextPending())
    }

    /** A song being worked on is not pending, or the queue would start it a second time. */
    @Test
    fun `a song in flight is not picked up again`() {
        val queue = DownloadQueue()
        queue.add(song(1, "First"))
        queue.entries.first().status = QueueStatus.Working(DownloadStage.DownloadingAudio)
        assertNull(queue.nextPending())
        assertTrue(queue.isBusy)
    }

    @Test
    fun `knows what it is holding`() {
        val queue = DownloadQueue()
        queue.add(song(7, "Seven"))
        assertTrue(queue.holds(7))
        assertFalse(queue.holds(8))

        queue.entries.first().status = QueueStatus.Failed(DownloadProblem.NETWORK, "no")
        assertFalse("a failure is not a holding -- it can be retried", queue.holds(7))
    }

    @Test
    fun `clearing keeps anything still in flight`() {
        val queue = DownloadQueue()
        queue.add(song(1, "Done"))
        queue.add(song(2, "Working"))
        queue.add(song(3, "Queued"))
        queue.entries[0].status = QueueStatus.Done("x")
        queue.entries[1].status = QueueStatus.Working(DownloadStage.Saving)

        queue.clearFinished()
        assertEquals(listOf("Working", "Queued"), queue.entries.map { it.song.title })
    }

    @Test
    fun `counts what is waiting and what landed`() {
        val queue = DownloadQueue()
        queue.add(song(1, "A"))
        queue.add(song(2, "B"))
        queue.add(song(3, "C"))
        queue.entries[0].status = QueueStatus.Done("x")
        queue.entries[1].status = QueueStatus.Working(DownloadStage.FindingAudio)

        assertEquals(1, queue.savedCount)
        assertEquals(1, queue.waitingCount)
        assertTrue(queue.isBusy)
    }

    @Test
    fun `an empty queue is not busy`() {
        assertFalse(DownloadQueue().isBusy)
        assertNull(DownloadQueue().nextPending())
    }

    // -----------------------------------------------------------------------------------------
    // What it says
    // -----------------------------------------------------------------------------------------

    /**
     * The wait must count down out loud. It is the longest part of a download by a wide margin,
     * and a spinner there reads as a hang -- a number going down is visibly not stuck.
     */
    @Test
    fun `the wait says who is waiting and how long is left`() {
        val label = stageLabel(DownloadStage.WaitingForUsdb(18))
        assertTrue("must name USDB", label.contains("USDB"))
        assertTrue("must carry the number", label.contains("18"))
    }

    @Test
    fun `every stage has words of its own`() {
        val labels = listOf(
            stageLabel(DownloadStage.FetchingChart),
            stageLabel(DownloadStage.FindingAudio),
            stageLabel(DownloadStage.DownloadingAudio),
            stageLabel(DownloadStage.FetchingArtwork),
            stageLabel(DownloadStage.Saving),
        )
        assertEquals("no two stages should read the same", labels.size, labels.toSet().size)
        assertTrue(labels.none { it.isBlank() })
    }

    @Test
    fun `a queued song says it is waiting its turn`() {
        assertEquals("Waiting its turn", statusLabel(QueueStatus.Queued))
    }

    /** A failure's own sentence is the useful one -- "try another version" and so on. */
    @Test
    fun `a failure shows its own message rather than a generic one`() {
        val status = QueueStatus.Failed(DownloadProblem.AUDIO_UNAVAILABLE, "Try another version.")
        assertEquals("Try another version.", statusLabel(status))
    }

    @Test
    fun `a finished song says so plainly`() {
        assertEquals("Added to your songs", statusLabel(QueueStatus.Done("A - B")))
    }

    // -----------------------------------------------------------------------------------------
    // The summary line
    // -----------------------------------------------------------------------------------------

    @Test
    fun `an untouched queue has nothing to say`() {
        assertNull(queueSummary(DownloadQueue()))
    }

    /** "2 more waiting" is a thing somebody can decide to wait for; a row of spinners is not. */
    @Test
    fun `says how many are still to come`() {
        val queue = DownloadQueue()
        queue.add(song(1, "A"))
        queue.add(song(2, "B"))
        queue.add(song(3, "C"))
        queue.entries[0].status = QueueStatus.Working(DownloadStage.DownloadingAudio)

        val summary = queueSummary(queue)!!
        assertTrue(summary.contains("Downloading"))
        assertTrue(summary.contains("2 more"))
    }

    @Test
    fun `points at Rescan once everything has landed`() {
        val queue = DownloadQueue()
        queue.add(song(1, "A"))
        queue.entries[0].status = QueueStatus.Done("A - A")

        val summary = queueSummary(queue)!!
        assertTrue(summary.contains("1 song added"))
        assertTrue("somebody has to know where the songs went", summary.contains("Rescan"))
    }

    @Test
    fun `counts songs plurally when there is more than one`() {
        val queue = DownloadQueue()
        queue.add(song(1, "A"))
        queue.add(song(2, "B"))
        queue.entries.forEach { it.status = QueueStatus.Done("x") }
        assertTrue(queueSummary(queue)!!.contains("2 songs added"))
    }

    // -----------------------------------------------------------------------------------------

    private fun song(id: Int, title: String) = UsdbSong(
        songId = id,
        artist = "An Artist",
        title = title,
        genre = "",
        year = "1984",
        edition = "",
        hasGoldenNotes = false,
        language = "English",
        creator = "someone",
        rating = 0,
        views = 1,
        coverUrl = null,
        sampleUrl = null,
    )
}
