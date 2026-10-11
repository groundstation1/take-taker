package app.taketaker.audio

/** A lane: one (mono) or two (stereo) global engine channels. */
data class Track(
    val name: String,
    val chans: List<Int>,
    val color: Int,
    val muted: Boolean = false,
) {
    val stereo get() = chans.size == 2
}

object Mix {
    private const val PAN_LAW = 0.70710677f

    /** Sums unmuted tracks into interleaved stereo [out]. Mono tracks sit center at -3 dB. */
    fun mix(tracks: List<Track>, ch: Array<FloatArray>, n: Int, out: FloatArray, gain: Float = 1f) {
        java.util.Arrays.fill(out, 0, n * 2, 0f)
        for (t in tracks) {
            if (t.muted) continue
            if (t.stereo) {
                val l = ch[t.chans[0]]; val r = ch[t.chans[1]]
                for (i in 0 until n) { out[2 * i] += l[i]; out[2 * i + 1] += r[i] }
            } else {
                val m = ch[t.chans[0]]
                for (i in 0 until n) { val v = m[i] * PAN_LAW; out[2 * i] += v; out[2 * i + 1] += v }
            }
        }
        if (gain != 1f) for (i in 0 until n * 2) out[i] *= gain
    }
}
