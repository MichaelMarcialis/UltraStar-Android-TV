package com.example.ultrastarandroidtv.download

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.ultrastarandroidtv.usdb.UsdbSong

/** Where one queued song has got to. */
sealed interface QueueStatus {

    data object Queued : QueueStatus

    data class Working(val stage: DownloadStage) : QueueStatus

    data class Done(val folderName: String) : QueueStatus

    data class Failed(val problem: DownloadProblem, val message: String) : QueueStatus
}

/** One song someone asked for, and how it is going. */
class QueuedSong(val song: UsdbSong) {
    var status: QueueStatus by mutableStateOf(QueueStatus.Queued)

    val isPending: Boolean get() = status is QueueStatus.Queued
    val isFinished: Boolean get() = status is QueueStatus.Done || status is QueueStatus.Failed
}

/**
 * Songs waiting to be downloaded, oldest first.
 *
 * **A queue rather than one download at a time, because USDB's throttle makes it necessary.** A
 * song costs about half a minute of waiting, so making somebody sit and watch each one before
 * choosing the next would turn picking five songs into a five-minute stare. Queueing lets the
 * choosing and the waiting happen at once, which is the only arrangement where the throttle stops
 * being the thing you are doing.
 *
 * **One at a time, always.** Running downloads in parallel would defeat the wait rather than
 * respect it, which is the fastest way to get a user's USDB account blocked — see [SongDownloader]
 * for why that is somebody else's problem to suffer and ours to avoid causing.
 *
 * Deliberately keeps finished entries. A song that failed is the one thing on the screen worth
 * reading — "that version is not available, try another" — and clearing it the moment it happened
 * would leave somebody wondering whether they had pressed the button at all.
 */
class DownloadQueue {

    private val _entries = mutableStateListOf<QueuedSong>()

    /** Everything asked for this session, in the order it was asked for. */
    val entries: List<QueuedSong> get() = _entries

    /**
     * Adds [song] unless it is already here.
     *
     * Returns false when it was already queued, which the screen shows rather than silently
     * ignoring: pressing a song twice and seeing nothing happen reads as a broken button.
     * A song that *failed* can be asked for again — that is a retry, and a reasonable thing to
     * want after a network came back.
     */
    fun add(song: UsdbSong): Boolean {
        val existing = _entries.indexOfFirst { it.song.songId == song.songId }
        if (existing >= 0) {
            val entry = _entries[existing]
            if (entry.status !is QueueStatus.Failed) return false
            _entries.removeAt(existing)
        }
        _entries.add(QueuedSong(song))
        return true
    }

    /** The next song to work on, or null when there is nothing waiting. */
    fun nextPending(): QueuedSong? = _entries.firstOrNull { it.isPending }

    /** True while anything is queued or in flight — what a "still working" line reads. */
    val isBusy: Boolean get() = _entries.any { !it.isFinished }

    val waitingCount: Int get() = _entries.count { it.isPending }

    val savedCount: Int get() = _entries.count { it.status is QueueStatus.Done }

    /** Forgets everything that has finished, leaving anything still in flight alone. */
    fun clearFinished() {
        _entries.removeAll { it.isFinished }
    }

    /** True when [songId] is already here and did not fail — what greys a button out. */
    fun holds(songId: Int): Boolean =
        _entries.any { it.song.songId == songId && it.status !is QueueStatus.Failed }
}

// ---------------------------------------------------------------------------------------------
// The words, as free functions: pure, and so testable without a screen.
// ---------------------------------------------------------------------------------------------

/**
 * What a download is doing, in words worth reading from a sofa.
 *
 * **The wait counts down out loud.** It is by far the longest part — around 24 seconds against a
 * second or two for everything else — and USDB asks for it on purpose. A spinner here would read
 * as a hang, and the honest version is also the more reassuring one: a number going down is
 * visibly not stuck. It says *who* is waiting and why, because "waiting" with no subject invites
 * the reading that the app is broken.
 */
fun stageLabel(stage: DownloadStage): String = when (stage) {
    is DownloadStage.WaitingForUsdb -> "USDB asks us to wait — ${stage.secondsLeft}s"
    DownloadStage.FetchingChart -> "Getting the song…"
    DownloadStage.FindingAudio -> "Finding the music…"
    DownloadStage.DownloadingAudio -> "Downloading the music…"
    DownloadStage.FetchingArtwork -> "Getting the artwork…"
    DownloadStage.Saving -> "Saving to the card…"
    is DownloadStage.DownloadingVideo -> "Video ${stage.percent}%"
}

/** One queued song's state, in the same voice. Failures speak for themselves, at length. */
fun statusLabel(status: QueueStatus): String = when (status) {
    QueueStatus.Queued -> "Waiting its turn"
    is QueueStatus.Working -> stageLabel(status.stage)
    is QueueStatus.Done -> "Added to your songs"
    is QueueStatus.Failed -> status.message
}

/**
 * The same state in a few words, for a fixed slot at the end of a row.
 *
 * **A failure's own sentence must not go here**, which is the whole point of this existing next to
 * [statusLabel]. Those sentences run to a couple of hundred characters -- and a network error once
 * carried four hundred characters of an HTML error page -- while the slot they were being drawn
 * in had no width limit. The long text took the row's whole width, the song's title was left with
 * none, and the title then wrapped to *one character per line*: a single result grew to the full
 * height of the television, shaped like a dome, with the reason unreadable inside it. Reported
 * from the sofa, and reproduced from a recording of the screen.
 *
 * So the row says "Try again" here and puts the reason where a sentence fits: on the second line,
 * in place of the song's details, where it has the width of the row and a two-line cap.
 */
fun shortStatusLabel(status: QueueStatus): String = when (status) {
    QueueStatus.Queued -> "Waiting its turn"
    is QueueStatus.Working -> stageLabel(status.stage)
    is QueueStatus.Done -> "Added"
    is QueueStatus.Failed -> "Try again"
}

/**
 * A line summarising the whole queue, or null when there is nothing to say.
 *
 * Saying how many are left is what makes a queue feel finite. "Downloading 2 more" is a thing
 * somebody can decide to wait for; a list of spinners is not.
 */
fun queueSummary(queue: DownloadQueue): String? {
    val saved = queue.savedCount
    val waiting = queue.waitingCount
    val working = queue.entries.any { it.status is QueueStatus.Working }
    return when {
        working && waiting > 0 -> "Downloading — $waiting more waiting" +
            if (saved > 0) ", $saved added" else ""
        working -> "Downloading" + if (saved > 0) " — $saved added" else ""
        saved > 0 -> "$saved ${if (saved == 1) "song" else "songs"} added. Rescan to see them."
        else -> null
    }
}
