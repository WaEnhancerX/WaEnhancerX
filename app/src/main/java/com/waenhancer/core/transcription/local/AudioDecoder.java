package com.waenhancer.core.transcription.local;

import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import androidx.annotation.NonNull;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * Decodes an audio file (Ogg/Opus voice notes, AAC dictation, WAV) with Android's own codecs
 * into the 16 kHz mono float samples whisper expects. Resampling happens while decoding, so a
 * long note never holds its full-rate audio in memory.
 */
final class AudioDecoder {

    static final int TARGET_RATE = 16_000;
    /** Ten minutes: far beyond a normal voice note, and a bound on memory and work. */
    static final int MAX_SECONDS = 600;
    private static final long TIMEOUT_US = 10_000;

    private AudioDecoder() {}

    @NonNull
    static float[] decode(@NonNull File file) throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec codec = null;
        try {
            extractor.setDataSource(file.getAbsolutePath());
            int track = -1;
            MediaFormat format = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat candidate = extractor.getTrackFormat(i);
                String mime = candidate.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    track = i;
                    format = candidate;
                    break;
                }
            }
            if (track < 0) throw new IOException("No audio track");
            extractor.selectTrack(track);

            codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME));
            codec.configure(format, null, null, 0);
            codec.start();

            Resampler resampler = new Resampler(
                    format.containsKey(MediaFormat.KEY_SAMPLE_RATE) ? format.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 48_000,
                    format.containsKey(MediaFormat.KEY_CHANNEL_COUNT) ? format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 1,
                    AudioFormat.ENCODING_PCM_16BIT);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false;
            while (true) {
                if (!inputDone) {
                    int in = codec.dequeueInputBuffer(TIMEOUT_US);
                    if (in >= 0) {
                        ByteBuffer buffer = codec.getInputBuffer(in);
                        int size = buffer == null ? -1 : extractor.readSampleData(buffer, 0);
                        if (size < 0) {
                            codec.queueInputBuffer(in, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            codec.queueInputBuffer(in, 0, size, extractor.getSampleTime(), 0);
                            extractor.advance();
                        }
                    }
                }
                int out = codec.dequeueOutputBuffer(info, TIMEOUT_US);
                if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat outFormat = codec.getOutputFormat();
                    int encoding = outFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)
                            ? outFormat.getInteger(MediaFormat.KEY_PCM_ENCODING) : AudioFormat.ENCODING_PCM_16BIT;
                    resampler = new Resampler(outFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE),
                            outFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT), encoding)
                            .continueFrom(resampler);
                } else if (out >= 0) {
                    ByteBuffer buffer = codec.getOutputBuffer(out);
                    if (buffer != null && info.size > 0) {
                        buffer.position(info.offset);
                        buffer.limit(info.offset + info.size);
                        resampler.feed(buffer.slice().order(ByteOrder.nativeOrder()));
                    }
                    codec.releaseOutputBuffer(out, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
                    if (resampler.outputLength() >= TARGET_RATE * MAX_SECONDS) break;
                }
            }
            return resampler.result();
        } catch (IllegalStateException | IllegalArgumentException e) {
            throw new IOException("Could not decode audio", e);
        } finally {
            if (codec != null) {
                try {
                    codec.stop();
                } catch (Throwable ignored) {}
                codec.release();
            }
            extractor.release();
        }
    }

    /** Downmixes to mono and linearly resamples to 16 kHz, one decoder buffer at a time. */
    static final class Resampler {
        private final int sourceRate;
        private final int channels;
        private final int encoding;
        private float[] output = new float[TARGET_RATE * 30];
        private int outputLength;
        /** Position of the next output sample, in source samples since the start. */
        private double nextSourcePosition;
        private long sourceConsumed;
        private float previous;

        Resampler(int sourceRate, int channels, int encoding) {
            this.sourceRate = Math.max(1, sourceRate);
            this.channels = Math.max(1, channels);
            this.encoding = encoding;
        }

        /** Keeps the samples produced before a mid-stream format change. */
        Resampler continueFrom(Resampler earlier) {
            output = earlier.output;
            outputLength = earlier.outputLength;
            previous = earlier.previous;
            return this;
        }

        int outputLength() { return outputLength; }

        void feed(ByteBuffer pcm) {
            int frameBytes = channels * (encoding == AudioFormat.ENCODING_PCM_FLOAT ? 4 : 2);
            double step = (double) sourceRate / TARGET_RATE;
            while (pcm.remaining() >= frameBytes) {
                float sum = 0;
                for (int c = 0; c < channels; c++) {
                    sum += encoding == AudioFormat.ENCODING_PCM_FLOAT
                            ? pcm.getFloat() : pcm.getShort() / 32768f;
                }
                float current = sum / channels;
                // Emit every output sample that falls between the previous and this source sample.
                while (nextSourcePosition <= sourceConsumed) {
                    double fraction = 1.0 - (sourceConsumed - nextSourcePosition);
                    append((float) (previous + (current - previous) * Math.max(0, Math.min(1, fraction))));
                    nextSourcePosition += step;
                }
                previous = current;
                sourceConsumed++;
            }
        }

        private void append(float sample) {
            if (outputLength == output.length) output = Arrays.copyOf(output, output.length * 2);
            output[outputLength++] = sample;
        }

        float[] result() {
            return Arrays.copyOf(output, outputLength);
        }
    }
}
