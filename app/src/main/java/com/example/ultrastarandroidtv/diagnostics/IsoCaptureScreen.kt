package com.example.ultrastarandroidtv.diagnostics

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.example.ultrastarandroidtv.mic.UsbAudioTarget
import com.example.ultrastarandroidtv.mic.UsbIsoCapture
import com.example.ultrastarandroidtv.mic.findAudioCaptureTargets
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min
import kotlin.math.sqrt

private const val TAG = "IsoCapture"
private const val ACTION_USB_PERMISSION = "com.example.ultrastarandroidtv.USB_PERMISSION"

/** Live state for one mic; written from its capture thread, read by Compose. */
private class MicMeter(val label: String) {
    var level by mutableStateOf(0f)
    var kbPerSec by mutableStateOf(0)
    var totalBytes by mutableStateOf(0L)
    var errors by mutableStateOf(0L)
    var note by mutableStateOf("")

    private var windowBytes = 0L
    private var windowStartNanos = System.nanoTime()

    fun onAudio(buffer: ByteBuffer, count: Int) {
        level = rms16(buffer, count)
        totalBytes += count
        windowBytes += count

        val now = System.nanoTime()
        val elapsed = now - windowStartNanos
        if (elapsed >= 500_000_000L) {
            kbPerSec = ((windowBytes * 1_000_000_000L) / elapsed / 1024).toInt()
            windowBytes = 0
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
        val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val captures = mutableListOf<UsbIsoCapture>()
        val byPort = mutableMapOf<String, MicMeter>()

        fun begin(target: UsbAudioTarget) {
            val meter = byPort[target.portId] ?: return
            dumpDescriptors(manager, target.device)
            val capture = UsbIsoCapture(manager, target)
            val failure = capture.start { buffer, count ->
                meter.onAudio(buffer, count)
                meter.errors = capture.errorPackets
            }
            if (failure != null) {
                meter.note = "FAILED: $failure"
                Log.e(TAG, "${target.portId}: $failure")
            } else {
                captures += capture
                meter.note = "capturing"
                Log.i(TAG, "${target.portId}: capture started")
            }
        }

        val targets = findAudioCaptureTargets(manager)
        meters = targets.map { target ->
            MicMeter("${target.device.productName}  @${target.portId.substringAfterLast('/')}")
                .also { byPort[target.portId] = it }
        }
        status = when (targets.size) {
            0 -> "No USB mic found. Plug one in and relaunch."
            1 -> "1 mic found."
            else -> "${targets.size} mics found — dual capture test."
        }
        Log.i(TAG, status)

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != ACTION_USB_PERMISSION) return
                val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                val target = targets.firstOrNull { it.device.deviceName == device?.deviceName }
                    ?: return
                if (granted) {
                    begin(target)
                } else {
                    byPort[target.portId]?.note = "permission denied"
                }
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(ACTION_USB_PERMISSION),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        targets.forEach { target ->
            if (manager.hasPermission(target.device)) {
                begin(target)
            } else {
                byPort[target.portId]?.note = "awaiting permission…"
                manager.requestPermission(target.device, permissionIntent(context))
            }
        }

        onDispose {
            captures.forEach { it.stop() }
            context.unregisterReceiver(receiver)
        }
    }

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
            ) {
                Text("Isochronous Capture", style = MaterialTheme.typography.headlineMedium)
                Text(status, style = MaterialTheme.typography.bodyLarge)

                meters.forEach { meter ->
                    Text("")
                    Text(meter.label, style = MaterialTheme.typography.titleMedium)
                    Text("${meter.note}   ${meter.kbPerSec} KB/s   ${meter.totalBytes} bytes   ${meter.errors} bad packets")
                    LevelBar(meter.level)
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

/**
 * Dumps the full descriptor blob, including the class-specific AudioStreaming descriptors
 * Android's [android.hardware.usb.UsbInterface] doesn't surface — that's where the real
 * channel count, bit depth and sample rates live.
 */
private fun dumpDescriptors(manager: UsbManager, device: UsbDevice) {
    val conn = manager.openDevice(device) ?: return
    try {
        val raw = conn.rawDescriptors ?: return
        Log.i(TAG, "raw descriptors for ${device.deviceName} (${raw.size} bytes):")
        raw.asIterable().chunked(16).forEachIndexed { row, chunk ->
            val hex = chunk.joinToString(" ") { "%02x".format(it) }
            Log.i(TAG, "  %04x  %s".format(row * 16, hex))
        }
    } finally {
        conn.close()
    }
}

private fun permissionIntent(context: Context): PendingIntent {
    // Android 12+ requires an explicit mutability flag, and USB permission intents must stay
    // mutable so the system can attach the device extra.
    val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
    return PendingIntent.getBroadcast(
        context,
        0,
        Intent(ACTION_USB_PERMISSION).setPackage(context.packageName),
        flags,
    )
}
