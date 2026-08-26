package com.example.ultrastarandroidtv.library

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * The scanned library, kept for as long as the app is running.
 *
 * The scan costs roughly 100 ms a song and is almost all Storage Access Framework I/O, so a
 * fifty-song card takes about five seconds. Paying that once is setup; paying it again every time
 * somebody finishes a song and comes back for another one is the whole evening.
 *
 * **Deliberately in memory only.** A cache on disk would have to answer when it goes stale, which
 * over SAF means asking the provider about every file — most of the cost of just scanning again —
 * or trusting a timestamp and eventually showing somebody a song that is no longer there. A
 * session-lived cache cannot go stale in any way a relaunch does not fix, and there is a Rescan
 * button for the one case that survives a session: adding songs while the app is open.
 *
 * Keyed on the granted folder, so picking a different one is not answered from the old one's
 * results.
 *
 * **Holds every song found, playable or not.** The picker shows only the ones that can be sung,
 * but a song with no audio is the single most useful thing the Songs screen has to say, and
 * filtering it out here would mean scanning the card twice to get it back.
 */
class SongLibraryCache {

    private var scannedTree: Uri? = null

    var songs: List<ScannedSong> by mutableStateOf(emptyList())
        private set

    /** The songs that can actually be sung — what the picker offers. */
    val playable: List<ScannedSong> get() = songs.filter { it.isPlayable }

    private var loaded = false

    /** True when this already holds a scan of [tree] and nothing needs to be read again. */
    fun holds(tree: Uri?): Boolean = loaded && tree != null && tree == scannedTree

    /**
     * How many times the card has been read, which is what makes a *scan* something a
     * decision can be pinned to.
     *
     * A repair plan is worked out from a scan and stops being true the moment the repair
     * runs — the song now has the audio the plan said was missing. Stamping the plan with
     * this is what lets a screen tell "there is still something to fetch" apart from "we
     * have not looked since we fetched it".
     */
    var generation: Int by mutableStateOf(0)
        private set

    fun put(tree: Uri?, found: List<ScannedSong>) {
        scannedTree = tree
        songs = found
        loaded = true
        generation++
    }

    /** Drops one song, so removing it does not cost a rescan of the whole card. */
    fun forget(textId: String) {
        songs = songs.filterNot { it.textId == textId }
    }

    /** Drops every song in a folder, for when the folder itself was deleted. */
    fun forgetFolder(folderId: String) {
        songs = songs.filterNot { it.folderId == folderId }
    }

    /**
     * Says the card has changed without throwing away what is known about it.
     *
     * The next visit rescans, exactly as [clear] would — but everything already found stays
     * readable in the meantime, and that difference matters. Downloading a song has to invalidate
     * the scan, and clearing it outright meant the Add-songs screen instantly forgot every song
     * already on the card and offered them all over again. What is here is out of date by exactly
     * one song, which is a far better answer than nothing at all.
     */
    fun markStale() {
        loaded = false
    }

    /** Forgets everything, so the next visit scans. Use when the *folder* changed. */
    fun clear() {
        scannedTree = null
        songs = emptyList()
        loaded = false
    }
}
