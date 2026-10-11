package app.taketaker.audio;

import de.sciss.jump3r.lowlevel.LameEncoder;
import javax.sound.sampled.AudioFormat;

/** Builds a LAME encoder for 16-bit little-endian interleaved stereo. Kept in Java so the javax stub stays local. */
public final class Mp3 {
    private Mp3() { }

    public static LameEncoder stereoEncoder(int sampleRate, int kbps) {
        AudioFormat fmt = new AudioFormat(sampleRate, 16, 2, true, false);
        return new LameEncoder(fmt, kbps, LameEncoder.CHANNEL_MODE_JOINT_STEREO, LameEncoder.QUALITY_MIDDLE, false);
    }
}
