package app.taketaker.audio

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.concurrent.atomic.AtomicLong

/**
 * One recording: a raw float32 file plus a peak summary per engine channel.
 * Channels from different devices may end up a few frames apart; readers zero-pad.
 */
class Take(val dir: File, val channels: Int, val rate: Int) {
    val startedAt = System.currentTimeMillis()
    /** Monotonic start, to line up video recorded alongside. */
    val startNanos = System.nanoTime()
    /** Camera footage recorded with this take (video only), once finalized. */
    @Volatile var video: File? = null
    @Volatile var videoPending = false
    /** When the video started, relative to the audio (positive = video started later). */
    @Volatile var videoOffsetUs = 0L
    /** Device orientation (0/90/180/270) when the video started, and the one held longest during it. */
    @Volatile var videoStartDegrees = 0
    @Volatile var videoDominantDegrees = 0
    @Volatile var videoFront = false

    /**
     * Degrees to add to the file's rotation flag so the video shows the way the phone was held
     * most of the take (back camera turns with the device, the front camera against it).
     */
    val videoRotationFix: Int
        get() {
            val d = videoDominantDegrees - videoStartDegrees
            return ((if (videoFront) -d else d) % 360 + 360) % 360
        }
    val files = Array(channels) { File(dir, "ch$it.f32") }
    val peaks = Array(channels) { PeakStore() }
    private val outs = Array(channels) { BufferedOutputStream(FileOutputStream(files[it]), 1 shl 17) }
    private val written = LongArray(channels)
    private val maxFrames = AtomicLong(0)

    /** Length in frames (longest channel). */
    val frames: Long get() = maxFrames.get()
    val seconds: Double get() = frames.toDouble() / rate

    /** Called only from the capture thread that owns channel [ch]. */
    fun append(ch: Int, data: FloatArray, n: Int, scratch: ByteBuffer) {
        scratch.clear()
        scratch.asFloatBuffer().put(data, 0, n)
        outs[ch].write(scratch.array(), 0, n * 4)
        peaks[ch].add(data, n)
        val w = written[ch] + n
        written[ch] = w
        maxFrames.accumulateAndGet(w) { a, b -> maxOf(a, b) }
    }

    fun finish() = outs.forEach { runCatching { it.flush(); it.close() } }

    fun delete() {
        finish()
        dir.deleteRecursively()
    }
}

/** Random access to a finished take's channels. Not thread-safe; make one per consumer. */
class TakeReader(take: Take) : AutoCloseable {
    private val raf = take.files.map { RandomAccessFile(it, "r") }
    private val chans: List<FileChannel> = raf.map { it.channel }
    private var bb = ByteBuffer.allocate(1 shl 16).order(ByteOrder.LITTLE_ENDIAN)

    /** Reads [n] frames of channel [ch] starting at [start]; anything past the end reads as silence. */
    fun read(ch: Int, start: Long, n: Int, dst: FloatArray) {
        if (bb.capacity() < n * 4) bb = ByteBuffer.allocate(n * 4).order(ByteOrder.LITTLE_ENDIAN)
        bb.clear(); bb.limit(n * 4)
        val fc = chans[ch]
        var pos = start * 4
        while (bb.hasRemaining()) {
            val r = fc.read(bb, pos)
            if (r <= 0) break
            pos += r
        }
        val got = bb.position() / 4
        bb.flip()
        bb.asFloatBuffer().get(dst, 0, got)
        if (got < n) java.util.Arrays.fill(dst, got, n, 0f)
    }

    override fun close() = raf.forEach { runCatching { it.close() } }
}
