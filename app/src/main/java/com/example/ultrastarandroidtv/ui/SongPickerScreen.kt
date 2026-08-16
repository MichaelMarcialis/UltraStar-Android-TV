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
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.graphics.Color
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
    /** Back to the microphones and names, which is one step up rather than out. */
    onChangeSingers: () -> Unit,
    onMenu: () -> Unit,
) {
    val context = LocalContext.current
    val location = remember { LibraryLocation(context) }

    var treeUri by remember { mutableStateOf<Uri?>(location.saved()) }
    var songs by remember { mutableStateOf<List<ScannedSong>>(emptyList()) }
    var status by remember { mutableStateOf("Looking for songs…") }
    var focusedIndex by remember { mutableIntStateOf(0) }

    val first = remember { FocusRequester() }
    BackHandler(onBack = onChangeSingers)

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

            // The two ways out, spelled out rather than left to the remote's back button. The
            // singers are already chosen by this point, and the commonest reason to leave this
            // screen is that the wrong person ended up holding the wrong microphone.
            Spacer(Modifier.height(20.dp))
            Row {
                Button(onClick = onChangeSingers) {
                    Text("Change singers", modifier = Modifier.padding(horizontal = 12.dp))
                }
                Spacer(Modifier.width(16.dp))
                Button(onClick = onMenu) {
                    Text("Main menu", modifier = Modifier.padding(horizontal = 12.dp))
                }
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
                    // Only meaningful with two people in the room: on your own, a duet is
                    // collapsed to a single line and there is nothing to distinguish.
                    badge = if (playerCount == 2) badgeFor(scanned) else null,
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

/** What a badge says, and the colour it says it in. */
private class SongBadge(val label: String, val color: Color)

/**
 * Nearly opaque, unlike the panels over the song video.
 *
 * A badge sits on album artwork, which is an image chosen by somebody else and is as likely to be
 * near-white as near-black. The gameplay panels can afford to be translucent because they always
 * sit over the same thing; this cannot.
 */
private val BADGE_BACKGROUND = Color(0xEE090A0F)

/**
 * Whether two singers will get different parts or the same one.
 *
 * The UltraStar format does say: a song written as a duet marks its lines `P1` and `P2`, and the
 * parser turns those into separate voice parts with their own notes, lyrics and timings.
 * Everything else is a single melody, which two people can still sing at once — they just sing
 * the same line and the scores are a comparison rather than a division of labour.
 *
 * Gold for a duet because it is the rarer and more interesting answer; the ordinary case is
 * stated plainly so that "no badge" never has to mean "not checked yet".
 */
private fun badgeFor(scanned: ScannedSong): SongBadge =
    if (scanned.song.voiceParts.size >= 2) {
        SongBadge("Duet", GameTheme.sparkWarm)
    } else {
        SongBadge("Versus", Color(0xFFDCE2ED))
    }

@Composable
private fun SongCard(
    scanned: ScannedSong,
    tree: SafDocumentTree?,
    badge: SongBadge?,
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

            badge?.let {
                Text(
                    it.label,
                    style = MaterialTheme.typography.labelMedium
                        .copy(fontWeight = FontWeight.Bold),
                    color = it.color,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(10.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(BADGE_BACKGROUND)
                        .padding(horizontal = 10.dp, vertical = 5.dp),
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
