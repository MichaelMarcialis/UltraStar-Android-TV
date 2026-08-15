package com.example.ultrastarandroidtv.game

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.example.ultrastarandroidtv.library.LibraryLocation
import com.example.ultrastarandroidtv.library.SafDocumentTree
import com.example.ultrastarandroidtv.library.ScannedSong
import com.example.ultrastarandroidtv.library.SongLibraryScanner
import com.example.ultrastarandroidtv.song.UltraStarSong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A song that has been picked, with its audio resolved to something the player can open. */
private class Chosen(val song: UltraStarSong, val audioUri: String)

/**
 * Picks a song and starts the game.
 *
 * Deliberately plain: this is a way into [GameplayScreen] on real songs, not the browse UI —
 * that is its own piece of work, with artwork, sorting and search. Keeping it this thin means
 * none of it has to be thrown away or defended later.
 */
@Composable
fun SongLauncher() {
    val context = LocalContext.current
    val location = remember { LibraryLocation(context) }

    var treeUri by remember { mutableStateOf<Uri?>(location.saved()) }
    var songs by remember { mutableStateOf<List<ScannedSong>>(emptyList()) }
    var status by remember { mutableStateOf("Looking for songs…") }
    var chosen by remember { mutableStateOf<Chosen?>(null) }

    val first = remember { FocusRequester() }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { picked ->
        when {
            picked == null -> status = "No folder picked."
            location.remember(picked) -> treeUri = picked
            else -> status = "Android would not keep access to that folder. Try another."
        }
    }

    LaunchedEffect(treeUri) {
        val uri = treeUri ?: run {
            status = "Choose the folder your songs are in."
            return@LaunchedEffect
        }
        status = "Scanning…"
        val tree = SafDocumentTree(context.contentResolver, uri)
        val found = withContext(Dispatchers.IO) { SongLibraryScanner(tree).scan() }
        songs = found.songs.filter { it.isPlayable }.sortedBy { it.song.metadata.title.lowercase() }
        status = if (songs.isEmpty()) {
            "No playable songs found in that folder."
        } else {
            "${songs.size} songs"
        }
    }

    LaunchedEffect(songs) {
        if (songs.isNotEmpty()) runCatching { first.requestFocus() }
    }

    chosen?.let { ready ->
        GameplayScreen(
            song = ready.song,
            audioUri = ready.audioUri,
            onExit = { chosen = null },
        )
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(GameTheme.background)
            .padding(40.dp),
    ) {
        Text("Sing", style = MaterialTheme.typography.headlineMedium, color = GameTheme.lyricActive)
        Text(status, style = MaterialTheme.typography.bodyLarge, color = GameTheme.lyricIdle)

        if (treeUri == null) {
            Button(onClick = { picker.launch(null) }, modifier = Modifier.padding(top = 24.dp)) {
                Text("Choose song folder")
            }
        }

        LazyColumn(modifier = Modifier.fillMaxWidth().padding(top = 24.dp)) {
            itemsIndexed(songs) { index, scanned ->
                val tree = SafDocumentTree(context.contentResolver, treeUri!!)
                Button(
                    onClick = {
                        val audioId = scanned.audioId ?: return@Button
                        chosen = Chosen(scanned.song, tree.uriFor(audioId).toString())
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .padding(vertical = 4.dp)
                        .then(if (index == 0) Modifier.focusRequester(first) else Modifier),
                ) {
                    Text("${scanned.song.metadata.artist} — ${scanned.song.metadata.title}")
                }
            }
        }
    }
}
