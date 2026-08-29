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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.core.content.ContextCompat
import java.nio.ByteBuffer

private const val TAG = "UsbMicSession"
private const val ACTION_USB_PERMISSION = "com.example.ultrastarandroidtv.USB_PERMISSION"

/**
 * Where one microphone has got to.
 *
 * An enum rather than the display string, because screens have to *act* on this — a refused
 * microphone has to be offered a second chance and must not be counted as a singer — and matching
 * on prose is how that quietly stops working the day somebody rewords a message.
 */
enum class MicState {
    /** Found, nothing asked for yet. */
    Waiting,

    /** A permission dialog is on screen for this one. Only ever one at a time; see [UsbMicSession]. */
    Asking,

    /** Permission was refused, or the dialog was dismissed. It can be asked for again. */
    Refused,

    /** Producing audio. */
    Capturing,

    /** Permission was given and opening it still failed. */
    Failed,
}

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

    /**
     * What this mic is doing, for anything that has to make a decision about it.
     *
     * Compose state rather than a plain field, because screens have to follow it: the claim
     * screen offers a refused microphone a second chance, and that offer has to appear and
     * disappear on its own. Only the main thread writes it -- the broadcast receiver and the
     * hot-plug refresh -- so there is no capture thread racing a redraw.
     */
    var state: MicState by mutableStateOf(MicState.Waiting)
        internal set

    /** Plain-language state, for the diagnostic screens. */
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
 * **Permission is asked for one microphone at a time**, and that is the whole reason this class
 * carries a queue rather than a loop. Android keys a grant on the device *path*
 * (`/dev/bus/usb/001/016`), which is handed out afresh on every enumeration — so unplugging the
 * hub, or rebooting, means every microphone needs granting again. Asking for two at once stacks
 * two identical system dialogs on top of one another: somebody answers the one they can see, the
 * second is never mentioned again, and the app is left holding one working microphone and one
 * that looks broken. Reported from the sofa on 2026-08-29 as a mic that "isn't being recognized",
 * and replugging could not fix it, because replugging is what causes it.
 *
 * [onAudio] is called on each mic's own capture thread, with a buffer that is only valid for
 * the duration of the call. [onChanged] fires on whichever thread noticed, whenever a mic's
 * [OpenMic.state] changes, so a UI can redraw.
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
     * The port a permission dialog is currently up for, or null if none is.
     *
     * Held by port rather than by index because the list is renumbered whenever anything is
     * plugged in or pulled out, and an index would then be answering about a different mic.
     */
    private var asking: String? = null

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

    /**
     * Mics that could still become singers — everything except the ones permission was refused for.
     *
     * A refused mic is present, listed, and can never make a sound, so counting it would let the
     * menu offer two-player and then leave the claim screen waiting on a voice that cannot
     * arrive. One still being asked about does count: an answer to it is a single press away.
     */
    val usableMics: List<OpenMic> get() = mics.filter { it.state != MicState.Refused }

    /** Mics whose permission was refused, so a screen can offer to ask again. */
    val refusedMics: List<OpenMic> get() = mics.filter { it.state == MicState.Refused }

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
                    // The dialog has been answered, whatever it said, so the next one may go up.
                    // Cleared before anything below can return early: one dismissed dialog must
                    // not stop every microphone queued behind it from ever being asked about.
                    if (asking == device?.deviceName) asking = null

                    val index = targets.indexOfFirst { it.device.deviceName == device?.deviceName }
                    if (index >= 0) {
                        if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                            begin(index)
                        } else {
                            mark(mics[index], MicState.Refused, "permission refused")
                            Log.i(TAG, "${mics[index].portId}: permission refused")
                        }
                    }
                    askNext()
                    onChanged()
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
     * Asks again for every microphone whose permission was refused.
     *
     * Refusing has to be recoverable, because the dialog can be dismissed by accident — on a
     * remote, Back is one press from anywhere — and the only other way out is knowing to unplug
     * the hub, which is not something anybody should have to know.
     */
    fun askAgain() {
        mics.forEach { if (it.state == MicState.Refused) mark(it, MicState.Waiting, "waiting…") }
        // Not only asking: a grant can arrive while a mic is sitting refused -- Android hands one
        // out for a device the moment anybody allows it -- and a microphone that is already
        // allowed has nothing left to ask about, so asking alone would leave it silent for ever.
        startGranted()
        askNext()
        onChanged()
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
                // A mic pulled out while its dialog is up will never answer it, and a queue left
                // pointing at it would strand every other mic behind a dialog for a device that
                // is no longer there.
                if (asking == mics[i].portId) asking = null
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

        startGranted()
        askNext()
        onChanged()
    }

    /**
     * Opens every mic that is already allowed.
     *
     * A grant lasts as long as the device keeps its path, so this is the ordinary case for a
     * microphone nobody has unplugged, and the reason a relaunch needs no dialogs at all.
     */

    private fun startGranted() {
        targets.forEachIndexed { index, target ->
            if (mics[index].capture == null && manager.hasPermission(target.device)) begin(index)
        }
    }

    /**
     * Puts the permission dialog up for one microphone, if none is up already.
     *
     * The queue is the point — see the note on [UsbMicSession] for what stacking them costs.
     */
    private fun askNext() {
        if (asking != null) return

        val index = targets.indices.firstOrNull { i ->
            mics[i].capture == null &&
                mics[i].state != MicState.Refused &&
                !manager.hasPermission(targets[i].device)
        } ?: return

        asking = targets[index].portId
        mark(mics[index], MicState.Asking, "awaiting permission…")
        Log.i(TAG, "${mics[index].portId}: asking for permission")
        manager.requestPermission(targets[index].device, permissionIntent(context))
    }

    private fun begin(index: Int) {
        val mic = mics[index]
        if (mic.capture != null) return

        val capture = UsbIsoCapture(manager, targets[index])
        val failure = capture.start { buffer, count -> onAudio?.invoke(mic, buffer, count) }
        if (failure != null) {
            mark(mic, MicState.Failed, "FAILED: $failure")
            Log.e(TAG, "${mic.portId}: $failure")
        } else {
            mic.capture = capture
            mark(mic, MicState.Capturing, "capturing")
            Log.i(TAG, "${mic.portId}: capture started")
        }
        onChanged()
    }

    private fun mark(mic: OpenMic, state: MicState, status: String) {
        mic.state = state
        mic.status = status
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
