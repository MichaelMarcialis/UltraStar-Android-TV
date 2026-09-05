package com.example.ultrastarandroidtv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.example.ultrastarandroidtv.game.GameTheme
import com.example.ultrastarandroidtv.settings.Difficulty
import com.example.ultrastarandroidtv.settings.GameSettings
import com.example.ultrastarandroidtv.settings.SettingsRange
import kotlin.math.roundToInt

/**
 * The few numbers worth exposing, and nothing else.
 *
 * Every one of them is here because it can only be decided in the room: how far behind this TV
 * shows a frame, how much song a person wants to read ahead, and how close a mouth has to be to
 * a microphone in this house with these voices. None can be derived, so all of them are dials.
 *
 * Microphone sensitivity is asked twice because singing alone and singing with somebody else are
 * different acoustic problems, and a single value tuned for one is wrong for the other. Asking
 * twice is what stops it having to be *changed* twice, every time the number of singers changes.
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
        // The heading and the instruction share a line, and both sit **outside** the scrolling
        // part below — so the one line telling you how to work this screen can never be scrolled
        // off it. The instruction used to be at the foot of the list, where a fifth setting
        // pushed it off the television entirely.
        //
        // The hint takes the leftover width, so a longer one wraps onto a second line rather than
        // running off the right edge. **Nothing on this screen may ever scroll sideways.** Down
        // the page is fine as long as it works; across is not, and horizontal overflow is half of
        // what made UltraStar Play unusable on this television.
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                "Settings",
                style = MaterialTheme.typography.headlineLarge,
                color = GameTheme.lyricActive,
            )
            Spacer(Modifier.width(28.dp))
            Text(
                "Left and right to adjust.  Up and down to move.",
                style = MaterialTheme.typography.bodyMedium,
                color = GameTheme.lyricIdle,
                modifier = Modifier.weight(1f).padding(bottom = 6.dp),
            )
            // The way out, said as a button as well as being on the Back key.
            //
            // In the header rather than under the list, and that is the point of it: the header
            // is the part of this screen that does not scroll, so the exit can never be scrolled
            // off the bottom — which is exactly what happened to the hint that used to live down
            // there. Pressing up from the first dial reaches it.
            Button(onClick = onBack) {
                Text("Main menu", modifier = Modifier.padding(horizontal = 12.dp))
            }
        }
        Spacer(Modifier.height(16.dp))

        // Scrolls vertically, which is the whole answer to "what happens when there are more
        // settings than fit". Explaining only the focused row already keeps the screen a constant
        // height, so this is a safety net rather than the normal case — but a safety net that has
        // to actually hold, because a control nobody can reach is worse than one that is not
        // there at all.
        //
        // **A plain Column, not a LazyColumn.** A lazy list does not compose what is off screen,
        // so there would be nothing below the last visible row for the focus search to find and
        // the remote would simply stop moving — the same dead end a disabled TV button creates,
        // and the same trap the song picker and the results list both document. Every row here
        // exists, so pressing down always has somewhere to go, and Compose brings the newly
        // focused row into view by itself.
        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {

        // First, and the only one here that changes what a performance is worth. Everything
        // below it describes the room and the hardware and is set once by whoever put this
        // together; this one belongs to whoever is about to sing.
        SettingRow(
            label = "Difficulty",
            explanation = "How close to the note you have to be for a beat to count. The note " +
                "bars are drawn exactly as tall as the window that scores them, so an easier " +
                "setting has visibly fatter notes to aim at — what you see is what is being " +
                "judged. Easy is roughly twice the room of Hard.",
            value = settings.difficulty.ordinal.toDouble(),
            range = 0.0..Difficulty.entries.lastIndex.toDouble(),
            step = 1.0,
            format = { Difficulty.entries[it.toIndex()].label },
            onChange = { settings.updateDifficulty(Difficulty.entries[it.toIndex()]) },
            modifier = Modifier.focusRequester(first),
        )

        SettingRow(
            label = "Display lead",
            explanation = "Every television waits a moment before it shows a frame, and this " +
                "draws that far ahead to cancel it out. Raise it if the words arrive at the line " +
                "just after you hear them sung, lower it if they arrive early. Changes only " +
                "what you see — never what you score. It also widens the shaded band behind " +
                "the arrows, which is the stretch of song being judged right now.",
            value = settings.displayLeadSeconds,
            range = SettingsRange.lead,
            // Ten milliseconds, not five. The range is three hundred, so a five-millisecond step
            // needed sixty presses to cross it, and a few presses moved the picture by less than
            // one frame — which is indistinguishable from the setting doing nothing at all.
            step = 0.01,
            format = { "%d ms".format((it * 1000).roundToInt()) },
            onChange = settings::updateLead,
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

        // Two of them, because the two modes are different acoustic problems. On your own the
        // only competition is the television; with two people each microphone also hears the
        // other singer. Splitting them is what stops anyone having to remember to change it.
        SettingRow(
            label = "Microphone sensitivity — one singer",
            explanation = "How loud a voice must be to count when you are singing alone. It can " +
                "sit high: nobody else is singing, so the only thing to keep out is the song " +
                "itself coming back off the television.",
            // Sensitivity, not the underlying gate: a sensitive microphone picks up more, and a
            // slider labelled this way has to move that way.
            value = settings.soloMicSensitivity.toDouble(),
            range = 0.0..1.0,
            step = 0.05,
            format = { "%d%%".format((it * 100).roundToInt()) },
            onChange = { settings.updateSoloMicSensitivity(it.toFloat()) },
        )

        SettingRow(
            label = "Microphone sensitivity — two singers",
            explanation = "The same, with two people in the room. Lower than the setting above, " +
                "because each microphone now hears the other singer too, and how loud a voice " +
                "is is the only clue to which mouth is nearest. Raise it if someone quiet is " +
                "scoring nothing; lower it if one singer is scoring the other.",
            value = settings.duetMicSensitivity.toDouble(),
            range = 0.0..1.0,
            step = 0.05,
            format = { "%d%%".format((it * 100).roundToInt()) },
            onChange = { settings.updateDuetMicSensitivity(it.toFloat()) },
        )

        SettingRow(
            label = "Fill the screen with video",
            explanation = "Zooms a song's music video until it fills the television, cropping " +
                "whatever will not fit — and measures away any black bars baked into the file. " +
                "Most videos here are 4:3 rips, so shown whole they sit in a black box. Turn " +
                "this off to see each video whole at its own shape instead.",
            value = if (settings.fillScreenVideo) 1.0 else 0.0,
            range = 0.0..1.0,
            step = 1.0,
            format = { if (it >= 0.5) "On" else "Off" },
            onChange = { settings.updateFillScreenVideo(it >= 0.5) },
        )

        SettingRow(
            label = "Low-latency picture",
            explanation = "Drives the television at 120 Hz while this app is open, to skip its " +
                "motion smoothing. Off, because measuring a song with it on found the opposite " +
                "of what it promised: it saves 8 ms on the Shield's side and adds about 25 ms " +
                "further down the pipeline, and it makes music videos judder. Here so it can be " +
                "measured again if this television or the amplifier ever changes.",
            value = if (settings.lowLatencyVideo) 1.0 else 0.0,
            range = 0.0..1.0,
            step = 1.0,
            format = { if (it >= 0.5) "On" else "Off" },
            onChange = { settings.updateLowLatencyVideo(it >= 0.5) },
        )
        }
    }
}

/** Nearest whole step of a dial that steps through a list rather than a range of numbers. */
private fun Double.toIndex(): Int = roundToInt().coerceIn(0, Difficulty.entries.lastIndex)

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

        // Only the dial being adjusted explains itself. Four settings with a paragraph each ran
        // off the bottom of the television and took the footer with them — which is exactly the
        // fault that made UltraStar Play unusable, and not one to reproduce. An explanation for a
        // control nobody is touching is noise, and showing one at a time keeps the whole screen
        // at a constant height however many settings there eventually are.
        if (focused) {
            Spacer(Modifier.height(8.dp))
            Text(
                explanation,
                style = MaterialTheme.typography.bodySmall,
                color = GameTheme.lyricIdle,
            )
        }
    }
}
