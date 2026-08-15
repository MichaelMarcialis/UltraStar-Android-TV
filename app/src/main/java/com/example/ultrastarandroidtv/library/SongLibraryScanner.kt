package com.example.ultrastarandroidtv.library

import com.example.ultrastarandroidtv.song.UltraStarSong
import com.example.ultrastarandroidtv.song.UltraStarSongParser

/** A song found in the library, with its media resolved to document ids in the same folder. */
data class ScannedSong(
    val song: UltraStarSong,
    val folderId: String,
    val textId: String,
    /** Folder name, which is what the library shows when a song's own headers are unhelpful. */
    val folderName: String,
    val audioId: String?,
    val videoId: String?,
    val coverId: String?,
    val backgroundId: String?,
) {
    /** A song whose audio is missing cannot be played, however well it parsed. */
    val isPlayable: Boolean get() = audioId != null
}

/** A `.txt` that looked like a song but could not be used. */
data class ScanFailure(
    val folderName: String,
    val fileName: String,
    val reason: String,
)

data class ScanSummary(
    val songs: List<ScannedSong>,
    val failures: List<ScanFailure>,
    val foldersVisited: Int,
    /** Files that looked like songs, whether or not they parsed. */
    val candidates: Int,
)

/** What counts as a song's video when no `#VIDEO` header names one. */
private val VIDEO_EXTENSIONS = setOf("mp4", "mkv", "avi", "webm", "mov", "m4v", "mpg", "mpeg")

/** Folders that are never worth descending into, whatever the card has on it. */
private val SKIPPED_FOLDERS = setOf(
    "android",
    "lost.dir",
    "system volume information",
    "\$recycle.bin",
)

/**
 * Walks a granted folder and finds every UltraStar song under it.
 *
 * Each `.txt` is its own song rather than each folder, because a folder can legitimately hold
 * several — an alternate duet arrangement next to the original is a normal thing to find.
 *
 * Reading is the expensive part over the Storage Access Framework, so the walk itself only ever
 * asks for directory listings, and the only files opened are the ones that already look like
 * songs. Songs are handed to [onSong] as they are parsed so a list can fill in while the scan is
 * still running; the same songs come back in the summary at the end.
 *
 * A library assembled by strangers over twenty years contains broken songs, and one of them must
 * not end the scan. Anything that fails is collected into [ScanSummary.failures] and the walk
 * carries on.
 *
 * @param maxDepth how far below the granted folder to look. Songs normally sit one or two levels
 *   down — `Library/Artist - Title/` — and occasionally three when sorted into editions.
 */
class SongLibraryScanner(
    private val tree: DocumentTree,
    private val maxDepth: Int = 4,
) {
    fun scan(onSong: (ScannedSong) -> Unit = {}): ScanSummary {
        val songs = mutableListOf<ScannedSong>()
        val failures = mutableListOf<ScanFailure>()
        var foldersVisited = 0
        var candidates = 0

        // An explicit stack rather than recursion: a pathological tree should not be able to
        // overflow the real one. Each folder carries its own name, since that is what the
        // library shows and the tree only reports names on the way past.
        val pending = ArrayDeque<Folder>()
        pending.add(Folder(tree.rootId, name = "", depth = 0))

        while (pending.isNotEmpty()) {
            val (folderId, folderName, depth) = pending.removeFirst()
            val entries = runCatching { tree.list(folderId) }.getOrElse { emptyList() }
            foldersVisited++

            for (entry in entries) {
                if (entry.isDirectory) {
                    if (depth + 1 > maxDepth) continue
                    if (entry.name.startsWith(".")) continue
                    if (entry.name.lowercase() in SKIPPED_FOLDERS) continue
                    pending.add(Folder(entry.id, entry.name, depth + 1))
                } else if (entry.name.endsWith(".txt", ignoreCase = true)) {
                    val bytes = runCatching { tree.readBytes(entry.id) }.getOrNull()
                        ?: continue
                    val text = SongTextDecoder.decode(bytes)
                    // Folders hold readmes and licences too. Something without a title header
                    // was never a song, so it is not a failure worth reporting.
                    if (!looksLikeSong(text)) continue

                    candidates++
                    val parsed = runCatching { UltraStarSongParser.parse(text) }.getOrElse { error ->
                        failures += ScanFailure(
                            folderName,
                            entry.name,
                            error.message ?: error::class.simpleName ?: "could not be read",
                        )
                        continue
                    }
                    val scanned = resolve(parsed, folderId, folderName, entry.id, entries)
                    songs += scanned
                    onSong(scanned)
                }
            }
        }

        return ScanSummary(songs, failures, foldersVisited, candidates)
    }

    private data class Folder(val id: String, val name: String, val depth: Int)

    /**
     * Ties a song's `#MP3`, `#VIDEO`, `#COVER` and `#BACKGROUND` headers to real files.
     *
     * Names are matched without regard to case because the files were written on Windows, where
     * `Song.MP3` and `song.mp3` are the same file and nobody was ever forced to be consistent.
     */
    private fun resolve(
        song: UltraStarSong,
        folderId: String,
        folderName: String,
        textId: String,
        entries: List<TreeEntry>,
    ): ScannedSong = ScannedSong(
        song = song,
        folderId = folderId,
        textId = textId,
        folderName = folderName,
        audioId = findFile(song.metadata.mp3, entries),
        videoId = findVideo(song.metadata.video, entries),
        coverId = findFile(song.metadata.cover, entries),
        backgroundId = findFile(song.metadata.background, entries),
    )

    /**
     * Only the file name is used, even when a header carries a path. Those paths were written on
     * another machine with another layout, and the file that matters is the one sitting beside
     * the song.
     */
    private fun findFile(reference: String?, entries: List<TreeEntry>): String? {
        val name = reference?.trim()?.replace('\\', '/')?.substringAfterLast('/')
        if (name.isNullOrEmpty()) return null
        return entries.firstOrNull { !it.isDirectory && it.name.equals(name, ignoreCase = true) }?.id
    }

    /**
     * The song's video, falling back to any video file sitting in its folder.
     *
     * `#VIDEO` is optional and frequently missing — not one song on this card declares it, while
     * several ship an `.mp4` right beside the `.txt`. A song folder holds exactly one song's
     * media, so a lone video file in it is not ambiguous, and refusing to use it because a
     * header is absent would mean shipping a feature that never fires on the actual library.
     */
    private fun findVideo(reference: String?, entries: List<TreeEntry>): String? =
        findFile(reference, entries) ?: entries.firstOrNull {
            !it.isDirectory &&
                it.name.substringAfterLast('.', "").lowercase() in VIDEO_EXTENSIONS
        }?.id

    private fun looksLikeSong(text: String): Boolean =
        text.lineSequence().take(40).any { it.trimStart().startsWith("#TITLE:", ignoreCase = true) }
}
