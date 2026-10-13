package app.taketaker.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process

/** Single-producer / single-consumer ring of interleaved stereo frames. Drops when full. */
class StereoRing(private val cap: Int) {
    private val buf = FloatArray(cap * 2)
    @Volatile private var w = 0L
    @Volatile private var r = 0L

    fun avail() = (w - r).toInt()

    fun push(src: FloatArray, n: Int) {
        if (w - r + n > cap) return // consumer stalled: drop rather than block capture
        var j = (w % cap).toInt()
        for (i in 0 until n) {
            buf[2 * j] = src[2 * i]; buf[2 * j + 1] = src[2 * i + 1]
            if (++j == cap) j = 0
        }
        w += n
    }

    fun pullAdd(dst: FloatArray, n: Int) {
        var j = (r % cap).toInt()
        for (i in 0 until n) {
            dst[2 * i] += buf[2 * j]; dst[2 * i + 1] += buf[2 * j + 1]
            if (++j == cap) j = 0
        }
        r += n
    }

    fun skip(n: Int) { r += n }
}

/**
 * Plays the live input mix through the default output (headphones / speaker / whatever is
 * routed), as low-latency as the phone allows. Each capture thread feeds its own ring; this
 * sums them. Latency is capped by skipping ahead when a ring backs up.
 */
class Monitor(private val rate: Int, private val rings: List<StereoRing>) {
    @Volatile private var running = true
    private val thread = Thread({ run() }, "monitor")

    fun start() = thread.start()

    fun stop() {
        running = false
        thread.join(300)
    }

    private fun run() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT)
        val at = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
            )
            .setAudioFormat(
                AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build()
            )
            .setBufferSizeInBytes(min)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        val block = 192
        val out = FloatArray(block * 2)
        at.play()
        try {
            while (running) {
                java.util.Arrays.fill(out, 0f)
                var any = false
                for (ring in rings) {
                    val a = ring.avail()
                    if (a > block * 8) ring.skip(a - block * 2) // keep latency bounded
                    val take = minOf(ring.avail(), block)
                    if (take > 0) { ring.pullAdd(out, take); any = true }
                }
                if (!any) { Thread.sleep(2); continue }
                at.write(out, 0, block * 2, AudioTrack.WRITE_BLOCKING)
            }
        } finally {
            runCatching { at.stop() }
            at.release()
        }
    }
}
