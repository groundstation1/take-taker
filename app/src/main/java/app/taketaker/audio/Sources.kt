package app.taketaker.audio

import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager

/** One physical input we can capture from. [channels] is how many we'll open it with. */
data class Source(
    val id: Int,
    val name: String,
    val kind: Kind,
    val channels: Int,
    val nativeRate: Int,
    val hiRes: Boolean,
    val device: AudioDeviceInfo,
) {
    enum class Kind(val label: String) { USB("usb"), MIC("mic"), JACK("jack"), LINE("line") }

    /** Short lowercase handle used for default track names, e.g. "melodee". */
    val handle: String
        get() = when (kind) {
            Kind.MIC -> "mic"
            else -> name.lowercase().split(' ', '-', '_').firstOrNull { it.isNotBlank() } ?: kind.label
        }
}

object Sources {
    private val hiResEncodings = setOf(
        AudioFormat.ENCODING_PCM_24BIT_PACKED,
        AudioFormat.ENCODING_PCM_32BIT,
        AudioFormat.ENCODING_PCM_FLOAT,
    )

    fun scan(am: AudioManager): List<Source> =
        am.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .filter { it.isSource }
            .mapNotNull(::describe)
            .sortedBy { it.kind.ordinal }

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
            Source.Kind.MIC -> "Phone mic" + d.address.takeIf { it.isNotBlank() && it.length < 16 }?.let { " · $it" }.orEmpty()
            else -> d.productName?.toString()?.removePrefix("USB-Audio - ")?.takeIf { it.isNotBlank() } ?: kind.label.uppercase()
        }
        return Source(
            id = d.id,
            name = name,
            kind = kind,
            channels = channels,
            nativeRate = nativeRate,
            hiRes = d.encodings.any { it in hiResEncodings },
            device = d,
        )
    }
}
