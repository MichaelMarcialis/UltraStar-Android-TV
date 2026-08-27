package com.example.ultrastarandroidtv.download

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.ultrastarandroidtv.usdb.UsdbSong
import kotlin.math.roundToInt

/** Where one queued song has got to. */
sealed interface QueueStatus {

    data object Queued : QueueStatus

    data class Working(val stage: DownloadStage) : QueueStatus

    data class Done(val folderName: String) : QueueStatus

    data class Failed(val problem: DownloadProblem, val message: String) : QueueStatus
}

/** One song someone asked for, and how it is going. */
class QueuedSong(
    val song: UsdbSong,
    /** Which run of the queue this was asked for in — see [DownloadQueue.current]. */
    val batch: Int = 0,
) {

    private var current: QueueStatus by mutableStateOf(QueueStatus.Queued)

    /**
     * The countdown USDB asked for, as first announced.
     *
     * Remembered because the stage only ever says how many seconds are *left*, and a fraction
     * needs to know what it is a fraction of. Taken as the largest ever seen rather than the
     * first, so it cannot be thrown off by arriving part-way through a countdown.
     */
    var waitSeconds: Int = 0
        private set

    /**
     * How far through this song's download we are, from 0 to 1.
     *
     * **Monotonic.** It is recomputed on every stage, and a couple of stages legitimately repeat —
     * "getting the song" is reported both before USDB's countdown and again to collect the file
     * afterwards. A bar that jumped back to 4% after sitting at 80% would read as the download
     * having restarted, so the highest point reached is the one kept.
     */
    var progress: Float by mutableStateOf(0f)
        private set

    var status: QueueStatus
        get() = current
        set(value) {
            (value as? QueueStatus.Working)?.stage?.let { stage ->
                if (stage is DownloadStage.WaitingForUsdb) {
                    waitSeconds = maxOf(waitSeconds, stage.secondsLeft)
                }
            }
            current = value
            progress = maxOf(progress, stageProgress(value, waitSeconds))
        }

    val isPending: Boolean get() = current is QueueStatus.Queued
    val isFinished: Boolean get() = current is QueueStatus.Done || current is QueueStatus.Failed
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

    private var batch = 0

    /**
     * The run happening now: what was asked for since the queue was last finished with.
     *
     * Finished entries are kept, because a row saying "Added" or "Try again" is the most
     * useful thing on the screen — but the *bar* has to be about the songs somebody is
     * waiting for. Averaged over the session it would open a new download at nine tenths
     * full, having counted every song already downloaded as progress towards it.
     */
    val current: List<QueuedSong> get() = _entries.filter { it.batch == batch }

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
        // Nothing left in flight means the last run is over, and this starts a new one.
        if (!isBusy) batch++
        _entries.add(QueuedSong(song, batch))
        return true
    }

    /** The next song to work on, or null when there is nothing waiting. */
    fun nextPending(): QueuedSong? = _entries.firstOrNull { it.isPending }

    /** True while anything is queued or in flight — what a "still working" line reads. */
    val isBusy: Boolean get() = _entries.any { !it.isFinished }

    val waitingCount: Int get() = _entries.count { it.isPending }

    val savedCount: Int get() = current.count { it.status is QueueStatus.Done }

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
    DownloadStage.FindingAnotherVersion -> "That version has gone — looking for another…"
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
 * The same, for a song in the queue, saying **one number** rather than naming the step.
 *
 * A row is not where the machinery belongs. Naming every stage in every row meant a list of songs
 * each counting down something different, which made the whole business look more precarious than
 * it is — the only question a row has to answer is how much longer. The step running right now,
 * USDB's countdown included, is said once above the list where there is room for a sentence.
 */
fun shortStatusLabel(entry: QueuedSong): String = when (entry.status) {
    is QueueStatus.Working -> "%d%%".format((entry.progress * 100).roundToInt())
    else -> shortStatusLabel(entry.status)
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
    val working = queue.current.any { it.status is QueueStatus.Working }
    return when {
        working && waiting > 0 -> "Downloading — $waiting more waiting" +
            if (saved > 0) ", $saved added" else ""
        working -> "Downloading" + if (saved > 0) " — $saved added" else ""
        saved > 0 -> "$saved ${if (saved == 1) "song" else "songs"} added. Rescan to see them."
        else -> null
    }
}

// ---------------------------------------------------------------------------------------------
// One bar instead of several countdowns
// ---------------------------------------------------------------------------------------------

/**
 * Roughly how long each step of a download takes, in seconds, measured on this device.
 *
 * These are **weights, not predictions**: what they buy is a bar that moves at a believable pace
 * rather than sitting still for half a minute and then leaping to done. The one that dominates is
 * USDB's throttle, and that is the honest shape of the thing — three quarters of a download is
 * waiting for permission to have it.
 *
 * Video used to dominate instead, at around a hundred seconds against everything else's handful.
 * Fetching media in bounded ranges took that to about three, which is why the shares here look
 * nothing like they would have a day ago; see
 * [com.example.ultrastarandroidtv.net.downloadInChunks].
 */
private const val PRECHECK_SECONDS = 2.0
private const val CHART_SECONDS = 25.0
private const val AUDIO_SECONDS = 1.0
private const val ARTWORK_SECONDS = 1.0
private const val SAVE_SECONDS = 1.0
private const val VIDEO_SECONDS = 3.0

private const val TOTAL_SECONDS =
    PRECHECK_SECONDS + CHART_SECONDS + AUDIO_SECONDS + ARTWORK_SECONDS + SAVE_SECONDS + VIDEO_SECONDS

/**
 * Where in the whole download a stage sits, from 0 to 1.
 *
 * **One number rather than a run of separate countdowns.** Downloading a song is six steps, two of
 * which report their own progress, and showing each of them in turn made the process look longer
 * and more precarious than it is — a great deal of machinery on display for something whose only
 * interesting question is "how much longer". The stages still have names, and the caption still
 * says which one is running and how long USDB is making everybody wait, because a bar that stalls
 * three quarters of the way through with no explanation is worse than no bar. But there is one
 * bar, and it only goes forwards.
 *
 * @param waitSeconds how long USDB's countdown was when it started, or 0 if it has not begun.
 */
fun stageProgress(status: QueueStatus, waitSeconds: Int): Float = when (status) {
    QueueStatus.Queued -> 0f
    // Both endings fill the bar. A failure is finished with, and a bar left stuck at 40% for a
    // song nobody is waiting for any more would stop the queue's own bar ever reaching the end.
    is QueueStatus.Done -> 1f
    is QueueStatus.Failed -> 1f
    is QueueStatus.Working -> workingProgress(status.stage, waitSeconds)
}

private fun workingProgress(stage: DownloadStage, waitSeconds: Int): Float {
    var before = 0.0

    fun slice(seconds: Double, within: Double = 0.0): Float =
        ((before + seconds * within) / TOTAL_SECONDS).toFloat().coerceIn(0f, 1f)

    if (stage == DownloadStage.FindingAudio) return slice(PRECHECK_SECONDS)
    // Searching for a replacement happens *instead of* getting on with it, so it sits
    // where the pre-check does rather than adding a share of its own: the bar holds
    // still while the caption explains, which is the honest picture of what is going on.
    if (stage == DownloadStage.FindingAnotherVersion) return slice(PRECHECK_SECONDS)
    before += PRECHECK_SECONDS

    if (stage == DownloadStage.FetchingChart) return slice(CHART_SECONDS)
    if (stage is DownloadStage.WaitingForUsdb) {
        val elapsed = if (waitSeconds <= 0) 0.0
        else (waitSeconds - stage.secondsLeft).toDouble() / waitSeconds
        return slice(CHART_SECONDS, elapsed.coerceIn(0.0, 1.0))
    }
    before += CHART_SECONDS

    if (stage == DownloadStage.DownloadingAudio) return slice(AUDIO_SECONDS)
    before += AUDIO_SECONDS

    if (stage == DownloadStage.FetchingArtwork) return slice(ARTWORK_SECONDS)
    before += ARTWORK_SECONDS

    if (stage == DownloadStage.Saving) return slice(SAVE_SECONDS)
    before += SAVE_SECONDS

    val within = (stage as? DownloadStage.DownloadingVideo)?.percent?.div(100.0) ?: 0.0
    return slice(VIDEO_SECONDS, within)
}

/**
 * How far through the *whole queue* everything is, from 0 to 1, or null when there is nothing on.
 *
 * Counts each song as an equal share, because from the sofa they are: nobody picking five songs
 * cares that one of them has a longer video. Finished songs count whole, whether they were saved
 * or failed — a failure is finished with, and leaving it stuck at 40% would keep a bar from ever
 * reaching the end for a song nobody is waiting for any more.
 */
fun queueProgress(queue: DownloadQueue): Float? {
    val entries = queue.current
    if (entries.isEmpty()) return null
    val done = entries.sumOf { if (it.isFinished) 1.0 else it.progress.toDouble() }
    return (done / entries.size).toFloat().coerceIn(0f, 1f)
}
