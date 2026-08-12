// Isochronous USB audio capture over usbfs.
//
// Why this exists: this Shield's audio HAL cannot profile any USB audio device, so
// AudioRecord can never see the mics (see the mic-spike investigation). Android's Java
// UsbRequest only does bulk/interrupt transfers, and USB audio is isochronous — so the
// last mile has to be raw usbfs ioctls, which is all this file does.
//
// Everything above this layer is already handled from Kotlin: permission, openDevice(),
// claimInterface(force=true) to detach snd-usb-audio, and setInterface() to select the
// alt setting that carries the endpoint. By the time we get here the fd is configured and
// the endpoint is live; we only submit URBs and reap them.

#include <jni.h>
#include <errno.h>
#include <poll.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include <sys/ioctl.h>
#include <linux/usbdevice_fs.h>
#include <android/log.h>

#define LOG_TAG "UsbIsoNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

typedef struct {
    int fd;
    int endpoint;
    int max_packet_size;
    int packets_per_urb;
    int urb_count;
    struct usbdevfs_urb **urbs;
    long error_packets;
    long reaped_urbs;
} iso_ctx;

static size_t urb_alloc_size(int packets) {
    return sizeof(struct usbdevfs_urb) +
           (size_t) packets * sizeof(struct usbdevfs_iso_packet_desc);
}

static void free_ctx(iso_ctx *ctx) {
    if (ctx == NULL) return;
    if (ctx->urbs != NULL) {
        for (int i = 0; i < ctx->urb_count; i++) {
            if (ctx->urbs[i] != NULL) {
                free(ctx->urbs[i]->buffer);
                free(ctx->urbs[i]);
            }
        }
        free(ctx->urbs);
    }
    free(ctx);
}

JNIEXPORT jlong JNICALL
Java_com_example_ultrastarandroidtv_mic_UsbIsoNative_start(
        JNIEnv *env, jobject thiz,
        jint fd, jint endpoint, jint max_packet_size,
        jint packets_per_urb, jint urb_count) {
    (void) env;
    (void) thiz;

    iso_ctx *ctx = calloc(1, sizeof(iso_ctx));
    if (ctx == NULL) return -ENOMEM;

    ctx->fd = fd;
    ctx->endpoint = endpoint;
    ctx->max_packet_size = max_packet_size;
    ctx->packets_per_urb = packets_per_urb;
    ctx->urb_count = urb_count;
    ctx->urbs = calloc((size_t) urb_count, sizeof(struct usbdevfs_urb *));
    if (ctx->urbs == NULL) {
        free_ctx(ctx);
        return -ENOMEM;
    }

    for (int i = 0; i < urb_count; i++) {
        struct usbdevfs_urb *urb = calloc(1, urb_alloc_size(packets_per_urb));
        if (urb == NULL) {
            free_ctx(ctx);
            return -ENOMEM;
        }
        ctx->urbs[i] = urb;

        size_t buf_len = (size_t) packets_per_urb * (size_t) max_packet_size;
        urb->buffer = calloc(1, buf_len);
        if (urb->buffer == NULL) {
            free_ctx(ctx);
            return -ENOMEM;
        }

        urb->type = USBDEVFS_URB_TYPE_ISO;
        urb->endpoint = (unsigned char) endpoint;
        urb->flags = USBDEVFS_URB_ISO_ASAP;
        urb->buffer_length = (int) buf_len;
        urb->number_of_packets = packets_per_urb;
        urb->usercontext = (void *) (intptr_t) i;

        // usbfs derives each packet's offset by summing the *requested* lengths, so with
        // every packet asking for max_packet_size, packet p lands at p * max_packet_size.
        for (int p = 0; p < packets_per_urb; p++) {
            urb->iso_frame_desc[p].length = (unsigned int) max_packet_size;
        }
    }

    for (int i = 0; i < urb_count; i++) {
        if (ioctl(fd, USBDEVFS_SUBMITURB, ctx->urbs[i]) < 0) {
            int err = errno;
            LOGE("SUBMITURB #%d failed: %s (%d)", i, strerror(err), err);
            free_ctx(ctx);
            return -err;
        }
    }

    LOGI("started: ep=0x%02x maxPacket=%d packets/urb=%d urbs=%d",
         endpoint, max_packet_size, packets_per_urb, urb_count);
    return (jlong) (intptr_t) ctx;
}

JNIEXPORT jint JNICALL
Java_com_example_ultrastarandroidtv_mic_UsbIsoNative_read(
        JNIEnv *env, jobject thiz,
        jlong handle, jobject dst, jint timeout_ms) {
    (void) thiz;

    iso_ctx *ctx = (iso_ctx *) (intptr_t) handle;
    if (ctx == NULL) return -EINVAL;

    unsigned char *out = (unsigned char *) (*env)->GetDirectBufferAddress(env, dst);
    jlong capacity = (*env)->GetDirectBufferCapacity(env, dst);
    if (out == NULL || capacity <= 0) return -EINVAL;

    struct pollfd pfd;
    pfd.fd = ctx->fd;
    pfd.events = POLLOUT;  // usbfs signals "a URB completed" as POLLOUT
    pfd.revents = 0;

    int pr = poll(&pfd, 1, timeout_ms);
    if (pr < 0) return -errno;
    if (pr == 0) return 0;  // nothing ready yet; caller loops
    if (pfd.revents & (POLLERR | POLLHUP)) return -ENODEV;

    struct usbdevfs_urb *done = NULL;
    if (ioctl(ctx->fd, USBDEVFS_REAPURBNDELAY, &done) < 0) {
        int err = errno;
        if (err == EAGAIN) return 0;
        return -err;
    }
    if (done == NULL) return 0;

    ctx->reaped_urbs++;

    int written = 0;
    const unsigned char *base = (const unsigned char *) done->buffer;
    for (int p = 0; p < done->number_of_packets; p++) {
        struct usbdevfs_iso_packet_desc *desc = &done->iso_frame_desc[p];
        if (desc->status != 0) {
            ctx->error_packets++;
            continue;
        }
        int len = (int) desc->actual_length;
        if (len <= 0) continue;
        if (written + len > capacity) break;
        memcpy(out + written, base + (size_t) p * (size_t) ctx->max_packet_size, (size_t) len);
        written += len;
    }

    // Straight back into the ring; a gap here is a gap in the audio.
    if (ioctl(ctx->fd, USBDEVFS_SUBMITURB, done) < 0) {
        int err = errno;
        LOGE("resubmit failed: %s (%d)", strerror(err), err);
        return -err;
    }

    return written;
}

JNIEXPORT void JNICALL
Java_com_example_ultrastarandroidtv_mic_UsbIsoNative_stop(
        JNIEnv *env, jobject thiz, jlong handle) {
    (void) env;
    (void) thiz;

    iso_ctx *ctx = (iso_ctx *) (intptr_t) handle;
    if (ctx == NULL) return;

    for (int i = 0; i < ctx->urb_count; i++) {
        ioctl(ctx->fd, USBDEVFS_DISCARDURB, ctx->urbs[i]);
    }
    // Drain whatever the kernel hands back so nothing is in flight when we free the buffers.
    struct usbdevfs_urb *done = NULL;
    while (ioctl(ctx->fd, USBDEVFS_REAPURBNDELAY, &done) == 0) {
        done = NULL;
    }

    LOGI("stopped: reaped=%ld urbs, %ld bad packets", ctx->reaped_urbs, ctx->error_packets);
    free_ctx(ctx);
}

JNIEXPORT jlong JNICALL
Java_com_example_ultrastarandroidtv_mic_UsbIsoNative_errorPackets(
        JNIEnv *env, jobject thiz, jlong handle) {
    (void) env;
    (void) thiz;
    iso_ctx *ctx = (iso_ctx *) (intptr_t) handle;
    return ctx == NULL ? 0 : ctx->error_packets;
}
