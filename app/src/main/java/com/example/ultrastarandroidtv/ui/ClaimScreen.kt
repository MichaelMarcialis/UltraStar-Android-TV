package com.example.ultrastarandroidtv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.example.ultrastarandroidtv.game.GameSession
import com.example.ultrastarandroidtv.game.GameTheme
import com.example.ultrastarandroidtv.game.MicClaim
import com.example.ultrastarandroidtv.mic.UsbMicSession
import com.example.ultrastarandroidtv.settings.GameSettings
import com.example.ultrastarandroidtv.settings.MAX_NAME_LENGTH
import com.example.ultrastarandroidtv.settings.Profiles
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/** How fast a level meter falls. Rising is instant; falling slowly is what makes it readable. */
private const val LEVEL_DECAY = 0.90f

/**
 * Where singers take a microphone and say who they are.
 *
 * The problem this solves is not "whose score is that" — a name fixes that. It is "which of
 * these two identical microphones am I holding", which no amount of on-screen labelling can
 * answer, because the label is on the screen and the microphone is in your hand.
 *
 * So the microphone answers it. Sing, and the mic that hears you becomes the first colour; the
 * next voice takes the second. Then, and only then, that person picks their name — which means
 * nobody is ever asked to guess whether they are "Player 1" before the system has told them.
 *
 * Names are asked every single game rather than remembered against a microphone. In a house
 * with four children the microphone is the one thing that does *not* identify a person: it gets
 * handed to whoever is singing next. Remembering would mean confidently putting last night's
 * name on tonight's singer, which is worse than asking.
 */
@Composable
fun ClaimScreen(
    playerCount: Int,
    micSession: UsbMicSession,
    profiles: Profiles,
    settings: GameSettings,
    onReady: (List<GameSession.SingerSlot>) -> Unit,
    onBack: () -> Unit,
) {
    val mics = micSession.mics
    val claimed = remember { mutableStateListOf<GameSession.SingerSlot>() }
    var naming by remember { mutableStateOf<Int?>(null) }

    val levels = remember(mics.size) { FloatArray(mics.size) }
    val shown = remember(mics.size) { FloatArray(mics.size) }
    val claim = remember(settings.micThreshold) { MicClaim(minLevel = settings.micThreshold) }

    // Repainted from the frame loop; read inside composition so the meters animate.
    var tick by remember { mutableFloatStateOf(0f) }

    BackHandler {
        // Back undoes the last claim rather than leaving the screen, so a mis-heard microphone
        // costs one press instead of starting the evening again.
        when {
            naming != null -> naming = null
            claimed.isNotEmpty() -> claimed.removeAt(claimed.size - 1)
            else -> onBack()
        }
    }

    DisposableEffect(micSession) {
        micSession.onAudio = { mic, buffer, count ->
            if (mic.index < levels.size) levels[mic.index] = rms(buffer, count)
        }
        onDispose { micSession.onAudio = null }
    }

    LaunchedEffect(playerCount, mics.size) {
        var seconds = 0.0
        while (claimed.size < minOf(playerCount, mics.size)) {
            withFrameNanos { }
            seconds += 1.0 / 60.0

            for (i in shown.indices) {
                shown[i] = maxOf(levels[i], shown[i] * LEVEL_DECAY)
            }
            tick = seconds.toFloat()

            // Nobody can claim while a name is being chosen: the room is not quiet, and the
            // singer being named is often still talking.
            if (naming != null) {
                claim.reset()
                continue
            }

            val eligible = BooleanArray(mics.size) { index ->
                claimed.none { it.portId == mics[index].portId }
            }
            claim.update(shown, eligible, seconds)?.let { index ->
                claimed.add(GameSession.SingerSlot(mics[index].portId, ""))
                naming = claimed.size - 1
                claim.reset()
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(GameTheme.background)
            .padding(56.dp),
    ) {
        val slot = naming
        if (slot != null) {
            NamePicker(
                colour = GameTheme.playerColors[slot % GameTheme.playerColors.size],
                profiles = profiles,
                onPicked = { name ->
                    profiles.use(name)
                    claimed[slot] = claimed[slot].copy(name = name)
                    naming = null
                    if (claimed.size >= minOf(playerCount, mics.size)) onReady(claimed.toList())
                },
            )
        } else {
            Text(
                if (claimed.isEmpty()) "Sing into your microphone" else "Now the other microphone",
                style = MaterialTheme.typography.headlineLarge,
                color = GameTheme.lyricActive,
            )
            Text(
                if (mics.isEmpty()) {
                    micSession.summary
                } else {
                    "Whoever sings first takes the first colour."
                },
                style = MaterialTheme.typography.bodyLarge,
                color = GameTheme.lyricIdle,
            )
            Spacer(Modifier.height(40.dp))

            @Suppress("UNUSED_EXPRESSION") tick // Read so the meters repaint each frame.
            Row {
                repeat(minOf(playerCount, maxOf(mics.size, 1))) { position ->
                    val taken = claimed.getOrNull(position)
                    MicSlot(
                        colour = GameTheme.playerColors[position % GameTheme.playerColors.size],
                        name = taken?.name,
                        level = if (taken != null) {
                            1f
                        } else {
                            // Before anything is claimed, show the loudest unclaimed mic, which
                            // is the one the singer is holding.
                            shown.filterIndexed { index, _ ->
                                claimed.none { it.portId == mics.getOrNull(index)?.portId }
                            }.maxOrNull() ?: 0f
                        },
                        progress = if (taken == null && claim.leading >= 0) {
                            claim.progress(tick.toDouble())
                        } else {
                            0f
                        },
                        threshold = settings.micThreshold,
                    )
                    Spacer(Modifier.width(28.dp))
                }
            }

            Spacer(Modifier.height(36.dp))
            Text(
                "Back undoes the last one.",
                style = MaterialTheme.typography.bodyMedium,
                color = GameTheme.lyricIdle,
            )
        }
    }
}

@Composable
private fun MicSlot(
    colour: Color,
    name: String?,
    level: Float,
    progress: Float,
    threshold: Float,
) {
    Column(
        modifier = Modifier
            .width(300.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(GameTheme.trackBackground)
            .border(3.dp, if (name != null) colour else colour.copy(alpha = 0.25f), RoundedCornerShape(16.dp))
            .padding(24.dp),
    ) {
        Text(
            name ?: "waiting…",
            style = MaterialTheme.typography.headlineSmall,
            color = if (name != null) colour else GameTheme.lyricIdle,
        )
        Spacer(Modifier.height(16.dp))

        // A meter rather than a spinner: it shows that the microphone is alive, which on this
        // hardware has never been something to take for granted.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(14.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(GameTheme.noteIdle),
        ) {
            val filled = (level / (threshold * 4f)).coerceIn(0f, 1f)
            Box(
                modifier = Modifier
                    .fillMaxWidth(filled)
                    .height(14.dp)
                    .background(colour.copy(alpha = 0.8f)),
            )
        }

        if (progress > 0f) {
            Spacer(Modifier.height(10.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth(progress)
                    .height(5.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(colour),
            )
        }
    }
}

/**
 * Picks the name for the microphone that has just been claimed.
 *
 * Recent names first, because with children rotating the best predictor of who is about to sing
 * is who sang last. Typing is the rare path — once per person, ever — and it is deliberately the
 * last option on the row rather than the first.
 */
@Composable
private fun NamePicker(
    colour: Color,
    profiles: Profiles,
    onPicked: (String) -> Unit,
) {
    var typing by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    val first = remember { FocusRequester() }

    LaunchedEffect(typing) {
        withFrameNanos { }
        runCatching { first.requestFocus() }
    }

    Text("Who's singing?", style = MaterialTheme.typography.headlineLarge, color = colour)
    Text(
        "This microphone is now ${if (colour == GameTheme.playerColors[0]) "the first" else "the second"} singer.",
        style = MaterialTheme.typography.bodyLarge,
        color = GameTheme.lyricIdle,
    )
    Spacer(Modifier.height(32.dp))

    if (typing) {
        BasicTextField(
            value = draft,
            onValueChange = { draft = it.take(MAX_NAME_LENGTH) },
            singleLine = true,
            textStyle = TextStyle(color = GameTheme.lyricActive, fontSize = 34.sp),
            cursorBrush = SolidColor(colour),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                onDone = { if (draft.isNotBlank()) onPicked(draft) },
            ),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                imeAction = ImeAction.Done,
            ),
            modifier = Modifier
                .focusRequester(first)
                .width(420.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(GameTheme.trackBackground)
                .padding(horizontal = 20.dp, vertical = 16.dp),
        )
        Spacer(Modifier.height(20.dp))
        Row {
            Button(onClick = { if (draft.isNotBlank()) onPicked(draft) }) {
                Text("Done", modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            }
            Spacer(Modifier.width(16.dp))
            Button(onClick = { typing = false }) {
                Text("Cancel", modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            }
        }
    } else {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            items(profiles.names) { name ->
                Button(
                    onClick = { onPicked(name) },
                    modifier = if (name == profiles.names.firstOrNull()) {
                        Modifier.focusRequester(first)
                    } else {
                        Modifier
                    },
                ) {
                    Text(
                        name,
                        fontSize = 26.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 22.dp, vertical = 10.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(28.dp))
        Button(
            onClick = {
                draft = ""
                typing = true
            },
            modifier = if (profiles.names.isEmpty()) Modifier.focusRequester(first) else Modifier,
        ) {
            Text("New name…", modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        }
    }
}

/** Normalised loudness of one buffer of mono 16-bit PCM. */
private fun rms(pcm: ByteBuffer, byteCount: Int): Float {
    val samples = pcm.duplicate().order(ByteOrder.LITTLE_ENDIAN)
    val count = byteCount / 2
    if (count == 0) return 0f

    var sum = 0.0
    for (i in 0 until count) {
        val value = samples.getShort(i * 2) / 32768f
        sum += value.toDouble() * value
    }
    return sqrt(sum / count).toFloat()
}
