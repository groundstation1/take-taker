package app.taketaker.audio

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
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
 * How to pull audio straight off a USB Audio Class 1 device: which streaming interface /
 * alt setting / endpoint, and what the PCM in it looks like.
 */
data class RawSpec(
    val device: UsbDevice?,
    val iface: Int,
    val alt: Int,
    val ep: Int,
    val maxPacket: Int,
    val channels: Int,
    val subframe: Int,
    val bits: Int,
    val rates: List<Int>,
) {
    val rate get() = rates.maxOrNull() ?: 48000
}

object UsbRaw {
    /** Devices with a class-compliant audio streaming interface that has an iso IN endpoint. */
    fun candidates(um: UsbManager): List<UsbDevice> = um.deviceList.values.filter(::hasAudioIn)

    fun hasAudioIn(d: UsbDevice): Boolean = (0 until d.interfaceCount).any { i ->
        val f = d.getInterface(i)
        f.interfaceClass == UsbConstants.USB_CLASS_AUDIO && f.interfaceSubclass == 2 &&
            (0 until f.endpointCount).any { e ->
                val ep = f.getEndpoint(e)
                ep.direction == UsbConstants.USB_DIR_IN && ep.type == UsbConstants.USB_ENDPOINT_XFER_ISOC
            }
    }

    /** Needs USB permission (reads class-specific descriptors). */
    fun probe(um: UsbManager, d: UsbDevice): RawSpec? {
        val c = um.openDevice(d) ?: return null
        return try { parse(c.rawDescriptors)?.copy(device = d) } finally { c.close() }
    }

    private class Alt(val iface: Int, val alt: Int) {
        var channels = 0; var subframe = 0; var bits = 0
        var rates = emptyList<Int>()
        var ep = -1; var maxPacket = 0
    }

    /** UAC1 only; UAC2 devices return null and stay on the system path. */
    fun parse(b: ByteArray): RawSpec? {
        val alts = mutableListOf<Alt>()
        var cur: Alt? = null
        var ifClass = 0; var ifSub = 0
        var uac2 = false
        var i = 0
        fun u8(k: Int) = b[k].toInt() and 0xFF
        while (i + 1 < b.size) {
            val len = u8(i)
            if (len < 2 || i + len > b.size) break
            val type = u8(i + 1)
            when (type) {
                4 -> { // interface
                    ifClass = u8(i + 5); ifSub = u8(i + 6)
                    cur = if (ifClass == 1 && ifSub == 2 && u8(i + 3) > 0) Alt(u8(i + 2), u8(i + 3)).also { alts += it } else null
                }
                0x24 -> {
                    val sub = u8(i + 2)
                    if (ifClass == 1 && ifSub == 1 && sub == 1 && len >= 5) {
                        val bcd = u8(i + 3) or (u8(i + 4) shl 8)
                        if (bcd >= 0x0200) uac2 = true
                    }
                    val a = cur
                    if (a != null && sub == 2 && len >= 8 && u8(i + 3) == 1) { // FORMAT_TYPE_I
                        a.channels = u8(i + 4); a.subframe = u8(i + 5); a.bits = u8(i + 6)
                        val n = u8(i + 7)
                        a.rates = if (n == 0 && len >= 14) listOf(rate3(b, i + 8), rate3(b, i + 11)) // continuous: lo, hi
                        else (0 until n).filter { i + 8 + it * 3 + 2 < i + len }.map { rate3(b, i + 8 + it * 3) }
                    }
                }
                5 -> { // endpoint
                    val a = cur
                    val addr = u8(i + 2); val attr = u8(i + 3)
                    if (a != null && addr and 0x80 != 0 && attr and 3 == 1 && a.ep < 0) {
                        a.ep = addr
                        a.maxPacket = (u8(i + 4) or (u8(i + 5) shl 8)) and 0x7FF
                    }
                }
            }
            i += len
        }
        if (uac2) return null
        val best = alts.filter { it.ep >= 0 && it.channels > 0 && it.subframe in 2..4 }
            .maxWithOrNull(compareBy<Alt> { it.channels }.thenBy { it.bits }) ?: return null
        return RawSpec(null, best.iface, best.alt, best.ep, best.maxPacket, best.channels, best.subframe, best.bits, best.rates)
    }

    private fun rate3(b: ByteArray, k: Int) =
        (b[k].toInt() and 0xFF) or ((b[k + 1].toInt() and 0xFF) shl 8) or ((b[k + 2].toInt() and 0xFF) shl 16)
}
