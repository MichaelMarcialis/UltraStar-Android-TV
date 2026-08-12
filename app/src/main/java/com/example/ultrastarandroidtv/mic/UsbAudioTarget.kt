package com.example.ultrastarandroidtv.mic

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager

/** USB Audio Class: bInterfaceSubClass for AudioStreaming. */
internal const val SUBCLASS_AUDIO_STREAMING = 2

/**
 * A USB mic we could record from.
 *
 * [streamingInterface] is the *alternate setting* holding the isochronous IN endpoint — UAC
 * devices park on alt 0 (zero bandwidth) until the host selects alt 1.
 */
internal data class UsbAudioTarget(
    val device: UsbDevice,
    val streamingInterface: UsbInterface,
    val endpoint: UsbEndpoint,
) {
    /**
     * Unique per physical port (e.g. `/dev/bus/usb/001/018`), which is how we tell two
     * identical mics apart — both Let's Sing units share vendor/product id `046d:0a03`.
     */
    val portId: String get() = device.deviceName
}

/** Every attached USB audio device exposing an isochronous IN endpoint, in enumeration order. */
internal fun findAudioCaptureTargets(manager: UsbManager): List<UsbAudioTarget> =
    manager.deviceList.values.mapNotNull { device ->
        var found: UsbAudioTarget? = null
        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            if (intf.interfaceClass != UsbConstants.USB_CLASS_AUDIO) continue
            if (intf.interfaceSubclass != SUBCLASS_AUDIO_STREAMING) continue
            for (e in 0 until intf.endpointCount) {
                val ep = intf.getEndpoint(e)
                if (ep.direction == UsbConstants.USB_DIR_IN &&
                    ep.type == UsbConstants.USB_ENDPOINT_XFER_ISOC
                ) {
                    found = UsbAudioTarget(device, intf, ep)
                    break
                }
            }
            if (found != null) break
        }
        found
    }
