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
 */
class SongLibraryCache {

    private var scannedTree: Uri? = null

    var songs: List<ScannedSong> by mutableStateOf(emptyList())
        private set

    private var loaded = false

    /** True when this already holds a scan of [tree] and nothing needs to be read again. */
    fun holds(tree: Uri?): Boolean = loaded && tree != null && tree == scannedTree

    fun put(tree: Uri?, found: List<ScannedSong>) {
        scannedTree = tree
        songs = found
        loaded = true
    }

    /** Forgets everything, so the next visit scans. */
    fun clear() {
        scannedTree = null
        songs = emptyList()
        loaded = false
    }
}
