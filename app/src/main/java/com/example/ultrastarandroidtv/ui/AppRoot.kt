package com.example.ultrastarandroidtv.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.example.ultrastarandroidtv.download.Downloads
import com.example.ultrastarandroidtv.game.GameSession
import com.example.ultrastarandroidtv.game.GameplayScreen
import com.example.ultrastarandroidtv.library.LibraryLocation
import com.example.ultrastarandroidtv.library.SongLibraryCache
import com.example.ultrastarandroidtv.mic.UsbMicSession
import com.example.ultrastarandroidtv.settings.GameSettings
import com.example.ultrastarandroidtv.settings.Profiles
import com.example.ultrastarandroidtv.tv.TvGameMode
import com.example.ultrastarandroidtv.song.UltraStarSong

/** A song that has been picked, with its media resolved to something the players can open. */
class ChosenSong(
    /** The song's `.txt` document id — unique per song, and what the library is scrolled back to. */
    val songId: String,
    val song: UltraStarSong,
    val audioUri: String,
    /** Null when the song ships no video, which is most of the time. */
    val videoUri: String?,
)

private enum class Screen {
    Menu, FirstRun, Players, Claim, Picker, Songs, AddSongs, Singers, Settings, Playing
}

/**
 * The whole app, and the order things happen in.
 *
 * Deliberately a `when` over an enum rather than a navigation library. There are five screens
 * and one of them is a game loop; anything more would be scaffolding for a building this size,
 * and every dependency here is one more thing that has to still exist in five years for the
 * karaoke machine in the living room to keep working.
 *
 * [GameSettings] is created once here and handed down, so every screen reads the same values
 * and a change made in Settings is in force the next time a song starts.
 */
@Composable
fun AppRoot() {
    val context = LocalContext.current
    val settings = remember { GameSettings(context) }

    // Applied here rather than in MainActivity so that turning it off in Settings takes effect
    // on the spot -- the blink as the link renegotiates is itself the confirmation that it did
    // something, which is the one bit of feedback this setting can give from inside the app.
    PreferLowLatencyVideo(settings.lowLatencyVideo)

    // The television's own game mode, which is the thing 120 Hz was a poor substitute for. Owned
    // here for the life of the app because that is exactly the span it describes: on when the app
    // is on screen, back to the picture mode that was there when it is not.
    val tvGameMode = remember { TvGameMode(context) }
    TvGameModeWhileOpen(tvGameMode)
    val profiles = remember { Profiles(context) }

    // Scanned once and kept for the session. Reading a fifty-song card over SAF takes seconds,
    // and the commonest thing anyone does after a song is come straight back for another one.
    val library = remember { SongLibraryCache() }

    // Opened once for the life of the app and handed between screens. Reopening them per screen
    // would repeat the USB permission dance and risk the capture threads racing a teardown.
    val micSession = remember { UsbMicSession(context) }
    DisposableEffect(micSession) {
        micSession.start()
        onDispose { micSession.stop() }
    }

    // Downloading lives here for the same reason the microphones do: it has to outlive the screen
    // that starts it. A download used to be cancelled by pressing Back, which made queueing songs
    // pointless -- the whole value of a queue is being able to go and do something else.
    val downloads = remember { Downloads(context) }
    LaunchedEffect(downloads) { downloads.work(library) }

    /**
     * Whether a song folder has been chosen. Held here rather than read where it is needed,
     * because choosing one has to enable Play immediately, on the screen already on the TV.
     */
    var hasLibrary by remember { mutableStateOf(LibraryLocation(context).saved() != null) }

    var screen by remember { mutableStateOf(Screen.Menu) }
    var playerCount by remember { mutableIntStateOf(2) }
    var lineup by remember { mutableStateOf<List<GameSession.SingerSlot>>(emptyList()) }
    var chosen by remember { mutableStateOf<ChosenSong?>(null) }

    /**
     * The song the library reopens on, or null to start at the top.
     *
     * Kept apart from [chosen] because it answers a different question. [chosen] is what is being
     * played; this is where somebody was, and it survives exactly one journey — see [toMenu] and
     * [toClaim].
     */
    var lastPlayed by remember { mutableStateOf<String?>(null) }

    /**
     * Going out to the main menu, which is also where the library forgets its place.
     *
     * Returning to the songs *from a song* should land back where it was; arriving any other way
     * is the start of something new and should begin at the top.
     */
    val toMenu = {
        lastPlayed = null
        screen = Screen.Menu
    }

    /**
     * Going back to the microphones, which also forgets the library's place.
     *
     * Changing who is singing is a new start in the only sense that matters here: the song that
     * was right for the last singer is not evidence about the next one, and landing halfway down
     * the row on somebody else's choice is the same disorientation as landing at the top after
     * finishing a song. Only *finishing a song* earns the row its position back.
     */
    val toClaim = {
        lastPlayed = null
        screen = Screen.Claim
    }

    // Held for the whole of a song, not just at its start: gameplay costs half a core with video,
    // and a download is network plus a burst of writes to the same card the song is streaming
    // from. One in flight finishes its current step and then waits -- see [Downloads].
    LaunchedEffect(screen) { downloads.paused = screen == Screen.Playing }

    Box {
    when (screen) {
        Screen.Menu -> MainMenuScreen(
            // Live, so plugging a mic in on this screen enables Play without a relaunch.
            micCount = micSession.mics.size,
            // Pressing Play with no folder chosen asks for one and carries on, rather than
            // refusing: somebody who pressed Play has said what they want, and the folder is a
            // question they can answer on the spot.
            onPlay = { screen = if (hasLibrary) Screen.Players else Screen.FirstRun },
            onSongs = { screen = Screen.Songs },
            onSingers = { screen = Screen.Singers },
            onSettings = { screen = Screen.Settings },
        )

        // What is on the card and what is wrong with it — the only screen that shows a song
        // which cannot be sung, and the only one that can remove it.
        Screen.Songs -> SongsScreen(
            cache = library,
            downloads = downloads,
            onAddSongs = { screen = Screen.AddSongs },
            onMenu = {
                // A folder can be chosen or changed here, which is what unblocks Play.
                hasLibrary = LibraryLocation(context).saved() != null
                toMenu()
            },
        )

        // Searching USDB and downloading. Reached from Songs rather than the main menu: it is
        // library management, and it is the same folder and the same cache that screen owns.
        Screen.AddSongs -> AddSongsScreen(
            cache = library,
            downloads = downloads,
            // Back to Songs, which rescans if anything was downloaded -- the cache is cleared on
            // every successful save, so the new songs are found without anybody pressing Rescan.
            onBack = { screen = Screen.Songs },
        )

        // Only ever seen with no folder chosen — a first run, or a grant that went away with
        // the card it described.
        Screen.FirstRun -> FirstRunScreen(
            onReady = {
                hasLibrary = true
                library.clear()
                screen = Screen.Players
            },
            onMenu = toMenu,
        )

        Screen.Singers -> ProfilesScreen(
            profiles = profiles,
            onBack = toMenu,
        )

        Screen.Players -> PlayerCountScreen(
            micCount = micSession.mics.size,
            onPick = {
                playerCount = it
                screen = Screen.Claim
            },
            onBack = toMenu,
        )

        // Who is holding which microphone, and what they are called. Asked every game: the
        // microphone identifies itself, the person does not.
        Screen.Claim -> ClaimScreen(
            playerCount = playerCount,
            micSession = micSession,
            profiles = profiles,
            settings = settings,
            onReady = {
                lineup = it
                screen = Screen.Picker
            },
            onBack = { screen = Screen.Players },
            onMenu = toMenu,
        )

        Screen.Settings -> SettingsScreen(
            settings = settings,
            tv = tvGameMode,
            onBack = toMenu,
        )

        Screen.Picker -> SongPickerScreen(
            playerCount = playerCount,
            cache = library,
            // Null unless a song has been sung since the last visit to the main menu, so a fresh
            // start opens at the top and coming back from a song does not.
            openAt = lastPlayed,
            // Records are shown on the cards, and only ever credited to a name this list still
            // holds — see `scoreIfKnown`.
            profiles = profiles,
            onPlay = {
                chosen = it
                lastPlayed = it.songId
                screen = Screen.Playing
            },
            // One step up rather than out: whoever is holding which microphone is the thing
            // most likely to be wrong by the time anyone is looking at the songs.
            onChangeSingers = toClaim,
            onMenu = toMenu,
        )

        Screen.Playing -> {
            val ready = chosen
            if (ready == null) {
                screen = Screen.Picker
            } else {
                GameplayScreen(
                    song = ready.song,
                    // What this song's record is filed under. The `.txt` document id is already
                    // the library's own identity for a song, so nothing new had to be invented.
                    songId = ready.songId,
                    audioUri = ready.audioUri,
                    videoUri = ready.videoUri,
                    settings = settings,
                    micSession = micSession,
                    lineup = lineup,
                    // Back out to the songs list rather than the menu: the usual next thing
                    // after one song is another song.
                    onExit = { screen = Screen.Picker },
                )
            }
        }
    }

        // Over everything, focusable by nothing. A song that lands while somebody is choosing the
        // next one is worth a line; a song that lands mid-performance can wait until it is over.
        DownloadNotice(downloads = downloads, visible = screen != Screen.Playing)
    }
}
