package com.example.ultrastarandroidtv.download

import com.example.ultrastarandroidtv.library.DocumentTree
import com.example.ultrastarandroidtv.library.DocumentWriter
import com.example.ultrastarandroidtv.net.Http
import com.example.ultrastarandroidtv.net.HttpFailure
import com.example.ultrastarandroidtv.net.AudioLookup
import com.example.ultrastarandroidtv.net.RefusalKind
import com.example.ultrastarandroidtv.net.YouTubeAudio
import com.example.ultrastarandroidtv.usdb.ChartFetch
import com.example.ultrastarandroidtv.usdb.UsdbCharts
import com.example.ultrastarandroidtv.usdb.UsdbSong
import com.example.ultrastarandroidtv.usdb.metaTagsOf

/** Where a download has got to, for a screen that has to be honest about a slow thing. */
sealed interface DownloadStage {

    /**
     * USDB's own throttle, counting down. Deliberately its own stage with a number in it: this is
     * the longest part of a download by far, and a spinner here reads as a hang.
     */
    data class WaitingForUsdb(val secondsLeft: Int) : DownloadStage

    data object FetchingChart : DownloadStage
    data object FindingAudio : DownloadStage
    data object DownloadingAudio : DownloadStage
    data object FetchingArtwork : DownloadStage
    data object Saving : DownloadStage
}

/** Why a download did not happen, in terms a screen can turn into a sentence. */
enum class DownloadProblem {
    ALREADY_HAVE_IT,
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
 */
class SongDownloader(
    private val charts: UsdbCharts,
    private val youTube: YouTubeAudio,
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

        onStage(DownloadStage.FindingAudio)
        val audio = when (val lookup = youTube.resolve(videoId)) {
            is AudioLookup.Found -> lookup.audio
            is AudioLookup.Refused -> return DownloadOutcome.Failed(
                DownloadProblem.AUDIO_UNAVAILABLE,
                explain(lookup),
            )
        }

        onStage(DownloadStage.DownloadingAudio)
        // The headers matter: see AudioFormat.fetchHeaders. Without the Range header this is a
        // two-minute download of a four-megabyte file.
        val audioBytes = http.getBytes(audio.format.url, audio.format.fetchHeaders)
        if (audioBytes.isEmpty()) {
            return DownloadOutcome.Failed(
                DownloadProblem.AUDIO_UNAVAILABLE,
                "The music downloaded as an empty file.",
            )
        }

        // Artwork is a nicety: a song with no cover still plays, so a failure here must not throw
        // away a good download.
        onStage(DownloadStage.FetchingArtwork)
        val coverBytes = song.coverUrl?.let { url -> runCatching { http.getBytes(url) }.getOrNull() }
            ?.takeIf { it.isNotEmpty() }

        onStage(DownloadStage.Saving)
        return save(folderName, chart, audio.format.container, audioBytes, coverBytes)
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
        audioExtension: String,
        audioBytes: ByteArray,
        coverBytes: ByteArray?,
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
            bytes = retargetChart(chart, audioName, coverName).toByteArray(Charsets.UTF_8),
        ) ?: return giveUp("The song file could not be saved to the card.")

        return DownloadOutcome.Saved(
            folderName = folderName,
            chartFile = names[chartId] ?: "$folderName.txt",
            audioFile = audioName,
            audioBytes = audioBytes.size,
            coverSaved = coverName != null,
        )
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
        RefusalKind.AGE_RESTRICTED -> "$name is age-restricted and cannot be downloaded."
        RefusalKind.LIVE -> "$name is a live stream, so there is no file to download."
        RefusalKind.NEEDS_SIGN_IN, RefusalKind.NO_AUDIO_STREAM ->
            "The music could not be fetched. YouTube may have changed how downloads work — " +
                "the app needs updating."
        RefusalKind.UNKNOWN -> "The music could not be fetched: ${refusal.reason}"
    }
}

private fun mimeForAudio(extension: String): String = when (extension.lowercase()) {
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
 * **Only header lines are touched, and line endings are preserved exactly.** Note lines carry
 * meaning in their trailing spaces — that is the only marker of a word ending in the UltraStar
 * format — and a well-meant trim of the whole file is how every syllable ended up hyphenated once
 * before. `#VIDEO:` is left alone on purpose: it holds USDB's meta tags, which record where the
 * media came from and are worth keeping even though no video is downloaded.
 */
fun retargetChart(chartText: String, audioFile: String, coverFile: String?): String {
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

    setHeader("MP3", audioFile)
    coverFile?.let { setHeader("COVER", it) }
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
