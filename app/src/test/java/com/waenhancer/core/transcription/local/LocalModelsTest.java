package com.waenhancer.core.transcription.local;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.media.AudioFormat;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashSet;
import java.util.Set;
import org.junit.Test;

public class LocalModelsTest {

    private static final long GIB = 1024L * 1024 * 1024;

    @Test
    public void catalogEntriesAreUniqueAndComplete() {
        Set<String> ids = new HashSet<>();
        for (LocalModels.Model model : LocalModels.ALL) {
            assertTrue(ids.add(model.id));
            assertTrue(model.fileName.endsWith(".bin"));
            assertEquals(64, model.sha256.length());
            assertTrue(model.sha256.matches("[0-9a-f]+"));
            assertTrue(model.sizeBytes > 1_000_000);
        }
        assertNotNull(LocalModels.byId("small"));
        assertEquals(null, LocalModels.byId("missing"));
    }

    @Test
    public void reportedRamBelowNominalSizeStillGetsThatTier() {
        // An "8 GB" phone reports about 7.4 GiB; a "4 GB" one about 3.6 GiB.
        assertEquals("small", LocalModels.recommend((long) (7.4 * GIB), 8, "sk").model.id);
        assertEquals("small", LocalModels.recommend((long) (3.6 * GIB), 8, "").model.id);
    }

    @Test
    public void weakPhonesGetLighterModels() {
        assertEquals("base", LocalModels.recommend((long) (2.8 * GIB), 8, "sk").model.id);
        assertEquals("base", LocalModels.recommend(8 * GIB, 2, "").model.id);
        assertEquals("tiny.en", LocalModels.recommend((long) (1.8 * GIB), 8, "en").model.id);
    }

    @Test
    public void englishUsesEnglishOnlyModelsAndTurboIsNeverTheDefault() {
        assertEquals("base.en", LocalModels.recommend(4 * GIB, 8, "en").model.id);
        assertEquals("small.en", LocalModels.recommend(12 * GIB, 8, "en").model.id);
        for (long ram : new long[]{2, 4, 8, 16}) {
            assertFalse(LocalModels.recommend(ram * GIB, 8, "sk").model.id.startsWith("large"));
            assertFalse(LocalModels.recommend(ram * GIB, 8, "sk").model.englishOnly);
        }
    }

    @Test
    public void cpuBackendNeedsEveryListedFeature() {
        String a55 = " fp asimd evtstrm aes pmull sha1 sha2 crc32 atomics fphp asimdhp cpuid asimdrdm lrcpc dcpop asimddp ";
        String a53 = " fp asimd evtstrm aes pmull sha1 sha2 crc32 cpuid ";
        assertTrue(WhisperNative.hasAll(a55, "asimddp asimdhp"));
        assertFalse(WhisperNative.hasAll(a55, "asimddp asimdhp i8mm"));
        assertFalse(WhisperNative.hasAll(a53, "asimddp"));
        assertTrue(WhisperNative.hasAll(a53, ""));
        assertTrue(WhisperNative.hasAll(null, ""));
        assertFalse(WhisperNative.hasAll(" asimddpx ", "asimddp"));
    }

    @Test
    public void resamplerDownmixesAndConvertsRate() {
        // One second of 48 kHz stereo 16-bit: left 0.5, right -0.5 -> mono 0.
        AudioDecoder.Resampler resampler = new AudioDecoder.Resampler(48_000, 2, AudioFormat.ENCODING_PCM_16BIT);
        ByteBuffer pcm = ByteBuffer.allocate(48_000 * 4).order(ByteOrder.nativeOrder());
        for (int i = 0; i < 48_000; i++) {
            pcm.putShort((short) 16384).putShort((short) -16384);
        }
        pcm.flip();
        resampler.feed(pcm);
        float[] out = resampler.result();
        assertTrue(Math.abs(out.length - 16_000) <= 1);
        for (float sample : out) assertEquals(0f, sample, 1e-6f);
    }

    @Test
    public void resamplerKeepsSignalAcrossBuffersAndUpsamples() {
        AudioDecoder.Resampler resampler = new AudioDecoder.Resampler(8_000, 1, AudioFormat.ENCODING_PCM_FLOAT);
        for (int chunk = 0; chunk < 4; chunk++) {
            ByteBuffer pcm = ByteBuffer.allocate(2_000 * 4).order(ByteOrder.nativeOrder());
            for (int i = 0; i < 2_000; i++) pcm.putFloat(0.25f);
            pcm.flip();
            resampler.feed(pcm);
        }
        float[] out = resampler.result();
        assertTrue(Math.abs(out.length - 16_000) <= 2);
        // After the first interpolated sample, a constant input stays constant.
        for (int i = 2; i < out.length; i++) assertEquals(0.25f, out[i], 1e-6f);
    }
}
