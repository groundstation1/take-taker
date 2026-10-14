package app.taketaker.audio

/** Decode signed, left-aligned little-endian Type I PCM into an existing buffer. */
object PcmDecoder {
    fun decode(bytes: ByteArray, size: Int, subslot: Int, channels: Int, out: FloatArray): Int {
        require(subslot in 2..4 && channels > 0)
        val frameBytes = subslot * channels
        require(size >= 0 && size <= bytes.size && size % frameBytes == 0) { "USB packet is not whole PCM frames" }
        val samples = size / subslot
        require(out.size >= samples)
        val scale = when (subslot) {
            2 -> 1f / 32768f
            3 -> 1f / 8388608f
            else -> 1f / 2147483648f
        }
        var p = 0
        for (k in 0 until samples) {
            val v = when (subslot) {
                2 -> (bytes[p].toInt() and 255) or (bytes[p + 1].toInt() shl 8)
                3 -> (bytes[p].toInt() and 255) or ((bytes[p + 1].toInt() and 255) shl 8) or
                    (bytes[p + 2].toInt() shl 16)
                else -> (bytes[p].toInt() and 255) or ((bytes[p + 1].toInt() and 255) shl 8) or
                    ((bytes[p + 2].toInt() and 255) shl 16) or (bytes[p + 3].toInt() shl 24)
            }
            out[k] = v * scale
            p += subslot
        }
        return size / frameBytes
    }
}
