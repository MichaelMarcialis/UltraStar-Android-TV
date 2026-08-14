package com.example.ultrastarandroidtv.diagnostics

import android.util.Log
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.example.ultrastarandroidtv.mic.OpenMic
import com.example.ultrastarandroidtv.mic.UsbMicSession
import com.example.ultrastarandroidtv.pitch.PitchTracker
import com.example.ultrastarandroidtv.playback.LatencyProbe
import com.example.ultrastarandroidtv.playback.SongPlayer
import com.example.ultrastarandroidtv.playback.SyncCalibration
import com.example.ultrastarandroidtv.score.PlayerScorer
import com.example.ultrastarandroidtv.song.BeatTimeConverter
import com.example.ultrastarandroidtv.song.UltraStarSong
import com.example.ultrastarandroidtv.song.UltraStarSongParser
import java.nio.ByteBuffer
import kotlin.math.roundToInt

private const val TAG = "SyncCalibration"
private const val SONG_TEXT = "calibration.txt"
private const val SONG_AUDIO = "asset:///calibration.wav"

/** Quiet tail after the last tone before the pass is called finished. */
private const val TAIL_SECONDS = 1.0

/**
 * Everything one mic contributes to a pass: what it heard, and what it scored.
 *
 * Compose state written from the capture thread, as elsewhere in these diagnostics.
 */
private class MicCalibration(
    val label: String,
    private val song: UltraStarSong,
    private val beats: BeatTimeConverter,
    private val calibration: SyncCalibration,
    expectedOnsets: List<Double>,
) {
    private val tracker = PitchTracker()
    private val probe = LatencyProbe(expectedOnsets)
    private var scorer = PlayerScorer(song.voiceParts[0], beats)
    private val noteCount = song.voiceParts[0].lines.sumOf { it.notes.size }

    var status by mutableStateOf("waiting…")
    var heard by mutableStateOf("no tones heard yet")
    var lastPass by mutableStateOf("—")

    /**
     * Called on the mic's capture thread. The measurement state it touches is torn down and
     * rebuilt by [finishPass] on the main thread, so both sides take the same lock — without
     * it the probe's map is mutated from two threads at once, and a rebuilt scorer might never
     * become visible here at all.
     */
    fun onAudio(buffer: ByteBuffer, count: Int, playerPosition: () -> Double, songEnd: Double) {
        tracker.process(buffer, count) { reading ->
            val position = playerPosition()
            // Readings still in flight when a pass restarts carry a position from the end of
            // the last one. Feeding those to a forward-only scorer would skip it to the end.
            if (position <= songEnd) {
                synchronized(this) {
                    probe.onReading(position, reading.voiced)
                    scorer.update(calibration.songTimeFor(position), reading)
                    heard = describe()
                }
            }
        }
    }

    private fun describe(): String {
        val median = probe.medianSeconds ?: return "no tones heard yet"
        return "%d/%d tones   latency %d ms   spread %d ms".format(
            probe.count,
            noteCount,
            (median * 1000).roundToInt(),
            ((probe.spreadSeconds ?: 0.0) * 1000).roundToInt(),
        )
    }

    /**
     * Ends a pass: adopts what was measured, then starts over.
     *
     * The score reported is the one just achieved using the calibration that was in force
     * during the pass — so the first pass scores on the default guess and the next scores on
     * the measured value. A correctly calibrated system scores near the maximum, because the
     * "singer" is the song itself, exactly on pitch and exactly in time.
     */
    fun finishPass(passNumber: Int) = synchronized(this) {
        val score = scorer.snapshot()
        val median = probe.medianSeconds
        lastPass = "pass $passNumber: %d / 10000   %d/%d beats hit   scored with %d ms".format(
            score.total,
            score.beatsHit,
            score.beatsScored,
            (calibration.totalLatencySeconds * 1000).roundToInt(),
        )
        Log.i(
            TAG,
            "$label $lastPass — heard ${probe.count} tones, " +
                "median ${median?.times(1000)?.roundToInt()} ms",
        )

        // One or two tones could be a cough. Wait for a real set before believing the number.
        if (median != null && probe.count >= 4) calibration.totalLatencySeconds = median

        probe.reset()
        // The pitch tracker is deliberately left alone: it is only ever touched from the
        // capture thread, and a half-filled window spanning the restart costs one reading.
        scorer = PlayerScorer(song.voiceParts[0], beats)
    }
}

/**
 * Measures how far the singer's captured voice lags the player, and proves the whole chain
 * end to end while doing it.
 *
 * Hold a mic up to the TV speaker. The mic hears the song itself, so every tone lands with the
 * full delay of the system — HDMI out to the TV, the speaker, USB capture, and the analysis
 * window — and the difference from the tone's written position is the number scoring needs.
 * The measurement is adopted at the end of each pass, so the score on the following pass shows
 * what it bought.
 */
@Composable
fun SyncCalibrationScreen() {
    val context = LocalContext.current

    val song = remember {
        UltraStarSongParser.parse(
            context.assets.open(SONG_TEXT).bufferedReader().use { it.readText() },
        )
    }
    val beats = remember { BeatTimeConverter(song.metadata) }
    val calibration = remember { SyncCalibration() }
    val notes = remember { song.voiceParts[0].lines.flatMap { it.notes } }
    val expectedOnsets = remember { notes.map { beats.beatToSeconds(it.startBeat) } }
    val songEnd = remember {
        beats.beatToSeconds(notes.last().let { it.startBeat + it.durationBeats }) + TAIL_SECONDS
    }

    var status by remember { mutableStateOf("Starting…") }
    var mics by remember { mutableStateOf<List<MicCalibration>>(emptyList()) }
    var positionText by remember { mutableStateOf("") }
    var clockText by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf(1) }

    val player = remember { SongPlayer(context) }

    DisposableEffect(Unit) {
        val byPort = mutableMapOf<String, MicCalibration>()
        var session: UsbMicSession? = null

        session = UsbMicSession(
            context = context,
            onAudio = { mic, buffer, count ->
                byPort[mic.portId]?.onAudio(
                    buffer = buffer,
                    count = count,
                    playerPosition = { player.clock.positionAt(System.nanoTime()) },
                    songEnd = songEnd,
                )
            },
            onChanged = {
                session?.mics?.forEach { mic -> byPort[mic.portId]?.status = mic.status }
            },
        )

        mics = session.mics.map { mic ->
            MicCalibration(mic.label, song, beats, calibration, expectedOnsets)
                .also { byPort[mic.portId] = it }
        }
        status = session.summary + "  Hold a mic to the TV speaker."
        session.start()

        player.onError = { status = "Playback failed: $it" }
        player.load(SONG_AUDIO)
        player.play()

        onDispose {
            session.stop()
            player.release()
        }
    }

    LaunchedEffect(Unit) {
        var previous = 0.0
        while (true) {
            withFrameNanos { }
            val position = player.clock.positionAt(System.nanoTime())
            positionText = "%.2f s / %.2f s   %s".format(
                position,
                songEnd,
                if (player.isPlaying) "playing" else "paused",
            )
            clockText = "clock correction %+.1f ms   applied latency %d ms".format(
                player.clock.lastCorrectionSeconds * 1000,
                (calibration.totalLatencySeconds * 1000).roundToInt(),
            )

            if (previous <= songEnd && position > songEnd) {
                mics.forEach { it.finishPass(pass) }
                pass++
                player.seekTo(0.0)
                player.play()
            }
            previous = position
        }
    }

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize().padding(32.dp)) {
                Text("Sync Calibration", style = MaterialTheme.typography.headlineMedium)
                Text(status, style = MaterialTheme.typography.bodyLarge)
                Text(positionText, style = MaterialTheme.typography.bodyMedium)
                Text(clockText, style = MaterialTheme.typography.bodyMedium)

                mics.forEach { mic ->
                    Text("")
                    Text(mic.label, style = MaterialTheme.typography.titleMedium)
                    Text(mic.status, style = MaterialTheme.typography.bodySmall)
                    Text(mic.heard, style = MaterialTheme.typography.headlineSmall)
                    Text(mic.lastPass, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}
