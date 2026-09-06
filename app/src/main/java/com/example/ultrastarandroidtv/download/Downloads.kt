package com.example.ultrastarandroidtv.download

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.ultrastarandroidtv.audio.ChromaScanner
import com.example.ultrastarandroidtv.library.LibraryLocation
import com.example.ultrastarandroidtv.library.SafDocumentTree
import com.example.ultrastarandroidtv.library.ScannedSong
import com.example.ultrastarandroidtv.library.SongLibraryCache
import com.example.ultrastarandroidtv.library.SongTextDecoder
import com.example.ultrastarandroidtv.song.SyncVerdict
import com.example.ultrastarandroidtv.song.UltraStarSongParser
import com.example.ultrastarandroidtv.song.checkSync
import com.example.ultrastarandroidtv.song.shiftGap
import com.example.ultrastarandroidtv.net.ITunesArtwork
import com.example.ultrastarandroidtv.net.UrlHttp
import com.example.ultrastarandroidtv.net.YouTubeAudio
import com.example.ultrastarandroidtv.usdb.UsdbCharts
import com.example.ultrastarandroidtv.usdb.UsdbDetails
import com.example.ultrastarandroidtv.usdb.UsdbSearch
import com.example.ultrastarandroidtv.usdb.UsdbSession
import com.example.ultrastarandroidtv.usdb.UsdbSong
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** How often the worker looks for something to do when the queue is empty or held. */
private const val IDLE_POLL_MS = 250L

/** Where a timing sweep reports itself, so it can be followed from `adb` instead of the sofa. */
private const val TIMING_TAG = "Timing"

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

    /**
     * Whether each USDB song's music can actually be fetched, by song id.
     *
     * Kept here rather than on the Add-songs screen so a verdict is worked out **once**: the check
     * costs a USDB detail page and a YouTube lookup, and searching for the same band twice in an
     * evening used to pay for both again. Absent means "not asked yet"; a check that fails for any
     * other reason records nothing at all, because a network blip must not label a good song broken.
     *
     * Deliberately **not** written to disk. A video can be taken down or restored between sessions,
     * and a stored "unavailable" would go on refusing a song that came back — which is the one
     * error nobody would think to look for. Living as long as the app is enough to stop the same
     * question being asked twice while somebody is browsing.
     */
    val availability = mutableStateMapOf<Int, Boolean>()

    /** Shared with the Songs screen, which repairs a song without going near USDB. */
    val artwork = ITunesArtwork(http)

    val queue = DownloadQueue()

    /** Songs already on the card waiting to have a missing file filled in. */
    val repairs = RepairQueue()

    /**
     * Repairs already tried and found fruitless, so the same seven songs are not offered for
     * ever. See [RepairMemory] — it is applied to the batch only, never to a song's own button.
     */
    val repairMemory = RepairMemory(context)

    /** What listening to a song found, kept so it need not be listened to twice. */
    val timing = TimingMemory(context)

    /**
     * Where the timing sweep runs.
     *
     * Its own scope rather than the `work` loop everything else uses, because this is not a
     * queue: it is a job somebody starts deliberately about one song, it must not hold up a
     * download behind it, and it has to outlive the screen that started it.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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
                // One song, finished: worth telling a screen about immediately.
                if (download.status is QueueStatus.Done) cache.markChanged()
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
            // A repair *run* publishes once, at the end. Repairing twenty-three songs and
            // announcing each would rescan the whole card twenty-three times -- five seconds
            // apiece, alongside the writes still going on.
            if (repair.status is RepairStatus.Done) {
                if (repairs.isBusy) cache.markStale() else cache.markChanged()
            }
        }
    }

    private suspend fun runRepair(job: RepairJob): RepairStatus {
        // The ids in this job describe one tree, and only that tree. Somebody changing the
        // song folder while a batch is queued would otherwise have the rest of it read and
        // write ids that mean nothing here -- or, where the same relative path exists under
        // both grants, mean somebody else's song.
        val here = cardUri()?.toString()
        if (job.tree != null && job.tree != here) {
            return RepairStatus.Failed(
                "The song folder changed while this was waiting. Try repairing it again.",
            )
        }

        val card = card() ?: return RepairStatus.Failed(
            "Choose your song folder again on the Songs screen before repairing.",
        )
        val repairer = SongRepairer(
            youTube = youTube,
            artwork = artwork,
            http = http,
            tree = card,
            writer = card,
            sync = syncFor(card),
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
            is RepairOutcome.Repaired -> {
                // Something arrived, so whatever was remembered about this song is out of date.
                repairMemory.forget(job.song.textId)
                RepairStatus.Done(outcome.summary)
            }
            is RepairOutcome.Failed -> {
                // Asked, and there was nothing to be had. Worth remembering: the survey cannot
                // tell in advance whether a better cover exists, so without this the song is
                // counted into "Repair N songs" again on the very next look at the card.
                if (outcome.problem == DownloadProblem.NOTHING_FETCHED) {
                    repairMemory.rememberNothing(job.song.textId, job.plan)
                }
                // Only when the *music* was the thing that could not be got. A video-only
                // repair fails with the same reason when its upload has gone -- and swapping
                // in a different chart there would delete a song that plays perfectly well,
                // because an optional video could not be fetched.
                if (outcome.problem == DownloadProblem.AUDIO_UNAVAILABLE && job.plan.needsAudio) {
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
            sync = syncFor(card),
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
        // The whole folder goes only when it is *known* to hold this song alone.
        //
        // A folder can hold a second arrangement beside the original, and a listing that
        // fails tells us nothing about which case this is. Defaulting an unknown count to
        // one took the destructive branch on no evidence: a transient read error would have
        // deleted somebody's duet arrangement along with the song being replaced. Unknown is
        // treated as shared, so the worst a bad listing can do is leave a chart behind.
        val charts = runCatching {
            card.list(job.song.folderId).count { it.name.endsWith(".txt", ignoreCase = true) }
        }.getOrNull()
        val removed = card.delete(if (charts == 1) job.song.folderId else job.song.textId)

        _downloaded.add(outcome.folderName.lowercase())

        // The delete can fail -- a card pulled out, a provider in a mood -- and saying
        // "replaced" then would be a lie with consequences: both songs are in the library,
        // one of them silent, and the picker shows them side by side. The new song is real
        // either way, so this is still a success; it just has to say what is actually there.
        return if (removed) {
            RepairStatus.Done("Its music had gone — replaced with ${other.artist}'s version")
        } else {
            RepairStatus.Done(
                "Added ${other.artist}'s version — the old one is still there and can be " +
                    "removed from the Songs screen",
            )
        }
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
            sync = syncFor(card),
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
        return QueueStatus.Done(outcome.folderName, outcome.timingNote)
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

    /**
     * Listens to one song and puts its timing right, if that is what it needs.
     *
     * About fifteen seconds: a decode of the whole recording and a transform over it. Deliberately
     * one song rather than a library — see [TimingMemory] for why the sweep this replaces was the
     * wrong shape. It still runs on this class's own scope and still honours [paused], because
     * fifteen seconds is long enough to walk away from and long enough to matter if a song starts.
     */
    fun checkTiming(song: ScannedSong, cache: SongLibraryCache) {
        // Claimed here rather than inside the coroutine, and this is the whole point of the
        // guard: `launch` returns immediately, so a slot taken on the other side of it is taken
        // *later* than the next press arrives, and two quick presses would both get through and
        // decode at once. Both callers are on the main thread, so this is enough.
        if (timing.checking != null) return
        timing.checking = song.textId

        // The tree this song was named in, taken now rather than when the work starts. A
        // document id only means anything inside its own tree, and this job can wait — for a
        // song to finish, or for a decode ahead of it — so the folder can change underneath it.
        // At best the ids then fail to resolve; at worst one exists under both grants and this
        // rewrites a `#GAP` in somebody else's chart. The same hazard `runRepair` already
        // guards, for the same reason.
        val askedIn = cardUri()?.toString()

        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    holdWhileSinging(atABoundary = true)
                    if (cardUri()?.toString() != askedIn) return@withContext null
                    val card = card() ?: return@withContext null
                    checkOne(card, song)
                } ?: return@launch
                Log.i(TIMING_TAG, "${song.folderName}: $result")
                timing.remember(song.textId, result)
                // Only a correction makes the scan out of date, and only then is a rescan worth
                // the five seconds it costs.
                if (result == TimingResult.Corrected) cache.markChanged()
            } finally {
                // Released whatever happened. Without this a single unexpected exception leaves
                // the button saying "Listening…" for the rest of the session, with nothing able
                // to start another check.
                timing.checking = null
            }
        }
    }

    /** One song: read it, listen to it, and move `#GAP` if that is what it needs. */
    private fun checkOne(card: SafDocumentTree, song: ScannedSong): TimingResult {
        val audioId = song.audioId ?: return TimingResult.Unknown
        val chart = runCatching { SongTextDecoder.decode(card.readBytes(song.textId)) }.getOrNull()
            ?: return TimingResult.Unknown
        val parsed = runCatching { UltraStarSongParser.parse(chart) }.getOrNull()
            ?: return TimingResult.Unknown
        val profile = ChromaScanner.scan(context, card.uriFor(audioId).toString())
            ?: return TimingResult.Unknown

        return when (val verdict = checkSync(parsed, profile)) {
            is SyncVerdict.Shifted -> {
                val moved = shiftGap(chart, verdict.offsetSeconds)
                val written = moved != null &&
                    card.overwrite(song.textId, moved.toByteArray(Charsets.UTF_8))
                if (written) TimingResult.Corrected else TimingResult.Unknown
            }
            is SyncVerdict.Mismatch -> TimingResult.Wrong
            SyncVerdict.Aligned -> TimingResult.Fine
            SyncVerdict.Unscoreable -> TimingResult.Unknown
        }
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
            is QueueStatus.Done ->
                Announcement(name, status.note?.let { "Added — $it" } ?: "Added to your songs", good = true)
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
    /**
     * The timing check, bound to the folder the files are being written into.
     *
     * Built per job rather than once, because it needs to turn a document id into a URI and that
     * is a property of the *tree* — a corrector held across a change of song folder would be
     * pointing at the old one.
     */
    private fun syncFor(card: SafDocumentTree): SyncCorrector =
        CardSyncCorrector(context) { id -> card.uriFor(id).toString() }

    private fun card(): SafDocumentTree? {
        val uri = cardUri() ?: return null
        return SafDocumentTree(context.contentResolver, uri)
    }

    /** The granted folder as it stands right now, or null when there is not a writable one. */
    fun cardUri(): Uri? {
        val location = LibraryLocation(context)
        if (!location.canModify()) return null
        return location.saved()
    }
}
