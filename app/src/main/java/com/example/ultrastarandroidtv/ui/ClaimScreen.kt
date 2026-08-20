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
import androidx.compose.runtime.FloatState
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
 * The problem this solves is not "whose score is that" — a name fixes that. It is "which of these
 * two identical microphones am I holding", which no amount of on-screen labelling can answer,
 * because the label is on the screen and the microphone is in your hand.
 *
 * **One singer is set up at a time, from beginning to end.** Sing, take a microphone, choose a
 * name, and only then does the next colour open. Two earlier versions asked the room for more
 * than that at once and both failed the same way in the same room: with two children, the moment
 * a meter appears they both shout at it, and whatever the app then decides, nobody watching can
 * tell which of them it decided about.
 *
 * **The meter belongs to the singer, not to the microphone, and it stays up while they choose a
 * name.** That is the actual fix, and it is why this screen is a row rather than a column: one
 * card on the right belongs to the colour being set up, and it does not move, resize or vanish
 * when the question changes from "sing" to "who are you". So a claim that landed on the wrong
 * child is *recoverable by singing* — one of them sings, and the meter says whether the name
 * about to be chosen is theirs. Before, the meters disappeared the instant a claim landed, which
 * is exactly the moment the room needed one.
 *
 * **The microphone is always discovered rather than named.** A previous version asked "who is
 * holding microphone 1?" whenever every attached mic had to be in somebody's hand, on the
 * grounds that naming one removes the race. It does remove the race, and it still could not
 * answer the question the room was actually asking. It also quietly assumed the microphone the
 * app chose was the one the singer had picked up, which stops being true as soon as a third
 * audio device is plugged in. Singing into the one in your hand proves it works *and* says which
 * it is, in the same breath.
 *
 * When more than one mic hears a voice at once the screen says so, because a claim that will not
 * land because two people are singing looks exactly like broken hardware otherwise — and on this
 * device that is a believable conclusion.
 *
 * Names are asked every single game rather than remembered against a microphone. In a house with
 * four children the microphone is the one thing that does *not* identify a person: it gets handed
 * to whoever is singing next. Remembering would mean confidently putting last night's name on
 * tonight's singer, which is worse than asking.
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

    // Written by the frame loop and read only inside the meter, so a sixty-times-a-second repaint
    // invalidates one card rather than the name list and the focus somebody is moving through it.
    val tick = remember { mutableFloatStateOf(0f) }
    var contested by remember { mutableStateOf(false) }

    val target = minOf(playerCount, mics.size)

    /** Mics still to be spoken for. */
    val free = mics.indices.filter { i -> claimed.none { it.portId == mics[i].portId } }

    /**
     * The singer being set up: the one choosing a name, or the next one to sing. They are the
     * same person a moment apart, which is why one index and one colour serve the whole screen.
     */
    val slot = naming ?: claimed.size
    val colour = GameTheme.playerColors[slot % GameTheme.playerColors.size]

    /** The mic this singer claimed, once they have one. */
    val claimedMic = naming?.let { s -> mics.indexOfFirst { it.portId == claimed[s].portId } } ?: -1

    // Back undoes the last step rather than leaving the screen, so a microphone that went to the
    // wrong child costs one press instead of starting the evening again. Backing out of the name
    // question hands the microphone back to the pool: leaving the claim in place with a blank
    // name would let the game start with a nameless singer.
    val stepBack: () -> Unit = {
        val naked = naming
        when {
            naked != null -> {
                claimed.removeAt(naked)
                naming = null
            }
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
        // Runs on through the name question, which is what keeps the meter alive while somebody
        // is being named — the whole reason the meter is still on screen at that point.
        while (claimed.size < target || naming != null) {
            withFrameNanos { }
            seconds += 1.0 / 60.0

            for (i in shown.indices) {
                shown[i] = maxOf(levels[i], shown[i] * LEVEL_DECAY)
            }
            tick.floatValue = seconds.toFloat()

            // Nobody can claim while a name is being chosen: the room is not quiet, and the
            // singer being named is often still talking.
            if (naming != null) {
                claim.reset()
                contested = false
                continue
            }

            // Recomputed every frame rather than captured: the pool shrinks as each mic is
            // claimed, and a value read once at composition would keep offering a taken one.
            val open = BooleanArray(mics.size) { index ->
                claimed.none { it.portId == mics[index].portId }
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

    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(GameTheme.background)
            .padding(56.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            val beingNamed = naming
            if (beingNamed != null) {
                NamePicker(
                    colour = colour,
                    profiles = profiles,
                    solo = playerCount == 1,
                    // Whoever has already been named this game. The slot being named is still in
                    // the list with a blank name, so nothing has to be excluded by index.
                    taken = claimed.map { it.name }.filter { it.isNotBlank() }.toSet(),
                    onPicked = { name ->
                        profiles.use(name)
                        claimed[beingNamed] = claimed[beingNamed].copy(name = name)
                        naming = null
                        if (claimed.size >= target) onReady(claimed.toList())
                    },
                )
            } else {
                Text(
                    when {
                        mics.isEmpty() -> "No microphone"
                        playerCount == 1 -> "Sing into your microphone"
                        slot == 0 -> "First singer, sing now"
                        else -> "Second singer, sing now"
                    },
                    style = MaterialTheme.typography.headlineLarge,
                    color = if (mics.isEmpty()) GameTheme.lyricActive else colour,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    when {
                        mics.isEmpty() -> micSession.summary
                        // Said plainly, because the alternative is a meter that fills and never
                        // finishes while both children shout at it and conclude it is broken.
                        contested -> "More than one microphone can hear singing — one voice at a time."
                        // Discovering the mic is what proves the one in your hand is the one that
                        // gets scored, which matters most when there is a spare on the table.
                        playerCount == 1 -> "Whichever microphone hears you is the one you will be scored on."
                        else -> "Pick up a microphone and sing. Whichever one hears you is yours."
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (contested) GameTheme.sparkWarm else GameTheme.lyricIdle,
                )

                Spacer(Modifier.height(48.dp))

                // This screen waits on a voice, so without these there is nothing on it to press
                // and no visible way out — which matters most in the one case that strands you, a
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

        if (mics.isNotEmpty()) {
            Spacer(Modifier.width(48.dp))
            SingerMeter(
                title = when {
                    claimedMic >= 0 -> "Microphone ${claimedMic + 1}"
                    playerCount == 1 -> "Your voice"
                    slot == 0 -> "First singer"
                    else -> "Second singer"
                },
                caption = when {
                    // Why it is still here after the claim: it is the only way to settle which of
                    // two children actually won it. One of them sings, and the meter answers.
                    claimedMic >= 0 -> "Sing to check this is yours"
                    else -> "sing now"
                },
                name = naming?.let { claimed[it].name }?.takeIf { it.isNotBlank() },
                colour = colour,
                threshold = settings.micThreshold,
                tick = tick,
                // Before a claim the meter shows whichever free mic is loudest, because which one
                // the singer picked up is exactly the open question. After it, only their own, so
                // the next singer's voice cannot move it.
                level = {
                    if (claimedMic >= 0) shown.getOrElse(claimedMic) { 0f }
                    else free.maxOfOrNull { shown.getOrElse(it) { 0f } } ?: 0f
                },
                progress = { now -> if (claim.leading >= 0) claim.progress(now) else 0f },
            )
        }
    }
}

/**
 * The singer's own level meter — one card, belonging to a colour rather than to a device.
 *
 * A meter rather than a spinner, because it shows that the microphone is *alive*, which on this
 * hardware has never been something to take for granted. It carries a tick at the gate, so "loud
 * enough" is a target rather than a guess.
 *
 * It reads [tick] itself so the frame loop repaints this card alone. Reading it in the parent
 * would recompose the name list sixty times a second, which is both wasteful and a good way to
 * lose the focus somebody is moving with a remote.
 */
@Composable
private fun SingerMeter(
    title: String,
    caption: String,
    name: String?,
    colour: Color,
    threshold: Float,
    tick: FloatState,
    level: () -> Float,
    progress: (Double) -> Float,
) {
    val now = tick.floatValue.toDouble()
    val loudness = level()
    val held = progress(now)

    Column(
        modifier = Modifier
            .width(320.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(GameTheme.trackBackground)
            .border(3.dp, colour, RoundedCornerShape(16.dp))
            .padding(24.dp),
    ) {
        Text(title, style = MaterialTheme.typography.bodyMedium, color = GameTheme.lyricIdle)
        Spacer(Modifier.height(6.dp))
        Text(name ?: caption, style = MaterialTheme.typography.headlineSmall, color = colour)
        Spacer(Modifier.height(16.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(14.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(GameTheme.noteIdle),
        ) {
            val filled = (loudness / (threshold * 4f)).coerceIn(0f, 1f)
            Box(
                modifier = Modifier
                    .fillMaxWidth(filled)
                    .height(14.dp)
                    .background(colour.copy(alpha = 0.85f)),
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

        if (held > 0f) {
            Spacer(Modifier.height(10.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth(held)
                    .height(5.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(colour),
            )
        }
    }
}

/**
 * Picks the name for the singer who has just claimed a microphone.
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
 * It asks about the microphone in the room rather than about a player number, and the meter
 * beside it is still moving while the question is on screen — which is what makes "this one" a
 * thing anybody can check rather than a thing they have to take on trust.
 *
 * @param solo true when only one person is singing, which changes what there is to say: "the
 *   first singer" is an answer to a question nobody on their own has asked.
 * @param taken names another singer has already claimed this game, compared without case the
 *   same way [Profiles] does, so "mia" cannot slip past an existing "Mia".
 */
@Composable
private fun NamePicker(
    colour: Color,
    profiles: Profiles,
    solo: Boolean,
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
        if (solo) "Who's singing?" else "Who has this microphone?",
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
