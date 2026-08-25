package com.example.ultrastarandroidtv.download

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.ultrastarandroidtv.library.ScannedSong

/** Where a repair has got to. Shorter than a download's list, because there is no USDB in it. */
sealed interface RepairStage {

    /** Asking YouTube whether the media is still there. A second or two. */
    data object Looking : RepairStage

    data object Music : RepairStage

    data object Artwork : RepairStage

    data class Video(val percent: Int) : RepairStage
}

sealed interface RepairStatus {

    data object Queued : RepairStatus

    data class Working(val stage: RepairStage) : RepairStatus

    data class Done(val summary: String) : RepairStatus

    data class Failed(val message: String) : RepairStatus
}

/** One song somebody asked to have filled in, and how it is going. */
class RepairJob(val song: ScannedSong, val plan: RepairPlan) {

    private var current: RepairStatus by mutableStateOf(RepairStatus.Queued)

    var progress: Float by mutableStateOf(0f)
        private set

    var status: RepairStatus
        get() = current
        set(value) {
            current = value
            progress = maxOf(progress, repairProgress(value, plan))
        }

    val isPending: Boolean get() = current is RepairStatus.Queued
    val isFinished: Boolean
        get() = current is RepairStatus.Done || current is RepairStatus.Failed
}

/**
 * Songs waiting to be filled in.
 *
 * A separate queue from [DownloadQueue] rather than a shared one, because the two jobs have almost
 * nothing in common beyond being slow: a download is a USDB song that does not exist here yet and
 * spends most of its life waiting for permission, a repair is a song already on the card that
 * needs one file and no account. Folding them together would mean a status type that is half
 * meaningless whichever kind of job it describes.
 *
 * They *are* worked by the same loop, one at a time, and held for the same reasons — see
 * [Downloads].
 */
class RepairQueue {

    private val _jobs = mutableStateListOf<RepairJob>()

    val jobs: List<RepairJob> get() = _jobs

    /** Adds a job unless the same song is already here and has not failed. Retries are allowed. */
    fun add(song: ScannedSong, plan: RepairPlan): Boolean {
        val existing = _jobs.indexOfFirst { it.song.textId == song.textId }
        if (existing >= 0) {
            if (_jobs[existing].status !is RepairStatus.Failed) return false
            _jobs.removeAt(existing)
        }
        _jobs.add(RepairJob(song, plan))
        return true
    }

    fun holds(textId: String): Boolean =
        _jobs.any { it.song.textId == textId && it.status !is RepairStatus.Failed }

    fun nextPending(): RepairJob? = _jobs.firstOrNull { it.isPending }

    val isBusy: Boolean get() = _jobs.any { !it.isFinished }

    val waitingCount: Int get() = _jobs.count { it.isPending }

    val fixedCount: Int get() = _jobs.count { it.status is RepairStatus.Done }

    fun clearFinished() {
        _jobs.removeAll { it.isFinished }
    }
}

// ---------------------------------------------------------------------------------------------
// The words and the numbers, as free functions: pure, and so testable without a screen
// ---------------------------------------------------------------------------------------------

/**
 * Roughly how long each part of a repair takes, in seconds.
 *
 * Its own weights rather than a download's, because the shapes are nothing alike. A download is
 * three quarters USDB's throttle; a repair has no throttle at all, so the media *is* the job — and
 * which media is missing changes the answer, which is why the plan is part of the sum.
 */
private const val LOOKUP_SECONDS = 2.0
private const val MUSIC_SECONDS = 3.0
private const val ARTWORK_SECONDS = 1.0
private const val VIDEO_SECONDS = 4.0

/** How far through a repair a stage sits, from 0 to 1, given what this one has to fetch. */
fun repairProgress(status: RepairStatus, plan: RepairPlan): Float {
    val music = if (plan.needsAudio) MUSIC_SECONDS else 0.0
    val artwork = if (plan.needsCover) ARTWORK_SECONDS else 0.0
    val video = if (plan.needsVideo) VIDEO_SECONDS else 0.0
    val total = LOOKUP_SECONDS + music + artwork + video

    return when (status) {
        RepairStatus.Queued -> 0f
        is RepairStatus.Done -> 1f
        is RepairStatus.Failed -> 1f
        is RepairStatus.Working -> when (val stage = status.stage) {
            RepairStage.Looking -> 0f
            RepairStage.Music -> (LOOKUP_SECONDS / total).toFloat()
            RepairStage.Artwork -> ((LOOKUP_SECONDS + music) / total).toFloat()
            is RepairStage.Video ->
                ((LOOKUP_SECONDS + music + artwork + video * stage.percent / 100.0) / total)
                    .toFloat()
        }
    }.coerceIn(0f, 1f)
}

fun repairStageLabel(stage: RepairStage): String = when (stage) {
    RepairStage.Looking -> "Finding the music…"
    RepairStage.Music -> "Downloading the music…"
    RepairStage.Artwork -> "Getting the artwork…"
    is RepairStage.Video -> "Video ${stage.percent}%"
}

fun repairStatusLabel(status: RepairStatus): String = when (status) {
    RepairStatus.Queued -> "Waiting its turn"
    is RepairStatus.Working -> repairStageLabel(status.stage)
    is RepairStatus.Done -> status.summary
    is RepairStatus.Failed -> status.message
}

/** How far through the whole repair queue, or null when there is nothing to fix. */
fun repairQueueProgress(queue: RepairQueue): Float? {
    val jobs = queue.jobs
    if (jobs.isEmpty()) return null
    val done = jobs.sumOf { if (it.isFinished) 1.0 else it.progress.toDouble() }
    return (done / jobs.size).toFloat().coerceIn(0f, 1f)
}

/**
 * A line summarising the queue, or null when there is nothing to say.
 *
 * Says how many are left, for the same reason the download queue does: a number somebody can
 * decide to wait for beats a spinner that might mean anything.
 */
fun repairSummary(queue: RepairQueue): String? {
    val fixed = queue.fixedCount
    val waiting = queue.waitingCount
    val working = queue.jobs.any { it.status is RepairStatus.Working }
    return when {
        working && waiting > 0 -> "Repairing — $waiting more waiting" +
            if (fixed > 0) ", $fixed fixed" else ""
        working -> "Repairing" + if (fixed > 0) " — $fixed fixed" else ""
        fixed > 0 -> "$fixed ${if (fixed == 1) "song" else "songs"} repaired. Rescan to see them."
        else -> null
    }
}
