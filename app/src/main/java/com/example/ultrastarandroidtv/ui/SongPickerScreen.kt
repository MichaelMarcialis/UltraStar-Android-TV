package com.example.ultrastarandroidtv.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.example.ultrastarandroidtv.game.GameTheme
import com.example.ultrastarandroidtv.library.CoverLoader
import com.example.ultrastarandroidtv.library.LibraryLocation
import com.example.ultrastarandroidtv.library.SafDocumentTree
import com.example.ultrastarandroidtv.library.ScannedSong
import com.example.ultrastarandroidtv.library.SongLibraryScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Wide enough that a cover reads across a room, narrow enough to show several at once. */
private val CARD_WIDTH = 230.dp

/**
 * How long a song must stay focused before its preview starts.
 *
 * Without this, scrolling along the row would start and abandon a stream per card, which stutters
 * and sounds like a fault. With it, moving through the library is silent and stopping on
 * something plays it.
 */
private const val PREVIEW_DELAY_MS = 450L

/** Where to start a preview when the song does not say. Far enough in to be past the intro. */
private const val FALLBACK_PREVIEW_SECONDS = 45.0

/**
 * Pick a song.
 *
 * A horizontal row of covers rather than a list of filenames, because that is how anyone
 * actually recognises a song, and because a TV row is the one layout a directional pad
 * navigates without thinking. Focusing a card plays a few seconds of it, so the library can be
 * browsed by ear as well as by eye — which matters most for exactly the people this is for,
 * who may not read quickly yet.
 */
@Composable
fun SongPickerScreen(
    playerCount: Int,
    onPlay: (ChosenSong) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val location = remember { LibraryLocation(context) }

    var treeUri by remember { mutableStateOf<Uri?>(location.saved()) }
    var songs by remember { mutableStateOf<List<ScannedSong>>(emptyList()) }
    var status by remember { mutableStateOf("Looking for songs…") }
    var focusedIndex by remember { mutableIntStateOf(0) }

    val first = remember { FocusRequester() }
    BackHandler(onBack = onBack)

    val tree = remember(treeUri) {
        treeUri?.let { SafDocumentTree(context.contentResolver, it) }
    }

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
        val currentTree = tree ?: run {
            status = "Choose the folder your songs are in."
            return@LaunchedEffect
        }
        status = "Scanning…"
        val found = withContext(Dispatchers.IO) { SongLibraryScanner(currentTree).scan() }
        songs = found.songs
            .filter { it.isPlayable }
            .sortedBy { it.song.metadata.title.lowercase() }
        status = if (songs.isEmpty()) "No playable songs found." else "${songs.size} songs"
    }

    LaunchedEffect(songs) {
        if (songs.isNotEmpty()) runCatching { first.requestFocus() }
    }

    // One player for the whole screen, reused as focus moves. Building an ExoPlayer is not
    // cheap and doing it per card would be felt.
    val preview = remember { ExoPlayer.Builder(context).build() }
    DisposableEffect(Unit) {
        onDispose { preview.release() }
    }

    LaunchedEffect(focusedIndex, songs, tree) {
        preview.pause()
        val song = songs.getOrNull(focusedIndex) ?: return@LaunchedEffect
        val audioId = song.audioId ?: return@LaunchedEffect
        val currentTree = tree ?: return@LaunchedEffect

        delay(PREVIEW_DELAY_MS)

        runCatching {
            preview.setMediaItem(MediaItem.fromUri(currentTree.uriFor(audioId)))
            preview.prepare()
            val start = song.song.metadata.previewStartSeconds ?: FALLBACK_PREVIEW_SECONDS
            preview.seekTo((start * 1000).toLong())
            preview.volume = 0.75f
            preview.play()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(GameTheme.background)
            .padding(vertical = 48.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 56.dp)) {
            Text(
                if (playerCount == 1) "Pick a song — one singer" else "Pick a song — two singers",
                style = MaterialTheme.typography.headlineMedium,
                color = GameTheme.lyricActive,
            )
            Text(status, style = MaterialTheme.typography.bodyMedium, color = GameTheme.lyricIdle)

            if (treeUri == null) {
                Spacer(Modifier.height(20.dp))
                Button(onClick = { picker.launch(null) }) { Text("Choose song folder") }
            }
        }

        Spacer(Modifier.height(28.dp))

        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 56.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            itemsIndexed(songs) { index, scanned ->
                SongCard(
                    scanned = scanned,
                    tree = tree,
                    onFocused = { focusedIndex = index },
                    onSelect = {
                        val audioId = scanned.audioId ?: return@SongCard
                        val currentTree = tree ?: return@SongCard
                        preview.stop()
                        onPlay(
                            ChosenSong(
                                song = scanned.song,
                                audioUri = currentTree.uriFor(audioId).toString(),
                                videoUri = scanned.videoId
                                    ?.let { currentTree.uriFor(it).toString() },
                            ),
                        )
                    },
                    modifier = if (index == 0) Modifier.focusRequester(first) else Modifier,
                )
            }
        }
    }
}

@Composable
private fun SongCard(
    scanned: ScannedSong,
    tree: SafDocumentTree?,
    onFocused: () -> Unit,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var focused by remember { mutableStateOf(false) }
    var cover by remember(scanned.coverId) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(scanned.coverId, tree) {
        val coverId = scanned.coverId ?: return@LaunchedEffect
        val uri = tree?.uriFor(coverId) ?: return@LaunchedEffect
        cover = withContext(Dispatchers.IO) {
            CoverLoader.load(context.contentResolver, uri)
        }
    }

    Column(
        modifier = modifier
            .width(CARD_WIDTH)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            }
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                if (event.key == Key.DirectionCenter || event.key == Key.Enter) {
                    onSelect()
                    true
                } else {
                    false
                }
            },
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(14.dp))
                .background(GameTheme.trackBackground)
                .then(
                    if (focused) {
                        Modifier.border(3.dp, GameTheme.playerColors[0], RoundedCornerShape(14.dp))
                    } else {
                        Modifier
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            val art = cover
            if (art != null) {
                Image(
                    bitmap = art,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                // Songs without artwork still need something to aim at, and the initial is
                // enough to tell two neighbouring cards apart at a glance.
                Text(
                    scanned.song.metadata.title.take(1).uppercase(),
                    style = MaterialTheme.typography.displayMedium,
                    color = GameTheme.lyricIdle,
                )
            }
        }

        Spacer(Modifier.height(10.dp))
        Text(
            scanned.song.metadata.title,
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
            color = if (focused) GameTheme.lyricActive else GameTheme.lyricIdle,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            scanned.song.metadata.artist,
            style = MaterialTheme.typography.bodySmall,
            color = GameTheme.lyricIdle,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
