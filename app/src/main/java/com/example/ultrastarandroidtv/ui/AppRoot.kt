package com.example.ultrastarandroidtv.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.example.ultrastarandroidtv.game.GameplayScreen
import com.example.ultrastarandroidtv.settings.GameSettings
import com.example.ultrastarandroidtv.song.UltraStarSong

/** A song that has been picked, with its audio resolved to something the player can open. */
class ChosenSong(val song: UltraStarSong, val audioUri: String)

private enum class Screen { Menu, Players, Songs, Settings, Playing }

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

    var screen by remember { mutableStateOf(Screen.Menu) }
    var playerCount by remember { mutableIntStateOf(2) }
    var chosen by remember { mutableStateOf<ChosenSong?>(null) }

    when (screen) {
        Screen.Menu -> MainMenuScreen(
            onPlay = { screen = Screen.Players },
            onSettings = { screen = Screen.Settings },
        )

        Screen.Players -> PlayerCountScreen(
            onPick = {
                playerCount = it
                screen = Screen.Songs
            },
            onBack = { screen = Screen.Menu },
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
                    settings = settings,
                    playerCount = playerCount,
                    // Back out to the songs list rather than the menu: the usual next thing
                    // after one song is another song.
                    onExit = { screen = Screen.Songs },
                )
            }
        }
    }
}
