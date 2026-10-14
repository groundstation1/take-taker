package app.taketaker.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class UsbRawTest {
    @Test fun parsesRealEp136Descriptors() {
        // /sys/bus/usb/devices/1-1.2.4/descriptors, EP-136 bcdDevice 1.01.
        // Raw descriptors contain only the serial string index, not its value.
        val bytes = javaClass.getResourceAsStream("/ep136_descriptors.bin")!!.readBytes()
        val all = UsbRaw.parseAll(bytes)
        assertEquals(1, all.size)
        val s = all.single()
        assertEquals(2, s.protocol)
        assertEquals(0, s.controlIface)
        assertEquals(3, s.terminal)
        assertEquals(16, s.clockId)
        assertEquals(7, s.clockControls)
        assertEquals(1, s.iface)
        assertEquals(1, s.alt)
        assertEquals(0x82, s.ep)
        assertEquals(224, s.maxPacket)
        assertEquals(1, s.interval)
        assertEquals(8, s.channels)
        assertEquals(4, s.subframe)
        assertEquals(32, s.bits)
    }

    private fun descriptors(vararg parts: IntArray): ByteArray = parts.flatMap { p ->
        (listOf(p.size + 1) + p.toList()).map(Int::toByte)
    }.toByteArray()

    private fun ep136Like(clockKind: Int = 10, terminalClock: Int = 16, usage: Int = 0): ByteArray = descriptors(
        intArrayOf(2, 0, 0), // configuration stub
        intArrayOf(11, 0, 3, 1, 0, 0, 0), // IAD: AC 0, AS 1 and playback 2
        intArrayOf(4, 0, 0, 0, 1, 1, 0x20, 0), // AC interface
        intArrayOf(0x24, 1, 0, 2, 1, 0, 0, 0), // AC header
        intArrayOf(0x24, 2, 1, 1, 2, 0, 16, 8, 0, 0, 0, 0, 0, 0, 0, 0), // input terminal
        intArrayOf(0x24, 3, 3, 1, 1, 0, 2, terminalClock, 0, 0, 0), // output terminal
        intArrayOf(0x24, clockKind, 16, 1, 7, 0, 0), // clock source, freq R/W + validity read
        intArrayOf(4, 1, 1, 1, 1, 2, 0x20, 0), // AS interface alt 1
        intArrayOf(0x24, 1, 3, 0, 1, 1, 0, 0, 0, 8, 0, 0, 0, 0, 0), // AS_GENERAL PCM
        intArrayOf(0x24, 2, 1, 4, 32), // Type I, 4-byte/32-bit
        intArrayOf(5, 0x82, 0x05 or (usage shl 4), 224, 0, 1), // async data endpoint
        intArrayOf(4, 1, 0, 1, 1, 2, 0x20, 0), // streaming zero-bandwidth alt
        intArrayOf(4, 2, 1, 1, 1, 2, 0x20, 0), // playback interface
        intArrayOf(5, 0x81, 0x11, 4, 0, 1), // explicit feedback endpoint
        intArrayOf(4, 3, 0, 0, 1, 3, 0, 0), // separate MIDI function
        intArrayOf(0x24, 1, 0, 1, 0, 0, 0),
    )

    @Test fun parsesCompositeUac2Capture() {
        val s = UsbRaw.parseAll(ep136Like()).single()
        assertEquals(2, s.protocol)
        assertEquals(0, s.controlIface)
        assertEquals(3, s.terminal)
        assertEquals(16, s.clockId)
        assertEquals(7, s.clockControls)
        assertEquals(1, s.iface)
        assertEquals(1, s.alt)
        assertEquals(0x82, s.ep)
        assertEquals(224, s.maxPacket)
        assertEquals(8, s.channels)
        assertEquals(4, s.subframe)
        assertEquals(32, s.bits)
        assertEquals(1, s.interval)
        assertTrue(UsbRaw.parseAll(ep136Like(usage = 2)).single().ep == 0x82)
    }

    @Test fun rejectsBadUac2TopologyAndTruncation() {
        fun fails(bytes: ByteArray, message: String) {
            try { UsbRaw.parseAll(bytes); fail("expected $message") }
            catch (e: IllegalStateException) { assertTrue(e.message.orEmpty().contains(message)) }
            catch (e: IllegalArgumentException) { assertTrue(e.message.orEmpty().contains(message)) }
        }
        fails(ep136Like(terminalClock = 17), "clock 17 missing")
        fails(ep136Like(clockKind = 11), "selector/multiplier")
        fails(ep136Like().copyOfRange(0, ep136Like().size - 2), "truncated")
    }

    @Test fun clockRequestsAndRateRanges() {
        val s = UsbRaw.parse(ep136Like())!!
        var current = 48000
        val calls = mutableListOf<List<Int>>()
        val control = UsbControl { type, req, value, index, data ->
            calls += listOf(type, req, value, index, data.size)
            when {
                type == 0xA1 && req == 1 && value == 0x0100 -> {
                    for (i in 0..3) data[i] = (current shr (8 * i)).toByte()
                }
                type == 0xA1 && req == 1 && value == 0x0200 -> data[0] = 1
                type == 0xA1 && req == 2 && data.size == 2 -> data[0] = 2
                type == 0xA1 && req == 2 -> {
                    data[0] = 2
                    val values = listOf(44100, 44100, 0, 48000, 96000, 48000)
                    values.forEachIndexed { n, v -> for (i in 0..3) data[2 + n * 4 + i] = (v shr (8 * i)).toByte() }
                }
                type == 0x21 && req == 1 -> current = (data[0].toInt() and 255) or
                    ((data[1].toInt() and 255) shl 8) or ((data[2].toInt() and 255) shl 16)
                else -> error("unexpected request")
            }
            data.size
        }
        val probed = Uac2Clock.probe(control, s)
        assertEquals(listOf(44100, 48000, 96000), probed.rateOptions())
        assertFalse(probed.supports(88200))
        assertEquals(96000, Uac2Clock.configure(control, probed, 96000))
        assertTrue(calls.any { it == listOf(0x21, 1, 0x0100, 0x1000, 4) })
    }

    @Test fun readOnlyClockUsesVerifiedCurrentRate() {
        val s = UsbRaw.parse(ep136Like())!!.copy(clockControls = 5)
        val c = UsbControl { _, request, value, _, data ->
            if (request == 1 && value == 0x0200) data[0] = 1
            if (request == 1 && value == 0x0100) { data[0] = 0x80.toByte(); data[1] = 0xbb.toByte() }
            if (request == 2) return@UsbControl -1
            data.size
        }
        val probed = Uac2Clock.probe(c, s)
        assertEquals(listOf(48000), probed.rateOptions())
        assertEquals(48000, Uac2Clock.configure(c, probed, 48000))
    }

    @Test fun rejectsShortClockResponsesAndInvalidClock() {
        val s = UsbRaw.parse(ep136Like())!!
        val short = UsbControl { _, _, _, _, data -> data.size - 1 }
        try { Uac2Clock.probe(short, s); fail("expected short response") }
        catch (e: IllegalArgumentException) { assertTrue(e.message.orEmpty().contains("returned")) }

        val invalid = UsbControl { _, request, value, _, data ->
            if (request == 1 && value == 0x0200) data[0] = 0
            data.size
        }
        try { Uac2Clock.probe(invalid, s); fail("expected invalid clock") }
        catch (e: IllegalArgumentException) { assertTrue(e.message.orEmpty().contains("invalid")) }
    }

    @Test fun rateRangeDoesNotOfferUnalignedMaximum() {
        val range = RateRange(44100, 95000, 44100)
        assertTrue(range.contains(88200))
        assertFalse(range.contains(95000))
        assertEquals(listOf(44100, 88200), range.options())
    }

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
