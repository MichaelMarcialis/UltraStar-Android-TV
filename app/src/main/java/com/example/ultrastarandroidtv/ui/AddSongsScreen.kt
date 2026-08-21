package com.example.ultrastarandroidtv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.example.ultrastarandroidtv.download.DownloadOutcome
import com.example.ultrastarandroidtv.download.DownloadQueue
import com.example.ultrastarandroidtv.download.DownloadStage
import com.example.ultrastarandroidtv.download.QueueStatus
import com.example.ultrastarandroidtv.download.SongDownloader
import com.example.ultrastarandroidtv.download.queueSummary
import com.example.ultrastarandroidtv.download.safeFileName
import com.example.ultrastarandroidtv.download.statusLabel
import com.example.ultrastarandroidtv.game.GameTheme
import com.example.ultrastarandroidtv.library.CoverLoader
import com.example.ultrastarandroidtv.library.LibraryLocation
import com.example.ultrastarandroidtv.library.SafDocumentTree
import com.example.ultrastarandroidtv.library.SongLibraryCache
import com.example.ultrastarandroidtv.net.UrlHttp
import com.example.ultrastarandroidtv.net.YouTubeAudio
import com.example.ultrastarandroidtv.usdb.SignIn
import com.example.ultrastarandroidtv.usdb.SongFilter
import com.example.ultrastarandroidtv.usdb.UsdbAccount
import com.example.ultrastarandroidtv.usdb.UsdbCharts
import com.example.ultrastarandroidtv.usdb.UsdbSearch
import com.example.ultrastarandroidtv.usdb.UsdbSession
import com.example.ultrastarandroidtv.usdb.UsdbSong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** How long a result must stay focused before its sample plays, matching the song picker. */
private const val PREVIEW_DELAY_MS = 450L

/** Frames to keep asking for focus on the first result while the list composes. */
private const val RESULT_FOCUS_ATTEMPTS = 30

private enum class AddMode { SignIn, Browse }

/**
 * Finding songs on USDB and putting them on the card, without a PC.
 *
 * This is the screen the whole `net/`, `usdb/` and `download/` stack exists for. Everything hard
 * happens underneath it — see [SongDownloader] for why nothing is written until the music is in
 * hand, and [DownloadQueue] for why downloads are queued one at a time.
 *
 * **Everyone signs in as themselves.** No account ships with the app, which is the only honest
 * arrangement if this is ever handed to another household: a USDB account is a person's, the
 * throttle is counted against it, and a shared one would make one person's impatience everybody's
 * problem. The login is kept so nobody types a password on a television twice — see [UsdbAccount].
 *
 * **Choosing and waiting happen at once.** A song costs about half a minute of USDB's throttle, so
 * pressing one adds it to a queue and the list stays live. Watching each download finish before
 * choosing the next would make picking five songs a five-minute stare at a progress bar.
 *
 * **A song can be heard before it is downloaded.** Every USDB result carries a thirty-second
 * sample, so focusing a row plays it after a moment, exactly as the song picker previews the
 * library. That matters most for the people this is for, who may not read quickly yet and will
 * recognise a song long before they recognise its title.
 */
@Composable
fun AddSongsScreen(
    cache: SongLibraryCache,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val account = remember { UsdbAccount(context) }
    val location = remember { LibraryLocation(context) }
    val http = remember { UrlHttp() }
    val session = remember { UsdbSession(http) }
    val search = remember { UsdbSearch(session) }
    val queue = remember { DownloadQueue() }

    val treeUri = remember { location.saved() }
    val canWrite = remember { location.canModify() }
    val tree = remember(treeUri) { treeUri?.let { SafDocumentTree(context.contentResolver, it) } }

    var mode by remember { mutableStateOf(if (account.hasAccount) AddMode.Browse else AddMode.SignIn) }
    var user by remember { mutableStateOf(account.username) }
    var password by remember { mutableStateOf("") }
    var signingIn by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }

    var artist by remember { mutableStateOf("") }
    var title by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<UsdbSong>>(emptyList()) }
    var resultNote by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var page by remember { mutableStateOf(0) }
    var morePages by remember { mutableStateOf(false) }
    var searchToken by remember { mutableStateOf(0) }
    var focused by remember { mutableStateOf<UsdbSong?>(null) }

    val covers = remember { mutableStateMapOf<Int, ImageBitmap?>() }

    /**
     * Folder names already on the card, so a song you have is marked rather than offered again.
     *
     * Held as live state and added to as downloads land, rather than read from the library cache
     * each time. A successful download *clears* that cache — it has to, so the Songs screen
     * rescans and finds the new song — and reading through to it meant every "Already yours"
     * marker on screen vanished the moment anything downloaded.
     */
    val owned = remember {
        mutableStateListOf<String>().apply {
            addAll(cache.songs.map { it.folderName.lowercase() })
        }
    }

    val first = remember { FocusRequester() }
    LaunchedEffect(mode) {
        withFrameNanos { }
        runCatching { first.requestFocus() }
    }

    /**
     * Focus moves to the first result as soon as a search lands.
     *
     * It is the only way to put the on-screen keyboard away. The keyboard belongs to whichever
     * text field holds focus, and it covers the bottom two-thirds of the television — so
     * searching and then leaving focus in the field means the results arrive underneath a
     * keyboard nobody asked to keep. Moving focus dismisses it and lands on the thing that was
     * just asked for, which is where somebody wants to be anyway.
     *
     * Only when something was found. A search with no results should leave the cursor in the
     * field, because the next thing to do is edit what was typed.
     */
    /**
     * Which result focus should land on — the first one that can actually take it.
     *
     * **Not simply the first row.** A song already on the card is shown but disabled, and a
     * disabled TV button cannot receive focus: asking it to leaves the screen with focus nowhere,
     * which on a remote is a dead end. The same rule the main menu follows for a greyed-out Play.
     * Searching for an artist whose first hit you already own is not a rare case — it is what
     * happens every time somebody looks for more by a band they like.
     *
     * −1 when nothing here can be pressed, in which case focus stays in the search field, which is
     * where the next useful thing to do is anyway.
     */
    val focusIndex = remember(results, owned.size, canWrite, tree) {
        if (!canWrite || tree == null) -1
        else results.indexOfFirst { safeFileName(it.folderName).lowercase() !in owned }
    }

    val firstResult = remember { FocusRequester() }
    LaunchedEffect(results, focusIndex) {
        if (results.isEmpty() || focusIndex < 0) return@LaunchedEffect
        // A LazyColumn has not composed its first row at the moment the results arrive, and the
        // focus requester that row carries does not exist until it has been. Asking once is too
        // early -- the same trap the song picker documents -- so ask on each frame until it takes.
        repeat(RESULT_FOCUS_ATTEMPTS) {
            withFrameNanos { }
            if (runCatching { firstResult.requestFocus() }.isSuccess) return@LaunchedEffect
        }
    }

    BackHandler { onBack() }

    // One player for the whole screen, reused as focus moves -- the song picker's arrangement,
    // for the same reason: building an ExoPlayer per row would stutter the list.
    val preview = remember { ExoPlayer.Builder(context).build() }
    androidx.compose.runtime.DisposableEffect(preview) {
        onDispose { preview.release() }
    }
    LaunchedEffect(focused) {
        preview.pause()
        val sample = focused?.sampleUrl ?: return@LaunchedEffect
        delay(PREVIEW_DELAY_MS)
        runCatching {
            preview.setMediaItem(MediaItem.fromUri(sample))
            preview.prepare()
            preview.volume = 0.75f
            preview.play()
        }
    }

    // Signing in silently when a login is already stored. USDB's session lasts six days, so this
    // is usually the first thing that happens on arrival and nobody should have to press anything.
    LaunchedEffect(mode) {
        if (mode != AddMode.Browse || session.hasSession || !account.hasAccount) return@LaunchedEffect
        signingIn = true
        val outcome = runCatching {
            withContext(Dispatchers.IO) { session.signIn(account.username, account.password()) }
        }
        signingIn = false
        when {
            outcome.getOrNull() == SignIn.SUCCESS -> problem = null
            outcome.isFailure -> problem = "Could not reach USDB. Check the internet connection."
            else -> {
                problem = "USDB did not accept that login any more. Sign in again."
                mode = AddMode.SignIn
            }
        }
    }

    LaunchedEffect(searchToken) {
        if (searchToken == 0) return@LaunchedEffect
        searching = true
        problem = null
        val outcome = runCatching {
            withContext(Dispatchers.IO) {
                search.search(SongFilter(artist = artist, title = title), page)
            }
        }
        searching = false
        outcome.onSuccess { found ->
            results = found.songs
            morePages = found.hasMore
            resultNote = when {
                found.totalResults == 0 -> "Nothing on USDB matches that."
                else -> "${found.totalResults} found — showing ${found.songs.size}" +
                    if (found.totalPages > 1) ", page ${page + 1} of ${found.totalPages}" else ""
            }
        }.onFailure {
            results = emptyList()
            problem = "The search could not reach USDB."
        }
    }

    // Artwork arrives after the list, so the row draws immediately and fills in.
    LaunchedEffect(results) {
        for (song in results) {
            val url = song.coverUrl ?: continue
            if (covers.containsKey(song.songId)) continue
            val image = withContext(Dispatchers.IO) {
                runCatching { CoverLoader.decode(http.getBytes(url), maxPixels = 128) }.getOrNull()
            }
            covers[song.songId] = image
        }
    }

    // The queue, worked one song at a time. See DownloadQueue for why never more than one.
    LaunchedEffect(tree, canWrite) {
        val card = tree ?: return@LaunchedEffect
        if (!canWrite) return@LaunchedEffect
        val downloader = SongDownloader(
            charts = UsdbCharts(session),
            youTube = YouTubeAudio(http),
            http = http,
            tree = card,
            writer = card,
        )
        while (true) {
            val next = queue.nextPending()
            if (next == null) {
                delay(250)
                continue
            }
            next.status = QueueStatus.Working(DownloadStage.FetchingChart)
            val outcome = runCatching {
                withContext(Dispatchers.IO) {
                    downloader.download(next.song) { stage ->
                        next.status = QueueStatus.Working(stage)
                    }
                }
            }.getOrElse {
                DownloadOutcome.Failed(
                    com.example.ultrastarandroidtv.download.DownloadProblem.NETWORK,
                    "The download could not reach the internet.",
                )
            }
            next.status = when (outcome) {
                is DownloadOutcome.Saved -> {
                    // The library has changed on disk, so whatever was scanned is now out of date.
                    cache.clear()
                    owned.add(outcome.folderName.lowercase())
                    QueueStatus.Done(outcome.folderName)
                }
                is DownloadOutcome.Failed -> QueueStatus.Failed(outcome.problem, outcome.message)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(GameTheme.background)
            .padding(48.dp),
    ) {
        when (mode) {
            AddMode.SignIn -> SignInPanel(
                user = user,
                password = password,
                busy = signingIn,
                problem = problem,
                first = first,
                onUser = { user = it },
                onPassword = { password = it },
                onSignIn = {
                    if (user.isBlank() || password.isEmpty()) {
                        problem = "Enter your USDB username and password."
                    } else {
                        signingIn = true
                        problem = null
                    }
                },
                onBack = onBack,
            )

            AddMode.Browse -> BrowsePanel(
                account = account,
                artist = artist,
                title = title,
                results = results,
                covers = covers,
                owned = owned,
                queue = queue,
                note = resultNote,
                problem = problem,
                searching = searching,
                signingIn = signingIn,
                morePages = morePages,
                canWrite = canWrite && tree != null,
                first = first,
                firstResult = firstResult,
                focusIndex = focusIndex,
                onArtist = { artist = it },
                onTitle = { title = it },
                onSearch = {
                    page = 0
                    searchToken++
                },
                onMore = {
                    page++
                    searchToken++
                },
                onFocusSong = { focused = it },
                onPick = { song ->
                    preview.pause()
                    if (!queue.add(song)) problem = "\"${song.title}\" is already in the queue."
                    else problem = null
                },
                onSignOut = {
                    preview.pause()
                    account.forget()
                    session.signOut()
                    password = ""
                    user = ""
                    results = emptyList()
                    mode = AddMode.SignIn
                },
                onBack = onBack,
            )
        }
    }

    // The actual sign-in attempt, kept out of the button so it can run off the main thread.
    LaunchedEffect(signingIn, mode) {
        if (!signingIn || mode != AddMode.SignIn) return@LaunchedEffect
        val outcome = runCatching {
            withContext(Dispatchers.IO) { session.signIn(user, password) }
        }
        signingIn = false
        when {
            outcome.isFailure -> problem = "Could not reach USDB. Check the internet connection."
            outcome.getOrNull() == SignIn.SUCCESS -> {
                account.remember(user, password)
                problem = null
                mode = AddMode.Browse
            }
            else -> problem = "USDB did not accept that username and password."
        }
    }
}

@Composable
private fun SignInPanel(
    user: String,
    password: String,
    busy: Boolean,
    problem: String?,
    first: FocusRequester,
    onUser: (String) -> Unit,
    onPassword: (String) -> Unit,
    onSignIn: () -> Unit,
    onBack: () -> Unit,
) {
    // Everything is kept high and tight on purpose. The on-screen keyboard covers the bottom of
    // the television and roughly the right half with it, and a field that lands under it cannot
    // be read while it is being typed into. The first draft of this screen cleared the keyboard
    // by about four pixels, which is not clearing it.
    Text("Add songs", style = MaterialTheme.typography.headlineMedium, color = GameTheme.lyricActive)
    Text(
        "Songs come from USDB, a free community database. Sign in with your own account — once.",
        style = MaterialTheme.typography.bodyMedium,
        color = GameTheme.lyricIdle,
    )

    Spacer(Modifier.height(16.dp))
    Text("Username", style = MaterialTheme.typography.bodySmall, color = GameTheme.lyricIdle)
    Spacer(Modifier.height(4.dp))
    NameEntry(
        value = user,
        onValueChange = onUser,
        colour = GameTheme.playerColors[0],
        focusRequester = first,
        onDone = { },
        maxLength = 40,
        width = 420.dp,
    )

    Spacer(Modifier.height(10.dp))
    Text("Password", style = MaterialTheme.typography.bodySmall, color = GameTheme.lyricIdle)
    Spacer(Modifier.height(4.dp))
    NameEntry(
        value = password,
        onValueChange = onPassword,
        colour = GameTheme.playerColors[0],
        focusRequester = remember { FocusRequester() },
        onDone = onSignIn,
        maxLength = 64,
        masked = true,
        width = 420.dp,
    )

    Spacer(Modifier.height(16.dp))
    Row {
        Button(onClick = onSignIn) {
            Text("Sign in", modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        }
        Spacer(Modifier.width(16.dp))
        Button(onClick = onBack) {
            Text("Back", modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        }
    }

    // Below the buttons rather than above: anything inserted between the fields and the buttons
    // moves the buttons down into the keyboard exactly when an error has made them matter most.
    problem?.let {
        Spacer(Modifier.height(14.dp))
        Text(it, style = MaterialTheme.typography.bodyMedium, color = GameTheme.sparkWarm)
    }

    if (busy) {
        Spacer(Modifier.height(14.dp))
        Text("Signing in…", style = MaterialTheme.typography.bodyMedium, color = GameTheme.lyricIdle)
        Spacer(Modifier.height(8.dp))
        LoadingBar()
    }
}

@Composable
private fun BrowsePanel(
    account: UsdbAccount,
    artist: String,
    title: String,
    results: List<UsdbSong>,
    covers: Map<Int, ImageBitmap?>,
    owned: List<String>,
    queue: DownloadQueue,
    note: String,
    problem: String?,
    searching: Boolean,
    signingIn: Boolean,
    morePages: Boolean,
    canWrite: Boolean,
    first: FocusRequester,
    firstResult: FocusRequester,
    focusIndex: Int,
    onArtist: (String) -> Unit,
    onTitle: (String) -> Unit,
    onSearch: () -> Unit,
    onMore: () -> Unit,
    onFocusSong: (UsdbSong?) -> Unit,
    onPick: (UsdbSong) -> Unit,
    onSignOut: () -> Unit,
    onBack: () -> Unit,
) {
    // The way out lives up here with the title, not in the search row.
    //
    // It was in the search row to begin with, and on the television "Sign out" wrapped into a
    // vertical stack of letters while "Back" fell off the right-hand edge entirely -- which is
    // precisely the fault that made UltraStar Play unusable and the reason this app exists. Five
    // controls do not fit across a 1920-wide row next to two text fields. The top-right corner
    // was empty, and a way out belongs somewhere predictable anyway.
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Add songs",
            style = MaterialTheme.typography.headlineLarge,
            color = GameTheme.lyricActive,
        )
        Spacer(Modifier.width(20.dp))
        Text(
            if (account.hasAccount) "signed in as ${account.username}" else "",
            style = MaterialTheme.typography.bodyMedium,
            color = GameTheme.lyricIdle,
        )
        Spacer(Modifier.weight(1f))
        Button(onClick = onSignOut) {
            Text("Sign out", modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp))
        }
        Spacer(Modifier.width(12.dp))
        Button(onClick = onBack) {
            Text("Back", modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp))
        }
    }

    // Said where it matters rather than at the point of failure: without a writable folder there
    // is nowhere for a song to go, and finding that out after a thirty-second wait is worse.
    if (!canWrite) {
        Spacer(Modifier.height(10.dp))
        Text(
            "Choose your song folder again on the Songs screen before downloading — the current " +
                "permission does not allow writing to the card.",
            style = MaterialTheme.typography.bodyMedium,
            color = GameTheme.sparkWarm,
        )
    }

    Spacer(Modifier.height(20.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column {
            Text("Artist", style = MaterialTheme.typography.bodySmall, color = GameTheme.lyricIdle)
            Spacer(Modifier.height(4.dp))
            NameEntry(
                value = artist,
                onValueChange = onArtist,
                colour = GameTheme.playerColors[0],
                focusRequester = first,
                onDone = onSearch,
                maxLength = 60,
                width = 320.dp,
            )
        }
        Spacer(Modifier.width(16.dp))
        Column {
            Text("Title", style = MaterialTheme.typography.bodySmall, color = GameTheme.lyricIdle)
            Spacer(Modifier.height(4.dp))
            NameEntry(
                value = title,
                onValueChange = onTitle,
                colour = GameTheme.playerColors[1],
                focusRequester = remember { FocusRequester() },
                onDone = onSearch,
                maxLength = 60,
                width = 320.dp,
            )
        }
        Spacer(Modifier.width(16.dp))
        Button(onClick = onSearch) {
            Text("Search", modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
        }
    }

    Spacer(Modifier.height(14.dp))
    val summary = queueSummary(queue)
    Text(
        when {
            signingIn -> "Signing in to USDB…"
            searching -> "Searching USDB…"
            summary != null -> summary
            note.isNotEmpty() -> note
            else -> "Search for a song to add."
        },
        style = MaterialTheme.typography.bodyLarge,
        color = GameTheme.lyricIdle,
    )
    problem?.let {
        Spacer(Modifier.height(6.dp))
        Text(it, style = MaterialTheme.typography.bodyMedium, color = GameTheme.sparkWarm)
    }

    if (searching || signingIn) {
        Spacer(Modifier.height(14.dp))
        LoadingBar()
    }

    Spacer(Modifier.height(18.dp))
    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        itemsIndexed(results, key = { _, song -> song.songId }) { index, song ->
            val queued = queue.entries.firstOrNull { it.song.songId == song.songId }
            ResultRow(
                song = song,
                cover = covers[song.songId],
                alreadyOnCard = safeFileName(song.folderName).lowercase() in owned,
                state = queued?.status,
                enabled = canWrite,
                onFocus = { onFocusSong(song) },
                onPick = { onPick(song) },
                modifier = if (index == focusIndex) Modifier.focusRequester(firstResult) else Modifier,
            )
        }
        if (morePages) {
            item {
                Button(onClick = onMore, modifier = Modifier.fillMaxWidth()) {
                    Text("More results", modifier = Modifier.padding(vertical = 8.dp))
                }
            }
        }
    }
}

@Composable
private fun ResultRow(
    song: UsdbSong,
    cover: ImageBitmap?,
    alreadyOnCard: Boolean,
    state: QueueStatus?,
    enabled: Boolean,
    onFocus: () -> Unit,
    onPick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onPick,
        // A failed download can be tried again; anything else in the queue cannot be re-added.
        enabled = enabled && !alreadyOnCard && (state == null || state is QueueStatus.Failed),
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { if (it.isFocused) onFocus() },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(46.dp)
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
            Spacer(Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(song.title, fontSize = 19.sp, fontWeight = FontWeight.Medium)
                Text(
                    listOfNotNull(
                        song.artist.ifBlank { null },
                        song.year.ifBlank { null },
                        song.language.ifBlank { null },
                        if (song.hasGoldenNotes) "golden notes" else null,
                    ).joinToString(" · "),
                    fontSize = 14.sp,
                    color = GameTheme.lyricIdle,
                )
            }

            Spacer(Modifier.width(12.dp))
            Text(
                when {
                    state != null -> statusLabel(state)
                    alreadyOnCard -> "Already yours"
                    else -> "Add"
                },
                fontSize = 15.sp,
                color = when {
                    alreadyOnCard -> GameTheme.noteIdle
                    state is QueueStatus.Failed -> GameTheme.sparkWarm
                    state is QueueStatus.Done -> GameTheme.playerColors[0]
                    else -> GameTheme.lyricIdle
                },
            )
        }
    }
}
