package com.example.ultrastarandroidtv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.example.ultrastarandroidtv.game.CLAIM_LEVEL_HEADROOM
import com.example.ultrastarandroidtv.game.GameSession
import com.example.ultrastarandroidtv.game.GameTheme
import com.example.ultrastarandroidtv.game.MicClaim
import com.example.ultrastarandroidtv.mic.MicState
import com.example.ultrastarandroidtv.mic.UsbMicSession
import com.example.ultrastarandroidtv.settings.GameSettings
import com.example.ultrastarandroidtv.settings.Profiles
import com.example.ultrastarandroidtv.settings.cleanName
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
 * **Every microphone the app can see is on screen, all of the time.** That is the change this
 * version makes, and the reason is that the room is looking at three microphones and the screen
 * was showing one card. A card per device means the answer to "did it hear *me*?" is read off the
 * one that is moving, rather than inferred from a single meter that could belong to any of them.
 *
 * **One singer is set up at a time, from beginning to end.** Sing, take a microphone, choose a
 * name, and only then does the next colour open. Two earlier versions asked the room for more
 * than that at once and both failed the same way in the same room: with two children, the moment
 * a meter appears they both shout at it, and whatever the app then decides, nobody watching can
 * tell which of them it decided about.
 *
 * **One bar per microphone, and never two at once.** While nobody has claimed anything, each bar
 * says how close *that* microphone is to being claimed — see [MicClaim.claimProgress], where the
 * lower half is getting loud enough and the upper half is holding it. The moment one is claimed
 * the screen spotlights it, dims the rest, and its bar switches to plain live level: the question
 * has changed from "which one" to "is this one yours", and the way to check is to sing into it and
 * watch it move. The stack of a level meter with a second progress bar underneath is gone.
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

    // The gate that this game will actually be scored with, and then the higher one a *claim*
    // has to clear. Claiming is a deliberate act performed once; scoring is continuous. Sharing
    // one number meant a microphone claimed itself as it was picked up — see CLAIM_LEVEL_HEADROOM.
    val micGate = settings.micThresholdFor(playerCount)
    val claimGate = micGate * CLAIM_LEVEL_HEADROOM
    val claim = remember(claimGate) { MicClaim(minLevel = claimGate) }

    // Written by the frame loop and read only inside a bar's draw pass, so a sixty-times-a-second
    // repaint costs the bars and nothing else — not the name list, and not the focus somebody is
    // moving through it.
    val tick = remember { mutableFloatStateOf(0f) }
    var contested by remember { mutableStateOf(false) }

    val target = minOf(playerCount, mics.size)
    val solo = playerCount == 1

    /** Mics Android has not let the app open. */
    val refused = micSession.refusedMics

    /**
     * True when a refused microphone is actually in the way.
     *
     * A microphone that cannot be opened is listed, silent, and can never claim anything, so
     * waiting for a voice from it is waiting for something that cannot arrive. This is the screen
     * that can fix that — the earlier ones count what is *plugged in*, deliberately, so that a
     * refusal never disables the only route to the button that undoes it.
     *
     * Judged against how many singers there are rather than on any refusal at all: a spare
     * microphone on the table that nobody allowed is not a problem worth a warning.
     */
    val blocked = micSession.usableMics.size < playerCount

    /**
     * The singer being set up: the one choosing a name, or the next one to sing. They are the
     * same person a moment apart, which is why one index and one colour serve the whole screen.
     */
    val slot = naming ?: claimed.size

    // Solo is its own colour rather than "player one", because on your own there is nobody to be
    // told apart from and the colour is free to say "there is one of you" instead.
    val colour = GameTheme.playerColor(slot, solo)

    // Only ever used while a microphone is refused; see the button it is attached to.
    val allow = remember { FocusRequester() }
    LaunchedEffect(blocked, naming) {
        if (blocked && naming == null) {
            withFrameNanos { }
            runCatching { allow.requestFocus() }
        }
    }

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

    LaunchedEffect(playerCount, mics.size, blocked) {
        // A mic pulled out mid-claim takes its singer with it, rather than leaving a slot
        // pointing at a device that is no longer there for the game to fail to open later.
        claimed.retainAll { slot -> mics.any { it.portId == slot.portId } }

        // Nothing to listen for while a microphone the game needs is not allowed. Claiming the
        // first singer and then waiting for ever on a second who cannot be heard is the failure
        // this whole screen exists to make impossible.
        if (blocked) return@LaunchedEffect

        var seconds = 0.0
        // Runs on through the name question, which is what keeps the bars alive while somebody
        // is being named — the whole reason the spotlighted card is still on screen at that point.
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
                    solo = solo,
                    // Whoever has already been named this game. The slot being named is still in
                    // the list with a blank name, so nothing has to be excluded by index.
                    taken = claimed.map { it.name }.filter { it.isNotBlank() }.toSet(),
                    onPicked = { name ->
                        profiles.use(name)
                        // Cleaned, because this is the name the score is filed under and the
                        // profile is saved cleaned. Storing what was typed would put " Mia " on
                        // a record that deleting the profile "Mia" could no longer find.
                        claimed[beingNamed] = claimed[beingNamed].copy(name = cleanName(name))
                        naming = null
                        if (claimed.size >= target) onReady(claimed.toList())
                    },
                )
            } else {
                Text(
                    when {
                        mics.isEmpty() -> "No microphone"
                        blocked -> "Microphone not allowed"
                        solo -> "Sing into your microphone"
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
                        // A refused microphone is indistinguishable from a broken one, and on
                        // this device broken is a believable conclusion -- so it is named, and
                        // the press that fixes it is right underneath. Android asks again from
                        // scratch whenever anything is replugged, because a grant is tied to the
                        // port number, which is handed out afresh every time.
                        blocked -> if (refused.size == 1) {
                            "Android is asking permission for one of the microphones. " +
                                "Allow it below, then answer the message that appears."
                        } else {
                            "Android is asking permission for the microphones. " +
                                "Allow them below, then answer each message that appears."
                        }
                        // Said plainly, because the alternative is a meter that fills and never
                        // finishes while both children shout at it and conclude it is broken.
                        contested -> "More than one microphone can hear singing — one voice at a time."
                        // Discovering the mic is what proves the one in your hand is the one that
                        // gets scored, which matters most when there is a spare on the table.
                        solo -> "Whichever microphone hears you is the one you will be scored on."
                        else -> "Pick up a microphone and sing. Whichever one hears you is yours."
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (contested || blocked) GameTheme.sparkWarm else GameTheme.lyricIdle,
                )

                // Says what the bar is for. Holding a note for the better part of a second is
                // deliberate and it is not guessable — without this the bar looks like a meter
                // that keeps falling back rather than a thing to be filled on purpose.
                if (!blocked && mics.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Keep singing until one bar fills.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = GameTheme.lyricIdle,
                    )
                }

                Spacer(Modifier.height(48.dp))

                // This screen waits on a voice, so without these there is nothing on it to press
                // and no visible way out — which matters most in the one case that strands you, a
                // microphone the room is not loud enough to claim.
                Row {
                    if (blocked) {
                        // Takes the focus, because it is the only press on this screen that
                        // changes anything while a microphone is refused -- and this screen
                        // otherwise waits on a voice, so a remote would have nowhere to go.
                        Button(
                            onClick = { micSession.askAgain() },
                            modifier = Modifier.focusRequester(allow),
                        ) {
                            Text(
                                "Allow microphone" + if (refused.size > 1) "s" else "",
                                modifier = Modifier.padding(horizontal = 12.dp),
                            )
                        }
                        Spacer(Modifier.width(16.dp))
                    }
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
            Column(
                modifier = Modifier.width(300.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                mics.forEachIndexed { index, mic ->
                    // Who has this one already, if anybody. A name in a colour is the whole
                    // answer to "is this microphone still going spare".
                    val ownerSlot = claimed.indexOfFirst { it.portId == mic.portId }
                    val owner = claimed.getOrNull(ownerSlot)?.name?.takeIf { it.isNotBlank() }
                    val spotlit = index == claimedMic

                    MicCard(
                        title = "Microphone ${index + 1}",
                        // Four states, and each of them is a different sentence rather than a
                        // different shade of the same one.
                        caption = when {
                            mic.state == MicState.Refused -> "not allowed yet"
                            owner != null -> owner
                            // Why this card is still here after the claim: it is the only way to
                            // settle which of two children actually won it. One of them sings,
                            // and the bar answers.
                            spotlit -> "sing to check this is yours"
                            blocked -> "waiting"
                            else -> "free"
                        },
                        // The claimed name is the loud part of the card; everything else is a
                        // caption under a label.
                        emphasised = owner != null || spotlit,
                        colour = when {
                            mic.state == MicState.Refused -> GameTheme.sparkWarm
                            ownerSlot >= 0 -> GameTheme.playerColor(ownerSlot, solo)
                            spotlit -> colour
                            else -> GameTheme.lyricIdle
                        },
                        // Spotlighting is done by dimming everything else, which is the one way
                        // of pointing at something that needs no arrow and no extra words.
                        dimmed = claimedMic >= 0 && !spotlit,
                        outlined = spotlit,
                        tick = tick,
                        fraction = when {
                            mic.state == MicState.Refused || blocked -> { _ -> 0f }
                            // Spoken for: a full bar, standing still. It is not measuring
                            // anything any more and must not look as though it is.
                            ownerSlot >= 0 && !spotlit -> { _ -> 1f }
                            // The claim landed and the question changed. Raw level now, scaled so
                            // an ordinary singing voice sits around the middle — this is "can you
                            // hear me", not "am I loud enough yet", and it has no target on it.
                            spotlit -> { _ -> shown.getOrElse(index) { 0f } / (claimGate * 3f) }
                            else -> { now -> claim.claimProgress(index, shown.getOrElse(index) { 0f }, now) }
                        },
                    )
                }
            }
        }
    }
}

/**
 * One microphone, as a card: what it is, who has it, and one bar.
 *
 * The bar reads [tick] inside its own draw pass rather than in composition, so sixty repaints a
 * second cost a redraw of a rounded rectangle and nothing else. Reading it out here would
 * recompose every card, the name list and the focus somebody is moving with a remote.
 */
@Composable
private fun MicCard(
    title: String,
    caption: String,
    emphasised: Boolean,
    colour: Color,
    dimmed: Boolean,
    outlined: Boolean,
    tick: FloatState,
    fraction: (Double) -> Float,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (dimmed) 0.35f else 1f)
            .clip(RoundedCornerShape(14.dp))
            .background(GameTheme.trackBackground)
            // Every card is outlined, because the panel colour alone is all but invisible against
            // this background -- measured on the television, the cards read as text floating in
            // the dark rather than as objects. The spotlit one takes the singer's colour and a
            // heavier line, which is what makes "this one" legible across the room.
            .border(
                width = if (outlined) 3.dp else 1.dp,
                color = if (outlined) colour else GameTheme.noteIdle,
                shape = RoundedCornerShape(14.dp),
            )
            // Deliberately compact: four microphones have to fit down the side of a 540 dp
            // screen, and a webcam counts as one. Three is the ordinary case and four is not
            // hypothetical -- `findAudioCaptureTargets` matches any USB audio input.
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MicGlyph(colour)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodySmall,
                    color = GameTheme.lyricIdle,
                )
                Text(
                    caption,
                    fontSize = if (emphasised) 21.sp else 15.sp,
                    fontWeight = if (emphasised) FontWeight.SemiBold else FontWeight.Normal,
                    color = colour,
                    // A long name must shorten rather than wrap: a second line here changes the
                    // height of one card and shoves every card under it down the screen.
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        Canvas(modifier = Modifier.fillMaxWidth().height(12.dp)) {
            // Both reads happen in the draw phase, so only drawing is invalidated.
            val filled = fraction(tick.floatValue.toDouble()).coerceIn(0f, 1f)
            val radius = CornerRadius(size.height / 2f)
            drawRoundRect(GameTheme.noteIdle, size = size, cornerRadius = radius)
            if (filled > 0f) {
                drawRoundRect(
                    color = colour,
                    size = Size(size.width * filled, size.height),
                    cornerRadius = radius,
                )
            }
        }
    }
}

/**
 * A microphone, drawn rather than imported.
 *
 * `androidx.tv:tv-material` ships no icon set, and pulling in the phone Material icon library for
 * one glyph would be a dependency for a shape — the same call [LoadingBar] made.
 */
@Composable
private fun MicGlyph(colour: Color, extent: Dp = 30.dp) {
    Canvas(modifier = Modifier.size(extent)) {
        val w = size.width
        val h = size.height
        val stroke = h * 0.08f

        val capsuleWidth = w * 0.34f
        drawRoundRect(
            color = colour,
            topLeft = Offset((w - capsuleWidth) / 2f, h * 0.04f),
            size = Size(capsuleWidth, h * 0.54f),
            cornerRadius = CornerRadius(capsuleWidth / 2f),
        )
        // The cradle: the bottom half of an ellipse, which is what makes it read as a microphone
        // rather than as a pill.
        drawArc(
            color = colour,
            startAngle = 0f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = Offset(w * 0.16f, h * 0.30f),
            size = Size(w * 0.68f, h * 0.52f),
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
        drawLine(
            color = colour,
            start = Offset(w / 2f, h * 0.82f),
            end = Offset(w / 2f, h * 0.94f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = colour,
            start = Offset(w * 0.30f, h * 0.96f),
            end = Offset(w * 0.70f, h * 0.96f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
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
 * It asks about the microphone in the room rather than about a player number, and that
 * microphone's card is spotlighted and still moving while the question is on screen — which is
 * what makes "this one" a thing anybody can check rather than a thing they take on trust.
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
