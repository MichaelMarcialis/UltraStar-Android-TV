package com.example.ultrastarandroidtv.game

import android.util.Log
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateIntAsState
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
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
import com.example.ultrastarandroidtv.score.combined
import com.example.ultrastarandroidtv.score.missBreakdown
import com.example.ultrastarandroidtv.score.ScoringConfig
import com.example.ultrastarandroidtv.settings.GameSettings
import com.example.ultrastarandroidtv.settings.HighScores
import com.example.ultrastarandroidtv.song.UltraStarSong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "Gameplay"

/** How often the bring-up trace says where the song is. Off the frame loop; see its use. */
private const val TRACE_INTERVAL_MS = 2_000L

/**
 * How long the score has to stop moving before the points earned are shown as one number.
 *
 * Beats are scored several times a second, so this is what turns a stream of twos into one gain
 * per phrase. Long enough to bridge the gap between two syllables, short enough that the number
 * still lands while everybody remembers singing it.
 */
private const val GAIN_SETTLE_MILLIS = 260L

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
    /** The song's `.txt` document id — what its record is filed under. See [HighScores]. */
    songId: String,
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
            // The difficulty setting, and the only thing it touches. It is handed in here rather
            // than read where scoring happens so that the note track can be drawn from the same
            // number — a note is exactly as tall as the window that scores it, and an easier
            // setting has to be *seen* to be easier rather than quietly being so.
            scoring = ScoringConfig(
                toleranceSemitones = settings.difficulty.toleranceSemitones,
            ),
        )
    }

    // Read inside the draw pass, so the track repaints each frame without recomposing anything.
    val nowSeconds = remember { mutableDoubleStateOf(0.0) }

    // How present the game is over the video. Written every frame and read only inside a
    // graphics-layer block, so a fade costs a layer update rather than a recomposition.
    val breakFade = remember { mutableFloatStateOf(1f) }

    var scores by remember { mutableStateOf<List<ScoreSnapshot>>(emptyList()) }
    var finished by remember { mutableStateOf(false) }

    /**
     * The last note has gone but the recording has not. Latched for the same reason [finished]
     * is: skipping pauses the player, and a paused clock must not read as "there is singing to
     * come" and take the offer away again.
     */
    var vocalsDone by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }

    /**
     * A word of encouragement per singer, and a counter that makes each one a fresh event.
     *
     * The counter is what the animation is keyed on: two "Great!"s in a row are the same value,
     * and without something changing the second would not replay.
     */
    val praiseTrackers = remember(session) { session.singers.map { PraiseTracker(it.scorer.noteScores) } }

    // Indexed by **slot**, not by position in the singers list. Those differ the moment a claimed
    // microphone is missing, and a mismatch here would congratulate the wrong person.
    var shouts by remember(session) { mutableStateOf<List<Shout?>>(List(session.playerCount) { null }) }
    var shoutCount by remember(session) { mutableIntStateOf(0) }

    // The song announces itself before it starts. `introDone` starts the music and the crossfade;
    // `introGone` takes the card out of the tree once it has finished fading.
    var introDone by remember(session) { mutableStateOf(false) }
    var introGone by remember(session) { mutableStateOf(false) }
    val reveal = animateFloatAsState(
        targetValue = if (introDone) 1f else 0f,
        animationSpec = tween(GameTheme.titleFadeMillis, easing = FastOutSlowInEasing),
        label = "intro",
    )

    // A song can name a video this device cannot decode, and it can name one that turns out to
    // be a single photograph held for three minutes. Both are worse to look at than the
    // visualiser, and both are found out rather than configured -- there is nothing to set per
    // song and nothing to do when a new song is added.
    var videoUnusable by remember(videoUri) { mutableStateOf(false) }
    val showVisualizer = videoUri == null || videoUnusable

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
            ("settings in effect: lead=%.0fms window=%.2fs micGate=%.3f difficulty=%s " +
                "tolerance=%.2f -> arrowLag=%.0fms").format(
                session.calibration.displayLeadSeconds * 1000,
                settings.windowSeconds,
                settings.micThresholdFor(lineup.size),
                settings.difficulty.name,
                session.scoring.toleranceSemitones,
                session.arrowLagSeconds * 1000,
            ),
        )
        session.start()
        onDispose { session.release() }
    }

    /**
     * End the song and put the results up — reached either by the recording running out or by
     * somebody pressing skip, and it must do the same thing both ways.
     */
    val finish = {
        if (!finished) {
            finished = true
            session.pause()
            // Why the score was what it was: whether the missed beats had a voice in them
            // decides whether the next thing to work on is latency or difficulty, and guessing
            // between those two wastes the work.
            session.singers.forEach { singer ->
                Log.i(
                    TAG,
                    "${singer.name}: ${missBreakdown(singer.scorer.noteScores, session.scoring, settings.micThresholdFor(lineup.size))
                        .summary()}",
                )
            }
        }
    }

    // Loading is what makes the first second of a song stutter — building the player, enumerating
    // USB, measuring every syllable — and it all happens while this card is on screen doing
    // nothing but being read. The music starts as the card begins to go, so the fade lands over
    // the song's intro rather than over silence.
    LaunchedEffect(session) {
        // Measured while the card is up, which is the only free moment in a song: everybody is
        // reading the title anyway. Bounded by the card's own hold, so a file that decodes slowly
        // costs the gain rather than the start of the music — and arriving late is survivable
        // because the gain ramps rather than steps.
        val measuring = launch(Dispatchers.IO) { session.normalizeVolume() }
        delay(GameTheme.titleHoldMillis.toLong())
        // A ceiling on top of the scanner's own budget, because the budget only covers decoding:
        // opening the file goes through SAF, which has no timeout of its own. The job is left to
        // finish in its own time and the gain ramps in whenever it lands.
        withTimeoutOrNull(1_000) { measuring.join() }
        introDone = true
        session.play()
        delay(GameTheme.titleFadeMillis.toLong())
        introGone = true
    }

    LaunchedEffect(session) {
        focus.requestFocus()
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
            if (!finished && session.isFinished) finish()

            // Not the same moment as the one above, and that is the whole point: from here the
            // score cannot change, but the song is still playing and is still worth hearing.
            if (!vocalsDone && session.isVocalFinished) vocalsDone = true

            // Polled here rather than from the scorer's own thread, because the words belong to
            // the drawing and this is the thread that draws. Nothing is published unless a phrase
            // has actually finished, so a quiet song costs one array walk of a few entries.
            praiseTrackers.forEachIndexed { position, tracker ->
                tracker.poll()?.let { word ->
                    shoutCount++
                    val slot = session.singers[position].index
                    shouts = shouts.toMutableList().also { it[slot] = Shout(word, shoutCount) }
                }
            }

            // Checked without building anything in the common case, which is every frame of
            // every song where the microphones are behaving. A `filter` here allocated a list
            // sixty times a second to answer a question whose answer is almost always "no".
            if (notice == null) {
                notice = when {
                    session.singers.isEmpty() -> session.micSummary
                    session.singers.any { it.micStatus != "capturing" } ->
                        session.singers
                            .filter { it.micStatus != "capturing" }
                            .joinToString { "${it.name}: ${it.micStatus}" }
                    else -> null
                }
            }
        }
    }

    // Bring-up trace. Beats keep being *scored* whether or not anyone sings, so this separates
    // "nobody is singing" from "the readings are not arriving at all", which look identical on
    // screen. Paced by the wall clock rather than by the song, because the most interesting
    // moment is the one where the song stops advancing.
    //
    // **Its own coroutine, and the line is built off the main thread.** It used to sit in the
    // frame loop above, which put five `String.format` calls and a log write inside one frame
    // every two seconds. Reported from the sofa as a subtle hitch "at a set interval", which is
    // exactly what a periodically expensive frame looks like: sixty frames cost the same and
    // then one costs more, on a strict two-second beat. Nothing here is drawn, so nothing here
    // belongs on the thread that draws.
    //
    // Reading a scorer from another thread is the same race the drawing already accepts -- one
    // writer per scorer, and a field may be a beat stale -- and `SongClock` is lock-free by
    // design so that any thread may ask it the time.
    LaunchedEffect(session) {
        while (true) {
            delay(TRACE_INTERVAL_MS)
            withContext(Dispatchers.Default) {
                Log.i(
                    TAG,
                    "%.1fs  ".format(session.playerPositionSeconds()) +
                        session.singers.joinToString("  ") {
                            val s = it.snapshot()
                            val pitch = if (it.currentMidi.isNaN()) {
                                "silent"
                            } else {
                                "%.1f".format(it.currentMidi)
                            }
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
                        // Once the singing is over the skip button owns the centre key — it is
                        // the only thing focused, and swallowing centre here would leave it
                        // sitting there unpressable.
                        if (vocalsDone) return@onPreviewKeyEvent false
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
                onFailed = { videoUnusable = true },
                onStillImage = { videoUnusable = true },
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
                    shoutFor = { shouts.getOrNull(it) },
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

        // The outro, with a way out of it. Deliberately not the whole screen: the song is still
        // playing and the video is still worth watching, so this is an offer rather than an
        // interruption.
        if (vocalsDone && !finished) {
            SkipToEnd(
                onSkip = finish,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(GameTheme.trackPadding),
            )
        }

        if (finished) {
            Results(
                session = session,
                songId = songId,
                scores = scores,
                onReplay = {
                    session.restart()
                    praiseTrackers.forEach { it.reset() }
                    shouts = List(session.playerCount) { null }
                    finished = false
                    vocalsDone = false
                },
                onPickAnother = onExit,
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}

/**
 * A singer in each top corner, and nothing else — unless they are singing together.
 *
 * The two scores used to share a chip in one corner while the song's name held the other, which
 * had it backwards: the title is read once and then sits there for three minutes, while the
 * scores are the only thing on screen that keeps changing. So the title moved to the card at the
 * start and the singers took a corner each — the same left/right split as their colours, their
 * arrows and, in a duet, their tracks.
 *
 * **A duet gets one score in the middle instead**, because a duet is the two of them singing one
 * song. Two scoreboards invite exactly the comparison the song is not about: one part being
 * shorter, or lower, or the one carrying the harmony does not make the person singing it worse.
 * Versus keeps its two corners, because there the comparison *is* the point.
 */
@Composable
private fun TopBar(
    session: GameSession,
    scores: List<ScoreSnapshot>,
    notice: String?,
) {
    // A box rather than one row, so the notice can be genuinely centred whichever arrangement the
    // scores are in. Squeezing it into the row alongside them put it hard against a score panel
    // in one mode and shoved the combined score off centre in the other.
    Box(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            if (session.isDuet) {
                Spacer(Modifier.weight(1f))
                Column(modifier = Modifier.panel()) {
                    ScoreReadout(
                        name = session.singers.joinToString("  &  ") { it.name },
                        score = combined(scores).total,
                        // White rather than either singer's colour: the score belongs to both of
                        // them, and painting it one of the two would say it was that one's.
                        color = GameTheme.lyricActive,
                        alignment = Alignment.CenterHorizontally,
                    )
                }
                Spacer(Modifier.weight(1f))
            } else {
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

        // Only ever there when something is wrong — a microphone that has not opened, or a
        // playback error. Centred, because it belongs to the room rather than to either singer.
        notice?.let {
            Column(modifier = Modifier.align(Alignment.TopCenter).panel()) {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = GameTheme.playerColors[1],
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

/** One word of encouragement, with an identity so that saying the same word twice replays it. */
private class Shout(val word: Praise, val id: Int)

@Composable
private fun TrackPanel(
    track: TrackSpec,
    showName: Boolean,
    toleranceSemitones: Float,
    now: () -> Double,
    arrowNow: () -> Double,
    solo: Boolean,
    /** The word this singer has just earned, by slot index, or null. */
    shoutFor: (Int) -> Shout?,
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

        // Two singers on one track get a side each, so their words never land on top of one
        // another — which would read as one unreadable word rather than as two people doing well.
        track.singers.forEachIndexed { position, singer ->
            PraiseShout(
                shout = shoutFor(singer.index),
                color = GameTheme.playerColor(singer.index, solo),
                modifier = Modifier.align(
                    if (track.singers.size < 2) {
                        Alignment.Center
                    } else {
                        BiasAlignment(if (position == 0) -0.45f else 0.45f, -0.1f)
                    },
                ),
            )
        }
    }
}

/**
 * "Nice!" over the track, for about a second.
 *
 * Deliberately short-lived, and over the *track* rather than the middle of the screen: it is a
 * reaction to the phrase just sung, and by the time the next phrase reaches the sing line it is
 * in the way of the one thing the singer has to read.
 *
 * It pops in a little oversized, because arriving at full size reads as a label appearing and
 * arriving from slightly too big reads as somebody in the room reacting.
 *
 * [color] tints only the plainest word. The better ones keep their own, which climbs from blue
 * through green to gold, so the ladder is legible without reading the word at all.
 */
@Composable
private fun PraiseShout(shout: Shout?, color: Color, modifier: Modifier = Modifier) {
    val life = remember { Animatable(1f) }
    LaunchedEffect(shout?.id) {
        if (shout == null) return@LaunchedEffect
        life.snapTo(0f)
        life.animateTo(1f, tween(GameTheme.praiseMillis, easing = LinearEasing))
    }

    if (shout == null || life.value >= 1f) return
    val progress = life.value

    Text(
        shout.word.word,
        style = MaterialTheme.typography.headlineLarge.copy(
            fontSize = GameTheme.praiseSize(shout.word.rank),
            fontWeight = FontWeight.Bold,
        ),
        color = if (shout.word.rank == 0) color else GameTheme.praiseColor(shout.word.rank),
        modifier = modifier.graphicsLayer {
            val pop = 1.25f - 0.25f * (progress / 0.2f).coerceAtMost(1f)
            scaleX = pop
            scaleY = pop
            translationY = -progress * 26.dp.toPx()
            alpha = ((1f - progress) / 0.4f).coerceIn(0f, 1f)
        },
    )
}

@Composable
private fun ScoreReadout(
    name: String,
    score: Int,
    color: Color,
    /** Which edge of the screen this singer's corner is on, so name and number line up with it. */
    alignment: Alignment.Horizontal,
) {
    // Counted up rather than replaced. A number that jumps by 240 between two frames is read as a
    // different number; one that walks there is read as points being earned, which is the whole
    // difference between a scoreboard and a game.
    val shown by animateIntAsState(
        targetValue = score,
        animationSpec = tween(GameTheme.scoreCountMillis, easing = FastOutSlowInEasing),
        label = "score",
    )

    // Gains are gathered up and released together at the end of a phrase, not per beat.
    //
    // Beats are scored several times a second, and a "+2" per beat would be a slot machine. The
    // debounce below turns that into one number per phrase, and it needs nothing to know where
    // the phrases are: the score simply stops moving when the singing stops, which is the same
    // thing. Restarting the effect on every change *is* the debounce.
    var pending by remember { mutableIntStateOf(0) }
    var previous by remember { mutableIntStateOf(score) }
    var gain by remember { mutableIntStateOf(0) }
    var gainId by remember { mutableIntStateOf(0) }

    LaunchedEffect(score) {
        val delta = score - previous
        previous = score
        if (delta > 0) pending += delta
        if (pending > 0) {
            delay(GAIN_SETTLE_MILLIS)
            gain = pending
            pending = 0
            gainId++
        }
    }

    val rise = remember { Animatable(1f) }
    LaunchedEffect(gainId) {
        if (gainId == 0) return@LaunchedEffect
        rise.snapTo(0f)
        rise.animateTo(1f, tween(GameTheme.gainMillis, easing = LinearEasing))
    }

    Column(horizontalAlignment = alignment) {
        Text(
            name,
            style = MaterialTheme.typography.labelLarge.copy(fontSize = GameTheme.nameSize),
            color = color,
        )
        Text(
            "%,d".format(shown),
            style = MaterialTheme.typography.headlineLarge.copy(
                fontSize = GameTheme.scoreSize,
                fontWeight = FontWeight.Bold,
            ),
            color = GameTheme.lyricActive,
        )

        // The lane is there whether or not anything is in it. A gain that made the panel taller
        // for a second would shove the whole top of the screen about once a phrase.
        Box(
            modifier = Modifier.height(GameTheme.gainLaneHeight),
            contentAlignment = Alignment.TopCenter,
        ) {
            if (gain > 0 && rise.value < 1f) {
                val progress = rise.value
                Text(
                    "+%,d".format(gain),
                    style = MaterialTheme.typography.headlineSmall.copy(
                        fontSize = GameTheme.gainSize,
                        fontWeight = FontWeight.Bold,
                    ),
                    color = GameTheme.gainColor,
                    modifier = Modifier.graphicsLayer {
                        translationY =
                            -progress * GameTheme.gainRise * GameTheme.gainLaneHeight.toPx()
                        alpha = ((1f - progress) / 0.35f).coerceIn(0f, 1f)
                    },
                )
            }
        }
    }
}

/**
 * Offered through the run-out of a song, once the last note has gone.
 *
 * The results used to appear the moment the chart ended, which threw everyone off a playing video
 * and into a scoreboard — often mid-guitar-solo, which reads as a crash rather than as an ending.
 * A song's outro is part of the song. But it can also be half a minute of nothing anybody is
 * waiting for, so the choice belongs to the room rather than to the chart.
 *
 * Bottom right, and focused, because by this point it is the only thing on screen anybody can
 * press — and a button nobody can reach with a remote is the same as no button.
 */
@Composable
private fun SkipToEnd(onSkip: () -> Unit, modifier: Modifier = Modifier) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        // After a frame: the button has to exist before it can take focus.
        withFrameNanos { }
        runCatching { focus.requestFocus() }
    }

    Button(onClick = onSkip, modifier = modifier.focusRequester(focus)) {
        Text("Skip to the end", modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp))
    }
}

/** One line of the scoreboard: a name, what it earned, and the colour it belongs to. */
private class ResultLine(val name: String, val score: ScoreSnapshot, val color: Color)

@Composable
private fun Results(
    session: GameSession,
    songId: String,
    scores: List<ScoreSnapshot>,
    onReplay: () -> Unit,
    onPickAnother: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val records = remember { HighScores(context) }

    val lines = if (session.isDuet) {
        // One line, for the same reason there is one score at the top: they sang it together.
        listOf(
            ResultLine(
                name = session.singers.joinToString("  &  ") { it.name },
                score = combined(scores),
                color = GameTheme.lyricActive,
            ),
        )
    } else {
        session.singers.map { singer ->
            ResultLine(
                name = singer.name,
                score = scores.getOrNull(singer.index) ?: ScoreSnapshot.EMPTY,
                color = GameTheme.playerColor(singer.index, session.playerCount == 1),
            )
        }
    }

    // Read before anything is written, and both exactly once — the results are composed with the
    // final scores already in, and re-running this on a recomposition would have the song beating
    // its own brand new record and saying so every frame.
    val previousBest = remember { records.best(songId, session.isDuet) }
    remember {
        lines.maxByOrNull { it.score.total }
            ?.let { records.record(songId, session.isDuet, it.score.total, it.name) }
    }

    val another = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        // After a frame, not before one: the button has to exist before it can take focus.
        withFrameNanos { }
        runCatching { another.requestFocus() }
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

        lines.forEach { line ->
            Text(
                "${line.name}   %,d".format(line.score.total),
                style = MaterialTheme.typography.headlineSmall,
                color = line.color,
            )
            Spacer(Modifier.height(8.dp))

            // Stars instead of a percentage. The percentage was precise and said nothing anybody
            // wanted to hear; five slots with four filled is read from the sofa without anybody
            // working out what 71 % of a song is.
            StarRow(line.score.stars)
            Spacer(Modifier.height(8.dp))

            val beaten = line.score.total > 0 &&
                (previousBest == null || line.score.total > previousBest.points)
            when {
                beaten -> Text(
                    if (previousBest == null) "First time through this one!" else "New best on this song!",
                    style = MaterialTheme.typography.bodyMedium,
                    color = GameTheme.recordColor,
                )
                previousBest != null -> Text(
                    "Best so far: %,d by %s".format(previousBest.points, previousBest.name),
                    style = MaterialTheme.typography.bodyMedium,
                    color = GameTheme.lyricIdle,
                )
            }
            Spacer(Modifier.height(20.dp))
        }

        // Buttons rather than a line of text telling people which remote button does what.
        // The two things anyone wants here are a different song or another go at this one, and
        // **a different song is first and focused** — that is what actually happens next almost
        // every time, and on a remote the focused button is the one that costs nothing to press.
        Row {
            Button(onClick = onPickAnother, modifier = Modifier.focusRequester(another)) {
                Text("Pick another song", modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
            }
            Spacer(Modifier.width(20.dp))
            Button(onClick = onReplay) {
                Text("Sing it again", modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
            }
        }
    }
}
