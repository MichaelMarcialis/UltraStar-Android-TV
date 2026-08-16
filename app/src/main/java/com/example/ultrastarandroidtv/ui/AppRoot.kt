package com.example.ultrastarandroidtv.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.example.ultrastarandroidtv.game.GameSession
import com.example.ultrastarandroidtv.game.GameplayScreen
import com.example.ultrastarandroidtv.mic.UsbMicSession
import com.example.ultrastarandroidtv.settings.GameSettings
import com.example.ultrastarandroidtv.settings.Profiles
import com.example.ultrastarandroidtv.song.UltraStarSong

/** A song that has been picked, with its media resolved to something the players can open. */
class ChosenSong(
    val song: UltraStarSong,
    val audioUri: String,
    /** Null when the song ships no video, which is most of the time. */
    val videoUri: String?,
)

private enum class Screen { Menu, Players, Claim, Songs, Settings, Playing }

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
    val profiles = remember { Profiles(context) }

    // Opened once for the life of the app and handed between screens. Reopening them per screen
    // would repeat the USB permission dance and risk the capture threads racing a teardown.
    val micSession = remember { UsbMicSession(context) }
    DisposableEffect(micSession) {
        micSession.start()
        onDispose { micSession.stop() }
    }

    var screen by remember { mutableStateOf(Screen.Menu) }
    var playerCount by remember { mutableIntStateOf(2) }
    var lineup by remember { mutableStateOf<List<GameSession.SingerSlot>>(emptyList()) }
    var chosen by remember { mutableStateOf<ChosenSong?>(null) }

    when (screen) {
        Screen.Menu -> MainMenuScreen(
            onPlay = { screen = Screen.Players },
            onSettings = { screen = Screen.Settings },
        )

        Screen.Players -> PlayerCountScreen(
            onPick = {
                playerCount = it
                screen = Screen.Claim
            },
            onBack = { screen = Screen.Menu },
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
                screen = Screen.Songs
            },
            onBack = { screen = Screen.Players },
        )

        Screen.Settings -> SettingsScreen(
            settings = settings,
            onBack = { screen = Screen.Menu },
        )

        Screen.Songs -> SongPickerScreen(
            playerCount = playerCount,
            onPlay = {
                chosen = it
                screen = Screen.Playing
            },
            onBack = { screen = Screen.Menu },
        )

        Screen.Playing -> {
            val ready = chosen
            if (ready == null) {
                screen = Screen.Songs
            } else {
                GameplayScreen(
                    song = ready.song,
                    audioUri = ready.audioUri,
                    videoUri = ready.videoUri,
                    settings = settings,
                    micSession = micSession,
                    lineup = lineup,
                    // Back out to the songs list rather than the menu: the usual next thing
                    // after one song is another song.
                    onExit = { screen = Screen.Songs },
                )
            }
        }
    }
}
