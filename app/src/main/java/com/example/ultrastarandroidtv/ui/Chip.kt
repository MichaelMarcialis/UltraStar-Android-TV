package com.example.ultrastarandroidtv.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Text
import com.example.ultrastarandroidtv.game.GameTheme

/**
 * A small two-state button, for the questions that are settings rather than actions.
 *
 * Distinct from an ordinary Button on purpose: sorting and filtering *stay* chosen, and a control
 * that looks the same before and after it is pressed cannot say which of five things is in force.
 */
@Composable
fun Chip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        // **`enabled = false` is not enough to keep the focus off it**, whatever the received
        // wisdom about TV buttons says. Measured on the television: three presses of the centre
        // key landed on a greyed-out "No artwork" chip and did nothing, which is exactly what a
        // broken button looks like from a sofa. Said explicitly here so it does not depend on
        // how a version of tv-material happens to wire `enabled` to focusability.
        //
        // Not focusable is the right answer *for a chip*, unlike a card in a grid, which is kept
        // focusable even when it cannot be pressed: a hole in a row is stepped over by the next
        // press, where a hole in a grid breaks vertical movement through it. There is always an
        // enabled chip on these rows — sorting never runs out of options — so a row can never
        // become unreachable.
        modifier = modifier.focusProperties { canFocus = enabled },
        colors = ButtonDefaults.colors(
            containerColor = if (selected) GameTheme.playerColors[0] else GameTheme.trackBackground,
            contentColor = if (selected) GameTheme.background else GameTheme.lyricIdle,
        ),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
    ) {
        Text(label, fontSize = 13.sp)
    }
}
