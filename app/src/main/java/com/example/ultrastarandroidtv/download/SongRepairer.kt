package com.example.ultrastarandroidtv.download

import com.example.ultrastarandroidtv.library.DocumentTree
import com.example.ultrastarandroidtv.library.DocumentWriter
import com.example.ultrastarandroidtv.library.ScannedSong
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
import com.example.ultrastarandroidtv.usdb.metaTagsFrom
import com.example.ultrastarandroidtv.usdb.readUsdbSidecar

/** What a song on the card is missing, and where the missing part can be got from. */
data class RepairPlan(
    /** The YouTube video the media comes from. */
    val videoId: String,
    val needsAudio: Boolean,
    val needsVideo: Boolean,
    val needsCover: Boolean,
    /** A full URL the chart names for its cover, when it names a fetchable one. */
    val coverUrl: String?,
) {
    val isWorthDoing: Boolean get() = needsAudio || needsVideo || needsCover
}

sealed interface RepairOutcome {

    data class Repaired(
        val audio: Boolean,
        val video: Boolean,
        val cover: Boolean,
    ) : RepairOutcome {
        /** What to put in front of somebody, shortest useful form. */
        val summary: String
            get() = listOfNotNull(
                if (audio) "music" else null,
                if (video) "video" else null,
                if (cover) "artwork" else null,
            ).let { parts ->
                when (parts.size) {
                    0 -> "Nothing was missing"
                    1 -> "Got the ${parts[0]}"
                    else -> "Got the " + parts.dropLast(1).joinToString(", ") + " and " + parts.last()
                }
            }
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
) {

    /**
     * What could be done for [song], or null when nothing can be.
     *
     * Null means there is no point offering the action — either the song is complete, or nothing
     * in the folder says where its media came from. A Repair button that answers "there is nothing
     * I can do" is worse than no button, so the question is asked before it is shown.
     */
    fun plan(song: ScannedSong): RepairPlan? {
        val tags = mediaTagsFor(song)
        val videoId = tags.audioSource ?: return null
        val plan = RepairPlan(
            videoId = videoId,
            needsAudio = song.audioId == null,
            needsVideo = song.videoId == null,
            needsCover = song.coverId == null,
            coverUrl = tags.coverFile?.takeIf {
                it.startsWith("http://", true) || it.startsWith("https://", true)
            },
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
        return readUsdbSidecar(text)?.metaTags ?: UsdbMetaTags()
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
        onStage(RepairStage.Looking)
        val media: ResolvedMedia = when (val lookup = youTube.resolve(plan.videoId)) {
            is AudioLookup.Found -> lookup.media
            is AudioLookup.Refused -> return RepairOutcome.Failed(
                DownloadProblem.AUDIO_UNAVAILABLE,
                explain(lookup),
            )
        }

        val base = song.folderName.ifBlank { safeFileName(song.song.metadata.title) }
        var gotAudio = false
        var gotCover = false

        if (plan.needsAudio) {
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
            if (!repointChart(song, audioFile = savedAs, coverFile = null)) {
                return RepairOutcome.Failed(
                    DownloadProblem.COULD_NOT_WRITE,
                    "The music was saved but the song file could not be updated.",
                )
            }
            gotAudio = true
        }

        if (plan.needsCover) {
            onStage(RepairStage.Artwork)
            val bytes = coverBytes(song, plan)
            if (bytes != null) {
                val name = "$base [CO].jpg"
                val coverId = writer.writeFile(song.folderId, name, "image/jpeg", bytes)
                if (coverId != null) {
                    val savedAs = tree.list(song.folderId).firstOrNull { it.id == coverId }?.name
                    gotCover = repointChart(song, audioFile = null, coverFile = savedAs ?: name)
                }
            }
        }

        // Last, and allowed to fail quietly: a song with no video gets the visualiser, which is
        // not a fault worth reporting as one.
        val picture = media.video
        val gotVideo = plan.needsVideo && picture != null &&
            saveVideo(song.folderId, base, picture, onStage)

        return RepairOutcome.Repaired(audio = gotAudio, video = gotVideo, cover = gotCover)
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
    private fun repointChart(song: ScannedSong, audioFile: String?, coverFile: String?): Boolean {
        val text = runCatching { SongTextDecoder.decode(tree.readBytes(song.textId)) }.getOrNull()
            ?: return false
        val updated = retargetChart(text, audioFile, coverFile)
        // Overwritten in place rather than written again by name. Creating a document whose name
        // is already taken gets it suffixed, and a second `.txt` in a folder is a second song in
        // the picker -- identical to the first and pointing at the same audio.
        return writer.overwrite(song.textId, updated.toByteArray(Charsets.UTF_8))
    }
}
