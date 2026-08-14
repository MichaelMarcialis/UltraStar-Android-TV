package com.example.ultrastarandroidtv.diagnostics

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.example.ultrastarandroidtv.mic.UsbMicSession
import com.example.ultrastarandroidtv.pitch.PitchTracker
import com.example.ultrastarandroidtv.pitch.centsOffPitch
import com.example.ultrastarandroidtv.pitch.midiNoteName
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

private const val TAG = "IsoCapture"
private const val ACTION_USB_PERMISSION = "com.example.ultrastarandroidtv.USB_PERMISSION"

/** MIDI range the pitch bar spans: E2 to C6, comfortably wider than anyone will sing. */
private const val BAR_MIN_MIDI = 40f
private const val BAR_MAX_MIDI = 84f

/** Live state for one mic; written from its capture thread, read by Compose. */
private class MicMeter(val label: String) {
    var level by mutableStateOf(0f)
    var kbPerSec by mutableStateOf(0)
    var totalBytes by mutableStateOf(0L)
    var errors by mutableStateOf(0L)
    var note by mutableStateOf("")

    var noteName by mutableStateOf("—")
    var pitchDetail by mutableStateOf("")
    /** Where the current pitch sits in [BAR_MIN_MIDI]..[BAR_MAX_MIDI], or -1 when unvoiced. */
    var pitchPosition by mutableStateOf(-1f)
    var pitchStats by mutableStateOf("")

    private val tracker = PitchTracker()

    private var windowBytes = 0L
    private var windowStartNanos = System.nanoTime()
    private var windowReadings = 0
    private var windowVoiced = 0

    fun onAudio(buffer: ByteBuffer, count: Int) {
        level = rms16(buffer, count)
        totalBytes += count
        windowBytes += count

        tracker.process(buffer, count) { reading ->
            windowReadings++
            if (reading.voiced) {
                windowVoiced++
                val midi = reading.midi.toDouble()
                val nearest = midi.roundToInt()
                val cents = centsOffPitch(midi).roundToInt()
                noteName = midiNoteName(nearest)
                pitchDetail = "%.1f Hz   %+d¢   conf %.2f".format(
                    reading.frequencyHz, cents, reading.probability,
                )
                pitchPosition =
                    ((reading.midi - BAR_MIN_MIDI) / (BAR_MAX_MIDI - BAR_MIN_MIDI))
                        .coerceIn(0f, 1f)
            } else {
                noteName = "—"
                pitchDetail = ""
                pitchPosition = -1f
            }
        }

        val now = System.nanoTime()
        val elapsed = now - windowStartNanos
        if (elapsed >= 500_000_000L) {
            kbPerSec = ((windowBytes * 1_000_000_000L) / elapsed / 1024).toInt()
            val perSec = (windowReadings * 1_000_000_000L / elapsed).toInt()
            val voicedPct = if (windowReadings == 0) 0 else windowVoiced * 100 / windowReadings
            pitchStats = "$perSec readings/s   $voicedPct% voiced"
            windowBytes = 0
            windowReadings = 0
            windowVoiced = 0
            windowStartNanos = now
        }
    }
}

/**
 * Diagnostic screen for USB mic capture — not real app UI.
 *
 * Starts a [UsbIsoCapture] per attached mic, so with both plugged in it doubles as the
 * dual-mic test. Expect ~93 KB/s per mic (48000 Hz x 2 bytes, mono).
 */
@Composable
fun IsoCaptureScreen() {
    val context = LocalContext.current
    var status by remember { mutableStateOf("Looking for mics…") }
    var meters by remember { mutableStateOf<List<MicMeter>>(emptyList()) }

    DisposableEffect(Unit) {
        val byPort = mutableMapOf<String, MicMeter>()
        var session: UsbMicSession? = null

        session = UsbMicSession(
            context = context,
            onAudio = { mic, buffer, count ->
                byPort[mic.portId]?.let { meter ->
                    meter.onAudio(buffer, count)
                    meter.errors = mic.errorPackets
                }
            },
            onChanged = {
                session?.mics?.forEach { mic -> byPort[mic.portId]?.note = mic.status }
            },
        )

        meters = session.mics.map { mic ->
            MicMeter(mic.label).also { byPort[mic.portId] = it }
        }
        status = session.summary
        Log.i(TAG, status)
        session.start()

        onDispose { session.stop() }
    }

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
            ) {
                Text("Capture + Pitch", style = MaterialTheme.typography.headlineMedium)
                Text(status, style = MaterialTheme.typography.bodyLarge)

                meters.forEach { meter ->
                    Text("")
                    Text(meter.label, style = MaterialTheme.typography.titleMedium)
                    Text("${meter.note}   ${meter.kbPerSec} KB/s   ${meter.totalBytes} bytes   ${meter.errors} bad packets")
                    LevelBar(meter.level)
                    Text("")
                    Text(meter.noteName, style = MaterialTheme.typography.headlineLarge)
                    Text(meter.pitchDetail, style = MaterialTheme.typography.bodyMedium)
                    PitchBar(meter.pitchPosition)
                    Text(meter.pitchStats, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun LevelBar(level: Float) {
    val fraction = min(1f, level * 6f)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(28.dp)
            .background(Color.DarkGray),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction)
                .height(28.dp)
                .background(Color.Green),
        )
    }
}

/**
 * Where the sung pitch sits across [BAR_MIN_MIDI]..[BAR_MAX_MIDI], or an empty track when
 * unvoiced. A marker rather than a fill, because the thing worth spotting here is the pitch
 * *jumping* — an octave error shows up as the marker snapping a third of the bar sideways.
 */
@Composable
private fun PitchBar(position: Float) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(20.dp)
            .background(Color.DarkGray),
    ) {
        if (position >= 0f) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(position.coerceIn(0.01f, 1f))
                    .height(20.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Box(
                    modifier = Modifier
                        .width(6.dp)
                        .height(20.dp)
                        .background(Color.Cyan),
                )
            }
        }
    }
}

/** Normalised RMS over 16-bit little-endian samples. */
private fun rms16(buffer: ByteBuffer, count: Int): Float {
    val view = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
    val samples = count / 2
    if (samples == 0) return 0f
    var sum = 0.0
    for (i in 0 until samples) {
        val s = view.getShort(i * 2).toDouble()
        sum += s * s
    }
    return (sqrt(sum / samples) / Short.MAX_VALUE).toFloat()
}
