package app.taketaker.audio

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.min
import kotlin.random.Random

enum class SaveMode(val title: String, val blurb: String) {
    MULTI("multitrack", "one lossless flac per track"),
    MULTI_MIX("multitrack + mix", "flac stems plus a stereo mp3 mixdown"),
    MIX("mixdown only", "stereo mp3 · 256 kbps"),
}

/** One published file. */
data class Saved(val uri: Uri, val name: String, val mime: String)

/** What an export produced: a display location, every file, and the mix if there is one. */
data class ExportResult(val where: String, val files: List<Saved>, val mix: Saved?, val video: Saved? = null)

enum class VideoKind { CAMERA, WAVEFORM, OVERLAY }

/** Ask for an MP4 too: the take's camera footage, a rendered waveform video, or footage with the waveform on top. */
data class VideoOpt(val title: String, val kind: VideoKind)

/** Renders a take to FLAC stems and/or an MP3 mix and publishes them under Music/TakeTaker. */
class Exporter(private val ctx: Context) {
    companion object {
        const val ROOT = "TakeTaker"
        /** Normalizing won't boost by more than this (+24 dB), so near-silence stays quiet. */
        private const val MAX_BOOST = 16f
        private const val CHUNK = 8192
        private const val MIX_CEILING = 0.891f // -1 dBFS
    }

    /** Returns a human-readable destination. Blocking; call off the main thread. */
    fun export(
        take: Take, tracks: List<Track>, from: Long, to: Long,
        mode: SaveMode, baseName: String, bits: Int, normalize: Boolean, video: VideoOpt?,
        progress: (Float) -> Unit,
    ): ExportResult {
        val tmp = File(ctx.cacheDir, "export").apply { deleteRecursively(); mkdirs() }
        val stems = mode != SaveMode.MIX
        val wantMix = mode != SaveMode.MULTI
        val total = (to - from).coerceAtLeast(1)
        val needMix = wantMix || video != null
        val passes = 1 + (if (wantMix) 1 else 0) + (if (video != null) 1 else 0)
        val bufs = Array(take.channels) { FloatArray(CHUNK) }
        val mixBuf = FloatArray(CHUNK * 2)
        val used = tracks.flatMap { it.chans }.distinct()

        // pass 1: stems + mix peak
        val writers = if (stems) tracks.mapIndexed { i, t ->
            val f = File(tmp, "%02d_%s.flac".format(i + 1, slug(t.name)))
            f to FlacWriter(f, t.chans.size, take.rate, bits)
        } else emptyList()
        var peak = 0f
        TakeReader(take).use { reader ->
            var p = from
            while (p < to) {
                val n = min(CHUNK.toLong(), to - p).toInt()
                for (c in used) reader.read(c, p, n, bufs[c])
                writers.forEachIndexed { i, (_, w) -> w.write(Array(tracks[i].chans.size) { bufs[tracks[i].chans[it]] }, n) }
                if (needMix) {
                    Mix.mix(tracks, bufs, n, mixBuf)
                    for (i in 0 until n * 2) { val a = abs(mixBuf[i]); if (a > peak) peak = a }
                }
                p += n
                progress((p - from).toFloat() / total / passes)
            }
        }
        writers.forEach { it.second.close() }

        // pass 2: mp3
        val gain = when {
            peak <= 0f -> 1f
            normalize -> (MIX_CEILING / peak).coerceAtMost(MAX_BOOST)
            peak > MIX_CEILING -> MIX_CEILING / peak // never clip
            else -> 1f
        }
        var mp3: File? = null
        if (wantMix) {
            mp3 = File(tmp, "${slug(baseName)}${if (stems) "_mix" else ""}.mp3")
            encodeMp3(take, tracks, from, to, gain, mp3) { progress((1 + it) / passes) }
        }

        // pass 3: video (camera footage or waveform), mixdown as AAC
        var mp4: File? = null
        if (video != null) {
            mp4 = File(tmp, "${slug(baseName)}.mp4")
            val footage = take.video?.takeIf { video.kind != VideoKind.WAVEFORM }?.let { app.taketaker.video.VideoExport.Footage(it, take.videoOffsetUs) }
            app.taketaker.video.VideoExport(ctx).render(
                take, tracks, from, to, gain, video.title, footage, mp4,
                progress = { progress((passes - 1 + it) / passes) },
                overlay = video.kind == VideoKind.OVERLAY,
            )
        }

        // publish
        val folder = if (stems) "${Environment.DIRECTORY_MUSIC}/$ROOT/$baseName/" else "${Environment.DIRECTORY_MUSIC}/$ROOT/"
        val files = writers.map { (f, _) -> publish(f, f.name, "audio/flac", folder) }
        val mix = mp3?.let { publish(it, it.name, "audio/mpeg", folder) }
        val vid = mp4?.let { publish(it, it.name, "video/mp4", "${Environment.DIRECTORY_MOVIES}/$ROOT/") }
        tmp.deleteRecursively()
        progress(1f)
        val where = when {
            stems -> folder
            mp3 != null -> folder + mp3.name
            else -> "${Environment.DIRECTORY_MOVIES}/$ROOT/${mp4?.name}"
        }
        return ExportResult(where, files + listOfNotNull(mix, vid), mix, vid)
    }

    private fun encodeMp3(take: Take, tracks: List<Track>, from: Long, to: Long, gain: Float, file: File, progress: (Float) -> Unit) {
        val enc = Mp3.stereoEncoder(take.rate, 256)
        val pcm = ByteArray(enc.pcmBufferSize)
        val frames = pcm.size / 4
        val outBuf = ByteArray(enc.getMP3BufferSize())
        val bufs = Array(take.channels) { FloatArray(frames) }
        val mixBuf = FloatArray(frames * 2)
        val used = tracks.flatMap { it.chans }.distinct()
        val rnd = Random(1)
        val total = (to - from).coerceAtLeast(1)
        FileOutputStream(file).use { out ->
            TakeReader(take).use { reader ->
                var p = from
                while (p < to) {
                    val n = min(frames.toLong(), to - p).toInt()
                    for (c in used) reader.read(c, p, n, bufs[c])
                    Mix.mix(tracks, bufs, n, mixBuf, gain)
                    for (i in 0 until n * 2) {
                        // TPDF dither down to 16 bit
                        val d = (rnd.nextFloat() - rnd.nextFloat())
                        val s = (mixBuf[i] * 32767f + d).toInt().coerceIn(-32768, 32767)
                        pcm[2 * i] = s.toByte(); pcm[2 * i + 1] = (s shr 8).toByte()
                    }
                    val m = enc.encodeBuffer(pcm, 0, n * 4, outBuf)
                    if (m > 0) out.write(outBuf, 0, m)
                    p += n
                    progress((p - from).toFloat() / total)
                }
            }
            val m = enc.encodeFinish(outBuf)
            if (m > 0) out.write(outBuf, 0, m)
        }
        enc.close()
    }

    private fun publish(src: File, name: String, mime: String, relPath: String): Saved {
        val resolver = ctx.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relPath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val coll = if (mime.startsWith("video/")) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        else MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(coll, values) ?: error("couldn't create $name")
        resolver.openOutputStream(uri)!!.use { out -> src.inputStream().use { it.copyTo(out, 1 shl 16) } }
        values.clear()
        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return Saved(uri, name, mime)
    }

    /** Zips published files into the share cache (on demand, so saving stays fast). */
    fun zip(files: List<Saved>, name: String): File {
        val dir = File(ctx.cacheDir, "share").apply { deleteRecursively(); mkdirs() }
        val out = File(dir, "$name.zip")
        java.util.zip.ZipOutputStream(out.outputStream().buffered()).use { z ->
            for (f in files) {
                // FLAC and MP3 are already compressed; storing is as small and much faster
                val e = java.util.zip.ZipEntry("$name/${f.name}")
                z.setLevel(java.util.zip.Deflater.NO_COMPRESSION)
                z.putNextEntry(e)
                ctx.contentResolver.openInputStream(f.uri)!!.use { it.copyTo(z, 1 shl 16) }
                z.closeEntry()
            }
        }
        return out
    }
}

fun slug(s: String): String =
    s.trim().replace(Regex("[\\\\/:*?\"<>|]"), "").replace(Regex("\\s+"), "-").ifBlank { "take" }
