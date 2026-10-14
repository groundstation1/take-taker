package app.taketaker.audio

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager

/** JNI side, see src/main/cpp/usbiso.c. */
object UsbIso {
    init { System.loadLibrary("usbiso") }

    /** Starts streaming; returns a handle, or -errno (-4095..-1; handles are tagged pointers and can be negative too). */
    @JvmStatic external fun open(fd: Int, ep: Int, pktSize: Int, pkts: Int, nUrbs: Int): Long
    /** Blocks for one batch of packets; returns bytes, or -errno. */
    @JvmStatic external fun read(handle: Long, out: ByteArray): Int
    @JvmStatic external fun close(handle: Long)
    @JvmStatic external fun reattach(fd: Int, iface: Int): Int
}

/**
 * How to pull audio straight off a USB Audio Class device: which streaming interface /
 * alt setting / endpoint, and what the PCM in it looks like.
 *
 * UAC2 adds a clock entity: rates aren't listed in the streaming descriptors but asked of the
 * clock ([clockId] on the control interface [acIface]), and set on it before streaming.
 */
data class RawSpec(
    val device: UsbDevice?,
    val iface: Int,
    val alt: Int,
    val ep: Int,
    /** Bytes per (micro)frame, including high-bandwidth extra transactions. */
    val maxPacket: Int,
    val channels: Int,
    val subframe: Int,
    val bits: Int,
    val rates: List<Int>,
    val uac2: Boolean = false,
    val clockId: Int = -1,
    val acIface: Int = -1,
) {
    /** UAC1: the device's highest rate. UAC2: 48k / 44.1k when offered (high rates are heavy), else the highest. */
    val rate: Int
        get() = if (uac2) PREFERRED.firstOrNull { it in rates } ?: rates.maxOrNull() ?: 48000
        else rates.maxOrNull() ?: 48000

    private companion object {
        val PREFERRED = listOf(48000, 44100)
    }
}

object UsbRaw {
    /** Devices with a class-compliant audio streaming interface that has an iso IN endpoint. */
    fun candidates(um: UsbManager): List<UsbDevice> = um.deviceList.values.filter(::hasAudioIn)

    fun hasAudioIn(d: UsbDevice): Boolean = (0 until d.interfaceCount).any { i ->
        val f = d.getInterface(i)
        f.interfaceClass == UsbConstants.USB_CLASS_AUDIO && f.interfaceSubclass == 2 &&
            (0 until f.endpointCount).any { e ->
                val ep = f.getEndpoint(e)
                ep.direction == UsbConstants.USB_DIR_IN && ep.type == UsbConstants.USB_ENDPOINT_XFER_ISOC &&
                    (ep.attributes shr 4) and 3 != 1 // not a feedback endpoint
            }
    }

    /** Every usable capture format, best first. Needs USB permission (reads class-specific descriptors). */
    fun probe(um: UsbManager, d: UsbDevice): List<RawSpec> {
        val c = um.openDevice(d) ?: return emptyList()
        return try {
            parseAll(c.rawDescriptors).map { s ->
                val withDev = s.copy(device = d)
                if (s.uac2) withDev.copy(rates = clockRates(c, d, s).ifEmpty { listOf(48000) }) else withDev
            }
        } finally { c.close() }
    }

    /** The widest / deepest format. */
    fun parse(b: ByteArray): RawSpec? = parseAll(b).firstOrNull()

    private class Alt(val iface: Int, val alt: Int, val uac2: Boolean) {
        var channels = 0; var subframe = 0; var bits = 0
        var rates = emptyList<Int>()
        var ep = -1; var maxPacket = 0
        var terminal = -1
    }

    fun parseAll(b: ByteArray): List<RawSpec> {
        val alts = mutableListOf<Alt>()
        var cur: Alt? = null
        var ifNum = 0; var ifClass = 0; var ifSub = 0
        var uac2 = false
        var acIface = -1
        val terminalClock = HashMap<Int, Int>() // UAC2 terminal id → clock entity id
        val selectorFirst = HashMap<Int, Int>() // UAC2 clock selector id → its first input
        val clockSources = mutableListOf<Int>()
        var i = 0
        fun u8(k: Int) = b[k].toInt() and 0xFF
        while (i + 1 < b.size) {
            val len = u8(i)
            if (len < 2 || i + len > b.size) break
            val type = u8(i + 1)
            when (type) {
                4 -> { // interface
                    ifNum = u8(i + 2); ifClass = u8(i + 5); ifSub = u8(i + 6)
                    cur = if (ifClass == 1 && ifSub == 2 && u8(i + 3) > 0) Alt(ifNum, u8(i + 3), uac2).also { alts += it } else null
                }
                0x24 -> {
                    val sub = u8(i + 2)
                    if (ifClass == 1 && ifSub == 1) { // audio control
                        when {
                            sub == 1 && len >= 5 -> { // header
                                val bcd = u8(i + 3) or (u8(i + 4) shl 8)
                                if (bcd >= 0x0200) { uac2 = true; acIface = ifNum }
                            }
                            uac2 && sub == 2 && len >= 8 -> terminalClock[u8(i + 3)] = u8(i + 7) // input terminal
                            uac2 && sub == 3 && len >= 9 -> terminalClock[u8(i + 3)] = u8(i + 8) // output terminal
                            uac2 && sub == 0x0A && len >= 4 -> clockSources += u8(i + 3)
                            uac2 && sub == 0x0B && len >= 6 -> selectorFirst[u8(i + 3)] = u8(i + 5)
                        }
                    }
                    val a = cur
                    if (a != null && a.uac2) {
                        if (sub == 1 && len >= 11) { a.terminal = u8(i + 3); a.channels = u8(i + 10) } // AS_GENERAL
                        if (sub == 2 && len >= 6 && u8(i + 3) == 1) { a.subframe = u8(i + 4); a.bits = u8(i + 5) } // FORMAT_TYPE_I
                    } else if (a != null && sub == 2 && len >= 8 && u8(i + 3) == 1) { // UAC1 FORMAT_TYPE_I
                        a.channels = u8(i + 4); a.subframe = u8(i + 5); a.bits = u8(i + 6)
                        val n = u8(i + 7)
                        a.rates = if (n == 0 && len >= 14) listOf(rate3(b, i + 8), rate3(b, i + 11)) // continuous: lo, hi
                        else (0 until n).filter { i + 8 + it * 3 + 2 < i + len }.map { rate3(b, i + 8 + it * 3) }
                    }
                }
                5 -> { // endpoint
                    val a = cur
                    val addr = u8(i + 2); val attr = u8(i + 3)
                    // iso IN data endpoint; skip explicit-feedback endpoints (usage bits 01), which playback interfaces have
                    if (a != null && addr and 0x80 != 0 && attr and 3 == 1 && (attr shr 4) and 3 != 1 && a.ep < 0) {
                        a.ep = addr
                        val w = u8(i + 4) or (u8(i + 5) shl 8)
                        a.maxPacket = (w and 0x7FF) * (1 + ((w shr 11) and 3)) // high-bandwidth: extra transactions
                    }
                }
            }
            i += len
        }
        fun clockFor(a: Alt): Int {
            var c = terminalClock[a.terminal] ?: clockSources.firstOrNull() ?: -1
            selectorFirst[c]?.let { c = it } // selector → its first source
            return c
        }
        return alts.filter { it.ep >= 0 && it.channels > 0 && it.subframe in 2..4 }
            .sortedWith(compareByDescending<Alt> { it.channels }.thenByDescending { it.bits })
            .map {
                RawSpec(
                    null, it.iface, it.alt, it.ep, it.maxPacket, it.channels, it.subframe, it.bits, it.rates,
                    uac2 = it.uac2, clockId = if (it.uac2) clockFor(it) else -1, acIface = if (it.uac2) acIface else -1,
                )
            }
    }

    // ---- UAC2 clock ----------------------------------------------------------

    private const val CS_SAM_FREQ = 0x0100 // CS_SAM_FREQ_CONTROL << 8
    private val COMMON_RATES = listOf(44100, 48000, 88200, 96000, 176400, 192000)

    /**
     * Asks the clock which rates it does (GET RANGE). Class requests to the control interface
     * need it claimed, which detaches the kernel driver; it's handed back afterwards.
     */
    private fun clockRates(c: UsbDeviceConnection, d: UsbDevice, s: RawSpec): List<Int> {
        val ac = (0 until d.interfaceCount).map { d.getInterface(it) }
            .firstOrNull { it.id == s.acIface && it.alternateSetting == 0 } ?: return emptyList()
        if (!c.claimInterface(ac, true)) return emptyList()
        return try {
            val buf = ByteArray(2 + 12 * 32)
            val n = c.controlTransfer(0xA1, 0x02, CS_SAM_FREQ, (s.clockId shl 8) or s.acIface, buf, buf.size, 500)
            if (n < 2) emptyList() else parseRanges(buf.copyOf(n))
        } finally {
            c.releaseInterface(ac)
            UsbIso.reattach(c.fileDescriptor, s.acIface)
        }
    }

    /** Sets the UAC2 clock (SET CUR). Caller has the control interface claimed. */
    fun setClock(c: UsbDeviceConnection, s: RawSpec, rate: Int): Boolean {
        val data = ByteArray(4) { (rate shr (8 * it)).toByte() }
        return c.controlTransfer(0x21, 0x01, CS_SAM_FREQ, (s.clockId shl 8) or s.acIface, data, 4, 500) == 4
    }

    /** Layout-3 RANGE block: wNumSubRanges, then (dMIN, dMAX, dRES) per subrange, little endian. */
    fun parseRanges(b: ByteArray): List<Int> {
        fun u32(k: Int) = (b[k].toInt() and 0xFF) or ((b[k + 1].toInt() and 0xFF) shl 8) or
            ((b[k + 2].toInt() and 0xFF) shl 16) or ((b[k + 3].toInt() and 0xFF) shl 24)
        if (b.size < 2) return emptyList()
        val n = (b[0].toInt() and 0xFF) or ((b[1].toInt() and 0xFF) shl 8)
        val out = sortedSetOf<Int>()
        for (r in 0 until n) {
            val k = 2 + r * 12
            if (k + 12 > b.size) break
            val lo = u32(k); val hi = u32(k + 4); val res = u32(k + 8)
            if (lo == hi) out += lo
            else COMMON_RATES.filter { it in lo..hi && (res == 0 || (it - lo) % res == 0) }.forEach { out += it }
        }
        return out.toList()
    }

    private fun rate3(b: ByteArray, k: Int) =
        (b[k].toInt() and 0xFF) or ((b[k + 1].toInt() and 0xFF) shl 8) or ((b[k + 2].toInt() and 0xFF) shl 16)
}
