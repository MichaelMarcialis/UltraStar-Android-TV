package com.example.ultrastarandroidtv.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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
        modifier = modifier,
        colors = ButtonDefaults.colors(
            containerColor = if (selected) GameTheme.playerColors[0] else GameTheme.trackBackground,
            contentColor = if (selected) GameTheme.background else GameTheme.lyricIdle,
        ),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
    ) {
        Text(label, fontSize = 13.sp)
    }
}
