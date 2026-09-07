package com.example.ultrastarandroidtv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Text
import com.example.ultrastarandroidtv.game.GameTheme
import kotlinx.coroutines.delay

/**
 * Seven across, which is what lets the letters, the digits and the punctuation fit in six rows.
 *
 * It was six wide and six rows of letters and digits alone. Seven columns costs two dp a key and
 * buys a whole row back, which is what paid for the punctuation — the alternative was a taller
 * keyboard, and there are only about twenty dp of slack under this one before the buttons beneath
 * it fall off the bottom of the television.
 */
private val ROWS = listOf(
    "ABCDEFG",
    "HIJKLMN",
    "OPQRSTU",
    "VWXYZ'-",
    "0123456",
    "789.,!?",
)

/**
 * Small enough that the whole grid, the query above it and the ways out below all fit.
 *
 * Measured on the television at 44 dp: the two buttons underneath were cut off by the bottom edge,
 * which is the same overflow that made UltraStar Play unusable and exactly what drawing our own
 * keyboard was meant to stop happening. The screen is 540 dp tall, so there is very little room to
 * play with — every key size here has been paid for out of somewhere else.
 *
 * At 32 dp the eight rows here are **shorter** than the seven rows of 38 dp they replaced, which is
 * the point: the layout that was measured as fitting is the floor, and a keyboard that grew two
 * rows had to get back under it rather than hope.
 */
private val KEY = 32.dp

/** Gap between keys, and the unit the wide keys are built from. */
private val GAP = 4.dp

/** How wide a key spanning [units] columns is, gaps included. */
private fun span(units: Int): Dp = KEY * units + GAP * (units - 1)

/**
 * A keyboard drawn on the page, instead of the one Android puts over it.
 *
 * ## Why this exists
 *
 * Every keyboard problem this app has had is the same problem: **Gboard covers the bottom
 * two-thirds of the television and about half its width.** The sign-in form's first draft cleared
 * it by four pixels. The profile screen has to drop its own title and subtitle whenever a field is
 * open, because anything left above a text box costs a button below it. The search row could not
 * hold five controls, so the ways out were moved into a corner. And the microphone key on that
 * keyboard cannot hear this app's microphones at all, which took three spellings of a private IME
 * option to suppress.
 *
 * None of those are bugs in the layouts. They are the cost of summoning a system keyboard onto a
 * screen that is only 1080 lines tall, and drawing the keys on the page instead removes the whole
 * class of them at once. It is what Plex, Netflix and YouTube all do on a television, for exactly
 * this reason.
 *
 * ## There is a cursor
 *
 * Which is the thing a drawn keyboard has to provide for itself: a system keyboard comes attached
 * to a text field that has a caret and arrow keys of its own, and this one is attached to a panel
 * that draws text. Without it, one wrong letter at the start of a word costs every letter after
 * it — which on a directional pad is the difference between a correction and starting again.
 *
 * So the last two rows carry left, right, backspace and delete, and [QueryDisplay] draws the caret
 * where the next letter will go. **Backspace and delete are named rather than drawn**, because a
 * glyph either of them would use (⌫, ⌦) is not reliably in the system font, and a key showing a
 * missing-character box is worse than a key showing a word.
 *
 * ## What it costs
 *
 * Typing is slower per character — every letter is a few presses of a directional pad. That is
 * real, and it is why searching runs on its own as the query changes rather than waiting for
 * somebody to find a Search button: the cost is paid per *word*, not per *attempt*, and USDB's
 * search is the part of it that is not throttled.
 *
 * Left where it is rather than centred, because on a remote the shortest path matters more than
 * symmetry: the grid, the query above it and the ways out below are one column that a thumb
 * travels in a straight line.
 */
@Composable
fun KeyGrid(
    onKey: (Char) -> Unit,
    onBackspace: () -> Unit,
    onDelete: () -> Unit,
    onLeft: () -> Unit,
    onRight: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
    firstKey: FocusRequester? = null,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(GAP)) {
        for ((rowIndex, row) in ROWS.withIndex()) {
            Row(horizontalArrangement = Arrangement.spacedBy(GAP)) {
                for ((columnIndex, character) in row.withIndex()) {
                    Key(
                        label = character.toString(),
                        onClick = { onKey(character) },
                        modifier = if (rowIndex == 0 && columnIndex == 0 && firstKey != null) {
                            Modifier.focusRequester(firstKey)
                        } else {
                            Modifier
                        },
                    )
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(GAP)) {
            // Space is wide because it is pressed most and is the only key whose label says
            // nothing -- a wide blank key is recognisable where a narrow one is a mystery.
            Key(label = "space", onClick = { onKey(' ') }, width = span(3))
            Key(label = "◀", onClick = onLeft)
            Key(label = "▶", onClick = onRight)
            Key(label = "clear", onClick = onClear, width = span(2))
        }

        Row(horizontalArrangement = Arrangement.spacedBy(GAP)) {
            Key(label = "backspace", onClick = onBackspace, width = span(4))
            Key(label = "delete", onClick = onDelete, width = span(3))
        }
    }
}

@Composable
private fun Key(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = KEY,
) {
    Button(
        onClick = onClick,
        modifier = modifier.size(width = width, height = KEY),
        // Both colours, said explicitly. A TV Button leaves an *unfocused* key dark on dark:
        // measured on the television, only the one key with the focus could be read at all and the
        // other forty were a faint grid of ghosts. A keyboard nobody can read is not a keyboard.
        colors = ButtonDefaults.colors(
            containerColor = GameTheme.noteIdle,
            contentColor = GameTheme.lyricActive,
        ),
        // Rounded squares rather than the default pill, so the wide keys read as keys instead of
        // as lozenges and a square key does not become a circle.
        shape = ButtonDefaults.shape(shape = RoundedCornerShape(6.dp)),
        contentPadding = PaddingValues(0.dp),
    ) {
        Text(
            label,
            fontSize = if (label.length == 1) 17.sp else 11.sp,
            fontWeight = if (label.length == 1) FontWeight.Medium else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Clip,
        )
    }
}

/** How long the caret stays on, and then off. Slow enough to read as a caret, not a flicker. */
private const val BLINK_MS = 530L

/**
 * The text being typed, shown above the keys, with the caret where the next letter goes.
 *
 * Its own panel rather than a text field, because there is no text field here to focus — the query
 * is built by pressing keys. The caret is therefore drawn by hand, and it has to be: with left,
 * right and forward delete on the keyboard, "where the cursor is" is a real question the screen is
 * the only thing that can answer.
 *
 * It blinks, and the blink **restarts solid on every edit**, so the caret is never invisible at the
 * moment somebody has just pressed a key and is looking for what happened.
 */
@Composable
fun QueryDisplay(query: TypedQuery, modifier: Modifier = Modifier) {
    var on by remember { mutableStateOf(true) }
    LaunchedEffect(query) {
        on = true
        while (true) {
            delay(BLINK_MS)
            on = !on
        }
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(GameTheme.noteIdle)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (query.before.isNotEmpty()) {
                Text(query.before, fontSize = 18.sp, color = GameTheme.lyricActive, maxLines = 1)
            }
            Box(
                modifier = Modifier
                    .width(2.dp)
                    .height(22.dp)
                    .alpha(if (on) 1f else 0f)
                    .background(GameTheme.lyricActive),
            )
            when {
                query.after.isNotEmpty() ->
                    Text(query.after, fontSize = 18.sp, color = GameTheme.lyricActive, maxLines = 1)
                // The prompt sits *after* the caret rather than replacing the whole panel, so the
                // caret is where it will be once typing starts instead of jumping there.
                query.isEmpty -> Text(
                    "Type a song or artist",
                    fontSize = 18.sp,
                    color = GameTheme.lyricIdle,
                    maxLines = 1,
                )
            }
        }
    }
}
