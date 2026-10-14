package app.taketaker.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/** Synthetic UAC2 device: no hardware to test against, so pin the descriptor walk and the range parsing. */
class Uac2Test {
    private fun desc(vararg b: Int): ByteArray = ByteArray(b.size + 1).also { a ->
        a[0] = (b.size + 1).toByte(); b.forEachIndexed { i, v -> a[i + 1] = v.toByte() }
    }

    private fun device(): ByteArray {
        val o = ByteArrayOutputStream()
        fun w(d: ByteArray) = o.write(d)
        w(desc(0x02, 0, 0, 2, 1, 0, 0x80, 50)) // config
        w(desc(0x04, 0, 0, 0, 1, 1, 0x20, 0)) // AC interface 0
        w(desc(0x24, 0x01, 0x00, 0x02, 0x08, 0x40, 0x00, 0x00)) // AC header bcdADC 2.00
        w(desc(0x24, 0x0A, 0x29, 0x03, 0x07, 0x00, 0x00)) // clock source 0x29
        w(desc(0x24, 0x0B, 0x28, 0x01, 0x29, 0x03, 0x00)) // clock selector 0x28 ← 0x29
        w(desc(0x24, 0x02, 0x01, 0x01, 0x02, 0x00, 0x28, 6, 0, 0, 0, 0, 0, 0, 0, 0, 0)) // input terminal (line)
        w(desc(0x24, 0x03, 0x0B, 0x01, 0x01, 0x00, 0x01, 0x28, 0, 0, 0)) // output terminal → USB, clock 0x28
        w(desc(0x04, 1, 0, 0, 1, 2, 0x20, 0)) // AS interface 1 alt 0
        w(desc(0x04, 1, 1, 1, 1, 2, 0x20, 0)) // AS interface 1 alt 1
        w(desc(0x24, 0x01, 0x0B, 0x00, 0x01, 0x01, 0, 0, 0, 6, 0, 0, 0, 0, 0)) // AS_GENERAL: terminal 0x0B, 6 ch
        w(desc(0x24, 0x02, 0x01, 0x04, 0x18)) // FORMAT_TYPE_I: 4-byte subslots, 24 bit
        w(desc(0x05, 0x81, 0x05, 0x40, 0x0A, 0x01)) // iso async IN, 0x0A40: 576 bytes x 2 transactions
        w(desc(0x25, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00)) // CS endpoint
        return o.toByteArray()
    }

    @Test fun parsesUac2Streaming() {
        val s = UsbRaw.parse(device())!!
        assertTrue(s.uac2)
        assertEquals(1, s.iface); assertEquals(1, s.alt)
        assertEquals(0x81, s.ep)
        assertEquals(576 * 2, s.maxPacket)
        assertEquals(6, s.channels); assertEquals(4, s.subframe); assertEquals(24, s.bits)
        assertEquals(0x29, s.clockId) // selector followed to its source
        assertEquals(0, s.acIface)
    }

    @Test fun parsesClockRanges() {
        fun u32(v: Int) = listOf(v and 0xFF, (v shr 8) and 0xFF, (v shr 16) and 0xFF, (v shr 24) and 0xFF)
        val bytes = (listOf(2, 0) + u32(48000) + u32(48000) + u32(0) + u32(44100) + u32(96000) + u32(0))
            .map { it.toByte() }.toByteArray()
        assertEquals(listOf(44100, 48000, 88200, 96000), UsbRaw.parseRanges(bytes))
        assertEquals(48000, UsbRaw.parse(device())!!.copy(rates = UsbRaw.parseRanges(bytes)).rate)
    }

    @Test fun uac1StillParses() {
        val bytes = javaClass.getResourceAsStream("/fm1_descriptors.bin")!!.readBytes()
        val s = UsbRaw.parse(bytes)!!
        assertEquals(false, s.uac2)
        assertEquals(4, s.channels)
    }
}
