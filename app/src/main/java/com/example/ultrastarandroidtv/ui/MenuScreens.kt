package com.example.ultrastarandroidtv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.example.ultrastarandroidtv.R
import com.example.ultrastarandroidtv.game.GameTheme

/**
 * The first thing on screen, and the one screen anybody ever sees cold.
 *
 * **Light, and alone in this app in being so.** Every other screen is near-black, because every
 * other screen sits either over a music video or beside one. This one sits beside the logo, and
 * the logo's wordmark runs from a very deep indigo (`#220F5F`) through magenta into cyan — on the
 * game's own background that first third is invisible, so "ULTRA" and "ANDROID" simply are not
 * there. A light ground is not decoration here; it is the only ground the artwork can be read on.
 *
 * The palette is taken *from* the logo rather than chosen to sit beside it — see [MenuTheme].
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

    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(MenuTheme.background)
            .padding(72.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Half the width and the whole of the height it is given, which on a 1080p set comes to a
        // little under 400 dp -- about what the xhdpi asset holds, so it is drawn near one image
        // pixel to one screen pixel rather than resampled up.
        Image(
            painter = painterResource(R.drawable.logo),
            contentDescription = "UltraStar Android TV",
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            contentScale = ContentScale.Fit,
        )

        Spacer(Modifier.width(56.dp))

        // **Stacked rather than in a row, which is what the logo bought.** Four buttons across the
        // bottom of a screen is a row to be scanned; four down the side of a title is a menu. On a
        // remote it is also the shorter journey -- three presses of down reaches Settings where
        // three of right reached it before, over a quarter of the distance.
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        ) {
            MenuButton(
                label = "Play",
                onClick = onPlay,
                enabled = canPlay,
                modifier = if (canPlay) Modifier.focusRequester(first) else Modifier,
            )
            // Songs sits directly under Play because it is about the same thing -- what there is
            // to sing. It is also the one item that still does something useful with no
            // microphone attached, which is why it takes the focus when Play cannot.
            MenuButton(
                label = "Songs",
                onClick = onSongs,
                modifier = if (canPlay) Modifier else Modifier.focusRequester(first),
            )
            MenuButton(label = "Singers", onClick = onSingers)
            MenuButton(label = "Settings", onClick = onSettings)

            if (!canPlay) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Plug in a microphone to play.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MenuTheme.warning,
                )
            }
        }
    }
}

/**
 * One item of the main menu.
 *
 * **Every colour is stated, unfocused and disabled included.** A tv-material Button takes its
 * defaults from the colour scheme, and that scheme is a dark one — so on a light ground an
 * unfocused button is pale on pale and a disabled one is very nearly gone. It is the same fault
 * that once left forty of forty-one drawn keys as unreadable ghosts, arrived at from the other
 * side.
 *
 * The shape is stated for the reason [KeyGrid] states it: the default is a pill, which is right
 * for something small and wide and reads as a lozenge at this size.
 */
@Composable
private fun MenuButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth(),
        colors = ButtonDefaults.colors(
            containerColor = MenuTheme.buttonIdle,
            contentColor = MenuTheme.ink,
            focusedContainerColor = MenuTheme.ink,
            focusedContentColor = MenuTheme.background,
            disabledContainerColor = MenuTheme.buttonDisabled,
            disabledContentColor = MenuTheme.inkFaded,
        ),
        shape = ButtonDefaults.shape(shape = RoundedCornerShape(14.dp)),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 14.dp),
    ) {
        // Left-aligned, and said out loud. A tv-material Button centres its content, but the
        // Row it centres in only wraps that content -- so a label in a full-width button lands
        // at the left anyway, by accident. A menu reads better down a common left edge than
        // with four labels of four lengths each centred somewhere different, so that accident
        // is the behaviour wanted: this fills the width and states the alignment, which means
        // it survives the day the library decides to fill the width itself.
        Text(
            label,
            fontSize = 26.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Start,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * The main menu's colours, and only the main menu's.
 *
 * Deliberately not folded into [GameTheme]. That object describes an instrument drawn over a
 * video and every colour in it assumes a near-black ground; these assume the opposite, and one
 * list holding both would mean every reader of either having to work out which world a colour
 * belongs to.
 *
 * The indigo is the logo's own, sampled out of the file rather than matched by eye — `#220F5F` is
 * where the wordmark starts, before it runs through `#A437B6` to `#79D2F4`.
 */
private object MenuTheme {
    /**
     * Near-white with the logo's own violet cast, rather than pure white.
     *
     * A full screen of `#FFFFFF` on an OLED in a dark living room is genuinely uncomfortable, and
     * this is an app used in the evening. Two points off white costs nothing that matters — the
     * wordmark's darkest ink still sits at about 13:1 against it — and takes the glare off.
     */
    val background = Color(0xFFF6F4FA)

    /** The wordmark's deep indigo, which is what makes the writing on this screen the logo's. */
    val ink = Color(0xFF220F5F)

    /** A tint of that same indigo: present enough to find, quiet enough to let the logo lead. */
    val buttonIdle = Color(0xFFE4DEF2)

    /**
     * Disabled: still visibly a button, visibly not a pressable one.
     *
     * Both are a shade further from the ground than the first attempt, which had the container
     * barely off the background and its label at about 2.5:1 against it -- muted past the point
     * of being readable, which is a different thing from looking unavailable. Play is disabled
     * exactly when somebody has no microphone, so it is the one moment this state is seen at
     * all, and it has a sentence underneath it that has to be worth reading.
     */
    val buttonDisabled = Color(0xFFE9E5F0)
    val inkFaded = Color(0xFF7C7391)

    /**
     * "Plug in a microphone to play."
     *
     * A deep amber rather than the warm yellow gameplay uses, which is legible over a video and
     * all but gone on paper. Warm, because it is the one line here that is not simply describing
     * what is already on the screen.
     */
    val warning = Color(0xFF8A4B00)
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
