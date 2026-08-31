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
 * The first thing on screen. Four choices, all large enough to read from a sofa.
 *
 * "Singers" sits beside Settings rather than inside it: Settings is about the machine — latency,
 * microphone sensitivity — and is set once by whoever put this together, while the list of people
 * changes whenever a friend comes round.
 *
 * **Play is refused outright with no microphone attached**, because there is nothing behind it: a
 * singer would be walked through counting players, claiming a mic and choosing a song before
 * finding out. Saying so here costs one line and saves four screens. It is honest only because
 * the mic list is live — plug one in and this enables itself.
 */
@Composable
fun MainMenuScreen(
    micCount: Int,
    onPlay: () -> Unit,
    onSongs: () -> Unit,
    onSingers: () -> Unit,
    onSettings: () -> Unit,
) {
    val canPlay = micCount >= 1
    val first = remember { FocusRequester() }

    // Focus follows what can actually be pressed. A disabled button cannot take focus, so
    // requesting it there would leave the screen with no focus at all and no way to move.
    LaunchedEffect(canPlay) { runCatching { first.requestFocus() } }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(GameTheme.background)
            .padding(72.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            "UltraStar Android TV",
            style = MaterialTheme.typography.displayLarge.copy(fontWeight = FontWeight.Bold),
            color = GameTheme.lyricActive,
        )
        Spacer(Modifier.height(48.dp))

        Row {
            Button(
                onClick = onPlay,
                enabled = canPlay,
                modifier = if (canPlay) Modifier.focusRequester(first) else Modifier,
            ) {
                Text("Play", fontSize = 28.sp, modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp))
            }
            Spacer(Modifier.width(24.dp))
            // Songs sits next to Play because it is about the same thing — what there is to sing.
            // It is also the one item that still does something useful with no microphone
            // attached, which is why it takes the focus when Play cannot.
            Button(
                onClick = onSongs,
                modifier = if (canPlay) Modifier else Modifier.focusRequester(first),
            ) {
                Text("Songs", fontSize = 28.sp, modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp))
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

        if (!canPlay) {
            Spacer(Modifier.height(28.dp))
            Text(
                "Plug in a microphone to play.",
                style = MaterialTheme.typography.bodyLarge,
                color = GameTheme.sparkWarm,
            )
        }
    }
}

/**
 * How many people are singing.
 *
 * Asked before the song rather than after, because the answer changes what a song *is*: with
 * one singer a duet has a part nobody is covering, so duets are collapsed to a single line and
 * only one microphone is scored.
 *
 * Two is refused with one microphone attached, and the reason is on screen. The alternative is
 * accepting the answer and then quietly scoring one person — which looks like the second
 * microphone has failed, on hardware where that is a believable thing to conclude.
 */
@Composable
fun PlayerCountScreen(micCount: Int, onPick: (Int) -> Unit, onBack: () -> Unit) {
    val canPair = micCount >= 2
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
            Button(onClick = { onPick(2) }, enabled = canPair) {
                Text("Two", fontSize = 28.sp, modifier = Modifier.padding(horizontal = 40.dp, vertical = 10.dp))
            }
        }

        Spacer(Modifier.height(28.dp))
        Text(
            if (canPair) {
                "On your own, whichever microphone you sing into is the one that's scored."
            } else {
                "Two singers needs a second microphone. Only one is plugged in."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = if (canPair) GameTheme.lyricIdle else GameTheme.sparkWarm,
        )
    }
}
