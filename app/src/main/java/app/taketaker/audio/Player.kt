package app.taketaker.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.min

/** Streams a stereo mix of a take through the default output. */
class Player(private val take: Take, private val tracks: () -> List<Track>) {
    @Volatile private var thread: Thread? = null
    @Volatile private var track: AudioTrack? = null
    @Volatile private var from = 0L
    @Volatile private var stopped = false

    val playing get() = thread != null

    /** Frame currently heard. */
    fun position(): Long = from + (track?.playbackHeadPosition?.toLong() ?: 0L)

    fun play(start: Long, end: Long, onEnd: () -> Unit) {
        stop()
        if (end <= start) return
        from = start
        stopped = false
        val at = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
            )
            .setAudioFormat(
                AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(take.rate).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build()
            )
            .setBufferSizeInBytes(take.rate / 10 * 8)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track = at
        thread = Thread({
            val block = 2048
            val bufs = Array(take.channels) { FloatArray(block) }
            val out = FloatArray(block * 2)
            TakeReader(take).use { reader ->
                at.play()
                var p = start
                while (!stopped && p < end) {
                    val n = min(block.toLong(), end - p).toInt()
                    val ts = tracks()
                    for (t in ts) for (c in t.chans) reader.read(c, p, n, bufs[c])
                    Mix.mix(ts, bufs, n, out)
                    at.write(out, 0, n * 2, AudioTrack.WRITE_BLOCKING)
                    p += n
                }
                val total = end - start
                while (!stopped && at.playbackHeadPosition < total) Thread.sleep(8)
            }
            runCatching { at.stop() }
            at.release()
            track = null
            thread = null
            if (!stopped) onEnd()
        }, "player").apply { start() }
    }

    fun stop() {
        stopped = true
        track?.let { runCatching { it.pause(); it.flush() } }
        thread?.join(300)
        thread = null
    }
}
