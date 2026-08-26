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

    // -----------------------------------------------------------------------------------------
    // The short label, and the bug it exists to prevent
    // -----------------------------------------------------------------------------------------

    /**
     * The regression this function was extracted for.
     *
     * A failure's own sentence runs to a couple of hundred characters, and one network error
     * carried four hundred characters of an HTML error page. Drawn into the row's trailing slot,
     * which had no width limit, that text took the whole row and left the song's title none -- so
     * the title wrapped to one character per line and a single result grew to the full height of
     * the television. Reported from the sofa, reproduced from a recording of the screen.
     */
    @Test
    fun `a failure's sentence never appears in the short label`() {
        val message = "\"Kelly Clarkson - Since U Been Gone\" is blocked in your country. " +
            "Try another version of the song."
        val short = shortStatusLabel(QueueStatus.Failed(DownloadProblem.AUDIO_UNAVAILABLE, message))

        assertFalse("the sentence belongs on the second line, not here", short.contains("blocked"))
        assertTrue("must still invite a retry", short.contains("Try again"))
    }

    /** Whatever a failure says, the short label is short. */
    @Test
    fun `the short label stays short however long the reason is`() {
        val enormous = "x".repeat(400)
        val short = shortStatusLabel(QueueStatus.Failed(DownloadProblem.NETWORK, enormous))
        assertTrue("was ${short.length} characters", short.length <= 30)
    }

    /** The countdown does belong here: it is the one thing worth watching on a slow row. */
    @Test
    fun `the short label still counts USDB's wait down`() {
        val short = shortStatusLabel(QueueStatus.Working(DownloadStage.WaitingForUsdb(18)))
        assertTrue(short.contains("USDB"))
        assertTrue(short.contains("18"))
    }

    @Test
    fun `every short label is short enough for a fixed slot`() {
        val labels = listOf(
            shortStatusLabel(QueueStatus.Queued),
            shortStatusLabel(QueueStatus.Done("A - B")),
            shortStatusLabel(QueueStatus.Failed(DownloadProblem.NETWORK, "anything at all")),
        ) + DownloadStage::class.let {
            listOf(
                DownloadStage.FetchingChart,
                DownloadStage.FindingAudio,
                DownloadStage.DownloadingAudio,
                DownloadStage.FetchingArtwork,
                DownloadStage.Saving,
                DownloadStage.DownloadingVideo(42),
                DownloadStage.WaitingForUsdb(24),
            ).map { stage -> shortStatusLabel(QueueStatus.Working(stage)) }
        }
        labels.forEach { assertTrue("too long for the slot: $it", it.length <= 30) }
    }

    /** The long form is still what the second line shows, so it must still carry the reason. */
    @Test
    fun `the long label still carries the whole reason`() {
        val message = "That video is blocked in your country. Try another version of the song."
        assertEquals(
            message,
            statusLabel(QueueStatus.Failed(DownloadProblem.AUDIO_UNAVAILABLE, message)),
        )
    }

    /**
     * The slowest stage of all -- YouTube serves a video at about the rate it plays, so this runs
     * for a hundred seconds. A number that moves is the difference between that and a hang.
     */
    @Test
    fun `the video says how far along it is`() {
        assertTrue(stageLabel(DownloadStage.DownloadingVideo(42)).contains("42"))
        assertTrue(stageLabel(DownloadStage.DownloadingVideo(42)).contains("Video"))
        assertFalse(
            "must not read the same as downloading the music",
            stageLabel(DownloadStage.DownloadingVideo(0)) == stageLabel(DownloadStage.DownloadingAudio),
        )
    }

    // -----------------------------------------------------------------------------------------
    // One bar instead of several countdowns
    // -----------------------------------------------------------------------------------------

    /**
     * The bar must only ever go forwards, and this is the case that breaks a naive version:
     * "getting the song" is reported twice, once before USDB's countdown and once to collect the
     * file afterwards. Reading the stage straight off would send the bar back to 4% having sat at
     * 80%, which looks exactly like the download restarting.
     */
    @Test
    fun `progress never goes backwards when a stage repeats`() {
        val entry = QueuedSong(song(1, "First"))

        entry.status = QueueStatus.Working(DownloadStage.FetchingChart)
        entry.status = QueueStatus.Working(DownloadStage.WaitingForUsdb(24))
        entry.status = QueueStatus.Working(DownloadStage.WaitingForUsdb(1))
        val afterWait = entry.progress
        entry.status = QueueStatus.Working(DownloadStage.FetchingChart)

        assertTrue("the countdown must have moved it", afterWait > 0.5f)
        assertEquals(afterWait, entry.progress, 0f)
    }

    @Test
    fun `the countdown fills its own share of the bar as it runs`() {
        val entry = QueuedSong(song(1, "First"))
        entry.status = QueueStatus.Working(DownloadStage.WaitingForUsdb(24))
        val atStart = entry.progress
        entry.status = QueueStatus.Working(DownloadStage.WaitingForUsdb(12))
        val halfway = entry.progress
        entry.status = QueueStatus.Working(DownloadStage.WaitingForUsdb(1))

        assertTrue(atStart < halfway)
        assertTrue(halfway < entry.progress)
    }

    /**
     * USDB shortens the wait for people who upload songs, so the countdown is not always 24. The
     * fraction has to be measured against whatever it actually started at.
     */
    @Test
    fun `a shorter countdown still fills the same share`() {
        val long = QueuedSong(song(1, "First"))
        long.status = QueueStatus.Working(DownloadStage.WaitingForUsdb(24))
        long.status = QueueStatus.Working(DownloadStage.WaitingForUsdb(12))

        val short = QueuedSong(song(2, "Second"))
        short.status = QueueStatus.Working(DownloadStage.WaitingForUsdb(6))
        short.status = QueueStatus.Working(DownloadStage.WaitingForUsdb(3))

        assertEquals(long.progress, short.progress, 0.001f)
    }

    @Test
    fun `the stages run in order and end at the top`() {
        val entry = QueuedSong(song(1, "First"))
        val seen = mutableListOf<Float>()
        for (stage in listOf(
            DownloadStage.FindingAudio,
            DownloadStage.FetchingChart,
            DownloadStage.DownloadingAudio,
            DownloadStage.FetchingArtwork,
            DownloadStage.Saving,
            DownloadStage.DownloadingVideo(0),
            DownloadStage.DownloadingVideo(100),
        )) {
            entry.status = QueueStatus.Working(stage)
            seen += entry.progress
        }
        entry.status = QueueStatus.Done("An Artist - First")

        assertEquals("must only ever climb", seen.sorted(), seen)
        assertEquals(seen.distinct().size, seen.size)
        assertEquals(1f, entry.progress, 0f)
    }

    /** A failure is finished with. Leaving it part-filled stops the queue's bar ever completing. */
    @Test
    fun `a failed song counts as finished rather than stuck`() {
        val entry = QueuedSong(song(1, "First"))
        entry.status = QueueStatus.Working(DownloadStage.DownloadingAudio)
        entry.status = QueueStatus.Failed(DownloadProblem.AUDIO_UNAVAILABLE, "gone")

        assertEquals(1f, entry.progress, 0f)
    }

    @Test
    fun `the queue bar is each song's equal share`() {
        val queue = DownloadQueue()
        assertNull("nothing queued means no bar at all", queueProgress(queue))

        queue.add(song(1, "First"))
        queue.add(song(2, "Second"))
        assertEquals(0f, queueProgress(queue)!!, 0.001f)

        queue.entries[0].status = QueueStatus.Done("An Artist - First")
        assertEquals(0.5f, queueProgress(queue)!!, 0.001f)

        queue.entries[1].status = QueueStatus.Failed(DownloadProblem.NETWORK, "no internet")
        assertEquals(1f, queueProgress(queue)!!, 0.001f)
    }

    /** The row says a number; the sentence naming the step is said once, above the list. */
    @Test
    fun `a working row reads as a percentage`() {
        val entry = QueuedSong(song(1, "First"))
        entry.status = QueueStatus.Working(DownloadStage.WaitingForUsdb(24))
        entry.status = QueueStatus.Working(DownloadStage.WaitingForUsdb(12))

        assertTrue(shortStatusLabel(entry).endsWith("%"))
        assertEquals("Added", shortStatusLabel(QueuedSong(song(2, "S")).also {
            it.status = QueueStatus.Done("x")
        }))
    }

    // -----------------------------------------------------------------------------------------
    // One run at a time
    // -----------------------------------------------------------------------------------------

    /**
     * The same bug the repair queue has a test for: a bar that opens nearly full.
     *
     * Finished entries are kept on purpose — a row saying "Added" or "Try again" is the most useful
     * thing on the screen — but counting them into the *bar* meant a new download opened at nine
     * tenths, having counted every song already fetched as progress towards it.
     */
    @Test
    fun `a new run starts its bar at the beginning`() {
        val queue = DownloadQueue()
        repeat(5) { queue.add(song(it, "Old $it")) }
        queue.entries.forEach { it.status = QueueStatus.Done("An Artist - ${it.song.title}") }
        assertEquals(1f, queueProgress(queue)!!, 0.001f)

        queue.add(song(99, "New"))

        assertEquals(0f, queueProgress(queue)!!, 0.001f)
        assertEquals("only the new one is being waited for", 1, queue.current.size)
        assertEquals("New", queue.current.single().song.title)
    }

    /** And what has been added is counted for this run, not for the whole session. */
    @Test
    fun `what was saved is counted per run`() {
        val queue = DownloadQueue()
        queue.add(song(1, "Old"))
        queue.entries.first().status = QueueStatus.Done("An Artist - Old")
        assertEquals(1, queue.savedCount)

        queue.add(song(2, "New"))

        assertEquals(0, queue.savedCount)
    }

    /** Adding while a run is still going joins it rather than starting another. */
    @Test
    fun `adding to a busy queue joins the same run`() {
        val queue = DownloadQueue()
        queue.add(song(1, "First"))
        queue.add(song(2, "Second"))

        assertEquals(2, queue.current.size)

        queue.entries.first().status = QueueStatus.Done("An Artist - First")
        assertEquals(0.5f, queueProgress(queue)!!, 0.001f)
    }

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
