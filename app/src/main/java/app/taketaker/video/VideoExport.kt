package app.taketaker.video

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import androidx.core.content.res.ResourcesCompat
import app.taketaker.R
import app.taketaker.audio.Mix
import app.taketaker.audio.Take
import app.taketaker.audio.TakeReader
import app.taketaker.audio.Track
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Builds an MP4 for a take: the stereo mixdown as AAC, plus either the camera footage recorded
 * alongside (copied, not re-encoded) or, without footage, a rendered waveform visualization.
 */
class VideoExport(private val ctx: Context) {
    private companion object {
        const val W = 1080
        const val H = 1080
        const val FPS = 30
        const val AUDIO_KBPS = 256
    }

    /** Footage recorded next to the take; [offsetUs] = when video started, relative to the audio. */
    /** [turnDegrees]: extra clockwise turn on top of the file's own rotation flag. */
    data class Footage(val file: File, val offsetUs: Long, val turnDegrees: Int = 0)

    fun render(
        take: Take, tracks: List<Track>, from: Long, to: Long, gain: Float,
        title: String, footage: Footage?, out: File, progress: (Float) -> Unit,
        /** With footage: draw the waveform visualization over it (re-encodes) instead of copying it. */
        overlay: Boolean = false,
    ) {
        val rate = take.rate
        var startF = from
        var extractor: MediaExtractor? = null
        var videoTrack = -1
        if (footage != null) {
            val ex = MediaExtractor().apply { setDataSource(footage.file.path) }
            videoTrack = (0 until ex.trackCount).firstOrNull {
                ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            } ?: -1
            if (videoTrack >= 0) {
                ex.selectTrack(videoTrack)
                // video can only start on a keyframe: begin there, and start the audio at the same moment
                val wantUs = from * 1_000_000L / rate - footage.offsetUs
                ex.seekTo(max(0L, wantUs), MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                val kfUs = max(0L, ex.sampleTime)
                // copying can only start on that keyframe; overlay decodes, so it can start exactly at the in point
                startF = if (overlay) from else min(from, ((kfUs + footage.offsetUs) * rate / 1_000_000L).coerceAtLeast(0))
                extractor = ex
            } else ex.release()
        }

        val tmp = File(ctx.cacheDir, "video-audio.bin")
        val samples = mutableListOf<LongArray>() // offset, size, ptsUs, flags
        val audioFormat = encodeAac(take, tracks, startF, to, gain, tmp, samples) { progress(it * 0.35f) }

        val muxer = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            val ex = extractor
            if (ex != null && overlay) {
                renderOverlay(
                    take, tracks, startF, to, title, ex, ex.getTrackFormat(videoTrack), footage!!.offsetUs, footage.turnDegrees,
                    muxer, audioFormat, tmp, samples,
                ) { progress(0.35f + it * 0.65f) }
            } else if (ex != null) {
                val vf = ex.getTrackFormat(videoTrack)
                val r0 = if (vf.containsKey("rotation-degrees")) vf.getInteger("rotation-degrees") else 0
                muxer.setOrientationHint((r0 + footage!!.turnDegrees) % 360)
                val vt = muxer.addTrack(vf)
                val at = muxer.addTrack(audioFormat)
                muxer.start()
                copyVideo(ex, muxer, vt, footage!!.offsetUs, startF * 1_000_000L / rate, to * 1_000_000L / rate) {
                    progress(0.35f + it * 0.5f)
                }
                writeAudio(muxer, at, tmp, samples)
            } else {
                renderViz(take, tracks, startF, to, title, muxer, audioFormat, tmp, samples) { progress(0.35f + it * 0.65f) }
            }
        } finally {
            runCatching { muxer.stop() }
            muxer.release()
            extractor?.release()
            tmp.delete()
        }
        progress(1f)
    }

    // ---- still preview -----------------------------------------------------

    /** One frame of what [kind] would export, from the middle of the trim. Blocking. */
    fun still(
        take: Take, tracks: List<Track>, from: Long, to: Long, title: String,
        kind: app.taketaker.audio.VideoKind, footage: Footage?,
    ): Bitmap? {
        val at = from + (to - from) / 2
        if (kind == app.taketaker.audio.VideoKind.WAVEFORM || footage == null) {
            val b = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
            Viz(take, tracks, from, to, title).also { it.draw(Canvas(b), at); it.recycle() }
            return b
        }
        val mmr = android.media.MediaMetadataRetriever()
        val raw = try {
            mmr.setDataSource(footage.file.path)
            val tUs = (at * 1_000_000L / take.rate - footage.offsetUs).coerceAtLeast(0)
            val rot = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val f = mmr.getFrameAtTime(tUs, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return null
            // some retrievers hand back the stored (unrotated) frame: turn it upright ourselves then
            val needs = if (rot % 180 != 0 && f.width > f.height) rot else if (rot == 180) 0 else 0
            turned(f, (needs + footage.turnDegrees) % 360)
        } finally { mmr.release() }
        // keep it light: the sheet shows it small
        val s = min(1f, 720f / max(raw.width, raw.height))
        val frame = Bitmap.createScaledBitmap(raw, (raw.width * s).toInt(), (raw.height * s).toInt(), true)
            .copy(Bitmap.Config.ARGB_8888, true)
        if (kind == app.taketaker.audio.VideoKind.OVERLAY) {
            val layer = Bitmap.createBitmap(frame.width, frame.height, Bitmap.Config.ARGB_8888)
            Viz(take, tracks, from, to, title, frame.width, frame.height, overlay = true).also { it.draw(Canvas(layer), at); it.recycle() }
            Canvas(frame).drawBitmap(layer, 0f, 0f, Paint().apply {
                xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SCREEN)
            })
            layer.recycle()
        }
        return frame
    }

    private fun turned(b: Bitmap, deg: Int): Bitmap =
        if (deg == 0) b else Bitmap.createBitmap(b, 0, 0, b.width, b.height, android.graphics.Matrix().apply { postRotate(deg.toFloat()) }, true)

    // ---- audio ------------------------------------------------------------

    private fun encodeAac(
        take: Take, tracks: List<Track>, from: Long, to: Long, gain: Float,
        tmp: File, samples: MutableList<LongArray>, progress: (Float) -> Unit,
    ): MediaFormat {
        val rate = take.rate
        val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, 2).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_KBPS * 1000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1 shl 15)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        var outFormat: MediaFormat? = null
        val info = MediaCodec.BufferInfo()
        val total = (to - from).coerceAtLeast(1)
        val chunk = 2048
        val bufs = Array(take.channels) { FloatArray(chunk) }
        val mix = FloatArray(chunk * 2)
        val used = tracks.flatMap { it.chans }.distinct()
        var p = from
        var inputDone = false
        RandomAccessFile(tmp, "rw").use { raf ->
            raf.setLength(0)
            val copy = ByteArray(1 shl 16)
            TakeReader(take).use { reader ->
                var outputDone = false
                while (!outputDone) {
                    if (!inputDone) {
                        val ii = codec.dequeueInputBuffer(5_000)
                        if (ii >= 0) {
                            val ib = codec.getInputBuffer(ii)!!.order(ByteOrder.LITTLE_ENDIAN)
                            ib.clear()
                            val n = min(min(chunk.toLong(), to - p), (ib.capacity() / 4).toLong()).toInt()
                            val pts = (p - from) * 1_000_000L / rate
                            if (n <= 0) {
                                codec.queueInputBuffer(ii, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                for (c in used) reader.read(c, p, n, bufs[c])
                                Mix.mix(tracks, bufs, n, mix, gain)
                                for (i in 0 until n * 2) ib.putShort((mix[i] * 32767f).roundToInt().coerceIn(-32768, 32767).toShort())
                                codec.queueInputBuffer(ii, 0, n * 4, pts, 0)
                                p += n
                                progress((p - from).toFloat() / total)
                            }
                        }
                    }
                    val oi = codec.dequeueOutputBuffer(info, 5_000)
                    when {
                        oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> outFormat = codec.outputFormat
                        oi >= 0 -> {
                            val ob = codec.getOutputBuffer(oi)!!
                            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                                ob.position(info.offset); ob.limit(info.offset + info.size)
                                val off = raf.filePointer
                                var left = info.size
                                while (left > 0) {
                                    val k = min(left, copy.size); ob.get(copy, 0, k); raf.write(copy, 0, k); left -= k
                                }
                                samples += longArrayOf(off, info.size.toLong(), info.presentationTimeUs, info.flags.toLong())
                            }
                            codec.releaseOutputBuffer(oi, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                        }
                    }
                }
            }
        }
        codec.stop(); codec.release()
        return outFormat ?: error("aac encoder gave no format")
    }

    private fun writeAudio(muxer: MediaMuxer, track: Int, tmp: File, samples: List<LongArray>) {
        val info = MediaCodec.BufferInfo()
        RandomAccessFile(tmp, "r").use { raf ->
            var buf = ByteArray(8192)
            for (s in samples) {
                val size = s[1].toInt()
                if (buf.size < size) buf = ByteArray(size)
                raf.seek(s[0]); raf.readFully(buf, 0, size)
                info.set(0, size, s[2], (s[3].toInt() and MediaCodec.BUFFER_FLAG_END_OF_STREAM.inv()))
                muxer.writeSampleData(track, ByteBuffer.wrap(buf, 0, size), info)
            }
        }
    }

    // ---- camera footage ---------------------------------------------------

    private fun copyVideo(
        ex: MediaExtractor, muxer: MediaMuxer, track: Int,
        offsetUs: Long, audioStartUs: Long, endUs: Long, progress: (Float) -> Unit,
    ) {
        val buf = ByteBuffer.allocate(4 shl 20)
        val info = MediaCodec.BufferInfo()
        val span = (endUs - audioStartUs).coerceAtLeast(1)
        while (true) {
            val size = ex.readSampleData(buf, 0)
            if (size < 0) break
            val t = ex.sampleTime + offsetUs - audioStartUs // on the audio's clock, relative to output start
            if (t > endUs - audioStartUs) break
            if (t >= 0) {
                val flags = if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                info.set(0, size, t, flags)
                muxer.writeSampleData(track, buf, info)
                progress(t.toFloat() / span)
            }
            ex.advance()
        }
    }

    // ---- footage with the visualization on top ----------------------------

    private fun renderOverlay(
        take: Take, tracks: List<Track>, from: Long, to: Long, title: String,
        ex: MediaExtractor, inFormat: MediaFormat, offsetUs: Long, turnDegrees: Int,
        muxer: MediaMuxer, audioFormat: MediaFormat, tmp: File, samples: List<LongArray>,
        progress: (Float) -> Unit,
    ) {
        val rotation = ((if (inFormat.containsKey("rotation-degrees")) inFormat.getInteger("rotation-degrees") else 0) + turnDegrees) % 360
        val sw = inFormat.getInteger(MediaFormat.KEY_WIDTH)
        val sh = inFormat.getInteger(MediaFormat.KEY_HEIGHT)
        // output at the footage's own resolution, turned the way it's meant to be seen; nothing scaled
        val turned = rotation % 180 != 0
        val ow = (if (turned) sh else sw) and 1.inv()
        val oh = (if (turned) sw else sh) and 1.inv()
        // the decoder would apply the rotation flag to its output on its own; we turn it in the shader,
        // so hide the flag from it or the frame gets turned twice
        inFormat.setInteger("rotation-degrees", 0)

        val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, ow, oh).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 12_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        encoder.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val gl = GlCompositor(encoder.createInputSurface(), ow, oh)
        encoder.start()
        val decoder = MediaCodec.createDecoderByType(inFormat.getString(MediaFormat.KEY_MIME)!!)
        decoder.configure(inFormat, gl.decoderSurface, null, 0)
        decoder.start()

        val viz = Viz(take, tracks, from, to, title, ow, oh, overlay = true)
        val layer = Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(layer)
        val rate = take.rate
        val startUs = from * 1_000_000L / rate
        val endRel = to * 1_000_000L / rate - startUs
        val info = MediaCodec.BufferInfo()
        val einfo = MediaCodec.BufferInfo()
        var videoTrack = -1
        var audioTrack = -1
        var framesIn = 0L
        var framesOut = 0L

        fun drainEncoder(end: Boolean) {
            val deadline = System.nanoTime() + 200_000_000L
            while (true) {
                val oi = encoder.dequeueOutputBuffer(einfo, if (end) 10_000 else 2_000)
                when {
                    oi == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!end && (framesOut >= framesIn || System.nanoTime() > deadline)) return
                    oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        videoTrack = muxer.addTrack(encoder.outputFormat)
                        audioTrack = muxer.addTrack(audioFormat)
                        muxer.start()
                    }
                    oi >= 0 -> {
                        val ob = encoder.getOutputBuffer(oi)!!
                        if (einfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && einfo.size > 0 && videoTrack >= 0) {
                            ob.position(einfo.offset); ob.limit(einfo.offset + einfo.size)
                            muxer.writeSampleData(videoTrack, ob, einfo)
                            framesOut++
                        }
                        encoder.releaseOutputBuffer(oi, false)
                        if (einfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                    }
                }
            }
        }

        try {
            val inBuf = ByteBuffer.allocate(4 shl 20)
            var inputDone = false
            while (true) {
                if (!inputDone) {
                    val ii = decoder.dequeueInputBuffer(5_000)
                    if (ii >= 0) {
                        val ib = decoder.getInputBuffer(ii)!!
                        val size = ex.readSampleData(ib, 0)
                        val rel = ex.sampleTime + offsetUs - startUs
                        if (size < 0 || rel > endRel) {
                            decoder.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(ii, 0, size, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val oi = decoder.dequeueOutputBuffer(info, 5_000)
                if (oi >= 0) {
                    val rel = info.presentationTimeUs + offsetUs - startUs // on the audio clock
                    val show = info.size > 0 && rel in 0..endRel
                    decoder.releaseOutputBuffer(oi, show)
                    if (show && gl.awaitFrame()) {
                        viz.draw(canvas, from + rel * rate / 1_000_000L)
                        gl.draw(rotation, layer)
                        gl.swap(rel * 1000)
                        framesIn++
                        drainEncoder(false)
                        progress(rel.toFloat() / endRel.coerceAtLeast(1))
                    }
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
            encoder.signalEndOfInputStream()
            drainEncoder(true)
        } finally {
            runCatching { decoder.stop() }; decoder.release()
            runCatching { encoder.stop() }; encoder.release()
            gl.release()
            viz.recycle(); layer.recycle()
        }
        if (audioTrack >= 0) writeAudio(muxer, audioTrack, tmp, samples)
    }

    // ---- waveform visualization ------------------------------------------

    private fun renderViz(
        take: Take, tracks: List<Track>, from: Long, to: Long, title: String,
        muxer: MediaMuxer, audioFormat: MediaFormat, tmp: File, samples: List<LongArray>,
        progress: (Float) -> Unit,
    ) {
        val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, W, H).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 6_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = codec.createInputSurface()
        codec.start()

        val viz = Viz(take, tracks, from, to, title)
        val frames = ((to - from) * FPS / take.rate).toInt().coerceAtLeast(1)
        val info = MediaCodec.BufferInfo()
        var videoTrack = -1
        var audioTrack = -1
        var outCount = 0L

        /** Drains output. Not at the end: keep going until [want] frames are out (or a short timeout). */
        fun drain(end: Boolean, want: Long = 0) {
            val deadline = System.nanoTime() + 200_000_000L
            while (true) {
                val oi = codec.dequeueOutputBuffer(info, if (end) 10_000 else 2_000)
                when {
                    oi == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!end && (outCount >= want || System.nanoTime() > deadline)) return
                    oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        videoTrack = muxer.addTrack(codec.outputFormat)
                        audioTrack = muxer.addTrack(audioFormat)
                        muxer.start()
                    }
                    oi >= 0 -> {
                        val ob = codec.getOutputBuffer(oi)!!
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0 && videoTrack >= 0) {
                            // surface frames carry wall-clock stamps; we render faster than real time,
                            // so restamp in output order (the encoder doesn't reorder: no B-frames)
                            info.presentationTimeUs = outCount++ * 1_000_000L / FPS
                            ob.position(info.offset); ob.limit(info.offset + info.size)
                            muxer.writeSampleData(videoTrack, ob, info)
                        }
                        codec.releaseOutputBuffer(oi, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                    }
                }
            }
        }

        for (i in 0 until frames) {
            val c = surface.lockHardwareCanvas()
            try { viz.draw(c, from + i.toLong() * take.rate / FPS) } finally { surface.unlockCanvasAndPost(c) }
            // the input surface keeps only the newest frame: wait for this one to come out before
            // drawing the next, or frames get silently replaced
            drain(false, want = i + 1L)
            progress(i.toFloat() / frames * 0.95f)
        }
        codec.signalEndOfInputStream()
        drain(true)
        codec.stop(); codec.release(); surface.release()
        viz.recycle()
        if (audioTrack >= 0) writeAudio(muxer, audioTrack, tmp, samples)
    }

    /** One frame of the take-taker look: title, timecode, lanes with played/unplayed wave, meters. */
    private inner class Viz(
        val take: Take, val tracks: List<Track>, val from: Long, val to: Long, val title: String,
        val w: Int = W, val h: Int = H,
        /** Drawn on transparent black for screen-blending over footage: no backgrounds, lanes low in frame. */
        val overlay: Boolean = false,
    ) {
        private val grotesk: Typeface = ResourcesCompat.getFont(ctx, R.font.space_grotesk) ?: Typeface.DEFAULT
        private val mono: Typeface = ResourcesCompat.getFont(ctx, R.font.jetbrains_mono) ?: Typeface.MONOSPACE
        private val bg = Color.rgb(12, 12, 13)
        private val text = Color.rgb(236, 234, 228)
        private val dim = Color.rgb(142, 141, 147)
        private val faint = Color.rgb(86, 85, 92)
        private val laneBg = Color.rgb(20, 20, 22)
        private val colors = intArrayOf(
            Color.rgb(224, 184, 142), Color.rgb(156, 196, 160), Color.rgb(143, 181, 222), Color.rgb(187, 165, 222),
            Color.rgb(222, 156, 156), Color.rgb(206, 201, 138), Color.rgb(142, 210, 203), Color.rgb(221, 169, 202),
        )
        // overlay: a side-scrolling band that fills most of the frame below the title
        private val u = min(w, h) / 1080f
        private val m = if (overlay) min(w, h) * 0.06f else 72f
        private val top = if (overlay) m + 120f * u else 300f
        private val bottom = if (overlay) h - m else 970f
        private val waveR = if (overlay) w - m else w - m - 28f
        private val n = max(1, tracks.size)
        private val laneH = (bottom - top) / n
        private val bright: Bitmap = Bitmap.createBitmap(if (overlay) 1 else w, if (overlay) 1 else h, Bitmap.Config.ARGB_8888)
        private val dull: Bitmap = Bitmap.createBitmap(if (overlay) 1 else w, if (overlay) 1 else h, Bitmap.Config.ARGB_8888)
        private val wave = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val mm = FloatArray(2)
        private val total = (to - from).coerceAtLeast(1)

        init {
            if (!overlay) prerender()
        }

        private fun prerender() {
            // waveforms are static: draw them once, bright and dimmed, and reveal by clipping
            val cb = Canvas(bright); val cd = Canvas(dull)
            val wp = Paint().apply { strokeWidth = 2f }
            tracks.forEachIndexed { ti, t ->
                val lt = top + ti * laneH + if (overlay) 0f else 10f
                val lh = laneH - if (overlay) 0f else 20f
                val subH = lh / t.chans.size
                val col = if (t.muted) faint else trackColor(t.color)
                t.chans.forEachIndexed { si, ch ->
                    val cy = lt + subH * (si + if (overlay) 0.5f else 0.62f)
                    val amp = subH * if (overlay) 0.42f else 0.34f
                    val peaks = take.peaks[ch]
                    var x = m
                    while (x < waveR) {
                        val s0 = from + ((x - m) / (waveR - m) * total).toLong()
                        val s1 = from + ((x + 3 - m) / (waveR - m) * total).toLong()
                        if (peaks.minMax(s0, s1, mm)) {
                            val y1 = cy - shape(mm[1]) * amp
                            val y2 = max(cy - shape(mm[0]) * amp, y1 + 2f)
                            wp.color = col; cb.drawLine(x, y1, x, y2, wp)
                            wp.color = (col and 0x00FFFFFF) or ((if (overlay) 0x9A else 0x48) shl 24); cd.drawLine(x, y1, x, y2, wp)
                        }
                        x += 3f
                    }
                }
            }
        }

        /**
         * Track colour; over footage the soft UI palette disappears, so push saturation up and
         * value to full there.
         */
        private fun trackColor(i: Int): Int {
            val c = colors[i % colors.size]
            if (!overlay) return c
            val hsv = FloatArray(3)
            Color.colorToHSV(c, hsv)
            hsv[1] = (hsv[1] * 1.9f).coerceAtMost(0.85f)
            hsv[2] = 1f
            return Color.HSVToColor(hsv)
        }

        private fun shape(v: Float) = Math.signum(v) * abs(v).pow(0.8f)

        fun draw(c: Canvas, at: Long) {
            val sec = (at - from).toDouble() / take.rate
            val len = total.toDouble() / take.rate
            val px = m + ((sec / len) * (waveR - m)).toFloat()
            if (overlay) {
                c.drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)
                drawScroll(c, at)
                // session name top left, track names over their lanes; sizes follow the frame
                p.style = Paint.Style.FILL; p.color = Color.WHITE
                p.typeface = grotesk; p.textSize = 64f * u; p.letterSpacing = -0.02f
                c.drawText(title, m, m + 52f * u, p)
                p.typeface = mono; p.textSize = 24f * u; p.letterSpacing = 0.14f
                tracks.forEachIndexed { ti, t ->
                    p.color = if (t.muted) faint else trackColor(t.color)
                    c.drawText(t.name.uppercase(), m, top + ti * laneH + 26f * u, p)
                }
                return
            }
            c.drawColor(bg)
            // header
            p.typeface = grotesk; p.textSize = 76f; p.color = text; p.letterSpacing = -0.02f
            c.drawText(title, m, 168f, p)
            p.typeface = mono; p.textSize = 26f; p.color = faint; p.letterSpacing = 0.12f
            c.drawText("${tracks.size} TRACKS · ${rateLabel(take.rate)}HZ", m, 222f, p)
            p.textSize = 54f; p.color = text; p.letterSpacing = -0.02f
            val tc = tc(sec)
            c.drawText(tc, w - m - p.measureText(tc), 168f, p)
            // lanes
            tracks.forEachIndexed { ti, t ->
                val lt = top + ti * laneH + 10f
                val lh = laneH - 20f
                p.style = Paint.Style.FILL
                p.color = laneBg; c.drawRoundRect(m - 12f, lt, w - m, lt + lh, 18f, 18f, p)
                p.typeface = grotesk; p.textSize = 30f; p.letterSpacing = 0f
                p.color = if (t.muted) faint else text
                c.drawText(t.name, m + 8f, lt + 40f, p)
                // meter: recent peak across the track's channels
                var lvl = 0f
                for (ch in t.chans) if (take.peaks[ch].minMax(at - take.rate / 15, at + 1, mm)) lvl = max(lvl, max(abs(mm[0]), abs(mm[1])))
                val db = if (lvl <= 0.001f) 0f else ((20f * kotlin.math.log10(lvl) + 60f) / 60f).coerceIn(0f, 1f)
                p.color = Color.rgb(38, 38, 43)
                c.drawRoundRect(w - m - 14f, lt + 14f, w - m - 4f, lt + lh - 14f, 5f, 5f, p)
                p.color = colors[t.color % colors.size]
                val mh = (lh - 28f) * db
                c.drawRoundRect(w - m - 14f, lt + lh - 14f - mh, w - m - 4f, lt + lh - 14f, 5f, 5f, p)
            }
            c.drawBitmap(dull, 0f, 0f, null)
            c.save(); c.clipRect(0f, 0f, px, h.toFloat()); c.drawBitmap(bright, 0f, 0f, null); c.restore()
            p.color = text; p.strokeWidth = 3f
            c.drawLine(px, top, px, bottom, p)
            // footer
            p.typeface = mono; p.textSize = 24f; p.color = faint; p.letterSpacing = 0.14f
            c.drawText("TAKE TAKER", m, h - 56f, p)
            val tot = "${tc(sec)} / ${tc(len)}"
            p.color = dim
            c.drawText(tot, w - m - p.measureText(tot), h - 56f, p)
        }

        fun recycle() { bright.recycle(); dull.recycle() }

        /** Seconds of history across the full width in overlay mode. */
        private val window = 8.0

        /** Side-scroller: right edge is [at], older audio further left, nothing ahead of now. */
        private fun drawScroll(c: Canvas, at: Long) {
            val rate = take.rate
            val perPx = window * rate / w // samples per pixel
            // sampling grid pinned to absolute time (not to the moving right edge): every slice of
            // audio gets the same min/max in every frame, so the waveform slides without shimmer
            val colPx = 2f * u
            val cs = (colPx * perPx).toLong().coerceAtLeast(1) // samples per column
            val kEnd = at / cs
            val cols = (w / colPx).toInt() + 3
            val xs = FloatArray(cols); val hi = FloatArray(cols); val lo = FloatArray(cols)
            val path = android.graphics.Path()
            tracks.forEachIndexed { ti, t ->
                val lt = top + ti * laneH
                val subH = laneH / t.chans.size
                wave.color = if (t.muted) faint else trackColor(t.color)
                t.chans.forEachIndexed { si, ch ->
                    val cy = lt + subH * (si + 0.55f)
                    val amp = subH * 0.42f
                    val peaks = take.peaks[ch]
                    var n = 0
                    var k = kEnd
                    while (n < cols) {
                        val s0 = k * cs
                        val s1 = min(s0 + cs, at) // the newest column is only partly heard
                        if (s1 <= from) break
                        val x = w - ((at - (s0 + s1) / 2).toFloat() / perPx.toFloat())
                        if (x < -colPx) break
                        var top = 0f; var bot = 0f
                        if (peaks.minMax(max(s0, from), s1, mm)) { top = shape(mm[1]); bot = shape(mm[0]) }
                        xs[n] = x
                        hi[n] = cy - max(top, 0f) * amp - 0.75f * u
                        lo[n] = cy - min(bot, 0f) * amp + 0.75f * u
                        n++; k--
                    }
                    if (n < 2) return@forEachIndexed
                    // one antialiased filled envelope: top edge newest→oldest, bottom edge back
                    path.rewind()
                    path.moveTo(xs[0], hi[0])
                    for (q in 1 until n) path.lineTo(xs[q], hi[q])
                    for (q in n - 1 downTo 0) path.lineTo(xs[q], lo[q])
                    path.close()
                    c.drawPath(path, wave)
                }
            }
        }

        private fun tc(s: Double): String {
            val m = (s / 60).toInt(); val ss = s % 60
            return "%02d:%04.1f".format(Locale.US, m, ss)
        }

        private fun rateLabel(r: Int) = if (r % 1000 == 0) "${r / 1000}K" else "%.1fK".format(Locale.US, r / 1000.0)
    }
}
