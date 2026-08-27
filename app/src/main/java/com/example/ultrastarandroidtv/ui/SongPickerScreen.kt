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
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.runtime.withFrameNanos
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
import com.example.ultrastarandroidtv.audio.LoudnessCache
import com.example.ultrastarandroidtv.audio.gainFor
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.example.ultrastarandroidtv.game.GameTheme
import com.example.ultrastarandroidtv.library.CoverLoader
import com.example.ultrastarandroidtv.library.LibraryLocation
import com.example.ultrastarandroidtv.library.SafDocumentTree
import com.example.ultrastarandroidtv.library.ScannedSong
import com.example.ultrastarandroidtv.library.SongLibraryCache
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
 * How loud a preview is played, before normalisation.
 *
 * Below the target the game itself uses, because this plays while somebody is *choosing* rather
 * than singing, and it has to be possible to talk over it.
 */
private const val PREVIEW_TARGET_DBFS = -19.0

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
    /** Held for the life of the app, so coming back for a second song is instant. */
    cache: SongLibraryCache,
    /** Document id of the song to open on, or null to start at the beginning. */
    openAt: String?,
    onPlay: (ChosenSong) -> Unit,
    /** Back to the microphones and names, which is one step up rather than out. */
    onChangeSingers: () -> Unit,
    onMenu: () -> Unit,
) {
    val context = LocalContext.current
    val location = remember { LibraryLocation(context) }

    var treeUri by remember { mutableStateOf<Uri?>(location.saved()) }
    var songs by remember { mutableStateOf(cache.playable) }
    var status by remember { mutableStateOf("Looking for songs…") }
    var focusedIndex by remember { mutableIntStateOf(0) }

    // Scanning is the only state with anything to animate, and the count is the only honest
    // measure of progress there is — the total is not known until the walk ends.
    var scanning by remember { mutableStateOf(false) }
    var counted by remember { mutableIntStateOf(0) }

    // The row is scrolled to this card and it takes the focus. Which card that is only becomes
    // known once the songs are in, so it starts attached to nothing.
    val opening = remember { FocusRequester() }
    var openingIndex by remember { mutableIntStateOf(-1) }
    val row = rememberLazyListState()

    BackHandler(onBack = onChangeSingers)

    val tree = remember(treeUri) {
        treeUri?.let { SafDocumentTree(context.contentResolver, it) }
    }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { picked ->
        when {
            picked == null -> status = "No folder picked."
            location.remember(picked) -> {
                // A different folder is a different library; the old scan cannot answer for it.
                cache.clear()
                treeUri = picked
            }
            else -> status = "Android would not keep access to that folder. Try another."
        }
    }

    LaunchedEffect(treeUri) {
        val currentTree = tree ?: run {
            status = "Choose the folder your songs are in."
            return@LaunchedEffect
        }

        if (cache.holds(treeUri)) {
            songs = cache.playable
            status = summarise(songs.size)
            return@LaunchedEffect
        }

        scanning = true
        counted = 0
        status = "Reading the card…"

        // `onSong` fires per song as the walk finds them, which is what turns a blank wait into
        // a number going up. The songs themselves are not shown until the end: they arrive in
        // folder order and the row is sorted by title, so filling it in as they came would have
        // every card jump sideways the moment the scan finished.
        val found = withContext(Dispatchers.IO) {
            SongLibraryScanner(currentTree).scan { counted++ }
        }

        // The cache keeps everything, including songs with no audio: those are what the Songs
        // screen exists to explain, and re-finding them would mean walking the card again.
        cache.put(treeUri, found.songs.sortedBy { it.song.metadata.title.lowercase() })
        songs = cache.playable
        scanning = false
        status = summarise(songs.size)
    }

    // Open on the song that was just sung. Coming back to the top of a fifty-song library after
    // finishing something is disorientating — the one card anybody has their bearings from is the
    // one they just chose, and the next song is usually near it.
    LaunchedEffect(songs, openAt) {
        if (songs.isEmpty()) return@LaunchedEffect

        val index = openingIndexFor(songs.map { it.textId }, openAt)
        openingIndex = index

        // A LazyRow does not compose what is off screen, so the card has to be brought into view
        // before it can be focused, and the focus requester it carries only exists once it has
        // been composed — hence waiting for frames rather than asking straight away.
        row.scrollToItem(index)
        repeat(FOCUS_ATTEMPTS) {
            withFrameNanos { }
            if (runCatching { opening.requestFocus() }.isSuccess) return@LaunchedEffect
        }
    }

    // One player for the whole screen, reused as focus moves. Building an ExoPlayer is not
    // cheap and doing it per card would be felt.
    val preview = remember { ExoPlayer.Builder(context).build() }
    val loudness = remember { LoudnessCache(context) }
    DisposableEffect(Unit) {
        onDispose { preview.release() }
    }

    LaunchedEffect(focusedIndex, songs, tree) {
        preview.pause()
        val song = songs.getOrNull(focusedIndex) ?: return@LaunchedEffect
        val audioId = song.audioId ?: return@LaunchedEffect
        val currentTree = tree ?: return@LaunchedEffect

        delay(PREVIEW_DELAY_MS)

        val uri = currentTree.uriFor(audioId)

        // Measured *before* the preview starts rather than applied to one already playing: a
        // volume that jumps a fraction of a second in is more distracting than the difference it
        // is correcting. The measurement is kept, so this is instant for anything sung before.
        //
        // Attenuation only, since a plain player volume cannot boost — which is why the target
        // here is well below the game's. A quiet recording is simply left alone.
        val loudnessOf = withContext(Dispatchers.IO) { loudness.measure(context, uri.toString()) }
        val level = gainFor(loudnessOf, targetDbfs = PREVIEW_TARGET_DBFS).coerceIn(0.05f, 1f)

        runCatching {
            preview.setMediaItem(MediaItem.fromUri(uri))
            preview.prepare()
            val start = song.song.metadata.previewStartSeconds ?: FALLBACK_PREVIEW_SECONDS
            preview.seekTo((start * 1000).toLong())
            preview.volume = level
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
            Text(
                if (scanning) "Found $counted so far…" else status,
                style = MaterialTheme.typography.bodyMedium,
                color = GameTheme.lyricIdle,
            )

            if (scanning) {
                Spacer(Modifier.height(18.dp))
                LoadingBar()
            }

            if (treeUri == null) {
                Spacer(Modifier.height(20.dp))
                Button(onClick = { picker.launch(null) }) { Text("Choose song folder") }
            }

            // The ways out, spelled out rather than left to the remote's back button. The singers
            // are already chosen by this point, and the commonest reason to leave this screen is
            // that the wrong person ended up holding the wrong microphone.
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
            state = row,
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
                                songId = scanned.textId,
                                song = scanned.song,
                                audioUri = currentTree.uriFor(audioId).toString(),
                                videoUri = scanned.videoId
                                    ?.let { currentTree.uriFor(it).toString() },
                            ),
                        )
                    },
                    modifier = if (index == openingIndex) {
                        Modifier.focusRequester(opening)
                    } else {
                        Modifier
                    },
                )
            }
        }
    }
}

/** Frames to keep trying for focus while the row settles. A fifth of a second, then give up. */
private const val FOCUS_ATTEMPTS = 12

/**
 * Which card the library should open on.
 *
 * Falls back to the beginning when the song is not there, which is not a theoretical case: a
 * rescan between one song and the next can remove the very song that was just sung, and a library
 * that opened on nothing would be a library that could not be navigated at all.
 */
fun openingIndexFor(songIds: List<String>, openAt: String?): Int =
    songIds.indexOf(openAt).coerceAtLeast(0)

private fun summarise(count: Int): String = when (count) {
    0 -> "No playable songs found."
    1 -> "1 song"
    else -> "$count songs"
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
