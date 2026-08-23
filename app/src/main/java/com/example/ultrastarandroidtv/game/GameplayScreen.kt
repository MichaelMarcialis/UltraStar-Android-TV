package com.example.ultrastarandroidtv.game

import android.util.Log
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.example.ultrastarandroidtv.mic.UsbMicSession
import com.example.ultrastarandroidtv.playback.SyncCalibration
import com.example.ultrastarandroidtv.score.ScoreSnapshot
import com.example.ultrastarandroidtv.score.missBreakdown
import com.example.ultrastarandroidtv.settings.GameSettings
import com.example.ultrastarandroidtv.song.UltraStarSong
import kotlinx.coroutines.delay

private const val TAG = "Gameplay"

/** One track on screen: a voice part, and whoever is singing it. */
private class TrackSpec(
    val geometry: TrackGeometry,
    val singers: List<GameSession.Singer>,
)

/**
 * The game.
 *
 * Layout follows the song. A solo song is one track with an arrow per singer, so two people on
 * the same melody can see at a glance who is closer. A duet is a track each, because the two
 * parts are genuinely different and overlaying them would be nonsense. Both cases are the same
 * component in a different arrangement — welding the lyrics to the scrolling notes is what made
 * a track self-contained enough for that to work.
 *
 * The track is a shallow strip along the bottom and the scores sit along the top, which leaves
 * the whole middle of the screen free for the song video. That is only affordable because the
 * pitch scale is fixed: nothing has to leave room for the view zooming or panning.
 */
@Composable
fun GameplayScreen(
    song: UltraStarSong,
    audioUri: String,
    videoUri: String?,
    settings: GameSettings,
    micSession: UsbMicSession,
    lineup: List<GameSession.SingerSlot>,
    onExit: () -> Unit = {},
) {
    val context = LocalContext.current
    val session = remember(song, audioUri, lineup) {
        GameSession(
            context = context,
            song = song,
            audioUri = audioUri,
            calibration = SyncCalibration().apply {
                displayLeadSeconds = settings.displayLeadSeconds
            },
            micSession = micSession,
            lineup = lineup,
            micThreshold = settings.micThresholdFor(lineup.size),
        )
    }

    // Read inside the draw pass, so the track repaints each frame without recomposing anything.
    val nowSeconds = remember { mutableDoubleStateOf(0.0) }

    // How present the game is over the video. Written every frame and read only inside a
    // graphics-layer block, so a fade costs a layer update rather than a recomposition.
    val breakFade = remember { mutableFloatStateOf(1f) }

    var scores by remember { mutableStateOf<List<ScoreSnapshot>>(emptyList()) }
    var finished by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }

    // The song announces itself before it starts. `introDone` starts the music and the crossfade;
    // `introGone` takes the card out of the tree once it has finished fading.
    var introDone by remember(session) { mutableStateOf(false) }
    var introGone by remember(session) { mutableStateOf(false) }
    val reveal = animateFloatAsState(
        targetValue = if (introDone) 1f else 0f,
        animationSpec = tween(GameTheme.titleFadeMillis, easing = FastOutSlowInEasing),
        label = "intro",
    )

    // A song can name a video that this device cannot decode. Falling back is automatic; there
    // is nothing to configure per song, and nothing to do when a new song is added.
    var videoFailed by remember(videoUri) { mutableStateOf(false) }
    val showVisualizer = videoUri == null || videoFailed

    val focus = remember { FocusRequester() }

    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    val trackHeight = screenHeight * when {
        session.isDuet -> GameTheme.duetTrackScreenShare
        else -> GameTheme.trackScreenShare
    }

    // Tracks come from the song's parts rather than from the mics, so a duet still shows both
    // lines when only one mic is plugged in — you can see the part you are not singing.
    val tracks = remember(session, settings.windowSeconds) {
        val partCount = if (session.isDuet) session.song.voiceParts.size else 1
        (0 until partCount).map { partIndex ->
            TrackSpec(
                geometry = TrackGeometry(
                    part = session.song.voiceParts[partIndex],
                    beats = session.beats,
                    windowSeconds = settings.windowSeconds,
                ),
                singers = session.singers.filter { it.partIndex == partIndex },
            )
        }
    }

    DisposableEffect(session) {
        session.onError = { notice = it }
        // Once per song, not per frame. A setting that is being ignored and a setting that is
        // too small to see look identical from the sofa, and this is the difference.
        Log.i(
            TAG,
            "settings in effect: lead=%.0fms window=%.2fs micGate=%.3f -> arrowLag=%.0fms".format(
                session.calibration.displayLeadSeconds * 1000,
                settings.windowSeconds,
                settings.micThresholdFor(lineup.size),
                session.arrowLagSeconds * 1000,
            ),
        )
        session.start()
        onDispose { session.release() }
    }

    // Loading is what makes the first second of a song stutter — building the player, enumerating
    // USB, measuring every syllable — and it all happens while this card is on screen doing
    // nothing but being read. The music starts as the card begins to go, so the fade lands over
    // the song's intro rather than over silence.
    LaunchedEffect(session) {
        delay(GameTheme.titleHoldMillis.toLong())
        introDone = true
        session.play()
        delay(GameTheme.titleFadeMillis.toLong())
        introGone = true
    }

    LaunchedEffect(session) {
        focus.requestFocus()
        var lastLogged = 0.0
        while (true) {
            withFrameNanos { }
            nowSeconds.doubleValue = session.drawTimeSeconds()
            breakFade.floatValue = session.vocalBreaks.hudAlpha(nowSeconds.doubleValue)

            // Only republish the scores when they actually move. Writing them every frame would
            // recompose the readouts sixty times a second to show the same number, which is
            // most of what the diagnostic screens spent their CPU on.
            val fresh = session.singers.map { it.snapshot() }
            if (fresh != scores) scores = fresh

            // A latch, not a mirror. Finishing pauses the player, which freezes the clock — and
            // the frozen position then reads as *not* finished, so tracking the condition both
            // ways cleared the flag on the very next frame and left the song paused with no
            // results on screen. That was the missing score screen: it appeared for one frame.
            // A song that has ended does not un-end; only restarting clears this.
            if (!finished && session.isFinished) {
                finished = true
                session.pause()
                // Why the score was what it was: whether the missed beats had a voice in them
                // decides whether the next thing to work on is latency or difficulty, and
                // guessing between those two wastes the work.
                session.singers.forEach { singer ->
                    Log.i(
                        TAG,
                        "${singer.name}: ${missBreakdown(singer.scorer.noteScores, session.scoring, settings.micThresholdFor(lineup.size))
                            .summary()}",
                    )
                }
            }

            if (notice == null) {
                val waiting = session.singers.filter { it.micStatus != "capturing" }
                notice = when {
                    session.singers.isEmpty() -> session.micSummary
                    waiting.isNotEmpty() -> waiting.joinToString { "${it.name}: ${it.micStatus}" }
                    else -> null
                }
            }

            // Bring-up trace. Beats keep being *scored* whether or not anyone sings, so this
            // separates "nobody is singing" from "the readings are not arriving at all" —
            // which look identical on screen. Paced by the wall clock rather than by the song,
            // because the most interesting moment is the one where the song stops advancing.
            val position = session.playerPositionSeconds()
            val nowMs = System.currentTimeMillis().toDouble()
            if (nowMs - lastLogged > 2000.0) {
                lastLogged = nowMs
                Log.i(
                    TAG,
                    "%.1fs  ".format(position) + session.singers.joinToString("  ") {
                        val s = it.snapshot()
                        val pitch =
                            if (it.currentMidi.isNaN()) "silent" else "%.1f".format(it.currentMidi)
                        "${it.name}: ${s.beatsHit}/${s.beatsScored} beats, ${s.total} pts, $pitch"
                    },
                )
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(GameTheme.background)
            .focusRequester(focus)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionCenter, Key.Enter -> {
                        // Once the results are up they own the button; the singers are picking
                        // between "again" and "another one", not un-pausing anything.
                        if (finished) return@onPreviewKeyEvent false
                        // Swallowed while the title card is up, rather than starting the song
                        // early and leaving it playing under a card that is still counting down.
                        if (!introDone) return@onPreviewKeyEvent true
                        if (session.player.isPlaying) session.pause() else session.play()
                        true
                    }
                    Key.Back -> {
                        onExit()
                        true
                    }
                    else -> false
                }
            },
    ) {
        // Behind everything, at full brightness. Contrast for the UI comes from panels behind
        // the UI, not from dimming the picture. Silent: the mp3 is the audio and the clock, and
        // this only has to look like the song.
        if (!showVisualizer) {
            SongVideo(
                videoUri = videoUri,
                videoGapSeconds = song.metadata.videoGapSeconds,
                songPosition = { session.drawTimeSeconds() },
                isPlaying = { session.player.isPlaying },
                onFailed = { videoFailed = true },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            // Stands in whenever there is no picture: no video file, or one that will not play.
            SongVisualizer(session.spectrum, modifier = Modifier.fillMaxSize())
        }

        // The whole game fades as one. Both reasons to hide it — the song has not started, and
        // there is nothing to sing for the next half minute — are the same request: let the
        // video have the screen. Read inside the layer block, so neither costs a recomposition.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(GameTheme.trackPadding)
                .graphicsLayer { alpha = reveal.value * breakFade.floatValue },
        ) {
            TopBar(session, scores, notice)

            // The song video's share of the screen: everything the game does not need.
            Spacer(Modifier.weight(1f))

            tracks.forEach { track ->
                TrackPanel(
                    track = track,
                    showName = session.isDuet,
                    toleranceSemitones = session.scoring.toleranceSemitones,
                    now = { nowSeconds.doubleValue },
                    arrowNow = { nowSeconds.doubleValue - session.arrowLagSeconds },
                    solo = session.playerCount == 1,
                    modifier = Modifier.fillMaxWidth().height(trackHeight).padding(top = 10.dp),
                )
            }
        }

        if (!introGone) {
            TitleCard(
                song = song,
                modifier = Modifier
                    .align(Alignment.Center)
                    .graphicsLayer { alpha = 1f - reveal.value },
            )
        }

        if (finished) {
            Results(
                session = session,
                scores = scores,
                onReplay = {
                    session.restart()
                    finished = false
                },
                onPickAnother = onExit,
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}

/**
 * A singer in each top corner, and nothing else.
 *
 * The two scores used to share a chip in one corner while the song's name held the other, which
 * had it backwards: the title is read once and then sits there for three minutes, while the
 * scores are the only thing on screen that keeps changing. So the title moved to the card at the
 * start and the singers took a corner each — the same left/right split as their colours, their
 * arrows and, in a duet, their tracks.
 */
@Composable
private fun TopBar(
    session: GameSession,
    scores: List<ScoreSnapshot>,
    notice: String?,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        session.singers.getOrNull(0)?.let { singer ->
            Column(modifier = Modifier.panel()) {
                ScoreReadout(
                    name = singer.name,
                    score = scores.getOrNull(singer.index)?.total ?: 0,
                    color = GameTheme.playerColor(singer.index, session.playerCount == 1),
                    alignment = Alignment.Start,
                )
            }
        }

        Spacer(Modifier.weight(1f))

        // Only ever there when something is wrong — a microphone that has not opened, or a
        // playback error. Centred, because it belongs to the room rather than to either singer.
        notice?.let {
            Column(modifier = Modifier.panel()) {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = GameTheme.playerColors[1],
                )
            }
            Spacer(Modifier.weight(1f))
        }

        session.singers.getOrNull(1)?.let { singer ->
            Column(modifier = Modifier.panel()) {
                ScoreReadout(
                    name = singer.name,
                    score = scores.getOrNull(singer.index)?.total ?: 0,
                    color = GameTheme.playerColor(singer.index, session.playerCount == 1),
                    alignment = Alignment.End,
                )
            }
        }
    }
}

/**
 * The song's name and artist, alone in the middle, before the music starts.
 *
 * Everyone is waiting at this moment anyway — the player is loading, the singers are getting
 * hold of their microphones — so it costs nothing and tells the whole room what is about to
 * happen, at a size that can be read from a sofa. It is also the only thing on screen, which is
 * why the title no longer needs a corner for the rest of the song.
 */
@Composable
private fun TitleCard(song: UltraStarSong, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .widthIn(max = 1000.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(GameTheme.trackBackground)
            .padding(horizontal = 64.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            song.metadata.title,
            style = MaterialTheme.typography.displayMedium.copy(
                fontSize = GameTheme.titleSize,
                fontWeight = FontWeight.Bold,
            ),
            color = GameTheme.lyricActive,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(14.dp))
        Text(
            song.metadata.artist,
            style = MaterialTheme.typography.headlineSmall.copy(
                fontSize = GameTheme.titleArtistSize,
            ),
            color = GameTheme.lyricIdle,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * A translucent card behind a piece of UI.
 *
 * This is where contrast against the video comes from now. Dimming the entire picture to
 * protect a few lines of text was the first approach and it was backwards — it spent every
 * pixel of the video on a problem that only exists where the text is.
 */
private fun Modifier.panel(): Modifier = this
    .clip(RoundedCornerShape(14.dp))
    .background(GameTheme.chipBackground)
    .padding(horizontal = 18.dp, vertical = 10.dp)

@Composable
private fun TrackPanel(
    track: TrackSpec,
    showName: Boolean,
    toleranceSemitones: Float,
    now: () -> Double,
    arrowNow: () -> Double,
    solo: Boolean,
    modifier: Modifier = Modifier,
) {
    val traces = remember(track, solo) {
        track.singers.map { singer ->
            Trace(
                noteScores = singer.scorer.noteScores,
                // A lone singer is green, which is where the arrow's accuracy scale starts, so
                // the arrow reads as one colour drifting off green rather than two ideas at once.
                color = GameTheme.playerColor(singer.index, solo),
                currentMidi = { singer.currentMidi },
            )
        }
    }

    Box(modifier = modifier) {
        NoteTrack(
            geometry = track.geometry,
            traces = traces,
            now = now,
            arrowNow = arrowNow,
            toleranceSemitones = toleranceSemitones,
            accuracyColored = solo,
            // Deliberately unclipped here: NoteTrack clips its own panel and leaves the arrows
            // free, so an arrow above or below the song's range stays visible instead of
            // vanishing exactly when it has most to say.
            modifier = Modifier.fillMaxSize(),
        )

        // Only a duet needs this: the scores at the top say who is who on a shared track, but
        // with a track each there is nothing to say which is which.
        if (showName) {
            track.singers.firstOrNull()?.let { singer ->
                Text(
                    singer.name,
                    style = MaterialTheme.typography.labelMedium,
                    color = GameTheme.playerColor(singer.index, solo),
                    modifier = Modifier.align(Alignment.TopStart).padding(10.dp),
                )
            }
        }
    }
}

@Composable
private fun ScoreReadout(
    name: String,
    score: Int,
    color: Color,
    /** Which edge of the screen this singer's corner is on, so name and number line up with it. */
    alignment: Alignment.Horizontal,
) {
    Column(horizontalAlignment = alignment) {
        Text(
            name,
            style = MaterialTheme.typography.labelLarge.copy(fontSize = GameTheme.nameSize),
            color = color,
        )
        Text(
            "%,d".format(score),
            style = MaterialTheme.typography.headlineLarge.copy(
                fontSize = GameTheme.scoreSize,
                fontWeight = FontWeight.Bold,
            ),
            color = GameTheme.lyricActive,
        )
    }
}

@Composable
private fun Results(
    session: GameSession,
    scores: List<ScoreSnapshot>,
    onReplay: () -> Unit,
    onPickAnother: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val replay = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        // After a frame, not before one: the button has to exist before it can take focus.
        withFrameNanos { }
        runCatching { replay.requestFocus() }
    }

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(GameTheme.trackBackground)
            .padding(48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Finished", style = MaterialTheme.typography.headlineMedium, color = GameTheme.lyricActive)
        Spacer(Modifier.height(24.dp))

        session.singers.forEach { singer ->
            val score = scores.getOrNull(singer.index) ?: ScoreSnapshot.EMPTY
            Text(
                "${singer.name}   %,d".format(score.total),
                style = MaterialTheme.typography.headlineSmall,
                color = GameTheme.playerColor(singer.index, session.playerCount == 1),
            )
            Text(
                "%d of %d beats  ·  %.0f%%".format(
                    score.beatsHit,
                    score.beatsScored,
                    score.accuracy * 100,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = GameTheme.lyricIdle,
            )
            Spacer(Modifier.height(16.dp))
        }

        Spacer(Modifier.height(12.dp))

        // Buttons rather than a line of text telling people which remote button does what.
        // The two things anyone wants here are another go at this song or a different one.
        Row {
            Button(onClick = onReplay, modifier = Modifier.focusRequester(replay)) {
                Text("Sing it again", modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
            }
            Spacer(Modifier.width(20.dp))
            Button(onClick = onPickAnother) {
                Text("Pick another song", modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
            }
        }
    }
}
