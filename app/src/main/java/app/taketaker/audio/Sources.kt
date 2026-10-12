package app.taketaker.audio

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.MediaRecorder

/**
 * One physical input. A USB interface can be reachable two ways: through Android's audio
 * stack ([device], "system") and/or directly over USB ([usb], "raw"). [raw] says which
 * one this instance will be captured with; [channels]/[nativeRate]/[hiRes] follow it.
 */
data class Source(
    val key: String,
    val name: String,
    val kind: Kind,
    val channels: Int,
    val nativeRate: Int,
    val hiRes: Boolean,
    val device: AudioDeviceInfo?,
    val usbDevice: UsbDevice? = null,
    val usb: RawSpec? = null,
    val raw: Boolean = false,
    val mic: MicMode? = null,
) {
    enum class Kind(val label: String) { USB("usb"), MIC("mic"), JACK("jack"), LINE("line") }

    val canRaw get() = usbDevice != null
    val canSystem get() = device != null

    /** Short lowercase handle used for default track names, e.g. "melodee". */
    val handle: String
        get() = when (kind) {
            Kind.MIC -> "mic"
            else -> name.lowercase().split(' ', '-', '_').firstOrNull { it.isNotBlank() } ?: kind.label
        }

    /** This source as captured raw, once its descriptors are known. */
    fun asRaw(spec: RawSpec) = copy(
        usb = spec, raw = true, channels = spec.channels, nativeRate = spec.rate, hiRes = spec.bits > 16,
    )
}

/**
 * The built-in mics are one input to an app; what differs is the recording preset, i.e. which
 * capsules are combined and what processing the vendor applies on top.
 */
enum class MicMode(val label: String, val blurb: String, val channels: Int, val presets: List<Int>) {
    STEREO("stereo", "dual mic · general", 2, listOf(MediaRecorder.AudioSource.CAMCORDER)),
    VOICE("voice", "speech · noise suppressed", 1, listOf(MediaRecorder.AudioSource.VOICE_COMMUNICATION)),
    RAW("raw", "mono · no processing", 1, listOf(MediaRecorder.AudioSource.UNPROCESSED, MediaRecorder.AudioSource.VOICE_RECOGNITION)),
}

object Sources {
    private val hiResEncodings = setOf(
        AudioFormat.ENCODING_PCM_24BIT_PACKED,
        AudioFormat.ENCODING_PCM_32BIT,
        AudioFormat.ENCODING_PCM_FLOAT,
    )

    /** Everything Android's audio stack offers, merged with raw-capable USB devices. */
    fun scan(am: AudioManager, um: UsbManager): List<Source> {
        val described = am.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .filter { it.isSource }
            .mapNotNull(::describe)
        // Android hands one app a single built-in-mic input no matter which capsule it asks for,
        // so separate "bottom"/"back" sources would just record the same stream. Offer one.
        val mics = described.filter { it.kind == Source.Kind.MIC }
        val system = described.filter { it.kind != Source.Kind.MIC } +
            listOfNotNull(mics.firstOrNull()?.copy(channels = mics.maxOf { it.channels }))
        val usb = UsbRaw.candidates(um)
        val out = system.map { s ->
            val match = if (s.kind == Source.Kind.USB) usb.firstOrNull { usbName(it) == s.name } else null
            if (match != null) s.copy(key = usbKey(match), usbDevice = match) else s
        }.toMutableList()
        // raw-capable devices Android isn't routing (e.g. still detached from a previous raw session)
        for (d in usb) if (out.none { it.usbDevice == d }) {
            out += Source(usbKey(d), usbName(d), Source.Kind.USB, 2, 48000, false, null, usbDevice = d)
        }
        return out.sortedBy { it.kind.ordinal }
    }

    private fun usbKey(d: UsbDevice) = "usb:${d.vendorId}:${d.productId}:${d.deviceName}"
    private fun usbName(d: UsbDevice) = d.productName?.takeIf { it.isNotBlank() } ?: "USB audio"

    private fun describe(d: AudioDeviceInfo): Source? {
        val kind = when (d.type) {
            AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_ACCESSORY -> Source.Kind.USB
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> Source.Kind.MIC
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> Source.Kind.JACK
            AudioDeviceInfo.TYPE_LINE_ANALOG, AudioDeviceInfo.TYPE_LINE_DIGITAL -> Source.Kind.LINE
            else -> return null
        }
        val maxCounts = maxOf(
            d.channelCounts.maxOrNull() ?: 0,
            d.channelIndexMasks.maxOfOrNull { Integer.bitCount(it) } ?: 0,
        )
        val channels = when (kind) {
            Source.Kind.MIC, Source.Kind.JACK -> maxCounts.coerceIn(1, 2)
            else -> if (maxCounts == 0) 2 else maxCounts.coerceAtMost(16)
        }
        val rates = d.sampleRates.toList()
        val nativeRate = when {
            rates.isEmpty() -> 48000
            kind == Source.Kind.MIC -> rates.filter { it <= 48000 }.maxOrNull() ?: 48000
            else -> rates.max()
        }
        val name = when (kind) {
            Source.Kind.MIC -> "Phone mic"
            else -> d.productName?.toString()?.removePrefix("USB-Audio - ")?.takeIf { it.isNotBlank() } ?: kind.label.uppercase()
        }
        return Source(
            key = "audio:${d.id}",
            name = name,
            kind = kind,
            channels = channels,
            nativeRate = nativeRate,
            hiRes = d.encodings.any { it in hiResEncodings },
            device = d,
        )
    }
}
