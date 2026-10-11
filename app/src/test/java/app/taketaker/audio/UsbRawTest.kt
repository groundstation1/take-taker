package app.taketaker.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class UsbRawTest {
    @Test fun parsesFm1() {
        // raw descriptors as read from /sys/bus/usb/devices/<dev>/descriptors on the phone
        val bytes = javaClass.getResourceAsStream("/fm1_descriptors.bin")!!.readBytes()
        val s = UsbRaw.parse(bytes)!!
        assertEquals(5, s.iface)
        assertEquals(2, s.alt)
        assertEquals(0x82, s.ep)
        assertEquals(540, s.maxPacket)
        assertEquals(4, s.channels)
        assertEquals(3, s.subframe)
        assertEquals(24, s.bits)
        assertEquals(listOf(44100), s.rates)
        val all = UsbRaw.parseAll(bytes)
        assertEquals(listOf(24 to 2, 16 to 1), all.map { it.bits to it.alt })
        assertEquals(360, all[1].maxPacket)
        assertEquals(2, all[1].subframe)
    }
}
