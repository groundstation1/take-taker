package javax.sound.sampled;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Minimal stand-in for the desktop-only javax.sound class. Android has no javax.sound,
 * but jump3r's LameEncoder only needs these few accessors to describe its PCM input.
 */
public class AudioFormat {
    public static class Encoding {
        public static final Encoding PCM_SIGNED = new Encoding("PCM_SIGNED");
        public static final Encoding PCM_UNSIGNED = new Encoding("PCM_UNSIGNED");
        public static final Encoding PCM_FLOAT = new Encoding("PCM_FLOAT");
        private final String name;
        public Encoding(String name) { this.name = name; }
        @Override public final boolean equals(Object o) { return o instanceof Encoding && name.equals(o.toString()); }
        @Override public final int hashCode() { return name.hashCode(); }
        @Override public final String toString() { return name; }
    }

    protected Encoding encoding;
    protected float sampleRate;
    protected int sampleSizeInBits;
    protected int channels;
    protected int frameSize;
    protected float frameRate;
    protected boolean bigEndian;
    private final Map<String, Object> props;

    public AudioFormat(Encoding encoding, float sampleRate, int sampleSizeInBits, int channels,
                       int frameSize, float frameRate, boolean bigEndian, Map<String, Object> properties) {
        this.encoding = encoding;
        this.sampleRate = sampleRate;
        this.sampleSizeInBits = sampleSizeInBits;
        this.channels = channels;
        this.frameSize = frameSize;
        this.frameRate = frameRate;
        this.bigEndian = bigEndian;
        this.props = properties == null ? new HashMap<>() : new HashMap<>(properties);
    }

    public AudioFormat(Encoding encoding, float sampleRate, int sampleSizeInBits, int channels,
                       int frameSize, float frameRate, boolean bigEndian) {
        this(encoding, sampleRate, sampleSizeInBits, channels, frameSize, frameRate, bigEndian, null);
    }

    public AudioFormat(float sampleRate, int sampleSizeInBits, int channels, boolean signed, boolean bigEndian) {
        this(signed ? Encoding.PCM_SIGNED : Encoding.PCM_UNSIGNED, sampleRate, sampleSizeInBits, channels,
                channels * ((sampleSizeInBits + 7) / 8), sampleRate, bigEndian, null);
    }

    public Encoding getEncoding() { return encoding; }
    public float getSampleRate() { return sampleRate; }
    public int getSampleSizeInBits() { return sampleSizeInBits; }
    public int getChannels() { return channels; }
    public int getFrameSize() { return frameSize; }
    public float getFrameRate() { return frameRate; }
    public boolean isBigEndian() { return bigEndian; }
    public Map<String, Object> properties() { return Collections.unmodifiableMap(props); }
    public Object getProperty(String key) { return props.get(key); }
}
