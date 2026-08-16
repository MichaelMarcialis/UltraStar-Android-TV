package com.example.ultrastarandroidtv.mic

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import java.nio.ByteBuffer

private const val TAG = "UsbMicSession"
private const val ACTION_USB_PERMISSION = "com.example.ultrastarandroidtv.USB_PERMISSION"

/** One mic the session is looking after. */
class OpenMic internal constructor(
    /** Position in the list, and the player number the mic belongs to. */
    val index: Int,
    /** Unique per physical port — the only way to tell two identical mics apart. */
    val portId: String,
    /** Product name and port, for showing on screen. */
    val label: String,
) {
    /** Plain-language state: waiting on permission, capturing, or why it failed. */
    @Volatile
    var status: String = "waiting…"
        internal set

    internal var capture: UsbIsoCapture? = null

    /** Isochronous packets the hardware reported as bad, as a running total. */
    val errorPackets: Long get() = capture?.errorPackets ?: 0L
}

/**
 * Finds every attached USB mic, gets permission for each, and keeps them capturing.
 *
 * Getting at these mics means the USB Host API rather than `AudioRecord` — this device's USB
 * audio HAL cannot open them at all. That path needs a permission grant per device, which
 * arrives asynchronously through a broadcast, so mics show up in [mics] straight away but only
 * start producing audio once the user says yes.
 *
 * [onAudio] is called on each mic's own capture thread, with a buffer that is only valid for
 * the duration of the call. [onChanged] fires on whichever thread noticed, whenever a mic's
 * [OpenMic.status] changes, so a UI can redraw.
 */
class UsbMicSession(
    private val context: Context,
    private val onChanged: () -> Unit = {},
) {
    /**
     * Who currently wants the audio, or null for nobody.
     *
     * Settable rather than fixed at construction because the microphones outlive any one screen:
     * they are opened once when the app starts and then handed from the screen where singers
     * claim them to the game that scores them. Reopening USB devices between screens would mean
     * repeating the permission dance and risking the capture threads racing a teardown, for no
     * benefit.
     */
    @Volatile
    var onAudio: ((OpenMic, ByteBuffer, Int) -> Unit)? = null
    private val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private val targets = findAudioCaptureTargets(manager)

    val mics: List<OpenMic> = targets.mapIndexed { index, target ->
        OpenMic(
            index = index,
            portId = target.portId,
            label = "${target.device.productName} @${target.portId.substringAfterLast('/')}",
        )
    }

    /** One line describing what was found, for a status area. */
    val summary: String = when (mics.size) {
        0 -> "No USB mic found. Plug one in and relaunch."
        1 -> "1 mic found."
        else -> "${mics.size} mics found."
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_USB_PERMISSION) return
            val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            val index = targets.indexOfFirst { it.device.deviceName == device?.deviceName }
            if (index < 0) return

            if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                begin(index)
            } else {
                mics[index].status = "permission denied"
                onChanged()
            }
        }
    }

    /** Registers for permission results and opens every mic that is already allowed. */
    fun start() {
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(ACTION_USB_PERMISSION),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        targets.forEachIndexed { index, target ->
            if (manager.hasPermission(target.device)) {
                begin(index)
            } else {
                mics[index].status = "awaiting permission…"
                manager.requestPermission(target.device, permissionIntent(context))
            }
        }
        onChanged()
    }

    fun stop() {
        mics.forEach { it.capture?.stop() }
        runCatching { context.unregisterReceiver(receiver) }
    }

    private fun begin(index: Int) {
        val mic = mics[index]
        if (mic.capture != null) return

        val capture = UsbIsoCapture(manager, targets[index])
        val failure = capture.start { buffer, count -> onAudio?.invoke(mic, buffer, count) }
        if (failure != null) {
            mic.status = "FAILED: $failure"
            Log.e(TAG, "${mic.portId}: $failure")
        } else {
            mic.capture = capture
            mic.status = "capturing"
            Log.i(TAG, "${mic.portId}: capture started")
        }
        onChanged()
    }
}

private fun permissionIntent(context: Context): PendingIntent {
    // Android 12+ requires an explicit mutability flag, and USB permission intents must stay
    // mutable so the system can attach the device extra.
    val flags =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
    return PendingIntent.getBroadcast(
        context,
        0,
        Intent(ACTION_USB_PERMISSION).setPackage(context.packageName),
        flags,
    )
}
