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
import androidx.compose.ui.text.input.PlatformImeOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ultrastarandroidtv.game.GameTheme
import com.example.ultrastarandroidtv.settings.MAX_NAME_LENGTH

/**
 * Asks the keyboard not to offer its dictation button.
 *
 * The button cannot work here and never will: it belongs to Gboard, which hands off to
 * `KatnissRecognitionService` — a separate process that opens its own input through Android's
 * audio stack, the one this device's USB HAL cannot serve. The singing microphones are not
 * merely a poor choice for it, they are invisible to it. So the button is an offer the app
 * cannot honour, which is worse than no offer at all: somebody presses it, holds a microphone
 * up, and concludes the microphone is broken.
 *
 * `privateImeOptions` is the convention AOSP LatinIME reads for this, and Gboard descends from
 * it. Three spellings are sent because the check has changed shape over the years and the field
 * is comma-splittable, so listing all of them costs nothing: the bare key, the compat
 * abbreviation, and the key qualified by the IME's own package, which is the form the current
 * source builds.
 *
 * It is a request rather than a guarantee — a keyboard is free to ignore it, and this one is not
 * ours. Verified on the device rather than assumed; if a keyboard ever ignores it, the button
 * comes back and nothing else changes.
 */
private const val NO_MICROPHONE =
    "noMicrophoneKey,nm,com.google.android.inputmethod.latin.noMicrophoneKey"

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
        keyboardOptions = KeyboardOptions(
            imeAction = ImeAction.Done,
            platformImeOptions = PlatformImeOptions(NO_MICROPHONE),
        ),
        modifier = modifier
            .focusRequester(focusRequester)
            .width(420.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(GameTheme.trackBackground)
            .padding(horizontal = 20.dp, vertical = 16.dp),
    )
}
