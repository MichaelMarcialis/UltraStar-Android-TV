package com.example.ultrastarandroidtv.download

import com.example.ultrastarandroidtv.library.DocumentTree
import com.example.ultrastarandroidtv.library.DocumentWriter
import com.example.ultrastarandroidtv.library.ScannedSong
import com.example.ultrastarandroidtv.library.SongLibraryCache
import com.example.ultrastarandroidtv.net.AudioLookup
import com.example.ultrastarandroidtv.net.Http
import com.example.ultrastarandroidtv.net.HttpFailure
import com.example.ultrastarandroidtv.net.ITunesArtwork
import com.example.ultrastarandroidtv.net.ResolvedMedia
import com.example.ultrastarandroidtv.net.VideoFormat
import com.example.ultrastarandroidtv.net.YouTubeAudio
import com.example.ultrastarandroidtv.net.downloadInChunks
import com.example.ultrastarandroidtv.net.fetchInChunks
import com.example.ultrastarandroidtv.library.SongTextDecoder
import com.example.ultrastarandroidtv.usdb.UsdbMetaTags
import com.example.ultrastarandroidtv.usdb.UsdbSidecar
import com.example.ultrastarandroidtv.usdb.metaTagsFrom
import com.example.ultrastarandroidtv.usdb.readUsdbSidecar

/** What a song on the card is missing, and where the missing part can be got from. */
data class RepairPlan(
    /**
     * The YouTube video the media comes from, or null when the folder does not say.
     *
     * Nullable because **artwork does not come from YouTube**. A playable song wearing a
     * thumbnail, whose chart names no video and which has no sidecar, can still have a proper
     * cover fetched from iTunes on the strength of its artist and title alone — and refusing
     * to offer that because of a missing video id would be refusing the one repair that was
     * actually possible.
     */
    val videoId: String?,
    val needsAudio: Boolean,
    val needsVideo: Boolean,
    /**
     * Whether the song has no artwork at all.
     *
     * A cover a song already has is left alone whatever size it is. Repair used to measure them
     * and offer to replace anything under 400 pixels on its shorter edge — real enough, since
     * USDB's own covers are 200x200 and visibly soft on a 4K set — and it is gone on the user's
     * call after an evening of living with it. It put "Repair 7 songs" on screen for seven songs
     * nobody could see anything wrong with, every one of which then failed because there was
     * nothing better to be had. Any artwork is now good artwork: a simpler promise, and one the
     * app can actually keep.
     */
    val needsCover: Boolean,
    /** A full URL the chart names for its cover, when it names a fetchable one. */
    val coverUrl: String?,
    /**
     * Which reading of the card this was worked out from — see [SongLibraryCache.generation].
     *
     * A plan describes what a song was missing *at the time of a scan*, and running it makes
     * it untrue. Carrying the scan is what lets [RepairQueue] refuse to run the same plan
     * twice before anybody has looked at the card again.
     */
    val scan: Int = 0,
) {
    val isWorthDoing: Boolean
        get() = needsAudio || needsVideo || needsCover

    /**
     * What this plan is asking for, in a form that can be compared with a later one.
     *
     * Deliberately not the whole plan: the scan it came from changes every time the card is read
     * and says nothing about what is wanted, so a plan already tried in vain would otherwise look
     * like a new one on the very next reading of the card.
     */
    val signature: String
        get() = "a$needsAudio v$needsVideo c$needsCover"
}

sealed interface RepairOutcome {

    data class Repaired(
        val audio: Boolean,
        val video: Boolean,
        val cover: Boolean,
        /**
         * What the timing check said about the music that just arrived, or null.
         *
         * Carried for the same reason a download carries it: fetching music for a chart puts two
         * files together that have never met, and if that made this app *edit the chart* — or
         * showed that the two do not belong together at all — saying so is not optional. A repair
         * that silently rewrote somebody's `#GAP` would be the app changing files behind their
         * back.
         */
        val timingNote: String? = null,
    ) : RepairOutcome {
        /** What to put in front of somebody, shortest useful form. */
        val summary: String
            get() = listOfNotNull(
                if (audio) "music" else null,
                if (video) "video" else null,
                if (cover) "artwork" else null,
            ).let { parts ->
                when (parts.size) {
                    0 -> "Nothing could be got"
                    1 -> "Got the ${parts[0]}"
                    else -> "Got the " + parts.dropLast(1).joinToString(", ") + " and " + parts.last()
                }
            }.let { got -> timingNote?.let { "$got — $it" } ?: got }
    }

    data class Failed(val problem: DownloadProblem, val message: String) : RepairOutcome
}

/**
 * Fills in what a song on the card is missing, without downloading it again.
 *
 * ## Why this is worth having at all
 *
 * Nineteen of the seventy-six folders on this card hold a chart, artwork, and **no music**. They
 * are not corrupt and they are not the wrong songs — the desktop tool created each folder and then
 * failed to fetch the sound, which is precisely the failure [SongDownloader] is arranged never to
 * repeat. Until now the only way to deal with one was to delete it and download it again, which
 * throws away a perfectly good chart to get back a file that is still sitting on YouTube.
 *
 * ## It costs no USDB account and no throttle
 *
 * This is the part that makes repair cheap where downloading is expensive. Everything needed is
 * already in the folder: the chart is there, and the YouTube id is in the chart's own `#VIDEO:`
 * meta tags or in the `.usdb` file USDB Syncer left beside it. So a repair is a lookup and a
 * download — three or four seconds — against the twenty-seven a fresh download costs, and it needs
 * nobody to be signed in.
 *
 * ## What it will not fix
 *
 * A song whose YouTube upload has been removed, gone private, or is blocked here cannot be
 * repaired by anybody, and it says so with the reason rather than trying again. The answer to
 * those is a different upload, which means the Add-songs screen and a different chart.
 *
 * ## Order
 *
 * Music first, then artwork, then the video. The same reasoning as a download, for the same
 * reason: the audio is what makes a song singable and everything after it is a nicety that must
 * not be able to fail the whole repair. Nothing here can leave a folder worse than it found it —
 * every file is written whole or not at all, and a chart is only re-pointed once its new audio
 * exists.
 */
class SongRepairer(
    private val youTube: YouTubeAudio,
    private val artwork: ITunesArtwork,
    private val http: Http,
    private val tree: DocumentTree,
    private val writer: DocumentWriter,
    /**
     * Puts a chart in time with music that has just been fetched for it.
     *
     * A repair is exactly the case a fresh download is: a chart written by one person and audio
     * taken from a video by another, meeting for the first time. See [SyncCorrector].
     */
    private val sync: SyncCorrector = SyncCorrector.NONE,
) {

    /**
     * What could be done for [song], or null when nothing can be.
     *
     * Null means there is no point offering the action — either the song is complete, or nothing
     * in the folder says where its media came from. A Repair button that answers "there is nothing
     * I can do" is worse than no button, so the question is asked before it is shown.
     */
    /**
     * What the timing check found while this repair was running, if anything.
     *
     * A field rather than a return value because it comes from three levels down inside
     * [repointChart], which already answers a different question — whether the chart could be
     * written at all. One repair at a time, one instance per repair, so there is nothing to race.
     */
    private var timingNote: String? = null

    fun plan(song: ScannedSong, scan: Int = 0): RepairPlan? {
        val tags = mediaTagsFor(song)
        val videoId = tags.audioSource

        // Media can only be fetched when the folder says where it came from; artwork can be
        // fetched from the song's own name. Deciding those separately is what stops a missing
        // video id from cancelling a repair that never needed one.
        val plan = RepairPlan(
            videoId = videoId,
            needsAudio = song.audioId == null && videoId != null,
            needsVideo = song.videoId == null && videoId != null,
            needsCover = song.coverId == null,
            coverUrl = tags.coverFile?.takeIf {
                it.startsWith("http://", true) || it.startsWith("https://", true)
            },
            scan = scan,
        )
        return plan.takeIf { it.isWorthDoing }
    }

    /**
     * Where a song says its media came from: its own chart first, then the sidecar.
     *
     * The chart wins because it is the file this app writes and controls. The sidecar is what
     * every song the desktop tool made has instead — USDB Syncer takes the meta tags *out* of the
     * chart and puts them there, which is why a broken folder's `.txt` has no `#VIDEO:` line.
     */
    private fun mediaTagsFor(song: ScannedSong): UsdbMetaTags {
        val fromChart = song.song.metadata.video?.let { metaTagsFrom(it) }
        if (fromChart?.audioSource != null) return fromChart

        val sidecarId = song.sidecarId ?: return UsdbMetaTags()
        val text = runCatching { SongTextDecoder.decode(tree.readBytes(sidecarId)) }.getOrNull()
            ?: return UsdbMetaTags()
        val sidecar = readUsdbSidecar(text) ?: return UsdbMetaTags()
        return if (belongsTo(song, sidecar)) sidecar.metaTags else UsdbMetaTags()
    }

    /**
     * Whether this sidecar was written for *this* chart.
     *
     * A folder holding one song needs no checking — the sidecar in it is the sidecar for it.
     * A folder holding two arrangements does: USDB Syncer writes one sidecar per download and
     * records the chart it belongs to, so believing it for both would let a repair fetch one
     * arrangement's recording and point the other's chart at it. That is the out-of-time
     * failure `tools/check_song_sync.py` exists to diagnose, arrived at from inside the app.
     *
     * When the sidecar does not say which chart it is for and there is more than one, nothing
     * is assumed: no repair is offered, which is worse than a repair and far better than the
     * wrong one.
     */
    private fun belongsTo(song: ScannedSong, sidecar: UsdbSidecar): Boolean {
        val entries = runCatching { tree.list(song.folderId) }.getOrNull() ?: return false
        val charts = entries.count { !it.isDirectory && it.name.endsWith(".txt", true) }
        if (charts <= 1) return true

        val named = sidecar.chartFile ?: return false
        val mine = entries.firstOrNull { it.id == song.textId }?.name ?: return false
        return named.equals(mine, ignoreCase = true)
    }

    fun repair(
        song: ScannedSong,
        plan: RepairPlan,
        onStage: (RepairStage) -> Unit = {},
    ): RepairOutcome = try {
        fetchAndFill(song, plan, onStage)
    } catch (e: HttpFailure) {
        RepairOutcome.Failed(
            DownloadProblem.NETWORK,
            e.message ?: "The repair could not reach the internet.",
        )
    }

    private fun fetchAndFill(
        song: ScannedSong,
        plan: RepairPlan,
        onStage: (RepairStage) -> Unit,
    ): RepairOutcome {
        // Only asked when there is media to fetch. A cover-only repair must not be blocked by
        // a video that has since been taken down -- the artwork is still perfectly gettable.
        val media: ResolvedMedia? = if (plan.needsAudio || plan.needsVideo) {
            onStage(RepairStage.Looking)
            when (val lookup = youTube.resolve(plan.videoId.orEmpty())) {
                is AudioLookup.Found -> lookup.media
                is AudioLookup.Refused -> return RepairOutcome.Failed(
                    DownloadProblem.AUDIO_UNAVAILABLE,
                    explain(lookup),
                )
            }
        } else {
            null
        }

        val base = song.folderName.ifBlank { safeFileName(song.song.metadata.title) }
        var gotAudio = false
        var gotCover = false

        if (plan.needsAudio && media != null) {
            onStage(RepairStage.Music)
            val bytes = fetchInChunks(
                http = http,
                url = media.format.url,
                headers = media.format.fetchHeaders,
                declaredLength = media.format.contentLength,
            )
            if (bytes.isEmpty()) {
                return RepairOutcome.Failed(
                    DownloadProblem.AUDIO_UNAVAILABLE,
                    "The music downloaded as an empty file.",
                )
            }
            val name = "$base.${media.format.container}"
            val audioId = writer.writeFile(
                song.folderId, name, mimeForAudio(media.format.container), bytes,
            ) ?: return RepairOutcome.Failed(
                DownloadProblem.COULD_NOT_WRITE,
                "The music could not be saved. The card may be full or write-protected.",
            )

            // Only now, with the file on the card and its real name read back, is the chart
            // allowed to point at it -- the same rule a fresh download follows, and for the same
            // reason: a `#MP3:` naming a file that is not there is a song that will not play.
            val savedAs = tree.list(song.folderId).firstOrNull { it.id == audioId }?.name ?: name
            if (!repointChart(song, audioFile = savedAs, coverFile = null, audioId = audioId)) {
                // The audio goes with the failure. Left behind it is a file the chart does not
                // name, and the next attempt would be given a *suffixed* copy by SAF rather
                // than replacing it -- so every retry would leave one more orphan in the
                // folder while the song still would not play.
                writer.delete(audioId)
                return RepairOutcome.Failed(
                    DownloadProblem.COULD_NOT_WRITE,
                    "The music was saved but the song file could not be updated.",
                )
            }
            gotAudio = true
        }

        // Only ever artwork that is *not* there. Nothing here replaces a cover a song already
        // has, so there is no name clash to resolve and no original that can be lost.
        if (plan.needsCover) {
            onStage(RepairStage.Artwork)
            val bytes = coverBytes(song, plan)
            gotCover = if (bytes == null) false else {
                val name = "$base [CO].jpg"
                val coverId = writer.writeFile(song.folderId, name, "image/jpeg", bytes)
                val pointed = coverId != null && repointChart(
                    song,
                    audioFile = null,
                    coverFile = tree.list(song.folderId)
                        .firstOrNull { it.id == coverId }?.name ?: name,
                )
                // Same rule as the music: a picture the chart does not name is litter, and
                // retrying would add another beside it rather than replacing it.
                if (!pointed && coverId != null) writer.delete(coverId)
                pointed
            }
        }

        // Last, and allowed to fail quietly: a song with no video gets the visualiser, which is
        // not a fault worth reporting as one.
        val picture = media?.video
        val gotVideo = plan.needsVideo && picture != null &&
            saveVideo(song.folderId, base, picture, onStage)

        // Getting none of it is a failure, not a quiet success. Reported as `Repaired` it was
        // counted among the songs fixed, marked the library scan out of date, and put a
        // success-coloured "Nothing could be got" in front of somebody -- three statements
        // that were all untrue. Partial success is still success: one asset is worth having.
        if (!gotAudio && !gotVideo && !gotCover) {
            return RepairOutcome.Failed(
                DownloadProblem.NOTHING_FETCHED,
                "Nothing could be found for this song.",
            )
        }
        return RepairOutcome.Repaired(
            audio = gotAudio,
            video = gotVideo,
            cover = gotCover,
            timingNote = timingNote,
        )
    }

    /** Cover sources, best first — the same order a fresh download uses, minus USDB's thumbnail. */
    private fun coverBytes(song: ScannedSong, plan: RepairPlan): ByteArray? {
        val sources = sequence {
            plan.coverUrl?.let { yield(it) }
            val meta = song.song.metadata
            artwork.find(meta.artist, meta.title)?.let { yield(it) }
        }
        return sources.firstNotNullOfOrNull { url ->
            runCatching { http.getBytes(url) }.getOrNull()?.takeIf { it.isNotEmpty() }
        }
    }

    private fun saveVideo(
        folderId: String,
        base: String,
        video: VideoFormat,
        onStage: (RepairStage) -> Unit,
    ): Boolean {
        onStage(RepairStage.Video(0))
        return runCatching {
            writer.writeStream(
                parentId = folderId,
                name = "$base.${video.container}",
                mimeType = mimeForVideo(video.container),
            ) { out ->
                var reported = 0
                downloadInChunks(
                    http = http,
                    url = video.url,
                    out = out,
                    headers = video.fetchHeaders,
                    declaredLength = video.contentLength,
                ) { written, total ->
                    if (total <= 0) return@downloadInChunks
                    val percent = ((written * 100) / total).toInt().coerceIn(0, 100)
                    if (percent != reported) {
                        reported = percent
                        onStage(RepairStage.Video(percent))
                    }
                }
            }
        }.getOrNull() != null
    }

    /**
     * Rewrites the chart's media headers in place.
     *
     * Reads the file back rather than using the parsed song, because a chart is not only its
     * headers: note lines carry meaning in their trailing spaces, and rebuilding one from parsed
     * data is how every syllable in the library got hyphenated once already. [retargetChart] only
     * ever touches header lines and preserves line endings byte for byte.
     */
    /**
     * Points the chart at what is now beside it, and — when music has just arrived — puts it in
     * time with that music first.
     *
     * [audioId] is null for a cover-only repair, where there is no new recording to measure
     * against and the chart's timing is none of this method's business.
     */
    private fun repointChart(
        song: ScannedSong,
        audioFile: String?,
        coverFile: String?,
        audioId: String? = null,
    ): Boolean {
        val text = runCatching { SongTextDecoder.decode(tree.readBytes(song.textId)) }.getOrNull()
            ?: return false
        val timing = if (audioId == null) null else sync.correct(audioId, text)
        timingNote = timing?.note
        val updated = retargetChart(timing?.chart ?: text, audioFile, coverFile)
        // Overwritten in place rather than written again by name. Creating a document whose name
        // is already taken gets it suffixed, and a second `.txt` in a folder is a second song in
        // the picker -- identical to the first and pointing at the same audio.
        return writer.overwrite(song.textId, updated.toByteArray(Charsets.UTF_8))
    }
}
