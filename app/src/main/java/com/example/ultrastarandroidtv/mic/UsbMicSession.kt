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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.core.content.ContextCompat
import java.nio.ByteBuffer

private const val TAG = "UsbMicSession"
private const val ACTION_USB_PERMISSION = "com.example.ultrastarandroidtv.USB_PERMISSION"

/** One mic the session is looking after. */
class OpenMic internal constructor(
    index: Int,
    /** Unique per physical port — the only way to tell two identical mics apart. */
    val portId: String,
    /** Product name and port, for showing on screen. */
    val label: String,
) {
    /**
     * Position in the list.
     *
     * Reassigned rather than fixed because mics come and go: unplugging the first of two has to
     * renumber the one that is left, or a screen indexing a per-mic array by this reads the
     * wrong slot — and on this hardware that would be the other singer's voice.
     */
    var index: Int = index
        internal set

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

    /** The USB side of each entry in [mics], same order, same length. */
    private val targets = mutableListOf<UsbAudioTarget>()

    /**
     * The mics attached right now.
     *
     * A snapshot list rather than a fixed one, so a screen that reads it redraws when somebody
     * plugs a microphone in. That matters because the menu now *refuses* two-player when only one
     * mic is present: an app that says "plug in another microphone" and then ignores it being
     * plugged in is worse than one that never mentioned it.
     */
    val mics: List<OpenMic> = mutableStateListOf()

    private val live get() = mics as SnapshotStateList<OpenMic>

    /** One line describing what is attached, for a status area. */
    val summary: String
        get() = when (mics.size) {
            0 -> "No USB mic found. Plug one in."
            1 -> "1 mic found."
            else -> "${mics.size} mics found."
        }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                // Plugging a mic in or pulling one out. Both arrive on the main thread, which is
                // the only thread allowed to touch the snapshot list.
                UsbManager.ACTION_USB_DEVICE_ATTACHED,
                UsbManager.ACTION_USB_DEVICE_DETACHED,
                -> refresh()

                ACTION_USB_PERMISSION -> {
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
        }
    }

    /** Registers for permission and hot-plug news, and opens every mic already attached. */
    fun start() {
        val filter = IntentFilter(ACTION_USB_PERMISSION).apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        refresh()
    }

    fun stop() {
        mics.forEach { it.capture?.stop() }
        runCatching { context.unregisterReceiver(receiver) }
    }

    /**
     * Brings [mics] into line with what is actually plugged in.
     *
     * Deliberately additive: a mic that is still there is left completely alone, capture thread
     * and all. Rebuilding the list wholesale would tear down and reopen working captures every
     * time anything at all was plugged into the Shield, which on this hardware is the one
     * operation with a history of not coming back.
     */
    private fun refresh() {
        val found = findAudioCaptureTargets(manager)
        var changed = false

        // Gone: stop the capture thread before dropping the mic, so nothing is left reading a
        // device that has been pulled out.
        for (i in mics.indices.reversed()) {
            if (found.none { it.portId == mics[i].portId }) {
                Log.i(TAG, "${mics[i].portId}: unplugged")
                mics[i].capture?.stop()
                live.removeAt(i)
                targets.removeAt(i)
                changed = true
            }
        }

        // New: appended rather than sorted in, because a mic that is unplugged and put back gets
        // a fresh device number, so there is no order to restore it to.
        for (target in found) {
            if (mics.any { it.portId == target.portId }) continue
            targets.add(target)
            live.add(
                OpenMic(
                    index = mics.size,
                    portId = target.portId,
                    label = "${target.device.productName} @${target.portId.substringAfterLast('/')}",
                )
            )
            changed = true
        }

        if (changed) mics.forEachIndexed { index, mic -> mic.index = index }

        targets.forEachIndexed { index, target ->
            if (mics[index].capture != null) return@forEachIndexed
            if (manager.hasPermission(target.device)) {
                begin(index)
            } else if (mics[index].status != "awaiting permission…") {
                mics[index].status = "awaiting permission…"
                manager.requestPermission(target.device, permissionIntent(context))
            }
        }
        onChanged()
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
