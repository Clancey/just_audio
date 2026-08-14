package com.ryanheise.just_audio;

import androidx.media3.exoplayer.audio.TeeAudioProcessor.AudioBufferSink;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A process-wide hook for observing decoded PCM on its way to the audio device.
 *
 * <p>Android offers no way to read an app's own playback without the {@code RECORD_AUDIO}
 * permission, so an app that wants to draw a spectrum of what it is playing has to take the samples
 * before they leave the player. Buffers are delivered on ExoPlayer's audio thread: a listener must
 * return promptly and must not block, allocate heavily, or retain the buffer.
 */
public final class PcmTap {
    /** Receives the decoded PCM of every {@link AudioPlayer} in this process. */
    public interface Listener {
        /**
         * Called when the sample format changes, including once before the first buffer.
         *
         * @param sourceId Identifies the player the samples came from. Several players can be
         *     audible at once — a crossfade is two — and each has its own thread, format and
         *     timeline, so a listener must keep their samples apart rather than pooling them.
         * @param encoding One of the {@code androidx.media3.common.C.ENCODING_PCM_*} constants.
         */
        void onPcmFormat(int sourceId, int sampleRateHz, int channelCount, int encoding);

        /**
         * Called for each buffer of interleaved samples.
         *
         * @param buffer A read-only buffer that is only valid for the duration of this call.
         */
        void onPcmBuffer(
                int sourceId, ByteBuffer buffer, int sampleRateHz, int channelCount, int encoding);
    }

    private static final AtomicInteger nextSourceId = new AtomicInteger(1);

    private static volatile Listener listener;

    private PcmTap() {}

    /** Installs the listener, or clears it when {@code value} is null. */
    public static void setListener(Listener value) {
        listener = value;
    }

    public static Listener getListener() {
        return listener;
    }

    static AudioBufferSink sink() {
        return new Sink();
    }

    /**
     * One sink per player, so two players tapped at once keep their own formats.
     *
     * <p>The fields are written on the audio thread and read on the audio thread, but a format
     * change and a buffer can be handled by different threads across a flush, so they are volatile.
     */
    private static final class Sink implements AudioBufferSink {
        private final int sourceId = nextSourceId.getAndIncrement();
        private volatile int sampleRateHz;
        private volatile int channelCount;
        private volatile int encoding;

        @Override
        public void flush(int sampleRateHz, int channelCount, int encoding) {
            this.sampleRateHz = sampleRateHz;
            this.channelCount = channelCount;
            this.encoding = encoding;
            final Listener current = listener;
            if (current != null) {
                current.onPcmFormat(sourceId, sampleRateHz, channelCount, encoding);
            }
        }

        @Override
        public void handleBuffer(ByteBuffer buffer) {
            final Listener current = listener;
            if (current != null) {
                current.onPcmBuffer(sourceId, buffer, sampleRateHz, channelCount, encoding);
            }
        }
    }
}
