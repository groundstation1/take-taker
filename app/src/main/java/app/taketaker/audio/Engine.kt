package app.taketaker.audio

import android.annotation.SuppressLint
import android.hardware.usb.UsbManager
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
    private val usbManager: UsbManager,
    val sources: List<Source>,
    val rate: Int,
    private val onErrorUi: (String) -> Unit,
) {
    private fun onError(msg: String) {
        android.util.Log.w("TakeTaker", msg)
        onErrorUi(msg)
    }

    val offsets: IntArray = sources.runningFold(0) { acc, s -> acc + s.channels }.dropLast(1).toIntArray()
    val channelCount = sources.sumOf { it.channels }

    /** Decaying peak per channel, linear 0..1. Written by capture threads, read by UI. */
    val meters = FloatArray(channelCount)
    /** Last time (ms) each channel hit full scale. */
    val clips = LongArray(channelCount)

    @Volatile private var take: Take? = null
    private val captures = sources.mapIndexed { i, s -> Capture(s, offsets[i]) }

    fun start() = captures.forEach { it.start() }

    // ---- monitoring: hear the inputs through the phone's output ----------

    private val rings = captures.map { StereoRing(rate / 2) }
    private var monitor: Monitor? = null
    /** Tracks to monitor (mix as in the mixdown), or null for off. */
    @Volatile var monitorTracks: List<Track>? = null; private set

    fun setMonitor(tracks: List<Track>?) {
        monitorTracks = tracks
        if (tracks != null && monitor == null) monitor = Monitor(rate, rings).also { it.start() }
        if (tracks == null) { monitor?.stop(); monitor = null }
    }

    fun release() {
        setMonitor(null)
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
        private var monFor: List<Track>? = null
        private var monLocal: List<Track> = emptyList()
        private var monOut = FloatArray(0)
        val lock = Any()
        @Volatile var running = true

        override fun run() {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val n = src.channels
            if (src.raw) {
                try { rawRun(n) } catch (e: Exception) { onError("${src.name}: ${e.message}") }
                return
            }
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
                if (f > 0) deliver(fbuf, f, n, chBuf, scratch)
            }
        }

        /** Deinterleave [f] frames, update meters, append to the active take. */
        private fun deliver(fbuf: FloatArray, f: Int, n: Int, chBuf: Array<FloatArray>, scratch: ByteBuffer) {
            for (c in 0 until n) {
                val dst = chBuf[c]
                var peak = 0f
                var i = c
                for (j in 0 until f) { val v = fbuf[i]; dst[j] = v; peak = max(peak, abs(v)); i += n }
                val g = offset + c
                meters[g] = max(peak, meters[g] * 0.82f)
                if (peak >= 0.999f) clips[g] = System.currentTimeMillis()
            }
            monitorFeed(f, n, chBuf)
            val t = take ?: return
            synchronized(lock) {
                if (take === t) for (c in 0 until n) t.append(offset + c, chBuf[c], f, scratch)
            }
        }

        /** Mixes the monitored tracks that live entirely on this input into its ring. */
        private fun monitorFeed(f: Int, n: Int, chBuf: Array<FloatArray>) {
            val mt = monitorTracks ?: return
            if (mt !== monFor) {
                monFor = mt
                monLocal = mt.filter { t -> t.chans.all { it in offset until offset + n } }
                    .map { t -> t.copy(chans = t.chans.map { it - offset }) }
            }
            if (monLocal.isEmpty()) return
            if (monOut.size < f * 2) monOut = FloatArray(f * 2)
            Mix.mix(monLocal, chBuf, f, monOut)
            rings[captures.indexOf(this)].push(monOut, f)
        }

        /** Direct USB: claim the streaming interface and read iso packets ourselves. */
        private fun rawRun(n: Int) {
            val spec = src.usb ?: error("no usb spec")
            val dev = spec.device ?: error("no usb device")
            val conn = usbManager.openDevice(dev) ?: error("no usb permission")
            val intf = (0 until dev.interfaceCount).map { dev.getInterface(it) }
                .firstOrNull { it.id == spec.iface && it.alternateSetting == spec.alt }
                ?: run { conn.close(); error("interface ${spec.iface}/${spec.alt} missing") }
            try {
                if (!conn.claimInterface(intf, true)) error("couldn't claim usb interface")
                if (!conn.setInterface(intf)) error("couldn't select alt setting ${spec.alt}")
                if (spec.rates.size > 1) { // UAC1 SET_CUR sampling frequency on the endpoint
                    val r = rate
                    val data = byteArrayOf(r.toByte(), (r shr 8).toByte(), (r shr 16).toByte())
                    conn.controlTransfer(0x22, 0x01, 0x0100, spec.ep, data, 3, 500)
                }
                val pkts = 8
                val h = UsbIso.open(conn.fileDescriptor, spec.ep, spec.maxPacket, pkts, 8)
                if (h in -4095L..0L) error("usb stream failed (errno ${-h})") // real handles may look negative (tagged pointers)
                try {
                    rawLoop(h, spec, n, pkts)
                } finally {
                    UsbIso.close(h)
                }
            } finally {
                // back to the zero-bandwidth alt setting before letting go
                (0 until dev.interfaceCount).map { dev.getInterface(it) }
                    .firstOrNull { it.id == spec.iface && it.alternateSetting == 0 }
                    ?.let { runCatching { conn.setInterface(it) } }
                conn.releaseInterface(intf)
                UsbIso.reattach(conn.fileDescriptor, spec.iface)
                conn.close()
            }
        }

        private fun rawLoop(h: Long, spec: RawSpec, n: Int, pkts: Int) {
            val bytes = ByteArray(spec.maxPacket * pkts)
            val frameBytes = spec.subframe * spec.channels
            val maxFrames = bytes.size / frameBytes + 1
            val fbuf = FloatArray(maxFrames * spec.channels)
            val chBuf = Array(n) { FloatArray(maxFrames) }
            val scratch = ByteBuffer.allocate(maxFrames * 4).order(ByteOrder.LITTLE_ENDIAN)
            val sub = spec.subframe
            val scale = when (sub) { 2 -> 1f / 32768f; 3 -> 1f / 8388608f; else -> 1f / 2147483648f }
            while (running) {
                val got = UsbIso.read(h, bytes)
                if (got < 0) { onError("${src.name} went away"); break }
                val f = got / frameBytes
                if (f == 0) continue
                val samples = f * spec.channels
                var p = 0
                for (k in 0 until samples) {
                    val v = when (sub) {
                        2 -> (bytes[p].toInt() and 0xFF) or (bytes[p + 1].toInt() shl 8)
                        3 -> (bytes[p].toInt() and 0xFF) or ((bytes[p + 1].toInt() and 0xFF) shl 8) or (bytes[p + 2].toInt() shl 16)
                        else -> (bytes[p].toInt() and 0xFF) or ((bytes[p + 1].toInt() and 0xFF) shl 8) or
                            ((bytes[p + 2].toInt() and 0xFF) shl 16) or (bytes[p + 3].toInt() shl 24)
                    }
                    fbuf[k] = v * scale
                    p += sub
                }
                deliver(fbuf, f, n, chBuf, scratch)
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
            val inputs = src.mic?.presets ?: listOf(
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
