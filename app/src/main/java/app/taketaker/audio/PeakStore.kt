package app.taketaker.audio

import kotlin.math.max
import kotlin.math.min

/**
 * Min/max summary of a channel for drawing, at two resolutions. Appended by the
 * capture thread, read by the UI: readers take [count] first, then the arrays,
 * so a concurrent grow never exposes unwritten slots.
 */
class PeakStore {
    companion object {
        const val BLOCK = 256
        const val COARSE = 32
    }

    @Volatile private var mins = FloatArray(8192)
    @Volatile private var maxs = FloatArray(8192)
    @Volatile var count = 0; private set
    @Volatile private var cMins = FloatArray(512)
    @Volatile private var cMaxs = FloatArray(512)
    @Volatile private var cCount = 0

    private var accMin = Float.MAX_VALUE
    private var accMax = -Float.MAX_VALUE
    private var accN = 0

    fun add(buf: FloatArray, n: Int) {
        for (i in 0 until n) {
            val v = buf[i]
            if (v < accMin) accMin = v
            if (v > accMax) accMax = v
            if (++accN == BLOCK) pushBlock()
        }
    }

    private fun pushBlock() {
        val c = count
        if (c == mins.size) {
            mins = mins.copyOf(c * 2); maxs = maxs.copyOf(c * 2)
        }
        mins[c] = accMin; maxs[c] = accMax
        count = c + 1
        accMin = Float.MAX_VALUE; accMax = -Float.MAX_VALUE; accN = 0
        if ((c + 1) % COARSE == 0) {
            var lo = Float.MAX_VALUE; var hi = -Float.MAX_VALUE
            for (j in c + 1 - COARSE..c) { lo = min(lo, mins[j]); hi = max(hi, maxs[j]) }
            val cc = cCount
            if (cc == cMins.size) { cMins = cMins.copyOf(cc * 2); cMaxs = cMaxs.copyOf(cc * 2) }
            cMins[cc] = lo; cMaxs[cc] = hi
            cCount = cc + 1
        }
    }

    /** Writes min into out[0], max into out[1] for samples [s0, s1). False if nothing there. */
    fun minMax(s0: Long, s1: Long, out: FloatArray): Boolean {
        val c = count
        if (c == 0 || s1 <= 0) return false
        var b0 = max(0L, s0 / BLOCK)
        var b1 = min(c.toLong(), (s1 + BLOCK - 1) / BLOCK)
        if (b0 >= c) return false
        if (b1 <= b0) b1 = b0 + 1
        out[0] = Float.MAX_VALUE; out[1] = -Float.MAX_VALUE
        if (b1 - b0 > COARSE * 3) {
            val cc = cCount
            val k0 = ((b0 + COARSE - 1) / COARSE).toInt()
            val k1 = min(cc.toLong(), b1 / COARSE).toInt()
            val mn = cMins; val mx = cMaxs
            for (k in k0 until k1) { if (mn[k] < out[0]) out[0] = mn[k]; if (mx[k] > out[1]) out[1] = mx[k] }
            // head and tail at fine resolution
            scanFine(b0, k0.toLong() * COARSE, out)
            b0 = max(b0, k1.toLong() * COARSE)
        }
        scanFine(b0, b1, out)
        return out[0] <= out[1]
    }

    private fun scanFine(from: Long, to: Long, out: FloatArray) {
        val mn = mins; val mx = maxs
        for (b in from.toInt() until to.toInt()) { if (mn[b] < out[0]) out[0] = mn[b]; if (mx[b] > out[1]) out[1] = mx[b] }
    }
}
