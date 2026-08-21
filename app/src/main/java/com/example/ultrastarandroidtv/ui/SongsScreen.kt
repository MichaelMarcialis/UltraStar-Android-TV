package com.example.ultrastarandroidtv.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.example.ultrastarandroidtv.game.GameTheme
import com.example.ultrastarandroidtv.library.LibraryLocation
import com.example.ultrastarandroidtv.library.SafDocumentTree
import com.example.ultrastarandroidtv.library.ScannedSong
import com.example.ultrastarandroidtv.library.SongLibraryCache
import com.example.ultrastarandroidtv.library.SongLibraryScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private enum class SongsMode { List, Managing, Confirming }

/**
 * What is on the card, and what is wrong with it.
 *
 * **This is the only screen that shows a song which cannot be sung.** The picker deliberately
 * hides them — offering a card that fails when pressed is worse than not offering it — but
 * hiding them everywhere is how a library quietly fills with rubbish. Twenty-two of the
 * seventy-one folders on this card hold a chart and a cover and no audio at all, and until this
 * screen existed the only way to find that out was to take the card to a computer.
 *
 * The three things it does are the three that otherwise need a PC: say where the songs are and
 * change it, say which ones are broken and why, and remove one.
 *
 * **Removing needs a write grant, which older builds never asked for.** Read and write are
 * separate permissions handed out once by the folder picker, and there is no later prompt that
 * widens one. So a folder chosen by a previous version is readable and nothing more, and the
 * honest response is to say so and offer the picker — not to show a Remove button that fails.
 */
@Composable
fun SongsScreen(
    cache: SongLibraryCache,
    onAddSongs: () -> Unit,
    onMenu: () -> Unit,
) {
    val context = LocalContext.current
    val location = remember { LibraryLocation(context) }

    var treeUri by remember { mutableStateOf<Uri?>(location.saved()) }
    var canModify by remember { mutableStateOf(location.canModify()) }
    var songs by remember { mutableStateOf(cache.songs) }
    var status by remember { mutableStateOf("") }
    var scanning by remember { mutableStateOf(false) }
    var counted by remember { mutableIntStateOf(0) }
    var rescans by remember { mutableIntStateOf(0) }

    var mode by remember { mutableStateOf(SongsMode.List) }
    var selected by remember { mutableStateOf<ScannedSong?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }

    val first = remember { FocusRequester() }
    LaunchedEffect(mode, songs, scanning) {
        withFrameNanos { }
        runCatching { first.requestFocus() }
    }

    BackHandler {
        if (mode == SongsMode.List) {
            onMenu()
        } else {
            mode = SongsMode.List
            problem = null
        }
    }

    val tree = remember(treeUri) {
        treeUri?.let { SafDocumentTree(context.contentResolver, it) }
    }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { picked ->
        when {
            picked == null -> Unit
            location.remember(picked) -> {
                cache.clear()
                treeUri = picked
                canModify = location.canModify()
                problem = null
            }
            else -> problem = "Android would not keep access to that folder. Try another."
        }
    }

    LaunchedEffect(treeUri, rescans) {
        val currentTree = tree ?: run {
            status = "Choose the folder your songs are in."
            return@LaunchedEffect
        }

        if (cache.holds(treeUri)) {
            songs = cache.songs
            status = tally(songs)
            return@LaunchedEffect
        }

        scanning = true
        counted = 0
        status = "Reading the card…"
        val found = withContext(Dispatchers.IO) {
            SongLibraryScanner(currentTree).scan { counted++ }
        }
        cache.put(treeUri, found.songs.sortedBy { it.song.metadata.title.lowercase() })
        songs = cache.songs
        scanning = false
        status = tally(songs)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(GameTheme.background)
            .padding(56.dp),
    ) {
        when (mode) {
            SongsMode.List -> {
                Text(
                    "Songs",
                    style = MaterialTheme.typography.headlineLarge,
                    color = GameTheme.lyricActive,
                )
                Text(
                    if (scanning) "Found $counted so far…" else status,
                    style = MaterialTheme.typography.bodyLarge,
                    color = GameTheme.lyricIdle,
                )

                if (scanning) {
                    Spacer(Modifier.height(18.dp))
                    LoadingBar()
                }

                problem?.let {
                    Spacer(Modifier.height(12.dp))
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = GameTheme.sparkWarm)
                }

                // Said once, at the top, rather than on every song: the reason Remove is missing
                // is about the folder, not about the song being looked at.
                if (treeUri != null && !canModify) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Choose the folder again to remove songs — the current permission is " +
                            "read-only.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = GameTheme.sparkWarm,
                    )
                }

                Spacer(Modifier.height(20.dp))
                PickerHint()

                Spacer(Modifier.height(20.dp))
                Row {
                    Button(
                        onClick = { picker.launch(null) },
                        modifier = Modifier.focusRequester(first),
                    ) {
                        Text(
                            if (treeUri == null) "Choose song folder" else "Change folder",
                            modifier = Modifier.padding(horizontal = 12.dp),
                        )
                    }
                    Spacer(Modifier.width(16.dp))
                    // Downloading needs somewhere to put a song, so it is offered only once a
                    // folder is chosen -- the same rule Play follows on the main menu.
                    Button(onClick = onAddSongs, enabled = treeUri != null) {
                        Text("Add songs", modifier = Modifier.padding(horizontal = 12.dp))
                    }
                    Spacer(Modifier.width(16.dp))
                    Button(
                        onClick = {
                            cache.clear()
                            rescans++
                        },
                    ) {
                        Text("Rescan", modifier = Modifier.padding(horizontal = 12.dp))
                    }
                    Spacer(Modifier.width(16.dp))
                    Button(onClick = onMenu) {
                        Text("Main menu", modifier = Modifier.padding(horizontal = 12.dp))
                    }
                }

                Spacer(Modifier.height(28.dp))

                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    itemsIndexed(songs) { _, scanned ->
                        SongRow(
                            scanned = scanned,
                            onSelect = {
                                selected = scanned
                                mode = SongsMode.Managing
                            },
                        )
                    }
                }
            }

            SongsMode.Managing -> {
                val song = selected
                if (song == null) {
                    mode = SongsMode.List
                } else {
                    val sharing = songs.count { it.folderId == song.folderId }

                    Text(
                        song.song.metadata.title.ifBlank { song.folderName },
                        style = MaterialTheme.typography.headlineMedium,
                        color = GameTheme.lyricActive,
                    )
                    Text(
                        song.song.metadata.artist.ifBlank { "Unknown artist" },
                        style = MaterialTheme.typography.bodyLarge,
                        color = GameTheme.lyricIdle,
                    )
                    Spacer(Modifier.height(20.dp))

                    Text(
                        "In folder “${song.folderName}”",
                        style = MaterialTheme.typography.bodyMedium,
                        color = GameTheme.lyricIdle,
                    )
                    Spacer(Modifier.height(10.dp))
                    Inventory("Audio", song.audioId != null, required = true)
                    Inventory("Video", song.videoId != null, required = false)
                    Inventory("Cover", song.coverId != null, required = false)

                    if (sharing > 1) {
                        Spacer(Modifier.height(14.dp))
                        Text(
                            "This folder holds $sharing songs, so only this arrangement is " +
                                "removed — the others keep their files.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = GameTheme.lyricIdle,
                        )
                    }

                    problem?.let {
                        Spacer(Modifier.height(14.dp))
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = GameTheme.sparkWarm)
                    }

                    Spacer(Modifier.height(28.dp))
                    Row {
                        Button(
                            onClick = {
                                mode = SongsMode.List
                                problem = null
                            },
                            modifier = Modifier.focusRequester(first),
                        ) {
                            Text("Back", modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                        }
                        if (canModify) {
                            Spacer(Modifier.width(16.dp))
                            Button(onClick = { mode = SongsMode.Confirming }) {
                                Text(
                                    "Remove",
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                                )
                            }
                        }
                    }
                }
            }

            SongsMode.Confirming -> {
                val song = selected
                if (song == null) {
                    mode = SongsMode.List
                } else {
                    val sharing = songs.count { it.folderId == song.folderId }

                    Text(
                        "Remove ${song.song.metadata.title.ifBlank { song.folderName }}?",
                        style = MaterialTheme.typography.headlineSmall,
                        color = GameTheme.lyricActive,
                    )
                    Text(
                        if (sharing > 1) {
                            "Only this song's text file is deleted. The audio and artwork stay " +
                                "for the other songs in the folder."
                        } else {
                            "The whole folder is deleted from the card — song, audio, video and " +
                                "artwork. This cannot be undone."
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        color = GameTheme.lyricIdle,
                    )
                    Spacer(Modifier.height(28.dp))

                    // Keep takes the focus, not Remove. On a remote the confirmation and the press
                    // that caused it are the same button, so a default of "yes" would make this no
                    // safer than having no confirmation at all.
                    Row {
                        Button(
                            onClick = {
                                mode = SongsMode.Managing
                            },
                            modifier = Modifier.focusRequester(first),
                        ) {
                            Text("Keep", modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                        }
                        Spacer(Modifier.width(16.dp))
                        Button(
                            onClick = {
                                val currentTree = tree
                                // A folder to itself goes entirely; one shared with another
                                // arrangement loses only its own text file.
                                val target = if (sharing > 1) song.textId else song.folderId
                                val gone = currentTree?.delete(target) == true
                                if (gone) {
                                    if (sharing > 1) {
                                        cache.forget(song.textId)
                                    } else {
                                        cache.forgetFolder(song.folderId)
                                    }
                                    songs = cache.songs
                                    status = tally(songs)
                                    selected = null
                                    problem = null
                                    mode = SongsMode.List
                                } else {
                                    problem = "That could not be deleted. The card may be " +
                                        "read-only or removed."
                                    mode = SongsMode.Managing
                                }
                            },
                        ) {
                            Text("Remove", modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                        }
                    }
                }
            }
        }
    }
}

/** One line of the inventory. [required] is what separates "broken" from "just doesn't have one". */
@Composable
private fun Inventory(label: String, present: Boolean, required: Boolean) {
    val colour = when {
        present -> GameTheme.lyricIdle
        required -> GameTheme.sparkWarm
        else -> GameTheme.noteIdle
    }
    Text(
        "$label: " + if (present) "yes" else if (required) "missing" else "none",
        style = MaterialTheme.typography.bodyMedium,
        color = colour,
    )
}

@Composable
private fun SongRow(scanned: ScannedSong, onSelect: () -> Unit) {
    val fault = faultWith(scanned)
    Button(onClick = onSelect, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    scanned.song.metadata.title.ifBlank { scanned.folderName },
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    scanned.song.metadata.artist.ifBlank { "Unknown artist" },
                    fontSize = 15.sp,
                    color = GameTheme.lyricIdle,
                )
            }
            Text(
                fault ?: "Ready",
                fontSize = 16.sp,
                color = if (fault == null) GameTheme.lyricIdle else GameTheme.sparkWarm,
            )
        }
    }
}

/**
 * What stops this song being sung, or null.
 *
 * Only the audio is fatal. A song with no video plays over the visualiser and a song with no
 * cover shows a plain card, and calling either of those a fault would bury the twenty-two that
 * genuinely cannot be played among forty that are perfectly fine.
 */
private fun faultWith(scanned: ScannedSong): String? =
    if (scanned.audioId == null) "No audio" else null

private fun tally(songs: List<ScannedSong>): String {
    val broken = songs.count { it.audioId == null }
    val ready = songs.size - broken
    return when {
        songs.isEmpty() -> "No songs found in that folder."
        broken == 0 -> "$ready ready to sing."
        else -> "$ready ready to sing, $broken missing audio."
    }
}
