package com.example.ultrastarandroidtv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.example.ultrastarandroidtv.download.DownloadQueue
import com.example.ultrastarandroidtv.download.Downloads
import com.example.ultrastarandroidtv.download.QueueStatus
import com.example.ultrastarandroidtv.download.QueuedSong
import com.example.ultrastarandroidtv.download.SongDownloader
import com.example.ultrastarandroidtv.download.queueProgress
import com.example.ultrastarandroidtv.download.queueSummary
import com.example.ultrastarandroidtv.download.safeFileName
import com.example.ultrastarandroidtv.download.shortStatusLabel
import com.example.ultrastarandroidtv.download.statusLabel
import com.example.ultrastarandroidtv.audio.previewPlayer
import com.example.ultrastarandroidtv.game.GameTheme
import com.example.ultrastarandroidtv.library.CoverLoader
import com.example.ultrastarandroidtv.library.LibraryLocation
import com.example.ultrastarandroidtv.library.filingKey
import com.example.ultrastarandroidtv.library.SongLibraryCache
import com.example.ultrastarandroidtv.net.AudioLookup
import com.example.ultrastarandroidtv.usdb.SignIn
import com.example.ultrastarandroidtv.usdb.SongFilter
import com.example.ultrastarandroidtv.usdb.UsdbAccount
import com.example.ultrastarandroidtv.usdb.UsdbSong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** How long a result must stay focused before its sample plays, matching the song picker. */
private const val PREVIEW_DELAY_MS = 450L

/**
 * How long a result must stay focused before its availability is checked.
 *
 * Longer than the preview delay: hearing a song is the point of pausing on it, whereas this is a
 * background question whose answer only matters if somebody is actually considering the song.
 */
private const val AVAILABILITY_DELAY_MS = 900L

/**
 * How long typing must stop before the search runs.
 *
 * Long, because every character costs several presses of a directional pad and a run of them is
 * one word being spelled rather than several searches being asked for. Short enough that finishing
 * a word and looking up is answered by the time the eye arrives.
 */
private const val SEARCH_DELAY_MS = 800L

/** Below this a search matches most of USDB and means nothing. */
private const val MIN_QUERY = 2

/**
 * The languages worth a button, and the order they are in.
 *
 * A fixed list rather than one built from whatever the last search returned: a filter that appears
 * and disappears as results arrive cannot be aimed at, and the cursor would land on a different
 * language every time the row changed shape. These are the ones USDB actually has in quantity;
 * anything else is still reachable by simply not filtering.
 *
 * The value is sent to USDB rather than used to sift the results here, which matters for the same
 * reason paging does: filtering after the fact would hide most of a page and leave three songs on
 * screen with fifty-six "found".
 */
private val LANGUAGES = listOf(
    "All" to "",
    "English" to "English",
    "German" to "German",
    "Spanish" to "Spanish",
    "French" to "French",
    "Italian" to "Italian",
)

/**
 * USDB's page, in the order it is worth reading on a television.
 *
 * **Artist matches stay first.** That split is deliberate and predates this: one keyword becomes
 * two searches, and somebody who types "queen" almost always means the band rather than every song
 * with the word in its title. Sorting the whole page alphabetically would shuffle the two together
 * and bury what was asked for.
 *
 * Within each half it is alphabetical **past the leading article**, so a page of results is not
 * three quarters of the way through T before it reaches the band anybody was looking for.
 */
internal fun orderedForDisplay(results: List<UsdbSong>, keyword: String): List<UsdbSong> {
    val word = keyword.trim().lowercase()
    val order = compareBy<UsdbSong>(
        { filingKey(it.artist).lowercase() },
        { filingKey(it.title).lowercase() },
    )
    if (word.isEmpty()) return results.sortedWith(order)

    val (byArtist, rest) = results.partition { it.artist.lowercase().contains(word) }
    return byArtist.sortedWith(order) + rest.sortedWith(order)
}

private enum class AddMode { SignIn, Browse }

/**
 * Finding songs on USDB and putting them on the card, without a PC.
 *
 * This is the screen the whole `net/`, `usdb/` and `download/` stack exists for. Everything hard
 * happens underneath it — see [SongDownloader] for why nothing is written until the music is in
 * hand, and [Downloads] for why the queue outlives this screen.
 *
 * **Everyone signs in as themselves.** No account ships with the app, which is the only honest
 * arrangement if this is ever handed to another household: a USDB account is a person's, the
 * throttle is counted against it, and a shared one would make one person's impatience everybody's
 * problem. The login is kept so nobody types a password on a television twice — see [UsdbAccount].
 *
 * ## The shape of the screen
 *
 * A third for asking, two thirds for answers, which is how every television app that searches is
 * laid out — and for a reason worth stating: the controls do not move or change size as results
 * arrive, so whatever the cursor is sitting on stays where it was while the screen fills up.
 *
 * The rail holds a **keyboard drawn on the page** rather than the system one. That is the change
 * that matters most here — see [KeyGrid] for the list of layout injuries Gboard has caused this
 * app — and it is what lets the ways out sit in the rail instead of being exiled to a corner.
 *
 * **Searching runs on its own** once typing stops, because a Search button on a letter grid is one
 * more journey across the screen for something the app can decide for itself. It is affordable
 * precisely here: USDB's search is the part of USDB that is *not* throttled.
 *
 * **A song can be heard before it is downloaded.** Every USDB result carries a thirty-second
 * sample, so focusing a card plays it after a moment, exactly as the song picker previews the
 * library. That matters most for the people this is for, who may not read quickly yet and will
 * recognise a song long before they recognise its title.
 */
@Composable
fun AddSongsScreen(
    cache: SongLibraryCache,
    downloads: Downloads,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val account = remember { UsdbAccount(context) }
    val location = remember { LibraryLocation(context) }

    // Everything that outlives this screen comes from [Downloads] -- the session so that walking
    // away does not sign you out, and the queue so that walking away does not cancel a download.
    val http = downloads.http
    val session = downloads.session
    val search = downloads.search
    val details = downloads.details
    val youTube = downloads.youTube
    val queue = downloads.queue

    val canWrite = remember { location.canModify() && location.saved() != null }

    var mode by remember { mutableStateOf(if (account.hasAccount) AddMode.Browse else AddMode.SignIn) }
    var user by remember { mutableStateOf(account.username) }
    var password by remember { mutableStateOf("") }
    var signingIn by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }

    var keyword by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<UsdbSong>>(emptyList()) }
    var resultNote by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var page by remember { mutableStateOf(0) }
    var morePages by remember { mutableStateOf(false) }
    var language by remember { mutableStateOf("") }
    var focused by remember { mutableStateOf<UsdbSong?>(null) }

    val covers = remember { mutableStateMapOf<Int, ImageBitmap?>() }

    /**
     * Whether a song's music can actually be fetched — checked for whatever is focused.
     *
     * USDB's detail page names the YouTube video with no throttle attached, so this costs about a
     * second and can be done while somebody is simply looking at a song. Knowing early matters
     * because the alternative is finding out after a 24-second wait.
     *
     * Only the focused song, and only once each: checking every result would be sixty requests for
     * a page nobody has read yet. A check that fails for any other reason records nothing, because
     * a network blip must not label a perfectly good song as broken.
     */
    val downloadable = remember { mutableStateMapOf<Int, Boolean>() }
    LaunchedEffect(focused) {
        val song = focused ?: return@LaunchedEffect
        if (downloadable.containsKey(song.songId)) return@LaunchedEffect
        delay(AVAILABILITY_DELAY_MS)
        val verdict = withContext(Dispatchers.IO) {
            runCatching {
                val videoId = details.fetch(song.songId).videoId ?: return@runCatching null
                youTube.resolve(videoId) is AudioLookup.Found
            }.getOrNull()
        }
        if (verdict != null) downloadable[song.songId] = verdict
    }

    /**
     * Folder names already on the card, so a song you have is marked rather than offered again.
     *
     * The library scan **plus** whatever has been downloaded since, because a download makes that
     * scan out of date the moment it lands. The cache is marked stale rather than emptied for
     * exactly this reason — clearing it outright made every "Already yours" marker on screen
     * vanish the instant anything downloaded, which is the opposite of what just happened.
     */
    val owned = remember(cache.songs, downloads.downloaded.size) {
        (cache.songs.map { it.folderName.lowercase() } + downloads.downloaded).toSet()
    }

    val first = remember { FocusRequester() }
    LaunchedEffect(mode) {
        withFrameNanos { }
        runCatching { first.requestFocus() }
    }

    /**
     * **Nothing takes the focus away from the keyboard.**
     *
     * The list this replaced moved focus to the first result the moment a search landed, and that
     * was right at the time: the system keyboard belonged to whichever text field had focus, so
     * moving focus was the only way to get the keyboard off the screen and let the results be seen.
     *
     * With the keys drawn on the page that reason is gone, and doing it anyway is actively wrong —
     * results now arrive *while somebody is still typing*. Measured on the television: typing
     * "abba" produced "abl", because the search for "ab" returned between the second letter and the
     * third and the grid took the cursor out from under the next press.
     *
     * So the results are simply there, and pressing right moves into them. Focus search skips a
     * card that cannot be pressed, so the case that needed handling before — the first hit being a
     * song you already own — is handled now by not doing anything at all.
     */

    BackHandler { onBack() }

    // One player for the whole screen, reused as focus moves -- the song picker's arrangement,
    // for the same reason: building an ExoPlayer per card would stutter the grid.
    // Levelled: previews are mastered decades apart and run 8.6 dB apart on this library.
    val preview = remember { previewPlayer(context) }
    DisposableEffect(preview) { onDispose { preview.release() } }
    LaunchedEffect(focused) {
        preview.pause()
        val sample = focused?.sampleUrl ?: return@LaunchedEffect
        delay(PREVIEW_DELAY_MS)
        runCatching {
            preview.setMediaItem(MediaItem.fromUri(sample))
            preview.prepare()
            // Full scale here, because PreviewLevel has already brought the clip to a
            // fixed loudness -- turning it down again would only undo half of that.
            preview.volume = 1f
            preview.play()
        }
    }

    // Signing in silently when a login is already stored. USDB's session lasts six days and now
    // outlives this screen, so after the first visit this usually does nothing at all.
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

    // The search itself, run on the query rather than on a button.
    //
    // Keyed on the words *and* the page, so turning a page re-runs it and editing the query starts
    // again at the first. The delay is what makes a run of key presses one search: a new keystroke
    // cancels this effect before it has finished waiting, which is debouncing for free.
    LaunchedEffect(keyword, page, language) {
        val words = keyword.trim()
        if (words.length < MIN_QUERY) {
            results = emptyList()
            resultNote = ""
            return@LaunchedEffect
        }
        delay(SEARCH_DELAY_MS)
        searching = true
        problem = null
        val outcome = runCatching {
            withContext(Dispatchers.IO) {
                search.search(SongFilter(keyword = words, language = language), page)
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

    // Artwork arrives after the grid, so a card draws immediately and fills in.
    LaunchedEffect(results) {
        for (song in results) {
            val url = song.coverUrl ?: continue
            if (covers.containsKey(song.songId)) continue
            val image = withContext(Dispatchers.IO) {
                runCatching { CoverLoader.decode(http.getBytes(url), maxPixels = 256) }.getOrNull()
            }
            covers[song.songId] = image
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(GameTheme.background)) {
        when (mode) {
            AddMode.SignIn -> Column(modifier = Modifier.fillMaxSize().padding(48.dp)) {
                SignInPanel(
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
            }

            AddMode.Browse -> Row(modifier = Modifier.fillMaxSize().padding(36.dp)) {
                SearchRail(
                    account = account,
                    keyword = keyword,
                    canWrite = canWrite,
                    firstKey = first,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    onKey = { keyword += it; page = 0 },
                    onBackspace = { keyword = keyword.dropLast(1); page = 0 },
                    onClear = { keyword = ""; page = 0 },
                    onSignOut = {
                        preview.pause()
                        account.forget()
                        session.signOut()
                        password = ""
                        user = ""
                        results = emptyList()
                        keyword = ""
                        mode = AddMode.SignIn
                    },
                    onBack = onBack,
                )

                Spacer(Modifier.width(28.dp))

                ResultsPanel(
                    language = language,
                    onLanguage = {
                        language = it
                        page = 0
                    },
                    results = results,
                    covers = covers,
                    owned = owned,
                    downloadable = downloadable,
                    queue = queue,
                    keyword = keyword,
                    note = resultNote,
                    problem = problem,
                    searching = searching,
                    signingIn = signingIn,
                    morePages = morePages,
                    canWrite = canWrite,
                    modifier = Modifier.weight(2f).fillMaxHeight(),
                    onMore = { page++ },
                    onFocusSong = { focused = it },
                    onPick = { song ->
                        preview.pause()
                        problem = if (!queue.add(song)) {
                            "\"${song.title}\" is already in the queue."
                        } else {
                            null
                        }
                    },
                )
            }
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

/**
 * The left third: what is being asked for, the keys to ask with, and the ways out.
 *
 * A fixed column that never resizes as results arrive. That is most of the point of the shape —
 * everything the cursor might be sitting on stays exactly where it was while the other two thirds
 * of the screen change completely.
 */
@Composable
private fun SearchRail(
    account: UsdbAccount,
    keyword: String,
    canWrite: Boolean,
    firstKey: FocusRequester,
    modifier: Modifier = Modifier,
    onKey: (Char) -> Unit,
    onBackspace: () -> Unit,
    onClear: () -> Unit,
    onSignOut: () -> Unit,
    onBack: () -> Unit,
) {
    Column(modifier = modifier) {
        Text(
            "Add songs",
            style = MaterialTheme.typography.headlineSmall,
            color = GameTheme.lyricActive,
        )
        Text(
            if (account.hasAccount) "signed in as ${account.username}" else "",
            style = MaterialTheme.typography.bodySmall,
            color = GameTheme.lyricIdle,
        )

        Spacer(Modifier.height(10.dp))
        QueryDisplay(keyword, modifier = Modifier.fillMaxWidth())

        Spacer(Modifier.height(8.dp))
        KeyGrid(
            onKey = { onKey(it.lowercaseChar()) },
            onBackspace = onBackspace,
            onClear = onClear,
            firstKey = firstKey,
        )

        // Said here rather than at the point of failure: without a writable folder there is
        // nowhere for a song to go, and finding that out after a thirty-second wait is worse.
        if (!canWrite) {
            Spacer(Modifier.height(12.dp))
            Text(
                "Choose your song folder again on the Songs screen before downloading.",
                style = MaterialTheme.typography.bodySmall,
                color = GameTheme.sparkWarm,
            )
        }

        Spacer(Modifier.height(12.dp))
        // The ways out live in the rail now. They were exiled to the top-right corner when the
        // system keyboard owned the bottom of the screen and five controls could not fit across a
        // row; with the keys drawn on the page there is room for them where they belong.
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onBack) {
                Text("Back", modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp))
            }
            Button(onClick = onSignOut) {
                Text("Sign out", modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp))
            }
        }
    }
}

/** The right two thirds: how it is going, then a grid of what was found. */
@Composable
private fun ResultsPanel(
    language: String,
    onLanguage: (String) -> Unit,
    results: List<UsdbSong>,
    covers: Map<Int, ImageBitmap?>,
    owned: Set<String>,
    downloadable: Map<Int, Boolean>,
    queue: DownloadQueue,
    keyword: String,
    note: String,
    problem: String?,
    searching: Boolean,
    signingIn: Boolean,
    morePages: Boolean,
    canWrite: Boolean,
    modifier: Modifier = Modifier,
    onMore: () -> Unit,
    onFocusSong: (UsdbSong?) -> Unit,
    onPick: (UsdbSong) -> Unit,
) {
    Column(modifier = modifier) {
        val summary = queueSummary(queue)
        Text(
            when {
                signingIn -> "Signing in to USDB…"
                searching -> "Searching USDB…"
                summary != null -> summary
                note.isNotEmpty() -> note
                keyword.isBlank() -> "Type a song or an artist on the left."
                else -> "Keep typing…"
            },
            style = MaterialTheme.typography.bodyLarge,
            color = GameTheme.lyricIdle,
        )
        problem?.let {
            Spacer(Modifier.height(4.dp))
            Text(it, style = MaterialTheme.typography.bodyMedium, color = GameTheme.sparkWarm)
        }

        if (searching || signingIn) {
            Spacer(Modifier.height(10.dp))
            LoadingBar()
        }

        // One bar for the whole queue, and one sentence saying what is happening right now.
        //
        // Downloading a song is six steps, two of which count down out loud, and showing all of
        // that on every card made the process look like more machinery than it is. There is still a
        // countdown -- USDB's wait is three quarters of a download and a bar that stalls there with
        // no explanation is worse than no bar -- but it is said once, here.
        val fraction = queueProgress(queue)
        val working = queue.entries.firstOrNull { it.status is QueueStatus.Working }
        if (fraction != null && queue.isBusy) {
            Spacer(Modifier.height(10.dp))
            ProgressBar(fraction, modifier = Modifier.fillMaxWidth())
            working?.let {
                Spacer(Modifier.height(4.dp))
                Text(
                    "${it.song.title} — ${statusLabel(it.status)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = GameTheme.lyricIdle,
                )
            }
        }

        // Language, across the top of the results rather than down in the rail with the keys.
        // It is a question about the answers, not about the question -- and it is asked far more
        // often than it is changed, so it belongs where the answers are being read.
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Language",
                style = MaterialTheme.typography.bodySmall,
                color = GameTheme.lyricIdle,
            )
            Spacer(Modifier.width(10.dp))
            for ((label, value) in LANGUAGES) {
                Chip(
                    label = label,
                    selected = language == value,
                    onClick = { onLanguage(value) },
                )
                Spacer(Modifier.width(6.dp))
            }
        }

        Spacer(Modifier.height(14.dp))
        val shown = remember(results, keyword) { orderedForDisplay(results, keyword) }
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            items(shown, key = { song -> song.songId }) { song ->
                ResultCard(
                    song = song,
                    cover = covers[song.songId],
                    alreadyOnCard = safeFileName(song.folderName).lowercase() in owned,
                    queued = queue.entries.firstOrNull { it.song.songId == song.songId },
                    unavailable = downloadable[song.songId] == false,
                    enabled = canWrite,
                    onFocus = { onFocusSong(song) },
                    onPick = { onPick(song) },
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
}

/**
 * One song, as a card.
 *
 * The cover is the whole top of it, because that is what anybody recognises first — and it is the
 * reason a grid beats a list here. A row of text is read; a wall of album covers is *scanned*.
 *
 * The status has a line of its own under the artist rather than a slot at the end. That is not
 * cosmetic: in the list this replaced, the status sat beside the title with no width limit, so a
 * long failure message took the whole row, left the title nothing, and wrapped it to one character
 * per line — one result grew to the full height of the television, shaped like a dome. A card has a
 * fixed width by construction, which removes the possibility rather than guarding against it.
 */
@Composable
private fun ResultCard(
    song: UsdbSong,
    cover: ImageBitmap?,
    alreadyOnCard: Boolean,
    queued: QueuedSong?,
    unavailable: Boolean,
    enabled: Boolean,
    onFocus: () -> Unit,
    onPick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state = queued?.status
    Button(
        onClick = onPick,
        // A failed download can be tried again; anything else in the queue cannot be re-added.
        enabled = enabled && !alreadyOnCard && (state == null || state is QueueStatus.Failed),
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { if (it.isFocused) onFocus() },
        // Explicitly a rectangle: a TV Button is a pill by default, and a pill as tall as a card
        // is an ellipse that clips its own title away at the sides.
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
                song.title,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                song.artist.ifBlank { "Unknown artist" },
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = GameTheme.lyricIdle,
            )
            Text(
                when {
                    state is QueueStatus.Failed -> state.message
                    queued != null -> shortStatusLabel(queued)
                    alreadyOnCard -> "Already yours"
                    // Said before it is pressed rather than after a wait, and deliberately still
                    // pressable: this card may hold the focus, and disabling what is focused
                    // strands a remote with nowhere to go.
                    unavailable -> "Music unavailable"
                    else -> listOfNotNull(
                        song.year.ifBlank { null },
                        song.language.ifBlank { null },
                    ).joinToString(" · ").ifBlank { "Add" }
                },
                fontSize = 12.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = when {
                    alreadyOnCard -> GameTheme.noteIdle
                    state is QueueStatus.Failed -> GameTheme.sparkWarm
                    state is QueueStatus.Done -> GameTheme.playerColors[0]
                    unavailable -> GameTheme.sparkWarm
                    else -> GameTheme.lyricIdle
                },
            )
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
