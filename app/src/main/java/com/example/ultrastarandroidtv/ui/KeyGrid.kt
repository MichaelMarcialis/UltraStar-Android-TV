package com.example.ultrastarandroidtv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Text
import com.example.ultrastarandroidtv.game.GameTheme

/** Six across, which is what makes the letters land in a shape a thumb can learn. */
private val ROWS = listOf(
    "ABCDEF",
    "GHIJKL",
    "MNOPQR",
    "STUVWX",
    "YZ0123",
    "456789",
)

/**
 * Small enough that the whole grid, the query above it and the ways out below all fit.
 *
 * Measured on the television at 44 dp: the two buttons underneath were cut off by the bottom edge,
 * which is the same overflow that made UltraStar Play unusable and exactly what drawing our own
 * keyboard was meant to stop happening.
 */
private val KEY = 38.dp

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
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
    firstKey: FocusRequester? = null,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for ((rowIndex, row) in ROWS.withIndex()) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
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

        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            // Space is wide because it is pressed most and is the only key whose label says
            // nothing -- a wide blank key is recognisable where a narrow one is a mystery.
            Key(label = "space", onClick = { onKey(' ') }, width = KEY * 3 + 8.dp)
            Key(label = "del", onClick = onBackspace)
            Key(label = "clear", onClick = onClear, width = KEY * 2 + 4.dp)
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
            fontSize = if (label.length == 1) 18.sp else 12.sp,
            fontWeight = if (label.length == 1) FontWeight.Medium else FontWeight.Normal,
        )
    }
}

/**
 * The text being typed, shown above the keys.
 *
 * Its own panel rather than a text field, because there is no text field here to focus — the query
 * is built by pressing keys, and a caret in something that cannot take focus would be a lie about
 * where the cursor is.
 */
@Composable
fun QueryDisplay(query: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(GameTheme.noteIdle)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            query.ifBlank { "Type a song or artist" },
            fontSize = 18.sp,
            color = if (query.isBlank()) GameTheme.lyricIdle else GameTheme.lyricActive,
            maxLines = 1,
        )
    }
}
