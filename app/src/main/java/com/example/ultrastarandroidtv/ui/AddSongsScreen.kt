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
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
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
import androidx.media3.exoplayer.ExoPlayer
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.example.ultrastarandroidtv.download.DownloadQueue
import com.example.ultrastarandroidtv.download.DownloadTerms
import com.example.ultrastarandroidtv.download.Downloads
import com.example.ultrastarandroidtv.download.QueueStatus
import com.example.ultrastarandroidtv.download.QueuedSong
import com.example.ultrastarandroidtv.download.SongDownloader
import com.example.ultrastarandroidtv.download.queueProgress
import com.example.ultrastarandroidtv.download.queueSummary
import com.example.ultrastarandroidtv.download.safeFileName
import com.example.ultrastarandroidtv.download.shortStatusLabel
import com.example.ultrastarandroidtv.download.statusLabel
import com.example.ultrastarandroidtv.audio.playSample
import com.example.ultrastarandroidtv.audio.previewPlayer
import com.example.ultrastarandroidtv.game.GameTheme
import com.example.ultrastarandroidtv.library.CoverLoader
import com.example.ultrastarandroidtv.library.LibraryLocation
import com.example.ultrastarandroidtv.library.filingKey
import com.example.ultrastarandroidtv.library.searchKey
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
 * How long to leave between one availability check and the next.
 *
 * The checks run on their own down the results rather than waiting for a card to be focused, so
 * they have to be paced: each one is a USDB page and a YouTube lookup, and firing thirty at once
 * for a page nobody has finished reading would be rude to a site that is lending us its bandwidth.
 */
private const val AVAILABILITY_GAP_MS = 350L

/** How many cards past the last visible one are checked, so scrolling meets answers already there. */
private const val AVAILABILITY_LOOKAHEAD = 4

/** How near the end of the loaded results the view has to get before the next page is fetched. */
private const val PAGE_LOOKAHEAD = 6

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
 * USDB's page, in the order it is worth reading on a television: **closest answer first, then
 * alphabetically.**
 *
 * It used to be one coarse split — every artist match, then everything else — which is right about
 * the big case and blunt about the rest: somebody who types "queen" almost always means the band,
 * but a song *called* "Queen" then ranked below every song by an artist with "queen" somewhere in
 * their name. [relevance] grades the same instinct instead, and songs of equal standing are still
 * filed alphabetically **past the leading article**, so a page is not three quarters of the way
 * through T before it reaches the band anybody was looking for.
 *
 * Ordering is per page, because pages are appended and never re-sorted — see `results` in
 * [AddSongsScreen]. A better match on page three does not climb over page one.
 */
internal fun orderedForDisplay(results: List<UsdbSong>, keyword: String): List<UsdbSong> {
    val word = searchKey(keyword)
    val order = compareBy<UsdbSong>(
        { filingKey(it.artist).lowercase() },
        { filingKey(it.title).lowercase() },
    )
    if (word.isEmpty()) return results.sortedWith(order)
    return results.sortedWith(compareBy<UsdbSong> { relevance(it, word) }.then(order))
}

/**
 * How closely one result answers what was typed. Lower is better.
 *
 * Compared on the same normalised form the library search uses, so punctuation and case cannot
 * change the ranking: "ymca" is as good a match for "Y.M.C.A." as it looks to the person who
 * typed it.
 *
 * The artist wins ties at equal strength, because a bare band name is the commonest query there
 * is — but an *exact* title still beats an artist the query merely begins.
 */
internal fun relevance(song: UsdbSong, needle: String): Int {
    val tight = needle.replace(" ", "")
    val artist = strength(searchKey(song.artist), needle, tight)
    val title = strength(searchKey(song.title), needle, tight)
    if (artist == NO_MATCH && title == NO_MATCH) return 6
    // Equal strength goes to the artist; a stronger title still beats a weaker artist.
    return if (artist <= title) artist * 2 else title * 2 + 1
}

/** Exact, prefix, contained, or not at all — 0, 1, 2, 3. */
private fun strength(text: String, needle: String, tight: String): Int {
    // Matched with the spaces closed up as well as with them kept, the same pair of rules the
    // library search uses. Without it "Y.M.C.A." — which normalises to four separate letters —
    // would rank below every song that merely has the word "ymca" somewhere in it.
    val flat = text.replace(" ", "")
    return when {
        text == needle || flat == tight -> 0
        text.startsWith(needle) || flat.startsWith(tight) -> 1
        text.contains(needle) || flat.contains(tight) -> 2
        else -> NO_MATCH
    }
}

private const val NO_MATCH = 3

/**
 * What the screen is doing.
 *
 * [Notice] comes first and is seen exactly once ever — see [DownloadTerms]. After that the screen
 * opens on [Browse] for anybody already signed in and [SignIn] for anybody not.
 */
private enum class AddMode { Notice, SignIn, Browse }

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
    val terms = remember { DownloadTerms(context) }
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

    // The notice is in front of both other modes rather than beside them: it is about what the
    // whole screen does, so it has to be read before anything on it can be reached, including a
    // sign-in that already exists from an older build.
    var mode by remember {
        mutableStateOf(
            when {
                !terms.accepted -> AddMode.Notice
                account.hasAccount -> AddMode.Browse
                else -> AddMode.SignIn
            }
        )
    }
    var user by remember { mutableStateOf(account.username) }
    var password by remember { mutableStateOf("") }
    var signingIn by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }

    // The words and the cursor together, because the keyboard has arrows now. See [TypedQuery].
    var typed by remember { mutableStateOf(TypedQuery()) }
    val keyword = typed.text

    /**
     * Every result loaded so far, already in the order they are shown in.
     *
     * **Accumulated rather than replaced, and ordered a page at a time.** Scrolling to the bottom
     * fetches the next page and appends it, so ordering the whole list afresh each time would
     * reshuffle cards somebody is already looking at — and on a television that moves the focus
     * out from under a thumb. Each page is sorted as it lands and then never moves again.
     */
    var results by remember { mutableStateOf<List<UsdbSong>>(emptyList()) }
    var resultNote by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var loadingMore by remember { mutableStateOf(false) }

    /** The page being fetched, and the last one that landed. Equal means nothing is in flight. */
    var wanted by remember { mutableIntStateOf(0) }
    var loadedPage by remember { mutableIntStateOf(-1) }
    var morePages by remember { mutableStateOf(false) }

    /** Bumped whenever a *new* search lands, which is what sends the grid back to the top. */
    var searchSeq by remember { mutableIntStateOf(0) }

    var language by remember { mutableStateOf("") }
    var focused by remember { mutableStateOf<UsdbSong?>(null) }

    val grid = rememberLazyGridState()

    /** Starting again: a different question, so the old answers and the old page count go. */
    val restart: () -> Unit = {
        wanted = 0
        loadedPage = -1
        morePages = false
    }

    val covers = remember { mutableStateMapOf<Int, ImageBitmap?>() }

    // One player for the whole screen, reused as focus moves -- the song picker's arrangement,
    // for the same reason: building an ExoPlayer per card would stutter the grid.
    // Levelled: previews are mastered decades apart and run 8.6 dB apart on this library.
    val preview = remember { previewPlayer(context) }
    DisposableEffect(preview) { onDispose { preview.release() } }

    /**
     * Whether each song's music can actually be fetched, worked out **before anybody focuses it**.
     *
     * USDB's detail page names the YouTube video with no throttle attached, so a verdict costs
     * about a second and can be had while somebody is simply reading the page. It used to wait for
     * a card to be focused, which meant the one thing worth knowing before choosing was only ever
     * said about the song already chosen.
     *
     * **Paced, and only as far as the eye has got.** Checking every result the moment it arrives
     * would be sixty requests for a page nobody has finished reading; this walks the list in
     * order, one at a time, and stops a few cards past the last one on screen. Scrolling extends
     * how far it goes, so answers are usually already there by the time a card comes into view.
     *
     * The verdicts live on [Downloads] rather than here, so searching for the same band twice does
     * not ask the same question twice. A check that fails for any other reason records nothing,
     * because a network blip must not label a perfectly good song as broken.
     */
    val downloadable = downloads.availability
    LaunchedEffect(results) {
        if (results.isEmpty()) return@LaunchedEffect
        while (true) {
            // **Nothing is checked while a sample is playing.** Measured on the television: a
            // verdict costs a USDB page and a YouTube player response, the latter running to
            // megabytes, and a run of them produced eight garbage collections in four seconds
            // freeing forty to sixty megabytes of large objects apiece. That is what made previews
            // choppy at first and clear later — later being once the sweep had run out of things
            // to ask about. Somebody listening to a song is doing the thing this screen is for;
            // knowing in advance whether its music can be fetched is a nicety, and the nicety
            // waits.
            if (preview.isPlaying) {
                delay(AVAILABILITY_GAP_MS)
                continue
            }

            val visible = grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            val reach = (visible + AVAILABILITY_LOOKAHEAD).coerceAtMost(results.lastIndex)
            val song = (0..reach).asSequence()
                .map { results[it] }
                .firstOrNull { !downloadable.containsKey(it.songId) }

            if (song == null) {
                // Nothing to ask about yet. Waking on a timer rather than on a scroll event keeps
                // this one loop rather than a loop plus a subscription; it costs a check a second.
                delay(AVAILABILITY_GAP_MS * 3)
                continue
            }

            val verdict = withContext(Dispatchers.IO) {
                runCatching {
                    val videoId = details.fetch(song.songId).videoId ?: return@runCatching null
                    youTube.resolve(videoId) is AudioLookup.Found
                }.getOrNull()
            }
            if (verdict != null) downloadable[song.songId] = verdict
            delay(AVAILABILITY_GAP_MS)
        }
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

    /**
     * The songs on the card, by artist and title rather than by folder name.
     *
     * Because a folder name is not a song, and reading one as the other put "Already yours" on a
     * Godsmack song nobody had downloaded and nobody could find in their library. A folder called
     * `Godsmack - Awake` will refuse a download whatever is inside it — that much the downloader
     * enforces on purpose, since two folders for one song is worse than not downloading — but
     * saying it is *yours* is a different claim, and only this can support it.
     *
     * Compared on the same normalised form the search uses, so punctuation and case cannot
     * separate a song from itself, and on equality rather than containment: "Hello" is inside
     * "Hello Again", and the two are not the same song.
     */
    val ownedSongs = remember(cache.songs) {
        cache.songs.mapTo(mutableSetOf()) { songKey(it.song.metadata.artist, it.song.metadata.title) }
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

    // Fetched whole and then played from memory rather than streamed -- see [playSample] for the
    // measurement and the reasoning. A sample is about half a megabyte, and this screen's network
    // is busy with covers, availability checks and quite possibly a download.
    //
    // **Stopped rather than paused when nothing is focused**, and `focused` genuinely becomes null
    // now: a card reports losing the focus as well as taking it. Before, it only ever reported
    // taking it, so walking back to the keyboard left the last sample playing — and a new search
    // left it playing for a card that was no longer on the screen at all.
    LaunchedEffect(focused) {
        preview.stop()
        val sample = focused?.sampleUrl ?: return@LaunchedEffect
        delay(PREVIEW_DELAY_MS)
        val bytes = withContext(Dispatchers.IO) {
            runCatching { http.getBytes(sample) }.getOrNull()
        } ?: return@LaunchedEffect
        runCatching { preview.playSample(bytes) }
    }

    // Nothing in the app should still be making a noise once it is not on the screen.
    PauseWhenBackgrounded(preview)

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
    // Keyed on the words, the language *and* the page being asked for, so scrolling to the bottom
    // re-runs it for the next page and editing the query starts again at the first. The delay is
    // what makes a run of key presses one search: a new keystroke cancels this effect before it has
    // finished waiting, which is debouncing for free — and it is skipped past the first page,
    // where nobody is typing and the wait would only be a stall at the bottom of the list.
    LaunchedEffect(keyword, language, wanted) {
        val words = keyword.trim()
        if (words.length < MIN_QUERY) {
            results = emptyList()
            resultNote = ""
            morePages = false
            return@LaunchedEffect
        }
        val first = wanted == 0
        if (first) delay(SEARCH_DELAY_MS)
        searching = first
        loadingMore = !first
        problem = null
        val outcome = runCatching {
            withContext(Dispatchers.IO) {
                search.search(SongFilter(keyword = words, language = language), wanted)
            }
        }
        searching = false
        loadingMore = false
        outcome.onSuccess { found ->
            // Ordered here, one page at a time, and appended. See [results] for why the whole
            // list is never re-sorted.
            val ordered = orderedForDisplay(found.songs, words)
            results = if (first) {
                // The card that was being previewed may not be in the new answer at all, and a
                // removed card reports no focus change on its way out.
                focused = null
                ordered
            } else {
                val already = results.mapTo(mutableSetOf()) { it.songId }
                results + ordered.filter { already.add(it.songId) }
            }
            loadedPage = wanted
            morePages = found.hasMore
            if (first) searchSeq++
            resultNote = when {
                // A rescue sweep's count means something different -- see SearchPage.narrowed.
                found.narrowed -> "Close matches — showing ${results.size}"
                found.totalResults == 0 -> "Nothing on USDB matches that."
                else -> "${found.totalResults} found — showing ${results.size}"
            }
        }.onFailure {
            // The pages already loaded are still good; only the one that failed is lost, and
            // saying so beats throwing away a screenful somebody is reading.
            if (first) results = emptyList()
            morePages = false
            problem = "The search could not reach USDB."
        }
    }

    /**
     * Back to the top when a *new* search lands.
     *
     * A lazy grid keeps its scroll **offset** when its contents change, so a search run halfway
     * down a list left the view halfway down a completely different one — which reads as the app
     * having jumped to a song at random. Keyed on the sequence number rather than on the results,
     * because appending a page must not scroll anywhere.
     */
    LaunchedEffect(searchSeq) {
        if (searchSeq > 0) grid.scrollToItem(0)
    }

    /**
     * Fetching the next page as the end comes into view, instead of a "More results" button.
     *
     * The button was one more thing to travel to, and it sat at the end of a grid rather than in
     * the rail with everything else pressable. It also had to be pressed *again* for each page.
     *
     * Guarded on `wanted == loadedPage`: the visible index changes many times while the grid is
     * settling, and without it every one of those frames would ask for another page.
     */
    LaunchedEffect(results, morePages, wanted, loadedPage) {
        if (!morePages || wanted != loadedPage) return@LaunchedEffect
        snapshotFlow { grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .collect { last -> if (last >= results.size - PAGE_LOOKAHEAD) wanted++ }
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
            AddMode.Notice -> SongSourceNotice(
                terms = terms,
                onAccept = { mode = if (account.hasAccount) AddMode.Browse else AddMode.SignIn },
                onBack = onBack,
            )

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
                    typed = typed,
                    canWrite = canWrite,
                    firstKey = first,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    // Moving the cursor changes no words, so it asks USDB nothing and leaves the
                    // page count alone. Everything that changes the words starts the search over.
                    onMove = { typed = it },
                    onEdit = {
                        typed = it
                        restart()
                    },
                    onSignOut = {
                        preview.pause()
                        account.forget()
                        session.signOut()
                        password = ""
                        user = ""
                        results = emptyList()
                        typed = TypedQuery()
                        restart()
                        mode = AddMode.SignIn
                    },
                    onBack = onBack,
                )

                Spacer(Modifier.width(28.dp))

                ResultsPanel(
                    language = language,
                    onLanguage = {
                        language = it
                        restart()
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
                    loadingMore = loadingMore,
                    canWrite = canWrite,
                    grid = grid,
                    modifier = Modifier.weight(2f).fillMaxHeight(),
                    ownedSongs = ownedSongs,
                    onFocusSong = { focused = it },
                    // Guarded, because focus moves card to card as "lost, then gained" and the
                    // two arrive in that order: clearing unconditionally would throw away the
                    // focus that has just been reported.
                    onBlurSong = { if (focused?.songId == it.songId) focused = null },
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
    typed: TypedQuery,
    canWrite: Boolean,
    firstKey: FocusRequester,
    modifier: Modifier = Modifier,
    /** Cursor moved, words unchanged — nothing downstream has to be told. */
    onMove: (TypedQuery) -> Unit,
    /** Words changed, so the search starts again at the first page. */
    onEdit: (TypedQuery) -> Unit,
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
        QueryDisplay(typed, modifier = Modifier.fillMaxWidth())

        Spacer(Modifier.height(8.dp))
        KeyGrid(
            onKey = { onEdit(typed.insert(it.lowercaseChar())) },
            onBackspace = { onEdit(typed.backspace()) },
            onDelete = { onEdit(typed.forwardDelete()) },
            onLeft = { onMove(typed.left()) },
            onRight = { onMove(typed.right()) },
            onClear = { onEdit(typed.cleared()) },
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
    ownedSongs: Set<Pair<String, String>>,
    downloadable: Map<Int, Boolean>,
    queue: DownloadQueue,
    keyword: String,
    note: String,
    problem: String?,
    searching: Boolean,
    signingIn: Boolean,
    loadingMore: Boolean,
    canWrite: Boolean,
    grid: LazyGridState,
    modifier: Modifier = Modifier,
    onFocusSong: (UsdbSong?) -> Unit,
    onBlurSong: (UsdbSong) -> Unit,
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

        // The one sentence from the notice, kept where the decision is actually made.
        //
        // The notice itself is seen once and then never again, which is right -- a question asked
        // every time is a door handle rather than a question -- but "once, months ago" is not
        // where somebody is when they press a card. Directly above the grid and below every
        // control, so it can cost nothing: the grid scrolls, so a line taken from the top of it
        // takes nothing away that can be pressed.
        Spacer(Modifier.height(12.dp))
        Text(
            "Only download songs you already own a lawful copy of.",
            style = MaterialTheme.typography.bodySmall,
            color = GameTheme.lyricIdle,
        )

        Spacer(Modifier.height(10.dp))
        // The results are already in the order they are shown in -- each page was sorted as it
        // landed. Sorting here would re-sort the whole accumulated list every time another page
        // arrived, moving cards that are already on screen.
        LazyVerticalGrid(
            state = grid,
            columns = GridCells.Fixed(3),
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            items(results, key = { song -> song.songId }) { song ->
                ResultCard(
                    song = song,
                    cover = covers[song.songId],
                    alreadyOnCard = songKey(song.artist, song.title) in ownedSongs,
                    folderTaken = safeFileName(song.folderName).lowercase() in owned,
                    queued = queue.entries.firstOrNull { it.song.songId == song.songId },
                    unavailable = downloadable[song.songId] == false,
                    enabled = canWrite,
                    onFocus = { onFocusSong(song) },
                    onBlur = { onBlurSong(song) },
                    onPick = { onPick(song) },
                )
            }
            // The next page arrives on its own as the end comes into view; this only says so.
            // Never a button: a focusable thing at the end of an infinite list is a place the
            // cursor can be sitting when the ground moves underneath it.
            if (loadingMore) {
                item {
                    Text(
                        "Finding more…",
                        style = MaterialTheme.typography.bodySmall,
                        color = GameTheme.lyricIdle,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
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
    /**
     * Whether a folder of this name is already on the card, whatever is in it.
     *
     * Kept apart from [alreadyOnCard] because the two say different things and only one of them
     * is about this song. A download would be refused either way, so both disable the card — but
     * "Folder name taken" is a fact somebody can act on, where "Already yours" about a song they
     * have never seen is just the app being wrong at them.
     */
    folderTaken: Boolean,
    queued: QueuedSong?,
    unavailable: Boolean,
    enabled: Boolean,
    onFocus: () -> Unit,
    onBlur: () -> Unit,
    onPick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state = queued?.status

    // A failed download can be tried again; anything else already downloaded, queued or owned
    // cannot be re-added.
    val addable = enabled && !alreadyOnCard && !folderTaken &&
        (state == null || state is QueueStatus.Failed)

    Button(
        // Pressing a card that has nothing left to offer does nothing, and says why on its own
        // face -- "Already yours", "Added", "Folder name taken".
        onClick = { if (addable) onPick() },
        // **Never disabled, however little it has to offer.** A disabled TV button cannot take
        // focus, and a grid full of holes is a grid the D-pad cannot be driven through: reported
        // from the sofa as pressing down and landing on the first song of the next row instead of
        // the one directly below, and only "after a song or two has been downloaded" -- which is
        // exactly when cards start dropping out of the focus map. Compose's two-dimensional
        // search finds nothing in the beam below, falls back to the next thing in traversal
        // order, and that is the start of the next row.
        //
        // The screen already had this rule and applied it to one case only: "Music unavailable"
        // is deliberately still pressable so that a card holding the focus cannot have it taken
        // away. The same reasoning covers every other state, so the rule is now the button's
        // rather than one branch's.
        enabled = true,
        // With `enabled` no longer saying it, the *colour* has to. Both halves are stated
        // explicitly, unfocused included: a TV Button leaves its content dark on dark otherwise,
        // which is how forty of forty-one drawn keys once became unreadable ghosts.
        colors = if (addable) {
            ButtonDefaults.colors()
        } else {
            ButtonDefaults.colors(
                containerColor = GameTheme.trackBackground,
                contentColor = GameTheme.lyricIdle,
                // Still visibly focused. A card that cannot be pressed must still show where the
                // cursor is, or driving past it looks like the remote has stopped working.
                focusedContainerColor = GameTheme.noteIdle,
                focusedContentColor = GameTheme.lyricActive,
            )
        },
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { if (it.isFocused) onFocus() else onBlur() },
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
                    folderTaken -> "Folder name taken"
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
                    alreadyOnCard || folderTaken -> GameTheme.noteIdle
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

/**
 * How a song on the card and a song on USDB are told to be the same song.
 *
 * Artist and title, normalised the way the library search normalises them and then closed up —
 * so `a-ha` and `A-Ha` are one artist, and `Y.M.C.A.`, which normalises to four separate letters,
 * is the same title as `YMCA`. Closing the spaces is the same trick the relevance ranking uses,
 * and for the same reason: how a title punctuates its own letters is not a fact about which song
 * it is.
 *
 * Both halves have to match **exactly**. A title that merely *contains* another is a different
 * song — the rule `AlternateVersions` had to learn the hard way when "Hello" matched "Hello
 * Again" — and here a wrong answer marks a song somebody does not own as already theirs, which
 * is precisely the report this exists to fix.
 */
internal fun songKey(artist: String, title: String): Pair<String, String> =
    searchKey(artist).replace(" ", "") to searchKey(title).replace(" ", "")
