package com.example.ultrastarandroidtv.game

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.example.ultrastarandroidtv.playback.SyncCalibration
import com.example.ultrastarandroidtv.score.ScoreSnapshot
import com.example.ultrastarandroidtv.settings.GameSettings
import com.example.ultrastarandroidtv.song.UltraStarSong

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
    settings: GameSettings,
    playerCount: Int,
    onExit: () -> Unit = {},
) {
    val context = LocalContext.current
    val session = remember(song, audioUri, playerCount) {
        GameSession(
            context = context,
            song = song,
            audioUri = audioUri,
            calibration = SyncCalibration().apply {
                displayLeadSeconds = settings.displayLeadSeconds
            },
            playerCount = playerCount,
            micThreshold = settings.micThreshold,
        )
    }

    // Read inside the draw pass, so the track repaints each frame without recomposing anything.
    val nowSeconds = remember { mutableDoubleStateOf(0.0) }

    var scores by remember { mutableStateOf<List<ScoreSnapshot>>(emptyList()) }
    var finished by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }

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
        session.start()
        session.play()
        onDispose { session.release() }
    }

    LaunchedEffect(session) {
        focus.requestFocus()
        var lastLogged = 0.0
        while (true) {
            withFrameNanos { }
            nowSeconds.doubleValue = session.drawTimeSeconds()

            // Only republish the scores when they actually move. Writing them every frame would
            // recompose the readouts sixty times a second to show the same number, which is
            // most of what the diagnostic screens spent their CPU on.
            val fresh = session.singers.map { it.snapshot() }
            if (fresh != scores) scores = fresh

            val over = session.isFinished
            if (over != finished) {
                finished = over
                if (over) session.pause()
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
            // which look identical on screen.
            val position = session.playerPositionSeconds()
            if (position - lastLogged > 2.0) {
                lastLogged = position
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
                        if (finished) {
                            session.restart()
                            finished = false
                        } else if (session.player.isPlaying) {
                            session.pause()
                        } else {
                            session.play()
                        }
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
        Column(modifier = Modifier.fillMaxSize().padding(GameTheme.trackPadding)) {
            TopBar(song, session, scores, notice)

            // Reserved for the song video. Empty for now, and deliberately so — it is the
            // reason the track is a strip rather than the whole screen.
            Spacer(Modifier.weight(1f))

            tracks.forEach { track ->
                TrackPanel(
                    track = track,
                    showName = session.isDuet,
                    toleranceSemitones = session.scoring.toleranceSemitones,
                    now = { nowSeconds.doubleValue },
                    modifier = Modifier.fillMaxWidth().height(trackHeight).padding(top = 10.dp),
                )
            }
        }

        if (finished) {
            Results(session, scores, modifier = Modifier.align(Alignment.Center))
        }
    }
}

@Composable
private fun TopBar(
    song: UltraStarSong,
    session: GameSession,
    scores: List<ScoreSnapshot>,
    notice: String?,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                song.metadata.title,
                style = MaterialTheme.typography.titleLarge,
                color = GameTheme.lyricActive,
            )
            Text(
                song.metadata.artist,
                style = MaterialTheme.typography.bodyMedium,
                color = GameTheme.lyricIdle,
            )
            notice?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = GameTheme.playerColors[1])
            }
        }

        session.singers.forEach { singer ->
            Spacer(Modifier.width(40.dp))
            ScoreReadout(
                name = singer.name,
                score = scores.getOrNull(singer.index)?.total ?: 0,
                color = GameTheme.playerColors[singer.index % GameTheme.playerColors.size],
            )
        }
    }
}

@Composable
private fun TrackPanel(
    track: TrackSpec,
    showName: Boolean,
    toleranceSemitones: Float,
    now: () -> Double,
    modifier: Modifier = Modifier,
) {
    val traces = remember(track) {
        track.singers.map { singer ->
            Trace(
                noteScores = singer.scorer.noteScores,
                color = GameTheme.playerColors[singer.index % GameTheme.playerColors.size],
                currentMidi = { singer.currentMidi },
            )
        }
    }

    Box(modifier = modifier) {
        NoteTrack(
            geometry = track.geometry,
            traces = traces,
            now = now,
            toleranceSemitones = toleranceSemitones,
            modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)),
        )

        // Only a duet needs this: the scores at the top say who is who on a shared track, but
        // with a track each there is nothing to say which is which.
        if (showName) {
            track.singers.firstOrNull()?.let { singer ->
                Text(
                    singer.name,
                    style = MaterialTheme.typography.labelMedium,
                    color = GameTheme.playerColors[singer.index % GameTheme.playerColors.size],
                    modifier = Modifier.align(Alignment.TopStart).padding(10.dp),
                )
            }
        }
    }
}

@Composable
private fun ScoreReadout(name: String, score: Int, color: Color) {
    Column(horizontalAlignment = Alignment.End) {
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
    modifier: Modifier = Modifier,
) {
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
                color = GameTheme.playerColors[singer.index % GameTheme.playerColors.size],
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

        Text("OK to sing it again", style = MaterialTheme.typography.bodySmall, color = GameTheme.lyricIdle)
    }
}
