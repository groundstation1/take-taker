package app.taketaker.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PcmDecoderTest {
    @Test fun decodesEightDistinctChannelsAcrossFrames() {
        val values = intArrayOf(0, 0x20000000, 0x40000000, 0x60000000,
            Int.MIN_VALUE, -0x20000000, -0x40000000, Int.MAX_VALUE)
        val bytes = ByteArray(values.size * 2 * 4)
        for (frame in 0..1) for (ch in values.indices) {
            val p = (frame * 8 + ch) * 4
            val v = if (frame == 0) values[ch] else -values[ch]
            for (i in 0..3) bytes[p + i] = (v shr (i * 8)).toByte()
        }
        val out = FloatArray(16)
        assertEquals(2, PcmDecoder.decode(bytes, bytes.size, 4, 8, out))
        for (ch in values.indices) {
            assertEquals(values[ch] / 2147483648f, out[ch], 0.000001f)
            assertEquals((-values[ch]) / 2147483648f, out[8 + ch], 0.000001f)
        }
    }

    @Test fun leftAlignedResolutionAndMalformedFrame() {
        val bytes = byteArrayOf(0, 0, 0, 0x40, 0, 0, 0, 0xc0.toByte())
        val out = FloatArray(2)
        assertEquals(1, PcmDecoder.decode(bytes, 8, 4, 2, out))
        assertEquals(0.5f, out[0], 0.000001f)
        assertEquals(-0.5f, out[1], 0.000001f)
        assertThrows(IllegalArgumentException::class.java) { PcmDecoder.decode(bytes, 7, 4, 2, out) }
    }
}
