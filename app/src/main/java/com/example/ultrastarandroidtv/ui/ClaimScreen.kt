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
import androidx.compose.ui.text.font.FontWeight
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
import com.example.ultrastarandroidtv.settings.Profiles
import com.example.ultrastarandroidtv.settings.isNameTaken
import com.example.ultrastarandroidtv.settings.namesAvailable
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
 * **With two singers the app names the microphone and the room supplies the person**, one at a
 * time. The first version asked the room for both at once — sing, and the mic that hears you
 * takes the first colour — and with two children that turns into both of them shouting
 * immediately, which is a race, and a race has a winner nobody can see. Asking about one
 * specific microphone removes the race outright: there is only ever one slot open, it is bound
 * to a named device, and the meters say which one your voice is moving.
 *
 * Two things make that legible rather than merely correct. **Every microphone's meter is live**,
 * not just the one being asked about, so a singer whose turn it is not can still see their own
 * voice registering somewhere and wait. And when more than one mic hears a voice the screen
 * *says so* — a claim that will not land because two people are singing looks exactly like
 * broken hardware otherwise.
 *
 * **On your own it stays the other way round**, and that is not an inconsistency. Alone there is
 * no question about who you are, only about which of two identical microphones you picked up —
 * so the mic has to be discovered rather than named. With two singers both mics are in play and
 * only the people are unknown, so the mic is named and the person discovered. Each flow asks
 * about the thing that is actually in doubt.
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
    onMenu: () -> Unit,
) {
    val mics = micSession.mics
    val claimed = remember { mutableStateListOf<GameSession.SingerSlot>() }
    var naming by remember { mutableStateOf<Int?>(null) }

    val levels = remember(mics.size) { FloatArray(mics.size) }
    val shown = remember(mics.size) { FloatArray(mics.size) }
    val claim = remember(settings.micThreshold) { MicClaim(minLevel = settings.micThreshold) }

    // Repainted from the frame loop; read inside composition so the meters animate.
    var tick by remember { mutableFloatStateOf(0f) }
    var contested by remember { mutableStateOf(false) }

    /** Mics still to be spoken for, in order. */
    val free = mics.indices.filter { i -> claimed.none { it.portId == mics[i].portId } }

    /**
     * The microphone the screen is asking about, or -1 when it is asking about any of them.
     *
     * With two singers this is the whole mechanism: exactly one slot is open at a time and it
     * belongs to a specific device, so two people singing at once cannot produce a wrong answer,
     * only a pause. On your own there is nothing to disambiguate, so any free mic will do.
     */
    val asking = if (playerCount == 1) -1 else free.firstOrNull() ?: -1

    // Back undoes the last claim rather than leaving the screen, so a mis-heard microphone costs
    // one press instead of starting the evening again. The same step the button offers.
    val stepBack: () -> Unit = {
        when {
            naming != null -> naming = null
            claimed.isNotEmpty() -> claimed.removeAt(claimed.size - 1)
            else -> onBack()
        }
    }
    BackHandler(onBack = stepBack)

    DisposableEffect(micSession) {
        micSession.onAudio = { mic, buffer, count ->
            if (mic.index < levels.size) levels[mic.index] = rms(buffer, count)
        }
        onDispose { micSession.onAudio = null }
    }

    LaunchedEffect(playerCount, mics.size) {
        // A mic pulled out mid-claim takes its singer with it, rather than leaving a slot
        // pointing at a device that is no longer there for the game to fail to open later.
        claimed.retainAll { slot -> mics.any { it.portId == slot.portId } }

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
                contested = false
                continue
            }

            // Recomputed every frame rather than captured: the target moves on as each mic is
            // claimed, and a value read once at composition would keep offering the first slot
            // after it had already been taken.
            val open = BooleanArray(mics.size) { index ->
                claimed.none { it.portId == mics[index].portId }
            }
            if (playerCount > 1) {
                val target = open.indexOfFirst { it }
                for (i in open.indices) open[i] = i == target
            }

            val taken = claim.update(shown, open, seconds)
            contested = claim.contested
            taken?.let { index ->
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
                solo = playerCount == 1,
                // The microphone that has just been claimed, named the same way the meters name
                // it, so the question follows on from the thing that just happened on screen.
                micNumber = mics.indexOfFirst { it.portId == claimed[slot].portId } + 1,
                // Whoever has already been named this game. The slot being named is still in
                // the list with a blank name, so nothing has to be excluded by index.
                taken = claimed.map { it.name }.filter { it.isNotBlank() }.toSet(),
                onPicked = { name ->
                    profiles.use(name)
                    claimed[slot] = claimed[slot].copy(name = name)
                    naming = null
                    if (claimed.size >= minOf(playerCount, mics.size)) onReady(claimed.toList())
                },
            )
        } else {
            Text(
                when {
                    mics.isEmpty() -> "No microphone"
                    playerCount == 1 -> "Sing into your microphone"
                    else -> "Whose microphone is this?"
                },
                style = MaterialTheme.typography.headlineLarge,
                color = GameTheme.lyricActive,
            )
            Text(
                when {
                    mics.isEmpty() -> micSession.summary
                    // Said plainly, because the alternative is a meter that fills and never
                    // finishes while both children shout at it and conclude it is broken.
                    contested -> "Both microphones can hear singing — one voice at a time."
                    // On your own there is no colour to race for; the claim is still worth doing,
                    // because it is what decides which of two identical microphones is yours.
                    playerCount == 1 -> "Whichever one hears you is the one you will be scored on."
                    else -> "Sing into the lit-up one. The meter that moves is the one you're holding."
                },
                style = MaterialTheme.typography.bodyLarge,
                color = if (contested) GameTheme.sparkWarm else GameTheme.lyricIdle,
            )
            Spacer(Modifier.height(40.dp))

            @Suppress("UNUSED_EXPRESSION") tick // Read so the meters repaint each frame.

            // One meter per actual microphone, not per player slot. Every one of them stays live
            // even when it is not the one being asked about: seeing your own voice register on
            // the other meter is what tells you to wait rather than to shout louder.
            Row {
                mics.forEachIndexed { index, mic ->
                    val slot = claimed.indexOfFirst { it.portId == mic.portId }
                    MicMeter(
                        title = "Microphone ${index + 1}",
                        colour = GameTheme.playerColors[
                            when {
                                slot >= 0 -> slot
                                playerCount == 1 -> 0
                                else -> index
                            } % GameTheme.playerColors.size
                        ],
                        name = claimed.getOrNull(slot)?.name,
                        active = playerCount == 1 || index == asking,
                        level = shown.getOrElse(index) { 0f },
                        progress = if (claim.leading == index) claim.progress(tick.toDouble()) else 0f,
                        threshold = settings.micThreshold,
                    )
                    Spacer(Modifier.width(28.dp))
                }
            }

            Spacer(Modifier.height(36.dp))

            // This screen waits on a voice, so without these there is nothing on it to press and
            // no visible way out — which matters most in the one case that strands you, a
            // microphone the room is not loud enough to claim.
            Row {
                Button(onClick = stepBack) {
                    Text(
                        if (claimed.isEmpty()) "How many singers" else "Undo last singer",
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                }
                Spacer(Modifier.width(16.dp))
                Button(onClick = onMenu) {
                    Text("Main menu", modifier = Modifier.padding(horizontal = 12.dp))
                }
            }
        }
    }
}

/**
 * One microphone, with what it is hearing right now.
 *
 * [active] is the difference between "this is the one you are being asked about" and "this one is
 * only here so you can see it is not you" — but an inactive meter still moves, because a singer
 * who cannot see their own voice anywhere assumes the microphone is dead and sings louder, which
 * is the one thing that makes the situation worse.
 */
@Composable
private fun MicMeter(
    title: String,
    colour: Color,
    name: String?,
    active: Boolean,
    level: Float,
    progress: Float,
    threshold: Float,
) {
    val claimedBy = !name.isNullOrBlank()
    val edge = when {
        claimedBy -> colour
        active -> colour.copy(alpha = 0.55f)
        else -> colour.copy(alpha = 0.12f)
    }

    Column(
        modifier = Modifier
            .width(300.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(GameTheme.trackBackground)
            .border(3.dp, edge, RoundedCornerShape(16.dp))
            .padding(24.dp),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.bodyMedium,
            color = if (active || claimedBy) GameTheme.lyricIdle else GameTheme.lyricIdle.copy(alpha = 0.5f),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            when {
                claimedBy -> name!!
                active -> "sing into this one"
                else -> "not this one"
            },
            style = MaterialTheme.typography.headlineSmall,
            color = when {
                claimedBy -> colour
                active -> GameTheme.lyricActive
                else -> GameTheme.lyricIdle.copy(alpha = 0.45f)
            },
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
                    .background(colour.copy(alpha = if (active || claimedBy) 0.85f else 0.4f)),
            )
            // Where "loud enough to count" sits. The meter is scaled to four times the gate, so
            // this lands a quarter of the way along — and it turns the meter from a wiggling bar
            // into a target, which is the difference between feedback and instruction.
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.25f)
                    .height(14.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Box(
                    modifier = Modifier
                        .width(2.dp)
                        .height(14.dp)
                        .background(GameTheme.background.copy(alpha = 0.7f)),
                )
            }
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
 *
 * **A name already in use this game is not offered at all.** One person cannot be holding both
 * microphones, so a second singer picking the first singer's name is never what they meant — and
 * the cost of allowing it is two identical names in the two top corners, which makes the scores
 * unreadable for the whole song. Removed from the list rather than shown and refused: an option
 * that cannot be chosen is only there to be pressed by mistake.
 *
 * @param solo true when only one person is singing, which changes what there is to say: "the
 *   first singer" is an answer to a question nobody on their own has asked.
 * @param micNumber which microphone was just claimed, counting from 1. With two singers the
 *   question is asked about the physical object rather than about a player number — "microphone
 *   1" is something you can be holding, and "player 1" is not.
 * @param taken names another singer has already claimed this game, compared without case the
 *   same way [Profiles] does, so "mia" cannot slip past an existing "Mia".
 */
@Composable
private fun NamePicker(
    colour: Color,
    profiles: Profiles,
    solo: Boolean,
    micNumber: Int,
    taken: Set<String>,
    onPicked: (String) -> Unit,
) {
    var typing by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    val first = remember { FocusRequester() }

    val available = namesAvailable(profiles.names, taken)

    LaunchedEffect(typing) {
        withFrameNanos { }
        runCatching { first.requestFocus() }
    }

    Text(
        if (solo) "Who's singing?" else "Who's holding microphone $micNumber?",
        style = MaterialTheme.typography.headlineLarge,
        color = colour,
    )

    // Dropped once the keyboard is up: it covers the bottom of the screen, and every line left
    // above the field pushes the Done and Cancel buttons further underneath it.
    if (!typing) {
        Text(
            when {
                solo -> "That microphone is yours for this song."
                colour == GameTheme.playerColors[0] -> "They'll sing on the left, in this colour."
                else -> "They'll sing on the right, in this colour."
            },
            style = MaterialTheme.typography.bodyLarge,
            color = GameTheme.lyricIdle,
        )
    }
    Spacer(Modifier.height(32.dp))

    if (typing) {
        // Typing the other singer's name reaches the same collision by a different road, so it
        // is refused here too — with the reason on screen, since a Done button that silently
        // does nothing is the one thing worse than allowing it.
        val clash = draft.isNotBlank() && isNameTaken(draft, taken)
        val accept = { if (draft.isNotBlank() && !clash) onPicked(draft) }

        NameEntry(
            value = draft,
            onValueChange = { draft = it },
            colour = colour,
            focusRequester = first,
            onDone = accept,
        )

        if (clash) {
            Spacer(Modifier.height(12.dp))
            Text(
                "${draft.trim()} is already singing on the other microphone.",
                style = MaterialTheme.typography.bodyLarge,
                color = GameTheme.playerColors[1],
            )
        }

        Spacer(Modifier.height(20.dp))
        Row {
            Button(onClick = accept) {
                Text("Done", modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            }
            Spacer(Modifier.width(16.dp))
            Button(onClick = { typing = false }) {
                Text("Cancel", modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            }
        }
    } else {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            items(available) { name ->
                Button(
                    onClick = { onPicked(name) },
                    modifier = if (name == available.firstOrNull()) {
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
            modifier = if (available.isEmpty()) Modifier.focusRequester(first) else Modifier,
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
