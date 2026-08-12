package com.example.ultrastarandroidtv.mic

import java.nio.ByteBuffer

/**
 * Thin JNI surface over usbfs isochronous transfers (see `src/main/cpp/usb_iso.c`).
 *
 * Callers must have already opened the device, claimed the interface with force=true, and
 * selected the alternate setting carrying the endpoint — this only moves bytes.
 *
 * Note: the native symbol names encode this package, so moving this file means editing
 * `usb_iso.c` to match.
 */
internal object UsbIsoNative {

    init {
        System.loadLibrary("usbiso")
    }

    /** Returns an opaque handle, or a negative errno if URB submission failed. */
    external fun start(
        fd: Int,
        endpoint: Int,
        maxPacketSize: Int,
        packetsPerUrb: Int,
        urbCount: Int,
    ): Long

    /**
     * Waits up to [timeoutMs] for one URB, copies its good packets into [dst], and resubmits.
     * Returns bytes written (0 means "nothing ready yet"), or a negative errno.
     */
    external fun read(handle: Long, dst: ByteBuffer, timeoutMs: Int): Int

    external fun stop(handle: Long)

    external fun errorPackets(handle: Long): Long
}
