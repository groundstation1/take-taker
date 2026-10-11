// Minimal isochronous IN streaming over usbfs, for reading USB audio class devices directly
// (bypassing the Android audio stack). Java hands us the fd from UsbDeviceConnection.
//
// Model: N URBs of P packets each are kept in flight. read() blocks reaping one URB,
// copies its packets' payload (variable length per packet, async devices) into the
// caller's buffer, resubmits it and returns the byte count.

#include <jni.h>
#include <errno.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <unistd.h>
#include <linux/usbdevice_fs.h>

#define MAX_URBS 32

typedef struct {
    int fd;
    int pktSize;
    int pkts;
    int nUrbs;
    struct usbdevfs_urb *urbs[MAX_URBS];
} Iso;

static void prime(Iso *s, struct usbdevfs_urb *u) {
    u->status = 0;
    u->actual_length = 0;
    u->error_count = 0;
    u->start_frame = 0;
    u->flags = USBDEVFS_URB_ISO_ASAP;
    for (int i = 0; i < s->pkts; i++) {
        u->iso_frame_desc[i].length = (unsigned) s->pktSize;
        u->iso_frame_desc[i].actual_length = 0;
        u->iso_frame_desc[i].status = 0;
    }
}

static void freeIso(Iso *s) {
    for (int i = 0; i < s->nUrbs; i++) {
        if (s->urbs[i]) {
            free(s->urbs[i]->buffer);
            free(s->urbs[i]);
        }
    }
    free(s);
}

JNIEXPORT jlong JNICALL
Java_app_taketaker_audio_UsbIso_open(JNIEnv *env, jclass cls, jint fd, jint ep, jint pktSize, jint pkts, jint nUrbs) {
    if (nUrbs > MAX_URBS) nUrbs = MAX_URBS;
    Iso *s = calloc(1, sizeof(Iso));
    s->fd = fd; s->pktSize = pktSize; s->pkts = pkts; s->nUrbs = nUrbs;
    for (int i = 0; i < nUrbs; i++) {
        struct usbdevfs_urb *u = calloc(1, sizeof(struct usbdevfs_urb) + pkts * sizeof(struct usbdevfs_iso_packet_desc));
        u->type = USBDEVFS_URB_TYPE_ISO;
        u->endpoint = (unsigned char) ep;
        u->buffer = calloc((size_t) pkts, (size_t) pktSize);
        u->buffer_length = pkts * pktSize;
        u->number_of_packets = pkts;
        u->usercontext = (void *) (long) i;
        s->urbs[i] = u;
        prime(s, u);
    }
    for (int i = 0; i < nUrbs; i++) {
        if (ioctl(fd, USBDEVFS_SUBMITURB, s->urbs[i]) < 0) {
            int err = errno;
            for (int j = 0; j < i; j++) ioctl(fd, USBDEVFS_DISCARDURB, s->urbs[j]);
            for (int j = 0; j < i; j++) { struct usbdevfs_urb *r; ioctl(fd, USBDEVFS_REAPURB, &r); }
            freeIso(s);
            return -(jlong) err;
        }
    }
    return (jlong) (long) s;
}

/** Blocks for one URB. Returns bytes copied into out, or -errno. */
JNIEXPORT jint JNICALL
Java_app_taketaker_audio_UsbIso_read(JNIEnv *env, jclass cls, jlong h, jbyteArray out) {
    Iso *s = (Iso *) (long) h;
    struct usbdevfs_urb *u = NULL;
    for (;;) {
        if (ioctl(s->fd, USBDEVFS_REAPURB, &u) == 0) break;
        if (errno == EINTR) continue;
        return -errno;
    }
    jsize cap = (*env)->GetArrayLength(env, out);
    jint n = 0;
    unsigned char *buf = (unsigned char *) u->buffer;
    for (int i = 0; i < s->pkts; i++) {
        struct usbdevfs_iso_packet_desc *d = &u->iso_frame_desc[i];
        int len = (int) d->actual_length;
        if (d->status != 0 || len <= 0) continue;
        if (n + len > cap) len = cap - n;
        if (len <= 0) break;
        (*env)->SetByteArrayRegion(env, out, n, len, (const jbyte *) (buf + i * s->pktSize));
        n += len;
    }
    prime(s, u);
    if (ioctl(s->fd, USBDEVFS_SUBMITURB, u) < 0) return -errno;
    return n;
}

JNIEXPORT void JNICALL
Java_app_taketaker_audio_UsbIso_close(JNIEnv *env, jclass cls, jlong h) {
    Iso *s = (Iso *) (long) h;
    for (int i = 0; i < s->nUrbs; i++) ioctl(s->fd, USBDEVFS_DISCARDURB, s->urbs[i]);
    // reap whatever is still owned by the kernel; discarded URBs complete promptly
    int reaped = 0, waits = 0;
    while (reaped < s->nUrbs && waits < 500) {
        struct usbdevfs_urb *r;
        if (ioctl(s->fd, USBDEVFS_REAPURBNDELAY, &r) == 0) { reaped++; continue; }
        if (errno != EAGAIN) break; // ENODEV etc: device gone, nothing left to reap
        usleep(1000);
        waits++;
    }
    freeIso(s);
}

/** Hands an interface back to its kernel driver (snd-usb-audio) after raw use. */
JNIEXPORT jint JNICALL
Java_app_taketaker_audio_UsbIso_reattach(JNIEnv *env, jclass cls, jint fd, jint iface) {
    struct usbdevfs_ioctl cmd = {.ifno = iface, .ioctl_code = USBDEVFS_CONNECT, .data = NULL};
    return ioctl(fd, USBDEVFS_IOCTL, &cmd) < 0 ? -errno : 0;
}
