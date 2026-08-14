package com.example.ultrastarandroidtv.diagnostics

import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.example.ultrastarandroidtv.library.LibraryLocation
import com.example.ultrastarandroidtv.library.SafDocumentTree
import com.example.ultrastarandroidtv.library.ScanSummary
import com.example.ultrastarandroidtv.library.ScannedSong
import com.example.ultrastarandroidtv.library.SongLibraryScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "SongLibrary"

/**
 * Picks the song folder, scans it, and shows what came back.
 *
 * A harness rather than the browse UI, but it exercises the part that has to be right: the
 * folder is remembered across launches, so this asks once and never again.
 */
@Composable
fun SongLibraryScreen() {
    val context = LocalContext.current
    val location = remember { LibraryLocation(context) }

    var treeUri by remember { mutableStateOf<Uri?>(location.saved()) }
    var status by remember { mutableStateOf("") }
    var timing by remember { mutableStateOf("") }
    var summary by remember { mutableStateOf<ScanSummary?>(null) }
    val found = remember { mutableStateListOf<ScannedSong>() }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { picked ->
        if (picked == null) {
            status = "No folder picked."
            return@rememberLauncherForActivityResult
        }
        if (location.remember(picked)) {
            treeUri = picked
        } else {
            status = "Android would not keep access to that folder. Try another."
        }
    }

    LaunchedEffect(treeUri) {
        val uri = treeUri ?: run {
            status = "No song folder chosen yet."
            return@LaunchedEffect
        }

        found.clear()
        summary = null
        status = "Scanning ${uri.lastPathSegment}…"

        val startedAt = System.nanoTime()
        val result = withContext(Dispatchers.IO) {
            val tree = SafDocumentTree(context.contentResolver, uri)
            SongLibraryScanner(tree).scan { song ->
                // Straight onto a snapshot-backed list, so the count climbs while the scan runs
                // rather than the screen sitting blank for however long a big card takes.
                found.add(song)
            }
        }
        val seconds = (System.nanoTime() - startedAt) / 1_000_000_000.0

        summary = result
        val playable = result.songs.count { it.isPlayable }
        status = "${result.songs.size} songs, $playable playable, ${result.failures.size} broken"
        timing = "%.1f s   %d folders   %.0f ms per song".format(
            seconds,
            result.foldersVisited,
            if (result.songs.isEmpty()) 0.0 else seconds * 1000 / result.songs.size,
        )
        Log.i(TAG, "$status — $timing")
        result.failures.take(10).forEach { Log.w(TAG, "broken: ${it.folderName}/${it.fileName}: ${it.reason}") }
        result.songs.take(5).forEach {
            Log.i(TAG, "song: ${it.song.metadata.artist} — ${it.song.metadata.title} (audio=${it.audioId != null})")
        }
    }

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize().padding(32.dp)) {
                Text("Song Library", style = MaterialTheme.typography.headlineMedium)
                Text(status, style = MaterialTheme.typography.bodyLarge)
                Text(timing, style = MaterialTheme.typography.bodyMedium)
                Text("")

                Button(onClick = { picker.launch(null) }) {
                    Text(if (treeUri == null) "Choose song folder" else "Choose a different folder")
                }
                Text("")

                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    items(found) { song ->
                        val mark = if (song.isPlayable) "" else "   [no audio]"
                        Text(
                            "${song.song.metadata.artist} — ${song.song.metadata.title}$mark",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }
    }
}
