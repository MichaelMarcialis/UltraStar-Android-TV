package com.example.ultrastarandroidtv.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.MediaItem
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.example.ultrastarandroidtv.audio.previewPlayer
import com.example.ultrastarandroidtv.game.GameTheme
import com.example.ultrastarandroidtv.game.StarRow
import com.example.ultrastarandroidtv.library.CoverLoader
import com.example.ultrastarandroidtv.library.LibraryFilter
import com.example.ultrastarandroidtv.library.LibraryLocation
import com.example.ultrastarandroidtv.library.SafDocumentTree
import com.example.ultrastarandroidtv.library.ScannedSong
import com.example.ultrastarandroidtv.library.SongLibraryCache
import com.example.ultrastarandroidtv.library.SongMode
import com.example.ultrastarandroidtv.library.SongSort
import com.example.ultrastarandroidtv.library.browse
import com.example.ultrastarandroidtv.library.decadeChoices
import com.example.ultrastarandroidtv.library.decadeLabel
import com.example.ultrastarandroidtv.library.firstIndexUnder
import com.example.ultrastarandroidtv.library.genreChoices
import com.example.ultrastarandroidtv.library.indexLetters
import com.example.ultrastarandroidtv.library.isDuetChart
import com.example.ultrastarandroidtv.library.songOrder
import com.example.ultrastarandroidtv.library.SongLibraryScanner
import com.example.ultrastarandroidtv.score.MAX_SCORE
import com.example.ultrastarandroidtv.score.starsFor
import com.example.ultrastarandroidtv.settings.HighScore
import com.example.ultrastarandroidtv.settings.HighScores
import com.example.ultrastarandroidtv.settings.Profiles
import com.example.ultrastarandroidtv.settings.scoreIfKnown
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Wide enough that a cover reads across a room, narrow enough to show several at once.
 *
 * Down from 230 when the card gained a line for the record and the screen gained a row of controls
 * above it and a row of letters below. Everything on this screen is competing for the same 1080
 * lines, and the cover is the part that degrades most gracefully.
 */
private val CARD_WIDTH = 200.dp

/**
 * Keeps whatever is focused in the middle of the row.
 *
 * A television has no pointer, so the focused card *is* the cursor — and a cursor that sits
 * wherever the last scroll happened to leave it reads as the row having drifted. Compose's
 * default is to scroll the least it can get away with, which is right for a list somebody is
 * dragging and wrong for one somebody is stepping through: the card lands hard against whichever
 * edge it came in from and stays there.
 *
 * Done through the bring-into-view spec rather than by scrolling on focus, which matters. The
 * spec *is* the scroll the focus already asks for, so there is one movement; an extra
 * `animateScrollToItem` on top would be a second one chasing the first, and the two are visibly
 * different animations.
 *
 * [offset] is the card's leading edge measured from the viewport's, [size] is the card and
 * [containerSize] the viewport, so the distance to travel is however far the card is from where
 * a centred card would start.
 */
/**
 * Where the focused card sits: hard against the left margin, in line with everything else.
 *
 * It was centred for a day, which is a defensible answer and not the one the screen wanted. The
 * page's heading, its chips and its letter rail all start at [ROW_PADDING], and a focused card
 * floating in the middle of the row is the one element on the screen that answers to nothing.
 * Left-aligned, the cursor sits on a line the eye already knows, and the whole row reads as a
 * list being stepped through rather than a carousel being spun.
 *
 * The lookahead is the other half of it: everything to the right of the focused card is songs
 * not yet considered, where centring spent half the screen on ones already passed.
 */
private val ROW_PADDING = 56.dp

/**
 * Puts the focused card at the left margin, whichever direction it was reached from.
 *
 * A spec rather than a scroll on focus, and that distinction matters: the spec *is* the scroll
 * the focus already asks for, so there is one movement. An `animateScrollToItem` on top would be
 * a second animation chasing the first, and the two look visibly different.
 *
 * Compose's default is to scroll the least it can get away with, which leaves the card against
 * whichever edge it arrived at — right when stepping right, left when stepping left. Correct for
 * a list being dragged, wrong for one being stepped through, because the cursor's position then
 * depends on which way you came.
 *
 * [offset] is the card's leading edge measured from the viewport's, so the distance to travel is
 * however far that is from the margin.
 */
@OptIn(ExperimentalFoundationApi::class)
private fun leftAligned(marginPx: Float) = object : BringIntoViewSpec {
    override fun calculateScrollDistance(
        offset: Float,
        size: Float,
        containerSize: Float,
    ): Float = offset - marginPx
}

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

/** Frames to keep trying for focus while the row settles. A fifth of a second, then give up. */
private const val FOCUS_ATTEMPTS = 12

/**
 * How many copies of the library the row holds when it loops.
 *
 * The row does not wrap by jumping any more, it simply goes on: the list is the library laid end
 * to end many times over, and moving off the last song walks straight onto the first because that
 * is genuinely the next card along. Nothing has to be intercepted and nothing teleports.
 *
 * Four hundred copies of a hundred songs is forty thousand positions, which costs nothing — a lazy
 * list composes what is on the screen and takes the count as a number. It is finite rather than
 * `Int.MAX_VALUE` so that scrolling to the far end is a real place rather than an overflow.
 */
private const val LOOP_COPIES = 400

/**
 * Fewest songs worth looping.
 *
 * About four cards fit across the television, so below this a copy would be visible beside its own
 * original — the same album twice on one screen, which reads as a bug rather than as a loop. A
 * library this short fits on the screen anyway, so wrapping it by jumping is invisible.
 */
private const val LOOP_MINIMUM = 8

/**
 * What the space under the controls is showing.
 *
 * Searching and choosing a genre both need a lot of room and neither is worth a second screen, so
 * they borrow the row's space rather than opening over it. **Deliberately not a `Popup`**: a popup
 * is a separate window, and on a television that means handing focus to something outside this
 * composition and hoping it comes back. Everything here stays in one column, which is also why
 * Back always has exactly one thing to undo.
 */
private enum class PickerPane { Browse, Search, Genre, Decade }

/**
 * Pick a song.
 *
 * A horizontal row of covers rather than a list of filenames, because that is how anyone actually
 * recognises a song, and because a TV row is the one layout a directional pad navigates without
 * thinking. Focusing a card plays a few seconds of it, so the library can be browsed by ear as
 * well as by eye — which matters most for exactly the people this is for, who may not read
 * quickly yet.
 *
 * Above the row: search, sort and the filters. Below it: the letters, laid out across rather than
 * down, because the songs run across and a rail down the side would point the wrong way.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SongPickerScreen(
    playerCount: Int,
    /** Held for the life of the app, so coming back for a second song is instant. */
    cache: SongLibraryCache,
    /** Document id of the song to open on, or null to start at the beginning. */
    openAt: String?,
    /** Whose names may appear on a record. A record by anybody else is not shown — see below. */
    profiles: Profiles,
    onPlay: (ChosenSong) -> Unit,
    /** Back to the microphones and names, which is one step up rather than out. */
    onChangeSingers: () -> Unit,
    onMenu: () -> Unit,
) {
    val context = LocalContext.current
    val location = remember { LibraryLocation(context) }
    val records = remember { HighScores(context) }

    var treeUri by remember { mutableStateOf<Uri?>(location.saved()) }
    var songs by remember { mutableStateOf(cache.playable) }
    var status by remember { mutableStateOf("Looking for songs…") }
    /** The card the cursor is on, or -1 when it has moved off the row entirely. */
    var focusedIndex by remember { mutableIntStateOf(0) }

    // Scanning is the only state with anything to animate, and the count is the only honest
    // measure of progress there is — the total is not known until the walk ends.
    var scanning by remember { mutableStateOf(false) }
    var counted by remember { mutableIntStateOf(0) }

    var sort by remember { mutableStateOf(SongSort.Title) }
    var filter by remember { mutableStateOf(LibraryFilter()) }
    var pane by remember { mutableStateOf(PickerPane.Browse) }

    // The row is scrolled to this card and it takes the focus. Which card that is only becomes
    // known once the songs are in, so it starts attached to nothing. The sequence number is what
    // makes jumping to the *same* card twice a second event rather than a no-op — which the
    // wrap-around needs, since a library of one song wraps to itself.
    val opening = remember { FocusRequester() }
    var openingPosition by remember { mutableIntStateOf(-1) }
    var jumpSeq by remember { mutableIntStateOf(0) }
    /** False until the row has been put somewhere, so the first jump starts in the middle copy. */
    var placed by remember { mutableStateOf(false) }
    val row = rememberLazyListState()
    val marginPx = with(LocalDensity.current) { ROW_PADDING.toPx() }
    val leftMargin = remember(marginPx) { leftAligned(marginPx) }

    BackHandler {
        // One thing at a time, and always the innermost. Leaving the screen from inside the
        // keyboard would be the remote doing two things for one press.
        if (pane != PickerPane.Browse) pane = PickerPane.Browse else onChangeSingers()
    }

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

    LaunchedEffect(treeUri, cache.revision) {
        val currentTree = tree ?: run {
            status = "Choose the folder your songs are in."
            return@LaunchedEffect
        }

        if (cache.holds(treeUri)) {
            songs = cache.playable
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
        cache.put(treeUri, found.songs.sortedWith(songOrder(SongSort.Title)))
        songs = cache.playable
        scanning = false
    }

    val arranged = remember(songs, sort, filter) { browse(songs, sort, filter) }
    val genres = remember(songs) { genreChoices(songs) }
    val decades = remember(songs) { decadeChoices(songs) }
    val letters = remember(arranged, sort) { indexLetters(arranged, sort) }

    /**
     * Every song's record, read once rather than per card per frame.
     *
     * **Filed the way the game about to be played will file it.** A duet sung by two people and
     * the same song sung by one are separate tables, so a card shows the record that whoever is
     * standing there could actually beat — not a number from a different kind of performance.
     *
     * Re-read when the profile list changes, because [scoreIfKnown] is what keeps a deleted
     * singer's name off the screen.
     */
    val bests = remember(arranged, playerCount, profiles.names) {
        arranged.associate { song ->
            val duet = playerCount == 2 && isDuetChart(song)
            song.textId to scoreIfKnown(records.best(song.textId, duet), profiles.names)
        }
    }

    // The row is the library repeated, so that running off one end simply arrives at the other.
    val cycle = arranged.size
    val loops = cycle >= LOOP_MINIMUM
    val cardCount = if (loops) cycle * LOOP_COPIES else cycle

    /** Where the middle copy begins: as much room to scroll backwards as forwards. */
    val loopOrigin = if (loops) (LOOP_COPIES / 2) * cycle else 0

    /**
     * Which *position* in the row shows song [index], chosen as the one nearest where the row
     * already is.
     *
     * That is what keeps a jump short: pressing M while looking at L should move a few cards, not
     * unwind two hundred copies back to some canonical first M.
     */
    fun positionFor(index: Int): Int {
        if (cycle == 0) return -1
        val safe = index.coerceIn(0, cycle - 1)
        if (!loops) return safe
        val from = if (placed) row.firstVisibleItemIndex else loopOrigin
        val base = from - Math.floorMod(from, cycle)
        return listOf(base - cycle, base, base + cycle)
            .map { it + safe }
            .filter { it in 0 until cardCount }
            .minByOrNull { kotlin.math.abs(it - from) }
            ?: (loopOrigin + safe)
    }

    /** Scrolls the row to [index] and puts the focus on it. Everything that moves goes through here. */
    fun jumpTo(index: Int) {
        openingPosition = positionFor(index)
        placed = true
        jumpSeq++
    }

    // Open on the song that was just sung. Coming back to the top of a fifty-song library after
    // finishing something is disorientating — the one card anybody has their bearings from is the
    // one they just chose, and the next song is usually near it.
    LaunchedEffect(songs, openAt) {
        if (songs.isEmpty()) return@LaunchedEffect
        jumpTo(openingIndexFor(arranged.map { it.textId }, openAt))
    }

    // Narrowing or re-filing the library goes back to the beginning. A lazy row keeps its scroll
    // *offset* when its contents change, so re-sorting seventy songs otherwise leaves the view
    // halfway down a list that now means something else — the same fault the songs grid had.
    //
    // **Not on the first composition**, which is the trap: this effect and the one above both run
    // then, and this one runs second, so it would overwrite "open on the song just sung" with
    // "open at the top" every single time.
    var arrangementTouched by remember { mutableStateOf(false) }
    LaunchedEffect(sort, filter) {
        if (arrangementTouched && arranged.isNotEmpty()) {
            // Back to the middle copy as well as to the first song: the row's positions are
            // measured in copies of a list that has just changed length, so where it happened to
            // be says nothing about where it should be now.
            placed = false
            jumpTo(0)
        }
        arrangementTouched = true
    }

    LaunchedEffect(jumpSeq) {
        val position = openingPosition
        if (position < 0 || position >= cardCount) return@LaunchedEffect

        // A LazyRow does not compose what is off screen, so the card has to be brought into view
        // before it can be focused, and the focus requester it carries only exists once it has
        // been composed — hence waiting for frames rather than asking straight away.
        //
        // Landed where the focus is about to ask for it anyway. A LazyRow with content padding
        // puts item zero's leading edge at the margin for a scroll offset of zero, which is
        // exactly where [leftAligned] wants it -- so there is no correcting slide to watch when
        // arriving at the screen or jumping to a letter.
        row.scrollToItem(position, 0)
        repeat(FOCUS_ATTEMPTS) {
            withFrameNanos { }
            if (runCatching { opening.requestFocus() }.isSuccess) return@LaunchedEffect
        }
    }

    // One player for the whole screen, reused as focus moves. Building an ExoPlayer is not
    // cheap and doing it per card would be felt.
    // Levelled: previews are mastered decades apart and run 8.6 dB apart on this library.
    val preview = remember { previewPlayer(context) }
    DisposableEffect(Unit) {
        onDispose { preview.release() }
    }

    // Nothing in the app should still be making a noise once it is not on the screen.
    PauseWhenBackgrounded(preview)

    // Stopped rather than paused, and `focusedIndex` really does become -1: a card reports losing
    // the focus as well as taking it, so walking up to the controls silences the sample instead of
    // leaving it playing under a screen nobody is pointing at any more.
    LaunchedEffect(focusedIndex, arranged, tree, pane) {
        preview.stop()
        // Nothing plays while the keyboard or a filter list is up: the card under `focusedIndex`
        // is not what anybody is looking at, and a song starting up under a keyboard sounds like
        // a stray press.
        if (pane != PickerPane.Browse) return@LaunchedEffect
        val song = arranged.getOrNull(focusedIndex) ?: return@LaunchedEffect
        val audioId = song.audioId ?: return@LaunchedEffect
        val currentTree = tree ?: return@LaunchedEffect

        delay(PREVIEW_DELAY_MS)

        runCatching {
            preview.setMediaItem(MediaItem.fromUri(currentTree.uriFor(audioId)))
            preview.prepare()
            val start = song.song.metadata.previewStartSeconds ?: FALLBACK_PREVIEW_SECONDS
            preview.seekTo((start * 1000).toLong())
            // Full scale here, because PreviewLevel has already brought the clip to a
            // fixed loudness -- turning it down again would only undo half of that.
            preview.volume = 1f
            preview.play()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(GameTheme.background)
            .padding(vertical = 32.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = ROW_PADDING)) {
            // The ways out live in the top-right corner, which was empty, rather than on a row of
            // their own under the title. That row is what the controls now use, and six buttons
            // across the top is how "Main menu" ends up wrapped onto two lines.
            Row(verticalAlignment = Alignment.Bottom) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        if (playerCount == 1) "Pick a song — one singer" else "Pick a song — two singers",
                        style = MaterialTheme.typography.headlineMedium,
                        color = GameTheme.lyricActive,
                    )
                    Text(
                        when {
                            scanning -> "Found $counted so far…"
                            treeUri == null -> status
                            songs.isEmpty() -> "No playable songs found."
                            else -> countLabel(arranged.size, songs.size, filter)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = GameTheme.lyricIdle,
                    )
                }
                Button(onClick = onChangeSingers) {
                    Text("Change singers", modifier = Modifier.padding(horizontal = 10.dp))
                }
                Spacer(Modifier.width(12.dp))
                Button(onClick = onMenu) {
                    Text("Main menu", modifier = Modifier.padding(horizontal = 10.dp))
                }
            }

            if (scanning) {
                Spacer(Modifier.height(14.dp))
                LoadingBar()
            }

            if (treeUri == null) {
                Spacer(Modifier.height(16.dp))
                Button(onClick = { picker.launch(null) }) { Text("Choose song folder") }
            }

            if (songs.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                Controls(
                    playerCount = playerCount,
                    sort = sort,
                    filter = filter,
                    pane = pane,
                    genreCount = genres.size,
                    decadeCount = decades.size,
                    onSort = { sort = it },
                    onFilter = { filter = it },
                    onPane = { pane = if (pane == it) PickerPane.Browse else it },
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        when (pane) {
            PickerPane.Search -> SearchPane(
                query = filter.query,
                found = arranged.size,
                onQuery = { filter = filter.copy(query = it) },
                onDone = { pane = PickerPane.Browse },
                modifier = Modifier.padding(horizontal = ROW_PADDING),
            )

            PickerPane.Genre -> ChoicePane(
                title = "Genre",
                options = genres,
                label = { it },
                selected = filter.genre,
                onPick = {
                    filter = filter.copy(genre = it)
                    pane = PickerPane.Browse
                },
                modifier = Modifier.padding(horizontal = ROW_PADDING),
            )

            PickerPane.Decade -> ChoicePane(
                title = "Decade",
                options = decades,
                label = ::decadeLabel,
                selected = filter.decade,
                onPick = {
                    filter = filter.copy(decade = it)
                    pane = PickerPane.Browse
                },
                modifier = Modifier.padding(horizontal = ROW_PADDING),
            )

            PickerPane.Browse -> {
                if (arranged.isEmpty() && songs.isNotEmpty()) {
                    Text(
                        "Nothing matches. Clear the filters to see the rest.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = GameTheme.lyricIdle,
                        modifier = Modifier.padding(horizontal = ROW_PADDING),
                    )
                }

                CompositionLocalProvider(LocalBringIntoViewSpec provides leftMargin) {
                    LazyRow(
                        state = row,
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = ROW_PADDING),
                        horizontalArrangement = Arrangement.spacedBy(20.dp),
                    ) {
                        // The library, repeated. A position is not a song: several positions show the
                        // same one, which is the whole mechanism — moving right off the last song
                        // lands on the first because that is the next card, not because anything
                        // caught the key press and put the row somewhere else.
                        items(count = cardCount, key = { it }) { position ->
                            val index = if (loops) position % cycle else position
                            val scanned = arranged[index]
                            SongCard(
                                scanned = scanned,
                                tree = tree,
                                // Only meaningful with two people in the room: on your own, a duet is
                                // collapsed to a single line and there is nothing to distinguish.
                                badge = if (playerCount == 2) badgeFor(scanned) else null,
                                record = bests[scanned.textId],
                                onFocused = { focusedIndex = index },
                                // Guarded, because focus moves card to card as "lost, then gained":
                                // clearing unconditionally would throw away the one just reported.
                                onBlurred = { if (focusedIndex == index) focusedIndex = -1 },
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
                                // Only a library too short to loop is caught at the ends, and then the
                                // whole of it is on the screen anyway, so the jump is invisible.
                                onWrap = { forward ->
                                    jumpTo(if (forward) 0 else arranged.lastIndex)
                                },
                                isFirst = !loops && position == 0,
                                isLast = !loops && position == cardCount - 1,
                                modifier = if (position == openingPosition) {
                                    Modifier.focusRequester(opening)
                                } else {
                                    Modifier
                                },
                            )
                        }
                    }
                }

                // Under the songs and running the same way they do. The management grid puts its
                // letters down the right-hand side because that grid fills downwards; this row
                // fills across, and a rail at right angles to the thing it indexes is a rail
                // pointing the wrong way.
                //
                // Built from the **arranged** list, not the whole library: a letter offered under
                // a filter that has nothing behind it does nothing when pressed, which on a remote
                // cannot be told apart from a broken button.
                if (letters.size > 1) {
                    Spacer(Modifier.height(12.dp))
                    LetterBar(
                        letters = letters,
                        onJump = { letter ->
                            val index = firstIndexUnder(arranged, sort, letter)
                            if (index >= 0) jumpTo(index)
                        },
                        modifier = Modifier.padding(horizontal = ROW_PADDING),
                    )
                }
            }
        }
    }
}

/**
 * Search, sort and the filters, on one line above the songs.
 *
 * Each of these is a *state* rather than an action, so they are chips: a control that looks the
 * same before and after it is pressed cannot say which of five things is in force.
 *
 * **Genre and decade open a list rather than cycling.** This library has seventeen genres, and a
 * chip that steps to the next one on every press is seventeen presses to reach the wrong end of
 * the alphabet. Sort and mode have two and three values, which is few enough that showing them all
 * is cheaper than hiding them.
 */
@Composable
private fun Controls(
    playerCount: Int,
    sort: SongSort,
    filter: LibraryFilter,
    pane: PickerPane,
    genreCount: Int,
    decadeCount: Int,
    onSort: (SongSort) -> Unit,
    onFilter: (LibraryFilter) -> Unit,
    onPane: (PickerPane) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Chip(
            label = if (filter.query.isBlank()) "Search" else "Search: ${filter.query}",
            selected = pane == PickerPane.Search || filter.query.isNotBlank(),
            onClick = { onPane(PickerPane.Search) },
        )

        Spacer(Modifier.width(18.dp))
        ControlLabel("Sort")
        for (option in SongSort.entries) {
            Chip(
                label = option.label,
                selected = sort == option,
                onClick = { onSort(option) },
            )
            Spacer(Modifier.width(6.dp))
        }

        Spacer(Modifier.width(12.dp))
        Chip(
            label = filter.genre?.let { "Genre: $it" } ?: "Genre",
            selected = pane == PickerPane.Genre || filter.genre != null,
            onClick = { onPane(PickerPane.Genre) },
            // Nothing on this card says what it is, so there is nothing to choose between.
            enabled = genreCount > 0,
        )
        Spacer(Modifier.width(6.dp))
        Chip(
            label = filter.decade?.let { "Decade: ${decadeLabel(it)}" } ?: "Decade",
            selected = pane == PickerPane.Decade || filter.decade != null,
            onClick = { onPane(PickerPane.Decade) },
            enabled = decadeCount > 0,
        )

        // Only ever asked with two people in the room. On your own a duet collapses to one line,
        // so "duet or versus" is a question about a game nobody is playing.
        if (playerCount == 2) {
            Spacer(Modifier.width(18.dp))
            ControlLabel("Mode")
            for (option in SongMode.entries) {
                Chip(
                    label = option.label,
                    selected = filter.mode == option,
                    onClick = { onFilter(filter.copy(mode = option)) },
                )
                Spacer(Modifier.width(6.dp))
            }
        }

        // One button that undoes all of it. Four filters cleared one at a time is four chances to
        // leave one on, and a filter still quietly in force looks exactly like a library that has
        // lost songs.
        if (!filter.isEmpty) {
            Spacer(Modifier.width(18.dp))
            Chip(
                label = "Clear",
                selected = false,
                onClick = { onFilter(LibraryFilter()) },
            )
        }
    }
}

@Composable
private fun ControlLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = GameTheme.lyricIdle,
        modifier = Modifier.padding(end = 8.dp),
    )
}

/**
 * Typing, with the keys drawn on the page.
 *
 * The same keyboard the add-songs screen uses, for the same reason: Gboard covers the bottom two
 * thirds of this television and about half its width, which on this screen is every song card. See
 * [KeyGrid], which carries the full argument.
 *
 * The count updates as the letters arrive, so there is never a moment where somebody has to guess
 * whether to keep typing — and it is what makes it safe to leave the cards hidden while typing,
 * because the number says what is waiting behind.
 */
@Composable
private fun SearchPane(
    query: String,
    found: Int,
    onQuery: (String) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { first.requestFocus() }
    }

    // The pane owns the cursor; the screen owns the words. Created when the pane opens, so the
    // caret starts after whatever was typed last rather than at the beginning of it.
    var typed by remember { mutableStateOf(TypedQuery(query)) }
    val edit: (TypedQuery) -> Unit = {
        typed = it
        onQuery(it.text)
    }

    Column(modifier = modifier) {
        QueryDisplay(typed)
        Spacer(Modifier.height(8.dp))
        // Only once there is something to count. Empty, `QueryDisplay` already shows the prompt,
        // and saying "type a song or artist" twice in two lines reads as a rendering fault.
        Text(
            if (query.isBlank()) " " else "$found matching",
            style = MaterialTheme.typography.bodyMedium,
            color = GameTheme.lyricIdle,
        )
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.Top) {
            KeyGrid(
                onKey = { edit(typed.insert(it)) },
                onBackspace = { edit(typed.backspace()) },
                onDelete = { edit(typed.forwardDelete()) },
                // Moving the caret changes no words, so the library is not re-filtered for it.
                onLeft = { typed = typed.left() },
                onRight = { typed = typed.right() },
                onClear = { edit(typed.cleared()) },
                modifier = Modifier.focusRequester(first),
            )
            Spacer(Modifier.width(28.dp))
            Column {
                Button(onClick = onDone) {
                    Text("Show songs", modifier = Modifier.padding(horizontal = 14.dp))
                }
            }
        }
    }
}

/**
 * One filter's values, as a list to walk down.
 *
 * "Any" is first and is always there, because the way out of a filter has to be as easy to find as
 * the way in — and on a remote the first thing in a list is the cheapest thing to reach.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> ChoicePane(
    title: String,
    options: List<T>,
    label: (T) -> String,
    selected: T?,
    onPick: (T?) -> Unit,
    modifier: Modifier = Modifier,
) {
    // "Any" takes the focus, and is first for the same reason: the way out of a filter has to be
    // as easy to find as the way in, and on a remote the first thing in a list is the cheapest
    // thing to reach.
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { first.requestFocus() }
    }

    Column(modifier = modifier) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = GameTheme.lyricActive)
        Spacer(Modifier.height(10.dp))

        // Wrapped left to right rather than stacked, which is how a list of short labels is read
        // and how a directional pad expects to walk one. Seventeen genres down the side of a
        // 1080-line screen runs off the bottom, and this pane has no room to scroll: it is
        // standing in the row's place, and the row is what the screen is for.
        val entries: List<T?> = listOf(null) + options
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            for (option in entries) {
                Chip(
                    label = option?.let(label) ?: "Any",
                    selected = option == selected,
                    onClick = { onPick(option) },
                    modifier = if (option == null) Modifier.focusRequester(first) else Modifier,
                )
            }
        }
    }
}

/**
 * The letters, across the bottom.
 *
 * Every letter here has songs behind it — see the note at the call site. Pressing one moves the
 * row rather than opening anything, so it is the only control on the screen whose effect is
 * somewhere else on the screen; the row moving *is* the feedback.
 */
@Composable
private fun LetterBar(
    letters: List<Char>,
    onJump: (Char) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (letter in letters) {
            Button(
                onClick = { onJump(letter) },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.colors(containerColor = GameTheme.trackBackground),
                shape = ButtonDefaults.shape(shape = RoundedCornerShape(4.dp)),
                contentPadding = PaddingValues(vertical = 2.dp),
            ) {
                Text(letter.toString(), fontSize = 14.sp, color = GameTheme.lyricIdle)
            }
        }
    }
}

/**
 * Which card the library should open on.
 *
 * Falls back to the beginning when the song is not there, which is not a theoretical case: a
 * rescan between one song and the next can remove the very song that was just sung, and a library
 * that opened on nothing would be a library that could not be navigated at all.
 */
fun openingIndexFor(songIds: List<String>, openAt: String?): Int =
    songIds.indexOf(openAt).coerceAtLeast(0)

/**
 * How many songs are on offer, and — when that is not all of them — how many there are altogether.
 *
 * Saying "12 songs" while a filter is on is the one number that could be read as the library
 * having lost sixty of them, which is exactly the alarm a filter should never raise.
 */
private fun countLabel(shown: Int, total: Int, filter: LibraryFilter): String = when {
    !filter.isEmpty -> "$shown of $total songs"
    total == 1 -> "1 song"
    else -> "$total songs"
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
    if (isDuetChart(scanned)) {
        SongBadge("Duet", GameTheme.sparkWarm)
    } else {
        SongBadge("Versus", Color(0xFFDCE2ED))
    }

@Composable
private fun SongCard(
    scanned: ScannedSong,
    tree: SafDocumentTree?,
    badge: SongBadge?,
    /** The score to beat on this song, or null if nobody the app still knows has set one. */
    record: HighScore?,
    onFocused: () -> Unit,
    onBlurred: () -> Unit,
    onSelect: () -> Unit,
    /** Ran off the end of the row: true going right, false going left. */
    onWrap: (Boolean) -> Unit,
    isFirst: Boolean,
    isLast: Boolean,
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
                if (it.isFocused) onFocused() else onBlurred()
            }
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when {
                    event.key == Key.DirectionCenter || event.key == Key.Enter -> {
                        onSelect()
                        true
                    }
                    // Only the two ends are intercepted. Everywhere else the ordinary focus
                    // search does the work, and taking that over would mean reimplementing it.
                    event.key == Key.DirectionRight && isLast -> {
                        onWrap(true)
                        true
                    }
                    event.key == Key.DirectionLeft && isFirst -> {
                        onWrap(false)
                        true
                    }
                    else -> false
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

        Spacer(Modifier.height(8.dp))
        Text(
            scanned.song.metadata.title,
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
            color = if (focused) GameTheme.lyricActive else GameTheme.lyricIdle,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            scanned.song.metadata.artist,
            style = MaterialTheme.typography.bodySmall,
            color = GameTheme.lyricIdle,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        // The lane is there whether or not anybody has a record, so that a row of cards does not
        // step up and down as it scrolls past the songs nobody has finished yet.
        Box(modifier = Modifier.height(RECORD_LANE).fillMaxWidth()) {
            record?.let {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StarRow(
                        stars = starsFor(it.points.toDouble() / MAX_SCORE),
                        starSize = 11.dp,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        it.name,
                        style = MaterialTheme.typography.bodySmall,
                        color = GameTheme.sparkWarm,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** Tall enough for a row of small stars and a name beside them. */
private val RECORD_LANE = 20.dp
