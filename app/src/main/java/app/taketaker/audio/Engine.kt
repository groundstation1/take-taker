package app.taketaker.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max

/**
 * Keeps every selected source open (for meters) and, while a take is active, streams
 * each channel to disk. Global channel index = source order, then channel within source.
 */
class Engine(
    private val workDir: File,
    val sources: List<Source>,
    val rate: Int,
    private val onError: (String) -> Unit,
) {
    val offsets: IntArray = sources.runningFold(0) { acc, s -> acc + s.channels }.dropLast(1).toIntArray()
    val channelCount = sources.sumOf { it.channels }

    /** Decaying peak per channel, linear 0..1. Written by capture threads, read by UI. */
    val meters = FloatArray(channelCount)
    /** Last time (ms) each channel hit full scale. */
    val clips = LongArray(channelCount)

    @Volatile private var take: Take? = null
    private val captures = sources.mapIndexed { i, s -> Capture(s, offsets[i]) }

    fun start() = captures.forEach { it.start() }

    fun release() {
        stopTake()?.delete()
        captures.forEach { it.running = false }
        captures.forEach { it.join(500) }
    }

    fun startTake(): Take {
        val dir = File(workDir, "take-${System.currentTimeMillis()}").apply { mkdirs() }
        return Take(dir, channelCount, rate).also { take = it }
    }

    /** Stops writing; returns the finished take (files flushed). */
    fun stopTake(): Take? {
        val t = take ?: return null
        take = null
        captures.forEach { synchronized(it.lock) { } } // wait out in-flight writes
        t.finish()
        return t
    }

    private inner class Capture(val src: Source, val offset: Int) : Thread("capture-${src.name}") {
        val lock = Any()
        @Volatile var running = true

        override fun run() {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val n = src.channels
            val rec = openVerified(n)
            if (rec == null) { onError("couldn't open ${src.name}"); return }
            try {
                loop(rec, n)
            } catch (e: Exception) {
                onError("${src.name}: ${e.message}")
            } finally {
                runCatching { rec.stop() }
                rec.release()
            }
        }

        private fun loop(rec: AudioRecord, n: Int) {
            val enc = rec.audioFormat
            val frames = rate / 50 // 20 ms
            val fbuf = FloatArray(frames * n)
            val sbuf = ShortArray(if (enc == AudioFormat.ENCODING_PCM_16BIT) frames * n else 0)
            val bbuf = ByteArray(if (enc == AudioFormat.ENCODING_PCM_24BIT_PACKED) frames * n * 3 else 0)
            val chBuf = Array(n) { FloatArray(frames) }
            val scratch = ByteBuffer.allocate(frames * 4).order(ByteOrder.LITTLE_ENDIAN)
            val decay = 0.82f
            while (running) {
                val samples = when (enc) {
                    AudioFormat.ENCODING_PCM_FLOAT -> rec.read(fbuf, 0, fbuf.size, AudioRecord.READ_BLOCKING)
                    AudioFormat.ENCODING_PCM_24BIT_PACKED -> {
                        val got = rec.read(bbuf, 0, bbuf.size, AudioRecord.READ_BLOCKING)
                        if (got < 0) got else {
                            val k = got / 3
                            for (i in 0 until k) {
                                val v = (bbuf[3 * i].toInt() and 0xFF) or
                                    ((bbuf[3 * i + 1].toInt() and 0xFF) shl 8) or
                                    (bbuf[3 * i + 2].toInt() shl 16)
                                fbuf[i] = v / 8388608f
                            }
                            k
                        }
                    }
                    else -> {
                        val got = rec.read(sbuf, 0, sbuf.size, AudioRecord.READ_BLOCKING)
                        for (i in 0 until maxOf(got, 0)) fbuf[i] = sbuf[i] / 32768f
                        got
                    }
                }
                if (samples < 0) { onError("${src.name} went away"); break }
                val f = samples / n
                if (f == 0) continue
                for (c in 0 until n) {
                    val dst = chBuf[c]
                    var peak = 0f
                    var i = c
                    for (j in 0 until f) { val v = fbuf[i]; dst[j] = v; peak = max(peak, abs(v)); i += n }
                    val g = offset + c
                    meters[g] = max(peak, meters[g] * decay)
                    if (peak >= 0.999f) clips[g] = System.currentTimeMillis()
                }
                val t = take ?: continue
                synchronized(lock) {
                    if (take === t) for (c in 0 until n) t.append(offset + c, chBuf[c], f, scratch)
                }
            }
        }

        /**
         * Android will happily hand back a 4-ch stream that's really the device opened in stereo
         * with two zero channels, if the requested format doesn't match a device profile.
         * So: prefer the device's native integer formats, start, and check what the device side
         * actually opened. Falls back to the widest stream found.
         */
        private fun openVerified(n: Int): AudioRecord? {
            val encodings = buildList {
                if (src.hiRes) add(AudioFormat.ENCODING_PCM_24BIT_PACKED)
                add(AudioFormat.ENCODING_PCM_16BIT)
                add(AudioFormat.ENCODING_PCM_FLOAT)
            }
            val inputs = listOf(
                MediaRecorder.AudioSource.UNPROCESSED,
                MediaRecorder.AudioSource.VOICE_PERFORMANCE,
                MediaRecorder.AudioSource.MIC,
            )
            var best: AudioRecord? = null
            var bestCh = -1
            for (enc in encodings) for (input in inputs) {
                val r = open(n, enc, input) ?: continue
                try {
                    r.startRecording()
                    if (r.recordingState != AudioRecord.RECORDSTATE_RECORDING) { r.release(); continue }
                    var devCh = -1
                    for (i in 0 until 50) { // config shows up a few ms after start
                        r.activeRecordingConfiguration?.format?.channelCount?.let { devCh = it }
                        if (devCh > 0) break
                        Thread.sleep(10)
                    }
                    if (devCh < 0) devCh = 0 // unknown: last resort
                    if (devCh >= n) { best?.release(); return r }
                    if (devCh > bestCh) { best?.let { it.stop(); it.release() }; best = r; bestCh = devCh } else { r.stop(); r.release() }
                } catch (_: Exception) {
                    r.release()
                }
            }
            if (best != null) onError("${src.name}: device only opened $bestCh of $n channels")
            return best
        }

        @SuppressLint("MissingPermission")
        private fun open(n: Int, enc: Int, input: Int): AudioRecord? = try {
            val fmt = AudioFormat.Builder().setEncoding(enc).setSampleRate(rate).apply {
                // >2 channels from USB only works with index masks (no positional meaning)
                if (n <= 2) setChannelMask(if (n == 1) AudioFormat.CHANNEL_IN_MONO else AudioFormat.CHANNEL_IN_STEREO)
                else setChannelIndexMask((1 shl n) - 1)
            }.build()
            val bps = when (enc) { AudioFormat.ENCODING_PCM_FLOAT -> 4; AudioFormat.ENCODING_PCM_24BIT_PACKED -> 3; else -> 2 }
            val r = AudioRecord.Builder()
                .setAudioSource(input)
                .setAudioFormat(fmt)
                .setBufferSizeInBytes((rate / 4) * n * bps)
                .build()
            if (r.state == AudioRecord.STATE_INITIALIZED) {
                r.preferredDevice = src.device
                r
            } else { r.release(); null }
        } catch (_: Exception) { null }
    }
}
