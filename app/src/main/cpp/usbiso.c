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
#include <stdatomic.h>

#define MAX_URBS 32

typedef struct {
    int fd;
    int pktSize;
    int pkts;
    int nUrbs;
    struct usbdevfs_urb *urbs[MAX_URBS];
    int owned[MAX_URBS];
    atomic_bool cancelled;
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
    if (pktSize <= 0 || pktSize > 3072 || pkts <= 0 || pkts > 32 || nUrbs <= 0) return -EINVAL;
    if (nUrbs > MAX_URBS) nUrbs = MAX_URBS;
    Iso *s = calloc(1, sizeof(Iso));
    if (!s) return -ENOMEM;
    s->fd = fd; s->pktSize = pktSize; s->pkts = pkts; s->nUrbs = nUrbs;
    for (int i = 0; i < nUrbs; i++) {
        struct usbdevfs_urb *u = calloc(1, sizeof(struct usbdevfs_urb) + pkts * sizeof(struct usbdevfs_iso_packet_desc));
        if (!u) { freeIso(s); return -ENOMEM; }
        u->type = USBDEVFS_URB_TYPE_ISO;
        u->endpoint = (unsigned char) ep;
        u->buffer = calloc((size_t) pkts, (size_t) pktSize);
        if (!u->buffer) { free(u); freeIso(s); return -ENOMEM; }
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
        s->owned[i] = 1;
    }
    return (jlong) (long) s;
}

/** Blocks for one URB. Returns bytes copied into out, or -errno. */
JNIEXPORT jint JNICALL
Java_app_taketaker_audio_UsbIso_read(JNIEnv *env, jclass cls, jlong h, jbyteArray out, jint frameBytes) {
    Iso *s = (Iso *) (long) h;
    struct usbdevfs_urb *u = NULL;
    for (;;) {
        if (atomic_load(&s->cancelled)) return -ECANCELED;
        if (ioctl(s->fd, USBDEVFS_REAPURBNDELAY, &u) == 0) break;
        if (errno == EINTR) continue;
        if (errno == EAGAIN) { usleep(1000); continue; }
        return -errno;
    }
    int id = (int) (long) u->usercontext;
    s->owned[id] = 0;
    jsize cap = (*env)->GetArrayLength(env, out);
    jint n = 0;
    unsigned char *buf = (unsigned char *) u->buffer;
    for (int i = 0; i < s->pkts; i++) {
        struct usbdevfs_iso_packet_desc *d = &u->iso_frame_desc[i];
        int len = (int) d->actual_length;
        if (d->status != 0) return d->status < 0 ? d->status : -EIO;
        if (len <= 0) continue;
        if (frameBytes <= 0 || len % frameBytes || n + len > cap) return -EMSGSIZE;
        (*env)->SetByteArrayRegion(env, out, n, len, (const jbyte *) (buf + i * s->pktSize));
        n += len;
    }
    prime(s, u);
    if (ioctl(s->fd, USBDEVFS_SUBMITURB, u) < 0) return -errno;
    s->owned[id] = 1;
    return n;
}

JNIEXPORT void JNICALL
Java_app_taketaker_audio_UsbIso_cancel(JNIEnv *env, jclass cls, jlong h) {
    Iso *s = (Iso *) (long) h;
    atomic_store(&s->cancelled, 1);
}

JNIEXPORT void JNICALL
Java_app_taketaker_audio_UsbIso_close(JNIEnv *env, jclass cls, jlong h) {
    Iso *s = (Iso *) (long) h;
    int remaining = 0;
    for (int i = 0; i < s->nUrbs; i++) if (s->owned[i]) {
        remaining++;
        ioctl(s->fd, USBDEVFS_DISCARDURB, s->urbs[i]);
    }
    // reap whatever is still owned by the kernel; discarded URBs complete promptly
    int reaped = 0, waits = 0;
    while (reaped < remaining && waits < 2000) {
        struct usbdevfs_urb *r;
        if (ioctl(s->fd, USBDEVFS_REAPURBNDELAY, &r) == 0) { reaped++; continue; }
        if (errno != EAGAIN && errno != EINTR) break; // ENODEV: kernel released all URBs
        usleep(1000);
        waits++;
    }
    // Never free a buffer the kernel may still own. A disconnected fd may stop reaping;
    // in that exceptional case leave this handle allocated rather than risk use-after-free.
    if (reaped < remaining && waits == 2000) return;
    freeIso(s);
}

/** Hands an interface back to its kernel driver (snd-usb-audio) after raw use. */
JNIEXPORT jint JNICALL
Java_app_taketaker_audio_UsbIso_reattach(JNIEnv *env, jclass cls, jint fd, jint iface) {
    struct usbdevfs_ioctl cmd = {.ifno = iface, .ioctl_code = USBDEVFS_CONNECT, .data = NULL};
    return ioctl(fd, USBDEVFS_IOCTL, &cmd) < 0 ? -errno : 0;
}
