package com.example.ultrastarandroidtv.download

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.ultrastarandroidtv.library.CoverLoader
import com.example.ultrastarandroidtv.library.LibraryLocation
import com.example.ultrastarandroidtv.library.SafDocumentTree
import com.example.ultrastarandroidtv.library.SongLibraryCache
import com.example.ultrastarandroidtv.net.ITunesArtwork
import com.example.ultrastarandroidtv.net.UrlHttp
import com.example.ultrastarandroidtv.net.YouTubeAudio
import com.example.ultrastarandroidtv.usdb.UsdbCharts
import com.example.ultrastarandroidtv.usdb.UsdbDetails
import com.example.ultrastarandroidtv.usdb.UsdbSearch
import com.example.ultrastarandroidtv.usdb.UsdbSession
import com.example.ultrastarandroidtv.usdb.UsdbSong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** How often the worker looks for something to do when the queue is empty or held. */
private const val IDLE_POLL_MS = 250L

/** How long a finished download stays on screen before the notice fades of its own accord. */
const val ANNOUNCEMENT_SECONDS = 6

/**
 * Something worth telling the room about, wherever they happen to be in the app.
 *
 * Deliberately one at a time and deliberately short-lived: a download finishing is news for a few
 * seconds and clutter after that, and a stack of them growing down the side of the song picker
 * would be worse than saying nothing.
 */
data class Announcement(val title: String, val detail: String, val good: Boolean)

/**
 * Downloading, for the whole life of the app rather than the life of a screen.
 *
 * ## Why this is not inside the Add-songs screen any more
 *
 * It was, and it meant **leaving the screen killed the download**. The worker was a
 * `LaunchedEffect` in the composable, so pressing Back part-way through a song — or simply going
 * to look at the library while waiting — cancelled it mid-transfer and lost USDB's throttle wait
 * with it. Queueing five songs only helps if you can then go and do something else, which is the
 * entire reason the queue exists.
 *
 * So the queue, the network stack and the USDB session all live here, created once in `AppRoot`
 * beside [com.example.ultrastarandroidtv.mic.UsbMicSession] and for the same reason. The session
 * outliving the screen is a bonus worth having on its own: signing in is no longer undone by
 * walking away from the search results.
 *
 * ## One at a time, and not while anybody is singing
 *
 * Never more than one download at once — see [DownloadQueue] for why parallel downloads would
 * defeat USDB's throttle rather than respect it. On top of that the worker holds off entirely
 * while [paused] is set, which `AppRoot` does for the whole of a song: gameplay already costs
 * 52-55% of a core with video, and a download is network, decompression and a burst of writes to
 * the same card the song is streaming from.
 *
 * The hold is taken at stage boundaries rather than mid-transfer, so nothing is ever parked
 * holding an open socket that will time out while a three-minute song plays. In practice that
 * means a download in flight finishes its current step — a couple of seconds now that media is
 * fetched in bounded ranges — and then waits.
 */
class Downloads(private val context: Context) {

    /** Shared with the screen: covers and samples come off the same client. */
    val http = UrlHttp()

    /** Shared with the screen so signing in, searching and downloading are one session. */
    val session = UsdbSession(http)
    val search = UsdbSearch(session)
    val details = UsdbDetails(session)
    val youTube = YouTubeAudio(http)

    /** Shared with the Songs screen, which repairs a song without going near USDB. */
    val artwork = ITunesArtwork(http)

    val queue = DownloadQueue()

    /** Songs already on the card waiting to have a missing file filled in. */
    val repairs = RepairQueue()

    /**
     * Set while a song is being sung. Stops the *next* step of a download from starting; see the
     * class note for why it is not an abort.
     */
    var paused: Boolean by mutableStateOf(false)

    /** The latest finished download nobody has been shown yet, or null. */
    var announcement: Announcement? by mutableStateOf(null)
        private set

    private val _downloaded = mutableStateListOf<String>()

    /**
     * Folder names saved this session, lowercased.
     *
     * Kept because a successful download makes the library scan out of date, so the screen cannot
     * simply ask the cache whether a song is already owned — it would have to rescan the card to
     * find out, in the middle of somebody choosing the next song.
     */
    val downloaded: List<String> get() = _downloaded

    fun dismissAnnouncement() {
        announcement = null
    }

    /**
     * Works the queue for as long as the app is running. Never returns.
     *
     * Takes the cache rather than reaching for one, so the same scan the rest of the app is using
     * is the one marked out of date when a song lands.
     */
    suspend fun work(cache: SongLibraryCache) {
        while (true) {
            if (paused) {
                delay(IDLE_POLL_MS)
                continue
            }
            // Downloads first. A repair is quick and needs nobody's permission, so it loses
            // nothing by waiting; a download is holding USDB's countdown and would have to start
            // it again.
            val download = queue.nextPending()
            if (download != null) {
                download.status = QueueStatus.Working(DownloadStage.FetchingChart)
                download.status = runOne(download)
                announce(download)
                if (download.status is QueueStatus.Done) cache.markStale()
                continue
            }

            val repair = repairs.nextPending()
            if (repair == null) {
                delay(IDLE_POLL_MS)
                continue
            }
            repair.status = RepairStatus.Working(RepairStage.Looking)
            repair.status = runRepair(repair)
            announceRepair(repair)
            if (repair.status is RepairStatus.Done) cache.markStale()
        }
    }

    private suspend fun runRepair(job: RepairJob): RepairStatus {
        val card = card() ?: return RepairStatus.Failed(
            "Choose your song folder again on the Songs screen before repairing.",
        )
        val repairer = SongRepairer(
            youTube = youTube,
            artwork = artwork,
            http = http,
            tree = card,
            writer = card,
            measureCover = CoverLoader::shortestEdge,
        )
        val outcome = runCatching {
            withContext(Dispatchers.IO) {
                repairer.repair(job.song, job.plan) { stage ->
                    job.status = RepairStatus.Working(stage)
                    holdWhileSinging(stage !is RepairStage.Video || stage.percent == 0)
                }
            }
        }.getOrElse {
            RepairOutcome.Failed(
                DownloadProblem.NETWORK,
                "The repair could not reach the internet.",
            )
        }
        return when (outcome) {
            is RepairOutcome.Repaired -> RepairStatus.Done(outcome.summary)
            is RepairOutcome.Failed -> {
                if (outcome.problem == DownloadProblem.AUDIO_UNAVAILABLE) {
                    replaceFromUsdb(job, card) ?: RepairStatus.Failed(outcome.message)
                } else {
                    RepairStatus.Failed(outcome.message)
                }
            }
        }
    }

    /**
     * When a song's music has gone for good, fetches a different chart of the same song instead.
     *
     * ## Why this is a replacement and not a repair
     *
     * A repair puts the missing media back beside **this** chart, and that only works because the
     * chart was written against that exact recording — its `#GAP` and every beat in it are timed to
     * one upload. Another chart of the same song is another *pair*: different notes, different
     * timing, its own audio. Pouring one chart's audio into another's notes produces a song that is
     * silently out of time, which is the Magic Dance failure this project already has a diagnostic
     * tool for. So the whole folder is swapped, not the file.
     *
     * ## Order, and why nothing is deleted first
     *
     * The replacement is downloaded **before** the original is removed, and a candidate whose
     * folder would have the same name is not considered at all. A song that has already lost its
     * music is not worth much, but it is worth more than an empty folder, and deleting first would
     * mean a failed download left nothing.
     *
     * Needs the USDB session, so it only runs when somebody has signed in on the Add-songs screen.
     * Returns null when there is nothing to be done, so the caller can report the original failure
     * — which is the one worth reading.
     */
    private suspend fun replaceFromUsdb(job: RepairJob, card: SafDocumentTree): RepairStatus? {
        if (!session.hasSession) return null

        val metadata = job.song.song.metadata
        job.status = RepairStatus.Working(RepairStage.Looking)

        val other = withContext(Dispatchers.IO) {
            AlternateVersions(search, details, youTube).findFor(
                artist = metadata.artist,
                title = metadata.title,
                alreadyHave = ownedFolders(),
                differentFolderFrom = job.song.folderName,
            )
        } ?: return null

        val downloader = SongDownloader(
            charts = UsdbCharts(session),
            details = details,
            youTube = youTube,
            artwork = artwork,
            http = http,
            tree = card,
            writer = card,
        )
        val outcome = runCatching {
            withContext(Dispatchers.IO) {
                downloader.download(other) { stage ->
                    job.status = RepairStatus.Working(
                        when (stage) {
                            is DownloadStage.DownloadingVideo -> RepairStage.Video(stage.percent)
                            DownloadStage.FetchingArtwork -> RepairStage.Artwork
                            else -> RepairStage.Music
                        },
                    )
                    holdWhilePaused(stage)
                }
            }
        }.getOrNull()

        if (outcome !is DownloadOutcome.Saved) return null

        // The old folder goes only now, with a working song already on the card in its place. A
        // folder holding a second arrangement loses just its own chart, the same rule the Songs
        // screen follows when removing a song by hand.
        val sharing = runCatching {
            card.list(job.song.folderId).count { it.name.endsWith(".txt", ignoreCase = true) }
        }.getOrDefault(1)
        card.delete(if (sharing > 1) job.song.textId else job.song.folderId)

        _downloaded.add(outcome.folderName.lowercase())
        return RepairStatus.Done("Its music had gone — replaced with ${other.artist}'s version")
    }

    private suspend fun runOne(entry: QueuedSong): QueueStatus {
        val card = card() ?: return QueueStatus.Failed(
            DownloadProblem.COULD_NOT_WRITE,
            "Choose your song folder again on the Songs screen before downloading.",
        )

        val downloader = SongDownloader(
            charts = UsdbCharts(session),
            details = details,
            youTube = youTube,
            artwork = artwork,
            http = http,
            tree = card,
            writer = card,
        )

        val first = attempt(downloader, entry, entry.song)
        if (first is DownloadOutcome.Saved) return saved(first)
        val failure = first as DownloadOutcome.Failed

        // Only worth a second look when the *music* is what was missing. A song already on the
        // card, a folder that will not write, or no internet are all facts about this machine,
        // and another chart of the same song would fail them in exactly the same way.
        if (failure.problem != DownloadProblem.AUDIO_UNAVAILABLE) {
            return QueueStatus.Failed(failure.problem, failure.message)
        }

        entry.status = QueueStatus.Working(DownloadStage.FindingAnotherVersion)
        val other = withContext(Dispatchers.IO) {
            AlternateVersions(search, details, youTube)
                .find(entry.song, _downloaded.toSet() + ownedFolders())
        } ?: return QueueStatus.Failed(failure.problem, failure.message)

        val second = attempt(downloader, entry, other)
        return when (second) {
            is DownloadOutcome.Saved -> saved(second)
            // The *first* failure is the one worth reporting. It is about the song somebody
            // actually chose, and "we also tried another one" is not something they asked for.
            is DownloadOutcome.Failed -> QueueStatus.Failed(failure.problem, failure.message)
        }
    }

    private suspend fun attempt(
        downloader: SongDownloader,
        entry: QueuedSong,
        song: UsdbSong,
    ): DownloadOutcome = runCatching {
        withContext(Dispatchers.IO) {
            downloader.download(song) { stage ->
                entry.status = QueueStatus.Working(stage)
                holdWhilePaused(stage)
            }
        }
    }.getOrElse {
        DownloadOutcome.Failed(
            DownloadProblem.NETWORK,
            "The download could not reach the internet.",
        )
    }

    private fun saved(outcome: DownloadOutcome.Saved): QueueStatus {
        _downloaded.add(outcome.folderName.lowercase())
        return QueueStatus.Done(outcome.folderName)
    }

    /** Folder names on the card, lowercased, so a replacement never collides with one. */
    private fun ownedFolders(): Set<String> = runCatching {
        val card = card() ?: return emptySet()
        card.list(card.rootId).filter { it.isDirectory }.map { it.name.lowercase() }.toSet()
    }.getOrDefault(emptySet())

    /**
     * Parks a download between steps while somebody is singing.
     *
     * Blocking rather than suspending because this is called from inside the downloader, which is
     * ordinary synchronous code on the IO dispatcher — the same place USDB's own throttle already
     * sleeps.
     *
     * **Never during a transfer.** A video reports its progress every buffer, and parking on one
     * of those would leave an HTTP connection open across a whole song and time out. The first
     * report of the video stage is a real boundary and is honoured; the rest are not.
     */
    private fun holdWhilePaused(stage: DownloadStage) {
        holdWhileSinging(stage !is DownloadStage.DownloadingVideo || stage.percent == 0)
    }

    private fun holdWhileSinging(atABoundary: Boolean) {
        if (!atABoundary) return
        while (paused) Thread.sleep(IDLE_POLL_MS)
    }

    private fun announceRepair(job: RepairJob) {
        val meta = job.song.song.metadata
        val name = "${meta.artist} — ${meta.title}".trim(' ', '—').ifBlank { job.song.folderName }
        announcement = when (val status = job.status) {
            is RepairStatus.Done -> Announcement(name, status.summary, good = true)
            is RepairStatus.Failed -> Announcement(name, status.message, good = false)
            else -> return
        }
    }

    private fun announce(entry: QueuedSong) {
        val name = "${entry.song.artist} — ${entry.song.title}".trim(' ', '—')
        announcement = when (val status = entry.status) {
            is QueueStatus.Done -> Announcement(name, "Added to your songs", good = true)
            is QueueStatus.Failed -> Announcement(name, status.message, good = false)
            else -> return
        }
    }

    /**
     * The card, or null when no writable folder has been granted.
     *
     * Resolved per download rather than held, so choosing a different folder part-way through an
     * evening is picked up without anything having to be told about it.
     */
    private fun card(): SafDocumentTree? {
        val location = LibraryLocation(context)
        if (!location.canModify()) return null
        val uri = location.saved() ?: return null
        return SafDocumentTree(context.contentResolver, uri)
    }
}
