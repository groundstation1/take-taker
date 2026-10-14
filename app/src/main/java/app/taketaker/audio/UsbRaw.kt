package app.taketaker.audio

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager

/** JNI side, see src/main/cpp/usbiso.c. The reader owns a handle until read returns. */
object UsbIso {
    init { System.loadLibrary("usbiso") }

    @JvmStatic external fun open(fd: Int, ep: Int, pktSize: Int, pkts: Int, nUrbs: Int): Long
    /** One batch of whole packets; negative errno on failure. */
    @JvmStatic external fun read(handle: Long, out: ByteArray, frameBytes: Int): Int
    @JvmStatic external fun cancel(handle: Long)
    @JvmStatic external fun close(handle: Long)
    @JvmStatic external fun reattach(fd: Int, iface: Int): Int
}

data class RateRange(val min: Int, val max: Int, val step: Int) {
    fun contains(rate: Int) = rate in min..max && (step == 0 || (rate - min) % step == 0)
    fun options(): List<Int> {
        if (min == max) return listOf(min)
        val common = listOf(44100, 48000, 88200, 96000, 176400, 192000)
        return (listOf(min) + listOf(max).filter(::contains) + common.filter(::contains)).distinct().sorted()
    }
}

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
    val protocol: Int = 1,
    val controlIface: Int = -1,
    val terminal: Int = 0,
    val clockId: Int = 0,
    val clockControls: Int = 0,
    val rateRanges: List<RateRange> = emptyList(),
    val currentRate: Int = 0,
    val interval: Int = 0,
) {
    val rate get() = currentRate.takeIf { it > 0 } ?: rates.maxOrNull() ?: rateRanges.maxOfOrNull { it.max } ?: 48000
    fun supports(rate: Int) = rate in rates || rateRanges.any { it.contains(rate) }
    fun rateOptions(): List<Int> = (rates + rateRanges.flatMap { it.options() }).distinct().sorted()
    val clockWritable get() = clockControls and 3 == 3
    val clockValidityReadable get() = (clockControls shr 2) and 3 != 0
}

fun interface UsbControl {
    fun transfer(requestType: Int, request: Int, value: Int, index: Int, data: ByteArray): Int
}

/** UAC2 class/interface clock requests. All responses are checked before use. */
object Uac2Clock {
    private const val TIMEOUT_MS = 500
    fun control(conn: UsbDeviceConnection) = UsbControl { type, request, value, index, data ->
        conn.controlTransfer(type, request, value, index, data, data.size, TIMEOUT_MS)
    }

    private fun index(s: RawSpec) = (s.clockId shl 8) or s.controlIface
    private fun read(c: UsbControl, s: RawSpec, selector: Int, request: Int, size: Int): ByteArray {
        val data = ByteArray(size)
        val got = c.transfer(0xA1, request, selector shl 8, index(s), data)
        require(got == size) { "clock ${s.clockId}: request $request returned $got/$size bytes" }
        return data
    }
    private fun u16(b: ByteArray, i: Int) = (b[i].toInt() and 255) or ((b[i + 1].toInt() and 255) shl 8)
    private fun u32(b: ByteArray, i: Int): Long =
        (b[i].toLong() and 255) or ((b[i + 1].toLong() and 255) shl 8) or
            ((b[i + 2].toLong() and 255) shl 16) or ((b[i + 3].toLong() and 255) shl 24)

    fun current(c: UsbControl, s: RawSpec): Int {
        val rate = u32(read(c, s, 1, 1, 4), 0)
        require(rate in 8000..384000) { "clock ${s.clockId}: invalid rate $rate" }
        return rate.toInt()
    }

    fun ranges(c: UsbControl, s: RawSpec): List<RateRange> {
        val count = u16(read(c, s, 1, 2, 2), 0)
        require(count in 1..64) { "clock ${s.clockId}: invalid range count $count" }
        val b = read(c, s, 1, 2, 2 + count * 12)
        require(u16(b, 0) == count) { "clock ${s.clockId}: range count changed" }
        return (0 until count).map { n ->
            val i = 2 + n * 12
            val lo = u32(b, i); val hi = u32(b, i + 4); val step = u32(b, i + 8)
            require(lo in 8000..384000 && hi in lo..384000 && step <= 384000) {
                "clock ${s.clockId}: invalid rate range"
            }
            RateRange(lo.toInt(), hi.toInt(), step.toInt())
        }
    }

    fun checkValid(c: UsbControl, s: RawSpec) {
        if (s.clockValidityReadable) require(read(c, s, 2, 1, 1)[0].toInt() != 0) {
            "clock ${s.clockId} is invalid"
        }
    }

    fun probe(c: UsbControl, s: RawSpec): RawSpec {
        require(s.clockControls and 3 != 0) { "clock ${s.clockId} has no readable frequency" }
        checkValid(c, s)
        val now = current(c, s)
        val ranges = runCatching { ranges(c, s) }.getOrElse {
            if (s.clockWritable) throw IllegalStateException("clock ${s.clockId}: ${it.message}", it)
            listOf(RateRange(now, now, 0))
        }
        require(ranges.any { it.contains(now) }) { "clock ${s.clockId}: current rate outside advertised ranges" }
        return s.copy(rateRanges = if (s.clockWritable) ranges else listOf(RateRange(now, now, 0)), currentRate = now)
    }

    fun configure(c: UsbControl, s: RawSpec, requested: Int): Int {
        checkValid(c, s)
        val now = current(c, s)
        require(s.supports(requested)) { "clock ${s.clockId}: $requested Hz unsupported" }
        if (now != requested) {
            require(s.clockWritable) { "clock ${s.clockId} is read-only at $now Hz" }
            val b = byteArrayOf(requested.toByte(), (requested shr 8).toByte(),
                (requested shr 16).toByte(), (requested shr 24).toByte())
            val got = c.transfer(0x21, 1, 0x0100, index(s), b)
            require(got == 4) { "clock ${s.clockId}: SET_CUR returned $got/4 bytes" }
        }
        val verified = current(c, s)
        require(verified == requested) { "clock ${s.clockId}: requested $requested Hz, got $verified Hz" }
        checkValid(c, s)
        return verified
    }
}

object UsbRaw {
    fun candidates(um: UsbManager): List<UsbDevice> = um.deviceList.values.filter(::hasAudioIn)

    fun hasAudioIn(d: UsbDevice): Boolean = (0 until d.interfaceCount).any { i ->
        val f = d.getInterface(i)
        f.interfaceClass == UsbConstants.USB_CLASS_AUDIO && f.interfaceSubclass == 2 &&
            (0 until f.endpointCount).any { e ->
                val ep = f.getEndpoint(e)
                ep.direction == UsbConstants.USB_DIR_IN && ep.type == UsbConstants.USB_ENDPOINT_XFER_ISOC &&
                    (ep.attributes shr 4) and 3 != 1
            }
    }

    fun probe(um: UsbManager, d: UsbDevice): List<RawSpec> {
        val c = um.openDevice(d) ?: error("USB permission or open failed")
        return try {
            parseAll(c.rawDescriptors).map { raw ->
                val s = raw.copy(device = d)
                if (s.protocol == 2) {
                    val control = (0 until d.interfaceCount).map { d.getInterface(it) }
                        .firstOrNull { it.id == s.controlIface && it.alternateSetting == 0 }
                        ?: error("control interface ${s.controlIface} missing")
                    check(c.claimInterface(control, true)) { "couldn't claim USB clock control interface" }
                    try { Uac2Clock.probe(Uac2Clock.control(c), s) }
                    finally {
                        c.releaseInterface(control)
                        UsbIso.reattach(c.fileDescriptor, s.controlIface)
                    }
                } else s
            }
        } finally { c.close() }
    }

    fun parse(b: ByteArray): RawSpec? = parseAll(b).firstOrNull()

    private data class ControlFunction(val iface: Int, val protocol: Int, val entities: MutableMap<Int, Entity> = mutableMapOf())
    private data class Entity(val kind: Int, val clock: Int)
    private class Alt(val iface: Int, val alt: Int, val protocol: Int) {
        var controlIface = -1; var terminal = 0; var clockId = 0; var clockControls = 0
        var formatType = 0; var formats = 0L; var channels = 0; var subframe = 0; var bits = 0
        var rates = emptyList<Int>(); var ranges = emptyList<RateRange>()
        var ep = -1; var maxPacket = 0; var interval = 0
    }

    fun parseAll(b: ByteArray): List<RawSpec> {
        val alts = mutableListOf<Alt>()
        val controls = mutableMapOf<Int, ControlFunction>()
        val associations = mutableListOf<IntRange>()
        var currentIface = -1; var ifClass = 0; var ifSub = 0; var protocol = 0
        var cur: Alt? = null
        fun u8(k: Int) = b[k].toInt() and 255
        fun u16(k: Int) = u8(k) or (u8(k + 1) shl 8)
        fun u32(k: Int) = (u16(k).toLong() or (u16(k + 2).toLong() shl 16))
        var i = 0
        while (i + 1 < b.size) {
            val len = u8(i)
            require(len >= 2 && i + len <= b.size) { "truncated USB descriptor at $i" }
            when (u8(i + 1)) {
                11 -> if (len >= 8 && u8(i + 4) == 1) associations += u8(i + 2) until (u8(i + 2) + u8(i + 3))
                4 -> {
                    require(len >= 9) { "short interface descriptor at $i" }
                    currentIface = u8(i + 2); ifClass = u8(i + 5); ifSub = u8(i + 6); protocol = u8(i + 7)
                    cur = if (ifClass == 1 && ifSub == 2 && u8(i + 3) > 0) {
                        Alt(currentIface, u8(i + 3), protocol).also { alts += it }
                    } else null
                    if (ifClass == 1 && ifSub == 1) controls.getOrPut(currentIface) {
                        ControlFunction(currentIface, protocol)
                    }
                }
                0x24 -> if (len >= 3) {
                    val sub = u8(i + 2)
                    if (ifClass == 1 && ifSub == 1) {
                        val ac = controls[currentIface]!!
                        when (sub) {
                            2 -> if (len >= 17 && ac.protocol == 0x20) ac.entities[u8(i + 3)] = Entity(sub, u8(i + 7))
                            3 -> if (len >= 12 && ac.protocol == 0x20) ac.entities[u8(i + 3)] = Entity(sub, u8(i + 8))
                            10 -> if (len >= 8 && ac.protocol == 0x20) ac.entities[u8(i + 3)] = Entity(sub, u8(i + 5))
                            11, 12 -> if (len >= 6 && ac.protocol == 0x20) ac.entities[u8(i + 3)] = Entity(sub, u8(i + 4))
                        }
                    }
                    val a = cur
                    if (a != null) when (sub) {
                        1 -> if (a.protocol == 0x20) {
                            require(len >= 16) { "short UAC2 AS_GENERAL on interface ${a.iface}" }
                            a.terminal = u8(i + 3); a.formatType = u8(i + 5)
                            a.formats = u32(i + 6); a.channels = u8(i + 10)
                        } else if (len >= 4) a.terminal = u8(i + 3)
                        2 -> if (a.protocol == 0x20) {
                            require(len >= 6) { "short UAC2 FORMAT_TYPE on interface ${a.iface}" }
                            if (u8(i + 3) == 1) { a.subframe = u8(i + 4); a.bits = u8(i + 5) }
                        } else if (len >= 8 && u8(i + 3) == 1) {
                            a.channels = u8(i + 4); a.subframe = u8(i + 5); a.bits = u8(i + 6)
                            val n = u8(i + 7)
                            require(len >= 8 + (if (n == 0) 6 else 3 * n)) { "short UAC1 sample-rate list" }
                            if (n == 0) a.ranges = listOf(RateRange(rate3(b, i + 8), rate3(b, i + 11), 0))
                            else a.rates = (0 until n).map { rate3(b, i + 8 + it * 3) }
                        }
                    }
                }
                5 -> {
                    require(len >= 7) { "short endpoint descriptor at $i" }
                    val a = cur
                    val addr = u8(i + 2); val attr = u8(i + 3)
                    if (a != null && addr and 0x80 != 0 && attr and 3 == 1 &&
                        (attr shr 4) and 3 != 1 && a.ep < 0) {
                        val w = u16(i + 4)
                        require((w shr 11) and 3 != 3) { "reserved high-bandwidth endpoint size" }
                        a.ep = addr; a.maxPacket = (w and 0x7ff) * (1 + ((w shr 11) and 3))
                        a.interval = u8(i + 6)
                    }
                }
            }
            i += len
        }
        return alts.mapNotNull { a ->
            if (a.ep < 0) return@mapNotNull null
            require(a.channels in 1..16 && a.subframe in 2..4 && a.bits in 1..(a.subframe * 8) &&
                a.maxPacket >= a.channels * a.subframe && a.interval in 1..16) {
                "invalid PCM format or packet size on interface ${a.iface}/${a.alt}"
            }
            if (a.protocol == 0x20) {
                require(a.formatType == 1 && a.formats and 1L != 0L && a.formats and 0xfffffffeL == 0L) {
                    "interface ${a.iface}/${a.alt}: unsupported UAC2 format"
                }
                val group = associations.firstOrNull { a.iface in it }
                val ac = controls.values.filter { it.iface < a.iface && it.protocol == 0x20 &&
                    (group == null || it.iface in group) }.maxByOrNull { it.iface }
                    ?: error("interface ${a.iface}: missing UAC2 control interface")
                val terminal = ac.entities[a.terminal] ?: error("interface ${a.iface}: terminal ${a.terminal} missing")
                require(terminal.kind == 3) { "interface ${a.iface}: capture terminal ${a.terminal} is not output" }
                val clock = ac.entities[terminal.clock] ?: error("terminal ${a.terminal}: clock ${terminal.clock} missing")
                require(clock.kind == 10) { "clock ${terminal.clock}: selector/multiplier topology unsupported" }
                a.controlIface = ac.iface; a.clockId = terminal.clock; a.clockControls = clock.clock
                require(a.clockControls and 3 != 0) { "clock ${a.clockId}: frequency control unreadable" }
            } else if (a.protocol != 0) return@mapNotNull null
            RawSpec(null, a.iface, a.alt, a.ep, a.maxPacket, a.channels, a.subframe, a.bits,
                a.rates, if (a.protocol == 0x20) 2 else 1, a.controlIface, a.terminal,
                a.clockId, a.clockControls, a.ranges, interval = a.interval)
        }.sortedWith(compareByDescending<RawSpec> { it.channels }.thenByDescending { it.bits })
    }

    private fun rate3(b: ByteArray, k: Int) =
        (b[k].toInt() and 255) or ((b[k + 1].toInt() and 255) shl 8) or ((b[k + 2].toInt() and 255) shl 16)
}
