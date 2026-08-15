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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.example.ultrastarandroidtv.playback.SyncCalibration
import com.example.ultrastarandroidtv.score.ScoreSnapshot
import com.example.ultrastarandroidtv.song.UltraStarSong

private const val TAG = "Gameplay"

/** How far one press of left/right moves the visible window, while tuning it on the TV. */
private const val WINDOW_STEP_SECONDS = 0.5
private const val MIN_WINDOW_SECONDS = 2.0
private const val MAX_WINDOW_SECONDS = 12.0

/** One track on screen: a voice part, and whoever is singing it. */
private class TrackSpec(
    val geometry: TrackGeometry,
    val singers: List<GameSession.Singer>,
)

/**
 * The game.
 *
 * Layout follows the song. A solo song is one track with a trace per singer, so two people on
 * the same melody can see at a glance who is closer. A duet is a track each, because the two
 * parts are genuinely different and overlaying them would be nonsense. Both cases are the same
 * component in a different arrangement — welding the lyrics to the scrolling notes is what made
 * a track self-contained enough for that to work.
 */
@Composable
fun GameplayScreen(
    song: UltraStarSong,
    audioUri: String,
    onExit: () -> Unit = {},
) {
    val context = LocalContext.current
    val session = remember(song, audioUri) {
        GameSession(context, song, audioUri, SyncCalibration())
    }

    // Read inside the draw pass, so the track repaints each frame without recomposing anything.
    val nowSeconds = remember { mutableDoubleStateOf(0.0) }

    var scores by remember { mutableStateOf<List<ScoreSnapshot>>(emptyList()) }
    var finished by remember { mutableStateOf(false) }
    var windowSeconds by remember { mutableStateOf(DEFAULT_WINDOW_SECONDS) }
    var notice by remember { mutableStateOf<String?>(null) }

    val focus = remember { FocusRequester() }

    // Tracks come from the song's parts rather than from the mics, so a duet still shows both
    // lines when only one mic is plugged in — you can see the part you are not singing.
    val tracks = remember(session, windowSeconds) {
        val partCount = if (session.isDuet) session.song.voiceParts.size else 1
        (0 until partCount).map { partIndex ->
            TrackSpec(
                geometry = TrackGeometry(
                    part = session.song.voiceParts[partIndex],
                    beats = session.beats,
                    windowSeconds = windowSeconds,
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
                        "${it.name}: ${s.beatsHit}/${s.beatsScored} beats, ${s.total} pts"
                    },
                )
            }

            if (notice == null) {
                val waiting = session.singers.filter { it.micStatus != "capturing" }
                notice = when {
                    session.singers.isEmpty() -> session.micSummary
                    waiting.isNotEmpty() -> waiting.joinToString { "${it.name}: ${it.micStatus}" }
                    else -> null
                }
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
                    // Live tuning of how much song is on screen. This is the number most worth
                    // deciding with a real song playing rather than in the abstract.
                    Key.DirectionLeft -> {
                        windowSeconds =
                            (windowSeconds - WINDOW_STEP_SECONDS).coerceAtLeast(MIN_WINDOW_SECONDS)
                        true
                    }
                    Key.DirectionRight -> {
                        windowSeconds =
                            (windowSeconds + WINDOW_STEP_SECONDS).coerceAtMost(MAX_WINDOW_SECONDS)
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
            SongHeading(song, windowSeconds, notice)

            tracks.forEach { track ->
                TrackPanel(
                    track = track,
                    scores = scores,
                    now = { nowSeconds.doubleValue },
                    modifier = Modifier.fillMaxWidth().weight(1f).padding(top = 12.dp),
                )
            }
        }

        if (finished) {
            Results(session, scores, modifier = Modifier.align(Alignment.Center))
        }
    }
}

@Composable
private fun SongHeading(song: UltraStarSong, windowSeconds: Double, notice: String?) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
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
        }
        notice?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = GameTheme.playerColors[1])
            Spacer(Modifier.width(16.dp))
        }
        Text(
            "◀ %.1fs ▶".format(windowSeconds),
            style = MaterialTheme.typography.bodySmall,
            color = GameTheme.lyricIdle,
        )
    }
}

@Composable
private fun TrackPanel(
    track: TrackSpec,
    scores: List<ScoreSnapshot>,
    now: () -> Double,
    modifier: Modifier = Modifier,
) {
    val traces = remember(track) {
        track.singers.map { singer ->
            Trace(
                noteScores = singer.scorer.noteScores,
                color = GameTheme.playerColors[singer.index % GameTheme.playerColors.size],
            )
        }
    }

    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth().height(GameTheme.headerHeight),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            track.singers.forEachIndexed { position, singer ->
                ScoreReadout(
                    name = singer.name,
                    score = scores.getOrNull(singer.index)?.total ?: 0,
                    color = GameTheme.playerColors[singer.index % GameTheme.playerColors.size],
                    // On a shared track the second singer is pushed to the far side, so the two
                    // scores sit at opposite corners and neither looks like the other's caption.
                    alignEnd = position > 0,
                )
            }
        }

        NoteTrack(
            geometry = track.geometry,
            traces = traces,
            now = now,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(12.dp)),
        )
    }
}

@Composable
private fun ScoreReadout(
    name: String,
    score: Int,
    color: androidx.compose.ui.graphics.Color,
    alignEnd: Boolean,
) {
    Column(horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start) {
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
