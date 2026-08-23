package com.example.ultrastarandroidtv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.example.ultrastarandroidtv.game.GameTheme
import com.example.ultrastarandroidtv.settings.GameSettings
import com.example.ultrastarandroidtv.settings.SettingsRange
import kotlin.math.roundToInt

/**
 * The three numbers worth exposing, and nothing else.
 *
 * Every one of them is here because it can only be decided in the room: how far behind this TV
 * shows a frame, how much song a person wants to read ahead, and how close a mouth has to be to
 * a microphone in this house with these voices. None can be derived, so all three are dials.
 */
@Composable
fun SettingsScreen(settings: GameSettings, onBack: () -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    BackHandler(onBack = onBack)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(GameTheme.background)
            .padding(horizontal = 72.dp, vertical = 32.dp),
    ) {
        Text(
            "Settings",
            style = MaterialTheme.typography.headlineLarge,
            color = GameTheme.lyricActive,
        )
        Spacer(Modifier.height(20.dp))

        SettingRow(
            label = "Display lead",
            explanation = "Every television waits a moment before it shows a frame, and this " +
                "draws that far ahead to cancel it out. Raise it if the words arrive at the line " +
                "just after you hear them sung, lower it if they arrive early. Changes only " +
                "what you see — never what you score.",
            value = settings.displayLeadSeconds,
            range = SettingsRange.lead,
            // Ten milliseconds, not five. The range is three hundred, so a five-millisecond step
            // needed sixty presses to cross it, and a few presses moved the picture by less than
            // one frame — which is indistinguishable from the setting doing nothing at all.
            step = 0.01,
            format = { "%d ms".format((it * 1000).roundToInt()) },
            onChange = settings::updateLead,
            modifier = Modifier.focusRequester(first),
        )

        SettingRow(
            label = "Visible window",
            explanation = "How many seconds of the song fit across the track. Less means larger " +
                "words that are easier to read, and less warning of the note coming next. More " +
                "means more warning, and everything packed tighter.",
            value = settings.windowSeconds,
            range = SettingsRange.window,
            step = 0.25,
            format = { "%.2f s".format(it) },
            onChange = settings::updateWindow,
        )

        SettingRow(
            label = "Microphone sensitivity",
            explanation = "How loud a voice must be to count as singing. Lower it if one " +
                "singer's microphone is scoring the other singer or the television; raise it if " +
                "someone quiet is scoring nothing. It sets how loudly a phrase must *begin* — " +
                "once a singer has started, they keep counting as they soften.",
            // Sensitivity, not the underlying gate: a sensitive microphone picks up more, and a
            // slider labelled this way has to move that way.
            value = settings.micSensitivity.toDouble(),
            range = 0.0..1.0,
            step = 0.05,
            format = { "%d%%".format((it * 100).roundToInt()) },
            onChange = { settings.updateMicSensitivity(it.toFloat()) },
        )

        Spacer(Modifier.height(24.dp))
        Text(
            "Left and right to adjust.  Back to return.",
            style = MaterialTheme.typography.bodyMedium,
            color = GameTheme.lyricIdle,
        )
    }
}

/**
 * One dial: a label, a bar, and a value, adjusted with left and right.
 *
 * It looks like a slider and is driven like a D-pad, because a drag slider on a television is
 * hostile — there is no pointer, and emulating one with a directional pad is worse than not
 * having the control at all. Left and right are what the remote is for.
 */
@Composable
private fun SettingRow(
    label: String,
    explanation: String,
    value: Double,
    range: ClosedFloatingPointRange<Double>,
    step: Double,
    format: (Double) -> String,
    onChange: (Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (focused) GameTheme.trackBackground else GameTheme.background)
            .padding(horizontal = 20.dp, vertical = 14.dp)
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionLeft -> {
                        onChange((value - step).coerceIn(range))
                        true
                    }
                    Key.DirectionRight -> {
                        onChange((value + step).coerceIn(range))
                        true
                    }
                    else -> false
                }
            },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                style = MaterialTheme.typography.titleMedium,
                color = if (focused) GameTheme.lyricActive else GameTheme.lyricIdle,
            )
            Spacer(Modifier.width(20.dp))
            Text(
                format(value),
                style = MaterialTheme.typography.titleMedium,
                color = GameTheme.playerColors[0],
            )
        }

        Spacer(Modifier.height(10.dp))

        val fraction = ((value - range.start) / (range.endInclusive - range.start))
            .coerceIn(0.0, 1.0)
            .toFloat()

        Canvas(modifier = Modifier.fillMaxWidth().height(10.dp)) {
            val radius = CornerRadius(size.height / 2f)
            drawRoundRect(GameTheme.noteIdle, size = size, cornerRadius = radius)
            if (fraction > 0f) {
                drawRoundRect(
                    color = if (focused) GameTheme.playerColors[0] else GameTheme.noteActive,
                    topLeft = Offset.Zero,
                    size = Size(size.width * fraction, size.height),
                    cornerRadius = radius,
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        Text(explanation, style = MaterialTheme.typography.bodySmall, color = GameTheme.lyricIdle)
    }
}
