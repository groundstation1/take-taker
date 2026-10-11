package app.taketaker.audio

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * Small FLAC encoder: fixed predictors (order 0-4), partitioned Rice residuals,
 * constant/wasted-bits detection. Not libFLAC-tight, but lossless and quick,
 * and 16-bit material stored at 24 bits costs nothing thanks to wasted bits.
 */
class FlacWriter(
    private val file: File,
    private val channels: Int,
    private val rate: Int,
    private val bits: Int,
) : AutoCloseable {
    private companion object {
        const val BLOCK = 4096
        const val HEADER = 42
    }

    private val out = BufferedOutputStream(FileOutputStream(file), 1 shl 16)
    private val buf = Array(channels) { IntArray(BLOCK) }
    private val work = IntArray(BLOCK)
    private val res = IntArray(BLOCK)
    private val bw = BitWriter()
    private val md5 = MessageDigest.getInstance("MD5")
    private val md5Buf = ByteArray(BLOCK * channels * (bits / 8))
    private val scale = (1L shl (bits - 1)).toFloat()
    private val maxV = (1L shl (bits - 1)) - 1
    private val minV = -(1L shl (bits - 1))
    private var fill = 0
    private var frameNo = 0L
    private var total = 0L

    init {
        require(bits == 16 || bits == 24)
        out.write(ByteArray(HEADER)) // patched on close
    }

    /** [data][c] holds [n] float samples for channel c. */
    fun write(data: Array<FloatArray>, n: Int) {
        var i = 0
        while (i < n) {
            val k = min(BLOCK - fill, n - i)
            for (c in 0 until channels) {
                val src = data[c]; val dst = buf[c]
                for (j in 0 until k) dst[fill + j] = (src[i + j] * scale).roundToLong().coerceIn(minV, maxV).toInt()
            }
            fill += k; i += k
            if (fill == BLOCK) flushBlock()
        }
    }

    override fun close() {
        if (fill > 0) flushBlock()
        out.close()
        RandomAccessFile(file, "rw").use { it.write(streamInfo()) }
    }

    private fun flushBlock() {
        val n = fill
        val bps = bits / 8
        var p = 0
        for (i in 0 until n) for (c in 0 until channels) {
            val v = buf[c][i]
            md5Buf[p++] = v.toByte(); md5Buf[p++] = (v shr 8).toByte()
            if (bps == 3) md5Buf[p++] = (v shr 16).toByte()
        }
        md5.update(md5Buf, 0, p)
        encodeFrame(n)
        out.write(bw.bytes, 0, bw.size)
        total += n; frameNo++; fill = 0
    }

    private fun streamInfo(): ByteArray {
        val b = ByteArray(HEADER)
        "fLaC".forEachIndexed { i, ch -> b[i] = ch.code.toByte() }
        b[4] = 0x80.toByte() // last metadata block, STREAMINFO
        b[7] = 34
        b[8] = (BLOCK shr 8).toByte(); b[10] = (BLOCK shr 8).toByte() // min/max blocksize 4096
        val packed = (rate.toLong() shl 44) or ((channels - 1).toLong() shl 41) or
            ((bits - 1).toLong() shl 36) or (total and 0xFFFFFFFFFL)
        for (i in 0 until 8) b[18 + i] = (packed ushr (56 - 8 * i)).toByte()
        md5.digest().copyInto(b, 26)
        return b
    }

    private fun encodeFrame(n: Int) {
        bw.reset()
        bw.write(0xFFF8, 16)
        val bsCode = when { n == BLOCK -> 12; n <= 256 -> 6; else -> 7 }
        bw.write(bsCode, 4)
        bw.write(rateCode(rate), 4)
        bw.write(channels - 1, 4)
        bw.write(if (bits == 16) 4 else 6, 3)
        bw.write(0, 1)
        writeUtf8(frameNo)
        if (bsCode == 6) bw.write(n - 1, 8) else if (bsCode == 7) bw.write(n - 1, 16)
        bw.write(Crc.crc8(bw.bytes, bw.size), 8)
        for (c in 0 until channels) subframe(buf[c], n)
        bw.alignByte()
        bw.write(Crc.crc16(bw.bytes, bw.size), 16)
    }

    private fun rateCode(r: Int) = when (r) {
        88200 -> 1; 176400 -> 2; 192000 -> 3; 8000 -> 4; 16000 -> 5; 22050 -> 6
        24000 -> 7; 32000 -> 8; 44100 -> 9; 48000 -> 10; 96000 -> 11; else -> 0
    }

    private fun writeUtf8(v: Long) {
        if (v < 0x80) { bw.write(v.toInt(), 8); return }
        val nBytes = when { v < 0x800 -> 2; v < 0x10000 -> 3; v < 0x200000 -> 4; v < 0x4000000 -> 5; else -> 6 }
        val lead = (0xFF00 shr nBytes) and 0xFF
        bw.write(lead or (v ushr (6 * (nBytes - 1))).toInt(), 8)
        for (i in nBytes - 2 downTo 0) bw.write(0x80 or ((v ushr (6 * i)) and 0x3F).toInt(), 8)
    }

    private fun subframe(x: IntArray, n: Int) {
        var or = 0
        for (i in 0 until n) or = or or x[i]
        if (or == 0) { bw.write(0, 8); bw.write(0, bits); return } // CONSTANT 0
        val wasted = Integer.numberOfTrailingZeros(or)
        val s = work
        for (i in 0 until n) s[i] = x[i] shr wasted
        val bps = bits - wasted

        fun header(type: Int) {
            bw.write(type, 7) // zero pad bit + 6 type bits
            if (wasted > 0) { bw.write(1, 1); bw.writeUnary(wasted - 1) } else bw.write(0, 1)
        }

        var constant = true
        for (i in 1 until n) if (s[i] != s[0]) { constant = false; break }
        if (constant) { header(0); bw.writeSigned(s[0], bps); return }

        if (n <= 8) { // too short to bother predicting
            header(1)
            for (i in 0 until n) bw.writeSigned(s[i], bps)
            return
        }

        val order = bestOrder(s, n)
        computeResidual(s, n, order)
        header(8 + order)
        for (i in 0 until order) bw.writeSigned(s[i], bps)
        writeResidual(n, order)
    }

    private fun bestOrder(s: IntArray, n: Int): Int {
        val sum = LongArray(5)
        for (i in 4 until n) {
            val e0 = s[i]
            val e1 = e0 - s[i - 1]
            val e2 = e1 - (s[i - 1] - s[i - 2])
            val e3 = e2 - (s[i - 1] - 2 * s[i - 2] + s[i - 3])
            val e4 = e3 - (s[i - 1] - 3 * s[i - 2] + 3 * s[i - 3] - s[i - 4])
            sum[0] += abs(e0); sum[1] += abs(e1); sum[2] += abs(e2); sum[3] += abs(e3); sum[4] += abs(e4)
        }
        var best = 0
        for (o in 1..4) if (sum[o] < sum[best]) best = o
        return best
    }

    private fun computeResidual(s: IntArray, n: Int, order: Int) {
        for (i in order until n) {
            res[i - order] = when (order) {
                0 -> s[i]
                1 -> s[i] - s[i - 1]
                2 -> s[i] - 2 * s[i - 1] + s[i - 2]
                3 -> s[i] - 3 * s[i - 1] + 3 * s[i - 2] - s[i - 3]
                else -> s[i] - 4 * s[i - 1] + 6 * s[i - 2] - 4 * s[i - 3] + s[i - 4]
            }
        }
        // zigzag in place
        for (i in 0 until n - order) { val r = res[i]; res[i] = (r shl 1) xor (r shr 31) }
    }

    private fun riceK(sum: Long, count: Int): Int {
        if (count == 0 || sum == 0L) return 0
        var k = 0
        while (k < 30 && (count.toLong() shl (k + 1)) < sum) k++
        return k
    }

    private fun writeResidual(n: Int, order: Int) {
        val count = n - order
        var bestP = 0; var bestBits = Long.MAX_VALUE
        var maxP = 0
        while (maxP < 8 && n % (1 shl (maxP + 1)) == 0 && (n shr (maxP + 1)) > order) maxP++
        for (p in 0..maxP) {
            val parts = 1 shl p
            val len = n shr p
            var bitsTotal = 0L
            var idx = 0
            for (k in 0 until parts) {
                val cnt = if (k == 0) len - order else len
                var sum = 0L
                for (j in 0 until cnt) sum += res[idx + j]
                idx += cnt
                val r = riceK(sum, cnt)
                bitsTotal += 5 + cnt.toLong() * (r + 1) + (sum ushr r)
            }
            if (bitsTotal < bestBits) { bestBits = bitsTotal; bestP = p }
        }
        val parts = 1 shl bestP
        val len = n shr bestP
        val ks = IntArray(parts)
        var idx = 0
        for (k in 0 until parts) {
            val cnt = if (k == 0) len - order else len
            var sum = 0L
            for (j in 0 until cnt) sum += res[idx + j]
            ks[k] = riceK(sum, cnt)
            idx += cnt
        }
        val method = if (ks.any { it > 14 }) 1 else 0
        val pBits = if (method == 1) 5 else 4
        bw.write(method, 2)
        bw.write(bestP, 4)
        idx = 0
        for (k in 0 until parts) {
            val cnt = if (k == 0) len - order else len
            val r = ks[k]
            bw.write(r, pBits)
            for (j in 0 until cnt) {
                val u = res[idx + j]
                bw.writeUnary(u ushr r)
                if (r > 0) bw.write(u and ((1 shl r) - 1), r)
            }
            idx += cnt
        }
        check(idx == count)
    }
}

class BitWriter {
    var bytes = ByteArray(1 shl 16); private set
    var size = 0; private set
    private var acc = 0L
    private var n = 0

    fun reset() { size = 0; acc = 0; n = 0 }

    /** Writes the low [bits] (<= 32) bits of [v]. */
    fun write(v: Int, bits: Int) {
        if (bits == 0) return
        acc = (acc shl bits) or (v.toLong() and ((1L shl bits) - 1))
        n += bits
        while (n >= 8) {
            n -= 8
            put((acc ushr n).toInt())
        }
        acc = acc and ((1L shl n) - 1)
    }

    fun writeSigned(v: Int, bits: Int) = write(v, bits)

    /** q zeros then a one. */
    fun writeUnary(q: Int) {
        var r = q
        while (r >= 31) { write(0, 31); r -= 31 }
        write(1, r + 1)
    }

    fun alignByte() { if (n > 0) write(0, 8 - n) }

    private fun put(b: Int) {
        if (size == bytes.size) bytes = bytes.copyOf(size * 2)
        bytes[size++] = b.toByte()
    }
}

object Crc {
    private val t8 = IntArray(256) { i ->
        var c = i
        repeat(8) { c = if (c and 0x80 != 0) ((c shl 1) xor 0x07) and 0xFF else (c shl 1) and 0xFF }
        c
    }
    private val t16 = IntArray(256) { i ->
        var c = i shl 8
        repeat(8) { c = if (c and 0x8000 != 0) ((c shl 1) xor 0x8005) and 0xFFFF else (c shl 1) and 0xFFFF }
        c
    }

    fun crc8(b: ByteArray, len: Int): Int {
        var c = 0
        for (i in 0 until len) c = t8[c xor (b[i].toInt() and 0xFF)]
        return c
    }

    fun crc16(b: ByteArray, len: Int): Int {
        var c = 0
        for (i in 0 until len) c = ((c shl 8) xor t16[((c shr 8) xor (b[i].toInt() and 0xFF)) and 0xFF]) and 0xFFFF
        return c
    }
}
