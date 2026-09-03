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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.mutableStateMapOf
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.example.ultrastarandroidtv.download.Downloads
import com.example.ultrastarandroidtv.download.RepairPlan
import com.example.ultrastarandroidtv.download.RepairStatus
import com.example.ultrastarandroidtv.download.SongRepairer
import com.example.ultrastarandroidtv.download.repairQueueProgress
import com.example.ultrastarandroidtv.download.repairStatusLabel
import com.example.ultrastarandroidtv.download.repairSummary
import com.example.ultrastarandroidtv.game.GameTheme
import com.example.ultrastarandroidtv.library.CoverLoader
import com.example.ultrastarandroidtv.library.LibraryLocation
import com.example.ultrastarandroidtv.library.SafDocumentTree
import com.example.ultrastarandroidtv.library.ScannedSong
import com.example.ultrastarandroidtv.library.SongLibraryCache
import com.example.ultrastarandroidtv.library.SongLibraryScanner
import com.example.ultrastarandroidtv.library.SongFilterState
import com.example.ultrastarandroidtv.library.SongSort
import com.example.ultrastarandroidtv.library.songOrder
import com.example.ultrastarandroidtv.library.arrange
import com.example.ultrastarandroidtv.library.firstIndexUnder
import com.example.ultrastarandroidtv.library.indexLetters
import com.example.ultrastarandroidtv.library.matches
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** How many frames to keep asking for the focus while the grid composes the card. */
private const val FOCUS_ATTEMPTS = 12

private enum class SongsMode { List, Managing, Confirming }

/**
 * Five across.
 *
 * Enough that a fifty-song library is two screens rather than ten, and not so many that a cover
 * stops being recognisable from the sofa — which is the whole reason for showing covers at all.
 */
private const val SONG_COLUMNS = 5

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
    downloads: Downloads,
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

    var sort by remember { mutableStateOf(SongSort.Title) }
    var filter by remember { mutableStateOf(SongFilterState.All) }
    var jumpTo by remember { mutableStateOf<Int?>(null) }

    /**
     * Album covers, decoded once each and kept for the visit.
     *
     * Filled in by the cards themselves as they compose, which means only what is on screen is
     * ever read: a cover is a Storage Access Framework read and a decode, and doing all seventy
     * up front would put a second scan's worth of work in front of a screen that has just
     * finished scanning.
     */
    val covers = remember { mutableStateMapOf<String, ImageBitmap?>() }

    var mode by remember { mutableStateOf(SongsMode.List) }
    var selected by remember { mutableStateOf<ScannedSong?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }

    /**
     * The grid's scroll, kept outside the `when` that chooses between the list and one song.
     *
     * It used to be remembered *inside* the list branch, so opening a song threw it away and
     * coming back rebuilt it at the top. Reported from the sofa: inspecting the fortieth song
     * and pressing Back put you on the first, which on a library of a hundred means finding your
     * place again every time you look at anything.
     */
    val gridState = rememberLazyGridState()

    /** The song to come back to, and the requester its card carries while it is on screen. */
    var returningTo by remember { mutableStateOf<String?>(null) }
    val returning = remember { FocusRequester() }

    val first = remember { FocusRequester() }
    LaunchedEffect(mode, songs, scanning) {
        withFrameNanos { }
        // Coming back from a song, the song is where the focus belongs -- not on the header
        // button, which would leave the cursor at the top of a grid scrolled to the middle.
        if (mode == SongsMode.List && returningTo != null) return@LaunchedEffect
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

    LaunchedEffect(songs, tree) {
        val currentTree = tree ?: return@LaunchedEffect
        for (song in songs) {
            val coverId = song.coverId ?: continue
            if (covers.containsKey(song.textId)) continue
            covers[song.textId] = withContext(Dispatchers.IO) {
                runCatching {
                    CoverLoader.decode(currentTree.readBytes(coverId), maxPixels = 256)
                }.getOrNull()
            }
        }
    }

    val repairer = remember(tree) {
        tree?.let {
            SongRepairer(
                youTube = downloads.youTube,
                artwork = downloads.artwork,
                http = downloads.http,
                tree = it,
                writer = it,
                measureCover = CoverLoader::shortestEdge,
            )
        }
    }

    /**
     * What can be done for the song being looked at, or null when nothing can.
     *
     * Worked out when the song is opened rather than for the whole list, because answering it can
     * mean reading a `.usdb` file — one round trip through the Storage Access Framework, which is
     * nothing for one song and most of a rescan for seventy.
     *
     * **The button only exists when there is an answer.** A Repair that says "there is nothing I
     * can do about this" is worse than no Repair: it invites somebody to press it, wait, and be
     * told off. A song whose folder does not say where its media came from simply does not offer
     * one, and the screen says why.
     */
    var plan by remember { mutableStateOf<RepairPlan?>(null) }
    LaunchedEffect(selected, repairer) {
        plan = null
        val song = selected ?: return@LaunchedEffect
        val fixer = repairer ?: return@LaunchedEffect
        plan = withContext(Dispatchers.IO) {
            runCatching { fixer.plan(song, cache.generation) }.getOrNull()
        }
    }

    /**
     * Every song with something worth fetching, and the plan for each.
     *
     * **Everything, not only the unplayable ones.** It started as songs missing their music, on the
     * grounds that "repair everything" should not quietly mean downloading two dozen music videos.
     * The user's call was that it should — a song with no video and a song with a 200-pixel
     * thumbnail are both worse than they need to be, and the machinery to fix them was already
     * written. It is one press either way, and the count in the button says how big a job it is.
     *
     * The cost is a survey: a `.usdb` file for a song whose chart names no video, and the header of
     * every cover to see whether it is a thumbnail. Off the main thread, once per scan, and the
     * button simply appears when it has an answer.
     */
    var repairable by remember { mutableStateOf<List<Pair<ScannedSong, RepairPlan>>>(emptyList()) }
    LaunchedEffect(songs, repairer) {
        val fixer = repairer ?: return@LaunchedEffect
        repairable = if (songs.isEmpty()) emptyList() else withContext(Dispatchers.IO) {
            songs.mapNotNull { song ->
                runCatching { fixer.plan(song, cache.generation) }.getOrNull()
                    ?.let { song to it }
            }
        }
    }

    /**
     * Songs measured as wearing a thumbnail rather than artwork.
     *
     * Falls out of the survey above, which has already read and measured every cover — so the
     * filter costs nothing beyond what Repair was doing anyway. It is the one filter state the
     * scanner cannot answer on its own.
     */
    val softArtwork = remember(repairable) {
        repairable.filter { it.second.needsBetterCover }.map { it.first.textId }.toSet()
    }

    // Back to the song that was being looked at: scroll to it, then focus it.
    //
    // Two steps and both are needed. A lazy grid does not compose what is off screen, so the
    // card's focus requester does not exist until the scroll has brought it into view -- the
    // same rule the song picker documents for its row, and the same wait for frames.
    LaunchedEffect(mode, returningTo, songs) {
        val textId = returningTo ?: return@LaunchedEffect
        if (mode != SongsMode.List) return@LaunchedEffect
        val at = arrange(songs, sort, filter, softArtwork).indexOfFirst { it.textId == textId }
        if (at >= 0) {
            gridState.scrollToItem(at)
            repeat(FOCUS_ATTEMPTS) {
                withFrameNanos { }
                if (runCatching { returning.requestFocus() }.isSuccess) return@repeat
            }
        } else {
            runCatching { first.requestFocus() }
        }
        returningTo = null
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

    LaunchedEffect(treeUri, rescans, cache.revision) {
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
        cache.put(treeUri, found.songs.sortedWith(songOrder(SongSort.Title)))
        songs = cache.songs
        scanning = false
        status = tally(songs)
    }

    // A finished batch rescans itself, once.
    //
    // Without this the screen keeps saying "Audio: missing" about a song it has just repaired,
    // beside a line saying it got the music -- and being told two opposite things at once is worse
    // than being told the slow one. Waiting until the queue is idle rather than rescanning per
    // song matters: a rescan is several seconds of card reads, and doing one after each of
    // nineteen repairs would cost more than the repairs.
    val repairsBusy = downloads.repairs.isBusy
    LaunchedEffect(repairsBusy) {
        if (!repairsBusy && downloads.repairs.fixedCount > 0) {
            cache.clear()
            rescans++
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(GameTheme.background)
            .padding(horizontal = 40.dp, vertical = 28.dp),
    ) {
        when (mode) {
            SongsMode.List -> {
                // Title and tally on the left, everything you can do on the right. A row rather
                // than a stack because vertical space is what the grid wants: the whole reason for
                // a grid is that a television is wide and a list of song titles uses about a fifth
                // of that.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text(
                            "Songs",
                            style = MaterialTheme.typography.headlineMedium,
                            color = GameTheme.lyricActive,
                        )
                        Text(
                            if (scanning) "Found $counted so far…" else status,
                            style = MaterialTheme.typography.bodyMedium,
                            color = GameTheme.lyricIdle,
                        )
                    }
                    Spacer(Modifier.weight(1f))

                    Button(
                        onClick = { picker.launch(null) },
                        modifier = Modifier.focusRequester(first),
                    ) {
                        Text(
                            if (treeUri == null) "Choose folder" else "Change folder",
                            modifier = Modifier.padding(horizontal = 8.dp),
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    // Downloading needs somewhere to put a song, so it is offered only once a
                    // folder is chosen -- the same rule Play follows on the main menu.
                    Button(onClick = onAddSongs, enabled = treeUri != null) {
                        Text("Add songs", modifier = Modifier.padding(horizontal = 8.dp))
                    }
                    Spacer(Modifier.width(10.dp))
                    Button(onClick = { cache.clear(); rescans++ }) {
                        Text("Rescan", modifier = Modifier.padding(horizontal = 8.dp))
                    }
                    Spacer(Modifier.width(10.dp))
                    Button(onClick = onMenu) {
                        Text("Main menu", modifier = Modifier.padding(horizontal = 8.dp))
                    }
                }

                problem?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = GameTheme.sparkWarm)
                }

                // Said once, at the top, rather than on every song: the reason Remove is missing
                // is about the folder, not about the song being looked at.
                if (treeUri != null && !canModify) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Choose the folder again to remove songs — the current permission is " +
                            "read-only.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = GameTheme.sparkWarm,
                    )
                }

                // The picker's one awkward rule, said only while it is about to matter. It used to
                // be here always, and a paragraph of instructions about a file dialog is not what
                // this screen is for once a folder has been chosen.
                if (treeUri == null) {
                    Spacer(Modifier.height(16.dp))
                    PickerHint()
                }

                if (scanning) {
                    Spacer(Modifier.height(16.dp))
                    LoadingBar()
                }

                // One bar for the whole batch, the same arrangement the Add-songs screen uses.
                val repairing = repairQueueProgress(downloads.repairs)
                if (repairing != null && downloads.repairs.isBusy) {
                    Spacer(Modifier.height(12.dp))
                    repairSummary(downloads.repairs)?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodyMedium,
                            color = GameTheme.lyricIdle,
                        )
                        Spacer(Modifier.height(4.dp))
                    }
                    ProgressBar(repairing, modifier = Modifier.fillMaxWidth())
                }

                Spacer(Modifier.height(16.dp))

                // Sorting and filtering, small and across the top rather than down the side. This
                // screen is about the songs somebody already has, so the songs get the width; the
                // two questions worth asking about them fit on one line.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Sort", style = MaterialTheme.typography.bodySmall, color = GameTheme.lyricIdle)
                    Spacer(Modifier.width(8.dp))
                    for (option in SongSort.entries) {
                        Chip(
                            label = option.label,
                            selected = sort == option,
                            onClick = { sort = option },
                        )
                        Spacer(Modifier.width(6.dp))
                    }

                    Spacer(Modifier.width(20.dp))
                    Text("Show", style = MaterialTheme.typography.bodySmall, color = GameTheme.lyricIdle)
                    Spacer(Modifier.width(8.dp))
                    for (option in SongFilterState.entries) {
                        Chip(
                            label = option.label,
                            selected = filter == option,
                            onClick = { filter = option },
                            // A filter matching nothing is a dead end on a remote: it takes the
                            // focus, empties the screen, and leaves nowhere obvious to go back to.
                            enabled = songs.any { matches(it, option, softArtwork) },
                        )
                        Spacer(Modifier.width(6.dp))
                    }

                    // Repairing lives on this row rather than up with the other actions: six
                    // buttons across the top wrapped "Main menu" into two lines, which is exactly
                    // the fault that made UltraStar Play unusable. It also belongs here -- it is a
                    // thing to do *about* what the filters are showing.
                    //
                    // Two things are left out of the count, and both were reported as bugs. A
                    // song already queued or done is held by the queue; a song whose exact
                    // repair has been tried and came back with nothing is remembered, because
                    // whether better artwork exists anywhere cannot be known without asking, and
                    // asking the same fruitless question every visit is how "Repair 7 songs"
                    // came to mean seven songs that would all fail.
                    val waiting = repairable.filterNot {
                        downloads.repairs.holds(it.first.textId, it.second.scan) ||
                            downloads.repairMemory.triedInVain(it.first.textId, it.second)
                    }
                    if (waiting.isNotEmpty()) {
                        Spacer(Modifier.weight(1f))
                        Button(
                            onClick = {
                                waiting.forEach {
                                    downloads.repairs.add(it.first, it.second, treeUri?.toString())
                                }
                            },
                        ) {
                            Text(
                                "Repair " + waiting.size + if (waiting.size == 1) " song" else " songs",
                                modifier = Modifier.padding(horizontal = 8.dp),
                            )
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))

                val arranged = remember(songs, sort, filter, softArtwork) {
                    arrange(songs, sort, filter, softArtwork)
                }
                // Indexed from the *filtered* list, which is the one a letter jumps into.
                // Taken from the whole library instead, a filter like "No music" left letters on
                // the rail with nothing behind them: pressing one found no song and silently did
                // nothing, which on a remote is indistinguishable from a broken button.
                val letters = remember(arranged, sort) { indexLetters(arranged, sort) }

                // Re-sorting starts at the top.
                //
                // A lazy grid keeps its scroll *offset* when its contents change, so re-ordering
                // seventy songs left the view halfway down a list that now meant something else
                // entirely -- which reads as the app having decided to show you a random song.
                // Arranging a library is a fresh look at it, and a fresh look starts at the
                // beginning. Focus stays on the chip that was just pressed.
                LaunchedEffect(sort, filter) {
                    if (returningTo == null) gridState.scrollToItem(0)
                }

                Row(modifier = Modifier.fillMaxSize()) {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(SONG_COLUMNS),
                        state = gridState,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        items(arranged, key = { it.textId }) { scanned ->
                            SongCard(
                                scanned = scanned,
                                cover = covers[scanned.textId],
                                onSelect = {
                                    selected = scanned
                                    returningTo = scanned.textId
                                    mode = SongsMode.Managing
                                },
                                modifier = if (scanned.textId == returningTo) {
                                    Modifier.focusRequester(returning)
                                } else {
                                    Modifier
                                },
                            )
                        }
                    }

                    if (letters.size > 1) {
                        Spacer(Modifier.width(10.dp))
                        LetterRail(
                            letters = letters,
                            modifier = Modifier.fillMaxHeight(),
                            onJump = { letter ->
                                val at = firstIndexUnder(arranged, sort, letter)
                                if (at >= 0) jumpTo = at
                            },
                        )
                    }
                }

                // Scrolling is done here rather than in the rail's own click, because a jump is a
                // suspending call and a button press is not -- and because the rail must keep the
                // focus while the grid moves underneath it. Taking focus to the grid would mean
                // one press per letter and then a journey back for the next.
                LaunchedEffect(jumpTo) {
                    val at = jumpTo ?: return@LaunchedEffect
                    gridState.scrollToItem(at)
                    jumpTo = null
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

                    // Said where the Repair button would have been, so its absence is explained
                    // rather than merely noticed.
                    if (!song.isPlayable && plan == null) {
                        Spacer(Modifier.height(14.dp))
                        Text(
                            "This song does not say where its music came from, so it cannot be " +
                                "repaired. Download it again from Add songs.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = GameTheme.lyricIdle,
                        )
                    }

                    val job = downloads.repairs.jobs.firstOrNull { it.song.textId == song.textId }
                    job?.let {
                        Spacer(Modifier.height(14.dp))
                        Text(
                            repairStatusLabel(it.status),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (it.status is RepairStatus.Failed) {
                                GameTheme.sparkWarm
                            } else {
                                GameTheme.lyricIdle
                            },
                        )
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
                        plan?.let { ready ->
                            if (canModify && !downloads.repairs.holds(song.textId, ready.scan)) {
                                Spacer(Modifier.width(16.dp))
                                Button(
                                    onClick = {
                                        downloads.repairs.add(song, ready, treeUri?.toString())
                                    },
                                ) {
                                    Text(
                                        repairLabel(ready),
                                        modifier = Modifier.padding(
                                            horizontal = 16.dp,
                                            vertical = 4.dp,
                                        ),
                                    )
                                }
                            }
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

/**
 * What a Repair button offers, in its own words.
 *
 * Naming the missing part rather than saying "Repair" is the difference between an action somebody
 * can predict and one they have to try. Getting a song's music back and fetching a nicer picture
 * for one that already plays are not the same act, and one word for both would make the bigger one
 * look trivial and the smaller one look alarming.
 */
private fun repairLabel(plan: RepairPlan): String = when {
    plan.needsAudio -> "Get the music"
    plan.needsVideo && plan.needsCover -> "Get the video and artwork"
    plan.needsVideo -> "Get the video"
    else -> "Get the artwork"
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

/**
 * One song, as a card.
 *
 * The cover carries it, because on a shelf of seventy songs a picture is recognised from across the
 * room and a title has to be read. A song with no cover still gets a card of exactly the same size
 * with a plain panel where the picture goes — the grid must not go ragged over what is, on this
 * card, a quarter of the library.
 *
 * The fault line only appears when there is a fault. "Ready" on every one of fifty-seven cards is
 * fifty-seven words nobody reads, and it makes the nineteen that matter harder to see rather than
 * easier.
 */
@Composable
private fun SongCard(
    scanned: ScannedSong,
    cover: ImageBitmap?,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val fault = faultWith(scanned)
    Button(
        onClick = onSelect,
        modifier = modifier.fillMaxWidth(),
        // A rectangle, said explicitly. `androidx.tv.material3.Button` is a *pill* by default,
        // which is right for a word and catastrophic for anything tall: a card came out as an
        // ellipse with its own title clipped off at the sides -- "7 Years" reading as "Years".
        // The same shape the dome-shaped download row turned out to be, arrived at from the
        // other direction.
        shape = ButtonDefaults.shape(shape = RoundedCornerShape(10.dp)),
        contentPadding = PaddingValues(0.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(6.dp))
                    .background(GameTheme.noteIdle),
            ) {
                cover?.let {
                    Image(
                        bitmap = it,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                scanned.song.metadata.title.ifBlank { scanned.folderName },
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                scanned.song.metadata.artist.ifBlank { "Unknown artist" },
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = GameTheme.lyricIdle,
            )
            if (fault != null) {
                Text(fault, fontSize = 12.sp, maxLines = 1, color = GameTheme.sparkWarm)
            }
        }
    }
}

/**
 * The letters down the right-hand edge, for jumping.
 *
 * On the right because that is where every alphabetical index has been since address books, and
 * because the grid is what the left of the screen is for. The one rule it has to obey on a remote:
 * **it keeps the focus while the grid moves**. Scrolling by taking focus to the target card would
 * cost a press to get back for the next letter, which is the opposite of what a jump list is for.
 */
@Composable
private fun LetterRail(
    letters: List<Char>,
    modifier: Modifier = Modifier,
    onJump: (Char) -> Unit,
) {
    Column(
        modifier = modifier.width(46.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        for (letter in letters) {
            Button(
                onClick = { onJump(letter) },
                modifier = Modifier.fillMaxWidth().weight(1f),
                colors = ButtonDefaults.colors(containerColor = GameTheme.trackBackground),
                shape = ButtonDefaults.shape(shape = RoundedCornerShape(4.dp)),
                contentPadding = PaddingValues(0.dp),
            ) {
                Text(letter.toString(), fontSize = 14.sp, color = GameTheme.lyricIdle)
            }
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
