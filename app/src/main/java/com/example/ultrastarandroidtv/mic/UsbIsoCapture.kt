package com.example.ultrastarandroidtv.mic

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.util.Log
import java.nio.ByteBuffer

private const val TAG = "UsbIsoCapture"

/** UAC1 endpoint control request: SET_CUR on SAMPLING_FREQ_CONTROL. */
private const val UAC_SET_CUR = 0x01
private const val UAC_SAMPLING_FREQ_CONTROL = 0x01
private const val REQ_TYPE_CLASS_ENDPOINT_OUT = 0x22

/**
 * Captures PCM from one USB mic, bypassing Android's audio stack entirely.
 *
 * This exists because the Shield's USB audio HAL cannot profile any USB audio device, so
 * `AudioRecord` never sees these mics at all. Instead we take the device away from the
 * kernel driver and stream isochronous transfers ourselves.
 *
 * Owns the whole chain: open the device, claim the interface, light up the endpoint, then
 * pump URBs on a dedicated thread. One instance per mic — because nothing here touches the
 * audio HAL, two instances run happily at once (the audio policy's `maxOpenCount: 1` on the
 * USB input port doesn't apply). Verified with two mics at 93 KB/s each.
 *
 * Audio arrives as mono 16-bit signed little-endian at [sampleRate].
 */
internal class UsbIsoCapture(
    private val manager: UsbManager,
    private val target: UsbAudioTarget,
    private val sampleRate: Int = 48000,
    private val packetsPerUrb: Int = 8,
    private val urbCount: Int = 8,
) {
    private var connection: UsbDeviceConnection? = null
    private var handle: Long = 0L
    private var thread: Thread? = null

    @Volatile
    private var running = false

    val errorPackets: Long get() = if (handle > 0L) UsbIsoNative.errorPackets(handle) else 0L

    /**
     * Returns null on success, or a human-readable reason for failure naming the step that
     * broke — which is also how we diagnose a device that stops cooperating.
     *
     * [onAudio] is called on the capture thread with a direct buffer valid only for the
     * duration of the call — copy anything you need to keep.
     */
    fun start(onAudio: (ByteBuffer, Int) -> Unit): String? {
        val conn = manager.openDevice(target.device) ?: return "openDevice() returned null"
        connection = conn

        if (!conn.claimInterface(target.streamingInterface, true)) {
            release()
            return "claimInterface(force=true) failed — kernel driver kept the mic"
        }
        if (!conn.setInterface(target.streamingInterface)) {
            release()
            return "setInterface(alt=${target.streamingInterface.alternateSetting}) failed"
        }
        setSampleRate(conn)

        val h = UsbIsoNative.start(
            fd = conn.fileDescriptor,
            endpoint = target.endpoint.address,
            maxPacketSize = target.endpoint.maxPacketSize,
            packetsPerUrb = packetsPerUrb,
            urbCount = urbCount,
        )
        if (h <= 0L) {
            release()
            return "native start failed (errno ${-h})"
        }
        handle = h

        running = true
        thread = Thread({ pump(onAudio) }, "iso-${target.portId.substringAfterLast('/')}")
            .apply { priority = Thread.MAX_PRIORITY; start() }
        return null
    }

    fun stop() {
        running = false
        thread?.join(1000)
        thread = null
        if (handle > 0L) {
            UsbIsoNative.stop(handle)
            handle = 0L
        }
        release()
    }

    private fun pump(onAudio: (ByteBuffer, Int) -> Unit) {
        val buffer = ByteBuffer.allocateDirect(packetsPerUrb * target.endpoint.maxPacketSize)
        while (running) {
            val n = UsbIsoNative.read(handle, buffer, 200)
            if (n < 0) {
                Log.e(TAG, "read failed on ${target.portId}: errno ${-n}")
                break
            }
            if (n > 0) onAudio(buffer, n)
        }
    }

    /** Best-effort: plenty of mics run at a fixed rate and reject this, which is harmless. */
    private fun setSampleRate(conn: UsbDeviceConnection) {
        val payload = byteArrayOf(
            (sampleRate and 0xFF).toByte(),
            ((sampleRate shr 8) and 0xFF).toByte(),
            ((sampleRate shr 16) and 0xFF).toByte(),
        )
        val result = conn.controlTransfer(
            REQ_TYPE_CLASS_ENDPOINT_OUT,
            UAC_SET_CUR,
            UAC_SAMPLING_FREQ_CONTROL shl 8,
            target.endpoint.address,
            payload,
            payload.size,
            1000,
        )
        if (result < 0) Log.w(TAG, "SET_CUR ${sampleRate}Hz rejected on ${target.portId}")
    }

    private fun release() {
        connection?.let {
            runCatching { it.releaseInterface(target.streamingInterface) }
            it.close()
        }
        connection = null
    }
}
