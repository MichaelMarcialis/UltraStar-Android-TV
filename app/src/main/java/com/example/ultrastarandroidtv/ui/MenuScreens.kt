package com.example.ultrastarandroidtv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.example.ultrastarandroidtv.game.GameTheme

/**
 * The first thing on screen. Three choices, all large enough to read from a sofa.
 *
 * "Singers" sits beside Settings rather than inside it: Settings is about the machine — latency,
 * microphone sensitivity — and is set once by whoever put this together, while the list of people
 * changes whenever a friend comes round.
 */
@Composable
fun MainMenuScreen(onPlay: () -> Unit, onSingers: () -> Unit, onSettings: () -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(GameTheme.background)
            .padding(72.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            "Sing",
            style = MaterialTheme.typography.displayLarge.copy(fontWeight = FontWeight.Bold),
            color = GameTheme.lyricActive,
        )
        Spacer(Modifier.height(48.dp))

        Row {
            Button(onClick = onPlay, modifier = Modifier.focusRequester(first)) {
                Text("Play", fontSize = 28.sp, modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp))
            }
            Spacer(Modifier.width(24.dp))
            Button(onClick = onSingers) {
                Text("Singers", fontSize = 28.sp, modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp))
            }
            Spacer(Modifier.width(24.dp))
            Button(onClick = onSettings) {
                Text("Settings", fontSize = 28.sp, modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp))
            }
        }
    }
}

/**
 * How many people are singing.
 *
 * Asked before the song rather than after, because the answer changes what a song *is*: with
 * one singer a duet has a part nobody is covering, so duets are collapsed to a single line and
 * only one microphone is scored.
 */
@Composable
fun PlayerCountScreen(onPick: (Int) -> Unit, onBack: () -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    BackHandler(onBack = onBack)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(GameTheme.background)
            .padding(72.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            "How many singers?",
            style = MaterialTheme.typography.headlineLarge,
            color = GameTheme.lyricActive,
        )
        Spacer(Modifier.height(40.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { onPick(1) }, modifier = Modifier.focusRequester(first)) {
                Text("One", fontSize = 28.sp, modifier = Modifier.padding(horizontal = 40.dp, vertical = 10.dp))
            }
            Spacer(Modifier.width(24.dp))
            Button(onClick = { onPick(2) }) {
                Text("Two", fontSize = 28.sp, modifier = Modifier.padding(horizontal = 40.dp, vertical = 10.dp))
            }
        }

        Spacer(Modifier.height(28.dp))
        Text(
            "One singer uses the first microphone only.",
            style = MaterialTheme.typography.bodyMedium,
            color = GameTheme.lyricIdle,
        )
    }
}
