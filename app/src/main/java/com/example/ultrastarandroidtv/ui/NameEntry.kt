package com.example.ultrastarandroidtv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ultrastarandroidtv.game.GameTheme
import com.example.ultrastarandroidtv.settings.MAX_NAME_LENGTH

/**
 * The one place a name is typed, wherever that happens.
 *
 * Shared rather than duplicated because typing on a television is the most fragile interaction in
 * the app — it depends on the field taking focus at the right moment for the on-screen keyboard
 * to appear at all — and two copies of it would eventually differ in exactly that detail. The
 * length cap lives here too, so no caller can forget it.
 */
@Composable
fun NameEntry(
    value: String,
    onValueChange: (String) -> Unit,
    /** The singer's colour where there is one, so the caret belongs to whoever is typing. */
    colour: Color,
    focusRequester: FocusRequester,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BasicTextField(
        value = value,
        onValueChange = { onValueChange(it.take(MAX_NAME_LENGTH)) },
        singleLine = true,
        textStyle = TextStyle(color = GameTheme.lyricActive, fontSize = 34.sp),
        cursorBrush = SolidColor(colour),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        modifier = modifier
            .focusRequester(focusRequester)
            .width(420.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(GameTheme.trackBackground)
            .padding(horizontal = 20.dp, vertical = 16.dp),
    )
}
