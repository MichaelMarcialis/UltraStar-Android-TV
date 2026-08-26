package com.example.ultrastarandroidtv.download

import com.example.ultrastarandroidtv.library.DocumentTree
import com.example.ultrastarandroidtv.library.DocumentWriter
import com.example.ultrastarandroidtv.net.Http
import com.example.ultrastarandroidtv.net.HttpFailure
import com.example.ultrastarandroidtv.net.downloadInChunks
import com.example.ultrastarandroidtv.net.fetchInChunks
import com.example.ultrastarandroidtv.net.AudioLookup
import com.example.ultrastarandroidtv.net.RefusalKind
import com.example.ultrastarandroidtv.net.YouTubeAudio
import com.example.ultrastarandroidtv.usdb.ChartFetch
import com.example.ultrastarandroidtv.net.ITunesArtwork
import com.example.ultrastarandroidtv.net.ResolvedMedia
import com.example.ultrastarandroidtv.net.VideoFormat
import com.example.ultrastarandroidtv.usdb.UsdbCharts
import com.example.ultrastarandroidtv.usdb.UsdbDetails
import com.example.ultrastarandroidtv.usdb.USDB_ID_HEADER
import com.example.ultrastarandroidtv.usdb.UsdbSong
import com.example.ultrastarandroidtv.usdb.metaTagsOf
import java.io.OutputStream

/** Where a download has got to, for a screen that has to be honest about a slow thing. */
sealed interface DownloadStage {

    /**
     * USDB's own throttle, counting down. Deliberately its own stage with a number in it: this is
     * the longest part of a download by far, and a spinner here reads as a hang.
     */
    data class WaitingForUsdb(val secondsLeft: Int) : DownloadStage

    data object FetchingChart : DownloadStage

    /**
     * Looking on USDB for another chart of the same song, after this one's music turned
     * out to be gone. Its own stage because it is the one step that is not about the song
     * that was asked for, and a screen going quiet here reads as a hang.
     */
    data object FindingAnotherVersion : DownloadStage
    data object FindingAudio : DownloadStage
    data object DownloadingAudio : DownloadStage
    data object FetchingArtwork : DownloadStage
    data object Saving : DownloadStage

    /**
     * Last, and after the song is already playable — see [SongDownloader.saveVideo].
     *
     * Carries a percentage because it is the largest thing a download fetches by an order of
     * magnitude — 60-70 MB against a song's 3-5 MB. It used to be the slowest stage by a distance
     * too, at around a hundred seconds, until the throttle behind that was measured properly and
     * turned out to be a limit on the size of a single response rather than a pace; see
     * [com.example.ultrastarandroidtv.net.downloadInChunks].
     */
    data class DownloadingVideo(val percent: Int) : DownloadStage
}

/** Why a download did not happen, in terms a screen can turn into a sentence. */
enum class DownloadProblem {
    ALREADY_HAVE_IT,

    /**
     * Everything that was asked for was attempted and none of it could be got.
     *
     * Its own value rather than reusing [AUDIO_UNAVAILABLE], which is load-bearing: a repair
     * failing with that one sends [com.example.ultrastarandroidtv.download.Downloads] off to
     * replace the whole song with a different chart, and a cover that iTunes happened not to
     * have is nowhere near reason enough for that.
     */
    NOTHING_FETCHED,
    USDB_REFUSED,
    CHART_NAMES_NO_VIDEO,
    AUDIO_UNAVAILABLE,
    NETWORK,
    COULD_NOT_WRITE,
}

sealed interface DownloadOutcome {

    data class Saved(
        val folderName: String,
        val chartFile: String,
        val audioFile: String,
        val audioBytes: Int,
        val coverSaved: Boolean,
        val videoSaved: Boolean,
    ) : DownloadOutcome

    data class Failed(val problem: DownloadProblem, val message: String) : DownloadOutcome
}

/**
 * Turns a song found on USDB into a folder on the card that the library can actually play.
 *
 * ## Nothing is written until the audio is in hand
 *
 * This is the rule the whole class is arranged around, and it comes straight from what the card
 * already looks like: 19 folders holding a chart, a cover, sometimes a video, and **no audio** —
 * because the desktop tool wrote the folder first and then failed to fetch the sound. Those songs
 * are unplayable, invisible in the picker, and had to be hunted down by hand.
 *
 * So the order is: chart, then video id, then *resolve and download the audio*, and only then
 * create a folder. A song that cannot be completed leaves **nothing at all** behind, and the
 * screen says why. If the writing itself fails half way, the folder is removed rather than left
 * as a convincing-looking ruin. This app must not become another way of making broken songs.
 *
 * ## The provider names the files, not us
 *
 * Media is written first and the chart last, because the Storage Access Framework may rename what
 * it creates — a clash gets a suffix, and some providers correct an extension to match the MIME
 * type. So the files are written, their *actual* names are read back, and the chart's `#MP3:` and
 * `#COVER:` are then pointed at those. Writing the chart first would mean guessing, and a chart
 * whose `#MP3:` is off by one character is a song that silently will not play.
 *
 * ## The music is checked before the wait, not after
 *
 * USDB's chart is what names the YouTube video, and getting a chart costs a 24-second throttle. So
 * the obvious order — chart, then check the video — means a song whose video has been removed takes
 * **twenty-seven seconds to say no**. The song's *detail* page names the same video with no wait
 * attached (see [UsdbDetails]), so it is asked first and a doomed download is refused in about two
 * seconds.
 *
 * The chart is still authoritative. Once it arrives its own id is compared with the one that was
 * pre-checked, and the lookup is only skipped when they agree — so a page that ever disagreed with
 * its chart would cost a redundant request, never a wrong answer.
 *
 * ## The music video comes last, because it is allowed to fail
 *
 * A song is finished — folder, audio, cover, chart — before the video is even asked for. It is the
 * same YouTube video the sound came from, so it costs no extra lookup, and a song without one
 * simply gets the visualiser instead. Downloading it earlier would mean a failed video could
 * discard a perfectly good song, which is the exact inversion of the rule above. It is also the
 * only thing here that is *streamed* to the card rather than held in memory; see [saveVideo].
 */
class SongDownloader(
    private val charts: UsdbCharts,
    private val details: UsdbDetails,
    private val youTube: YouTubeAudio,
    private val artwork: ITunesArtwork,
    private val http: Http,
    private val tree: DocumentTree,
    private val writer: DocumentWriter,
    /** Injected so the throttle can be tested without actually waiting half a minute. */
    private val sleepMillis: (Long) -> Unit = { Thread.sleep(it) },
) {

    fun download(song: UsdbSong, onStage: (DownloadStage) -> Unit = {}): DownloadOutcome {
        val folderName = safeFileName(song.folderName)
        if (folderName.isBlank()) {
            return DownloadOutcome.Failed(
                DownloadProblem.COULD_NOT_WRITE,
                "That song's name cannot be used as a folder name.",
            )
        }

        // Refuse a song already on the card rather than making a second copy of it. Two folders
        // for one song is worse than not downloading: both show in the picker, identically.
        if (alreadyHave(folderName)) {
            return DownloadOutcome.Failed(
                DownloadProblem.ALREADY_HAVE_IT,
                "\"${song.title}\" is already in your library.",
            )
        }

        return try {
            fetchAndSave(song, folderName, onStage)
        } catch (e: HttpFailure) {
            DownloadOutcome.Failed(
                DownloadProblem.NETWORK,
                e.message ?: "The download could not reach the internet.",
            )
        }
    }

    private fun fetchAndSave(
        song: UsdbSong,
        folderName: String,
        onStage: (DownloadStage) -> Unit,
    ): DownloadOutcome {
        // Before the throttle, not after: a removed video should cost two seconds, not twenty-seven.
        onStage(DownloadStage.FindingAudio)
        val previewId = runCatching { details.fetch(song.songId).videoId }.getOrNull()
        var preflight: ResolvedMedia? = null
        if (previewId != null) {
            when (val lookup = youTube.resolve(previewId)) {
                is AudioLookup.Found -> preflight = lookup.media
                is AudioLookup.Refused -> return DownloadOutcome.Failed(
                    DownloadProblem.AUDIO_UNAVAILABLE,
                    explain(lookup),
                )
            }
        }

        val chart = fetchChart(song.songId, onStage)
            ?: return DownloadOutcome.Failed(
                DownloadProblem.USDB_REFUSED,
                "USDB did not hand over the song file. It may be asking you to wait longer.",
            )

        val videoId = metaTagsOf(chart).audioSource
            ?: return DownloadOutcome.Failed(
                DownloadProblem.CHART_NAMES_NO_VIDEO,
                "That song file does not say where its music comes from.",
            )

        // The chart has the last word. Reuse the pre-check only when it was about the same video.
        val audio = preflight?.takeIf { videoId == previewId } ?: run {
            onStage(DownloadStage.FindingAudio)
            when (val lookup = youTube.resolve(videoId)) {
                is AudioLookup.Found -> lookup.media
                is AudioLookup.Refused -> return DownloadOutcome.Failed(
                    DownloadProblem.AUDIO_UNAVAILABLE,
                    explain(lookup),
                )
            }
        }

        onStage(DownloadStage.DownloadingAudio)
        // Fetched in bounded ranges like everything else off YouTube: a plain GET of a media URL
        // is served at 31 KB/s, and one big ranged request is barely better. See downloadInChunks.
        val audioBytes = fetchInChunks(
            http = http,
            url = audio.format.url,
            headers = audio.format.fetchHeaders,
            declaredLength = audio.format.contentLength,
        )
        if (audioBytes.isEmpty()) {
            return DownloadOutcome.Failed(
                DownloadProblem.AUDIO_UNAVAILABLE,
                "The music downloaded as an empty file.",
            )
        }

        // Artwork is a nicety: a song with no cover still plays, so a failure here must not throw
        // away a good download.
        onStage(DownloadStage.FetchingArtwork)
        val coverBytes = fetchCover(song, chart)

        onStage(DownloadStage.Saving)
        return save(
            folderName = folderName,
            chart = chart,
            usdbId = song.songId,
            audioExtension = audio.format.container,
            audioBytes = audioBytes,
            coverBytes = coverBytes,
            video = audio.video,
            onStage = onStage,
        )
    }

    /**
     * The best cover anybody offers for this song, or null.
     *
     * Three sources, best first, and the sequence is lazy so a later one is only asked for when
     * the one before it had nothing. USDB's own thumbnail is last because it is **200x200** — fine
     * as a row icon, visibly soft as a card on a television, and softer than the rest of a library
     * whose covers the desktop tool fetched at 1000x1000.
     *
     * A cover is a nicety, so every source is allowed to fail quietly; the song is downloaded
     * either way.
     */
    private fun fetchCover(song: UsdbSong, chart: String): ByteArray? =
        coverSources(song, chart).firstNotNullOfOrNull { url ->
            runCatching { http.getBytes(url) }.getOrNull()?.takeIf { it.isNotEmpty() }
        }

    private fun coverSources(song: UsdbSong, chart: String): Sequence<String> = sequence {
        // What the chart's author chose, when they named somewhere it can actually be fetched
        // from. Bare filenames here are fanart.tv, which no plain HTTP client can reach.
        metaTagsOf(chart).coverFile
            ?.takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) }
            ?.let { yield(it) }
        artwork.find(song.artist, song.title)?.let { yield(it) }
        song.coverUrl?.let { yield(it) }
    }

    /** Runs USDB's countdown, reporting each second, then collects the chart. */
    private fun fetchChart(songId: Int, onStage: (DownloadStage) -> Unit): String? {
        onStage(DownloadStage.FetchingChart)
        when (val begun = charts.beginChart(songId)) {
            is ChartFetch.Ready -> return begun.text
            is ChartFetch.Waiting -> {
                for (left in begun.seconds downTo 1) {
                    onStage(DownloadStage.WaitingForUsdb(left))
                    sleepMillis(1_000)
                }
                onStage(DownloadStage.FetchingChart)
                return (charts.collectChart(songId) as? ChartFetch.Ready)?.text
            }
        }
    }

    private fun save(
        folderName: String,
        chart: String,
        usdbId: Int,
        audioExtension: String,
        audioBytes: ByteArray,
        coverBytes: ByteArray?,
        video: VideoFormat?,
        onStage: (DownloadStage) -> Unit,
    ): DownloadOutcome {
        val folderId = writer.createFolder(tree.rootId, folderName)
            ?: return DownloadOutcome.Failed(
                DownloadProblem.COULD_NOT_WRITE,
                "The song folder could not be created on the card.",
            )

        fun giveUp(message: String): DownloadOutcome {
            // Leave nothing behind. A folder with a chart and no music is exactly the state this
            // whole class exists to stop producing.
            writer.delete(folderId)
            return DownloadOutcome.Failed(DownloadProblem.COULD_NOT_WRITE, message)
        }

        val audioId = writer.writeFile(
            parentId = folderId,
            name = "$folderName.$audioExtension",
            mimeType = mimeForAudio(audioExtension),
            bytes = audioBytes,
        ) ?: return giveUp("The music could not be saved. The card may be full or write-protected.")

        val coverId = coverBytes?.let { bytes ->
            writer.writeFile(folderId, "$folderName [CO].jpg", "image/jpeg", bytes)
        }

        // Read back what the files are actually called before pointing the chart at them.
        val names = tree.list(folderId).associate { it.id to it.name }
        val audioName = names[audioId] ?: "$folderName.$audioExtension"
        val coverName = coverId?.let { names[it] }

        val chartId = writer.writeFile(
            parentId = folderId,
            name = "$folderName.txt",
            mimeType = "text/plain",
            bytes = retargetChart(chart, audioName, coverName, usdbId).toByteArray(Charsets.UTF_8),
        ) ?: return giveUp("The song file could not be saved to the card.")

        // Everything above this line is the song. The video is added afterwards on purpose: it is
        // the one part that may fail without spoiling anything, so it is fetched once there is
        // already something worth keeping.
        val videoSaved = video != null && saveVideo(folderId, folderName, video, onStage)

        return DownloadOutcome.Saved(
            folderName = folderName,
            chartFile = names[chartId] ?: "$folderName.txt",
            audioFile = audioName,
            audioBytes = audioBytes.size,
            coverSaved = coverName != null,
            videoSaved = videoSaved,
        )
    }

    /**
     * Saves the music video beside the song, streaming it rather than holding it.
     *
     * **Streamed, not buffered.** A 1080p video runs to 60-70 MB and this app's heap is capped at
     * 192 MB, so a single allocation that size — next to Compose, a decoder and the audio already
     * in hand — is how an out-of-memory kill happens. The bytes go straight from the socket to the
     * card and nothing holds them, one bounded range at a time.
     *
     * **`#VIDEO:` is not touched, and does not need to be.** That header carries USDB's meta tags
     * recording where the media came from, and the scanner already falls back to any video file
     * sitting in a song's folder — which is how every video in this library is found, since not
     * one chart on the card declares the header. So the file simply being there is enough.
     *
     * Returns whether it landed. Every failure is quiet: the song is already complete and
     * playable, and a song with no video gets the visualiser instead, which is not a downgrade
     * worth reporting as an error.
     */
    private fun saveVideo(
        folderId: String,
        folderName: String,
        video: VideoFormat,
        onStage: (DownloadStage) -> Unit,
    ): Boolean {
        onStage(DownloadStage.DownloadingVideo(0))
        return runCatching {
            writer.writeStream(
                parentId = folderId,
                name = "$folderName.${video.container}",
                mimeType = mimeForVideo(video.container),
            ) { out -> copyReporting(out, video, onStage) }
        }.getOrNull() != null
    }

    /**
     * Copies the picture across, saying how far along it is.
     *
     * Reports only when the whole number of percent changes, so a screen is woken about a hundred
     * times over the whole download rather than once per buffer, and says nothing at all when the
     * length is unknown — a wrong percentage would be worse than none.
     */
    private fun copyReporting(
        out: OutputStream,
        video: VideoFormat,
        onStage: (DownloadStage) -> Unit,
    ) {
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
                onStage(DownloadStage.DownloadingVideo(percent))
            }
        }
    }

    private fun alreadyHave(folderName: String): Boolean =
        runCatching {
            tree.list(tree.rootId).any { it.isDirectory && it.name.equals(folderName, true) }
        }.getOrDefault(false)
}

/** Turns a refusal into something worth reading on a television. */
fun explain(refusal: AudioLookup.Refused): String {
    val name = refusal.title?.let { "\"$it\"" } ?: "That video"
    return when (refusal.kind) {
        // The made-for-kids case. Worth naming precisely: it is 6 of the 19 audio-less songs on
        // the card, and the answer is a different upload rather than trying again.
        RefusalKind.UNAVAILABLE ->
            "$name cannot be downloaded — it is private, removed, or restricted. " +
                "Try another version of the song."
        // Alive, and blocked here. Different advice from a dead link: waiting will not help, and
        // another upload of the same song usually works.
        RefusalKind.REGION_BLOCKED ->
            "$name is blocked in your country. Try another version of the song."
        RefusalKind.AGE_RESTRICTED -> "$name is age-restricted and cannot be downloaded."
        RefusalKind.LIVE -> "$name is a live stream, so there is no file to download."
        RefusalKind.NEEDS_SIGN_IN, RefusalKind.NO_AUDIO_STREAM ->
            "The music could not be fetched. YouTube may have changed how downloads work — " +
                "the app needs updating."
        RefusalKind.UNKNOWN -> "The music could not be fetched: ${refusal.reason}"
    }
}

internal fun mimeForVideo(extension: String): String = when (extension.lowercase()) {
    "mp4", "m4v" -> "video/mp4"
    "webm" -> "video/webm"
    else -> "application/octet-stream"
}

internal fun mimeForAudio(extension: String): String = when (extension.lowercase()) {
    "m4a", "mp4" -> "audio/mp4"
    "webm" -> "audio/webm"
    "mp3" -> "audio/mpeg"
    "ogg", "opus" -> "audio/ogg"
    else -> "application/octet-stream"
}

// ---------------------------------------------------------------------------------------------
// The rules, as free functions: pure, and so testable without a card or a network.
// ---------------------------------------------------------------------------------------------

/**
 * Makes a name a file system will accept, following what the library already looks like.
 *
 * A slash becomes a hyphen rather than vanishing, because it usually separates something — the
 * card's own "KPop Demon Hunters (Huntr/x)" is stored as "Huntr-x", and dropping the slash would
 * give "Huntrx". The rest of the reserved characters are simply removed. Trailing dots and spaces
 * go too: FAT and exFAT accept them at creation and then cannot reliably open them again.
 */
fun safeFileName(name: String): String =
    name.replace('/', '-')
        .replace('\\', '-')
        .filterNot { it in FORBIDDEN || it.code < 0x20 }
        .replace(Regex("""\s+"""), " ")
        .trim()
        .trimEnd('.', ' ')
        .take(MAX_NAME_CHARS)
        .trimEnd('.', ' ')

private const val MAX_NAME_CHARS = 120
private val FORBIDDEN = charArrayOf(':', '*', '?', '"', '<', '>', '|')

/**
 * Points a chart's media headers at the files that were actually saved.
 *
 * USDB's charts name an mp3 nobody has — `#MP3:David Bowie - China Girl.mp3` — because the desktop
 * tool is expected to produce one. This app saves whatever YouTube served, usually `.m4a`, so the
 * header has to be corrected or the scanner will look for a file that is not there. `#COVER:` is
 * usually absent altogether and is inserted.
 *
 * `#USDBID:` records which song on USDB this is. Nothing reads it yet — it is written now
 * because it can only be known *now*, at the one moment the app holds both the chart and the
 * USDB id together. Working it out later would mean searching USDB for a song already on the
 * card and guessing from its artist and title, which is exactly the sort of question with no
 * reliable answer. USDB Syncer keeps the same fact in a `.usdb` file beside the song; this app
 * deliberately does not write one, because filling in another program's format with fields it
 * does not track would have that program act on them. See
 * [com.example.ultrastarandroidtv.usdb.UsdbSidecar].
 *
 * **Only header lines are touched, and line endings are preserved exactly.** Note lines carry
 * meaning in their trailing spaces — that is the only marker of a word ending in the UltraStar
 * format — and a well-meant trim of the whole file is how every syllable ended up hyphenated once
 * before. `#VIDEO:` is left alone on purpose: it holds USDB's meta tags, which record where the
 * media came from and are worth keeping even though no video is downloaded.
 */
fun retargetChart(
    chartText: String,
    audioFile: String?,
    coverFile: String?,
    usdbId: Int? = null,
): String {
    val lines = chartText.split("\n").toMutableList()

    fun setHeader(key: String, value: String) {
        val at = lines.indexOfFirst { headerKeyOf(it).equals(key, ignoreCase = true) }
        val carriageReturn = if (at >= 0 && lines[at].endsWith("\r")) "\r" else ""
        if (at >= 0) {
            lines[at] = "#${key.uppercase()}:$value$carriageReturn"
        } else {
            val lastHeader = lines.indexOfLast { headerKeyOf(it).isNotEmpty() }
            val insertAt = if (lastHeader >= 0) lastHeader + 1 else 0
            val sample = lines.getOrNull(lastHeader)?.endsWith("\r") == true
            lines.add(insertAt, "#${key.uppercase()}:$value" + if (sample) "\r" else "")
        }
    }

    // Null means "leave it as it is", which is what a repair fixing only the artwork wants: it
    // must not have to know the audio's name to avoid destroying the header naming it.
    audioFile?.let { setHeader("MP3", it) }
    coverFile?.let { setHeader("COVER", it) }
    usdbId?.let { setHeader(USDB_ID_HEADER, it.toString()) }
    return lines.joinToString("\n")
}

/** The `KEY` of a `#KEY:value` line, or empty when the line is not a header. */
fun headerKeyOf(line: String): String {
    val trimmed = line.trimStart('﻿', ' ', '\t')
    if (!trimmed.startsWith('#')) return ""
    val colon = trimmed.indexOf(':')
    if (colon <= 1) return ""
    return trimmed.substring(1, colon).trim()
}
