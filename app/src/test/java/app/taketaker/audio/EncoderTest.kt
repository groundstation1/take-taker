package app.taketaker.audio

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * Writes test FLACs (+ the exact expected integer samples) to build/enc-test so they can be
 * cross-checked with an external decoder, plus an MP3 through jump3r.
 */
class EncoderTest {
    private val dir = File("build/enc-test").apply { mkdirs() }

    private fun signal(ch: Int, n: Int, bits16: Boolean): Array<FloatArray> {
        val rnd = Random(ch)
        return Array(ch) { c ->
            FloatArray(n) { i ->
                val v = when {
                    i in 10000..14000 -> 0f // digital silence block
                    i in 20000..21000 -> 0.25f // DC block (constant subframe)
                    else -> (0.5 * sin(2 * PI * (220.0 * (c + 1)) * i / 44100) + 0.05 * (rnd.nextFloat() - 0.5f)).toFloat()
                }
                if (bits16) (v * 32768f).roundToInt().coerceIn(-32768, 32767) / 32768f else v
            }
        }
    }

    private fun run(name: String, ch: Int, bits: Int, n: Int, bits16: Boolean) {
        val sig = signal(ch, n, bits16)
        FlacWriter(File(dir, "$name.flac"), ch, 44100, bits).use { w ->
            var i = 0
            val chunk = 3001 // odd size to exercise block buffering
            while (i < n) {
                val k = minOf(chunk, n - i)
                w.write(Array(ch) { c -> sig[c].copyOfRange(i, i + k) }, k)
                i += k
            }
        }
        // expected interleaved s32 samples, little endian
        val scale = (1L shl (bits - 1)).toFloat()
        DataOutputStream(FileOutputStream(File(dir, "$name.s32")).buffered()).use { out ->
            for (i in 0 until n) for (c in 0 until ch) {
                val v = (sig[c][i] * scale).toDouble().let { Math.round(it) }
                    .coerceIn(-(1L shl (bits - 1)), (1L shl (bits - 1)) - 1).toInt()
                out.writeInt(Integer.reverseBytes(v))
            }
        }
        assertTrue(File(dir, "$name.flac").length() > 0)
    }

    @Test fun mono24() = run("mono24", 1, 24, 100_000, false)
    @Test fun stereo16() = run("stereo16", 2, 16, 77_777, true)
    @Test fun mono16in24() = run("mono16in24", 1, 24, 50_000, true)
    @Test fun tiny() = run("tiny", 1, 24, 5, false)

    @Test fun mp3() {
        val enc = Mp3.stereoEncoder(44100, 256)
        val pcm = ByteArray(enc.pcmBufferSize)
        val out = ByteArray(enc.getMP3BufferSize())
        FileOutputStream(File(dir, "test.mp3")).use { f ->
            var t = 0
            repeat(100) {
                for (i in 0 until pcm.size / 4) {
                    val s = (sin(2 * PI * 440 * t++ / 44100) * 12000).toInt()
                    pcm[4 * i] = s.toByte(); pcm[4 * i + 1] = (s shr 8).toByte()
                    pcm[4 * i + 2] = s.toByte(); pcm[4 * i + 3] = (s shr 8).toByte()
                }
                val m = enc.encodeBuffer(pcm, 0, pcm.size, out)
                if (m > 0) f.write(out, 0, m)
            }
            val m = enc.encodeFinish(out)
            if (m > 0) f.write(out, 0, m)
        }
        enc.close()
        assertTrue(File(dir, "test.mp3").length() > 1000)
    }
}
