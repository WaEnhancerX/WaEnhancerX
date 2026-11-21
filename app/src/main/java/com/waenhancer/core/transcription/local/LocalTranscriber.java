package com.waenhancer.core.transcription.local;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.waenhancer.core.transcription.TranscriptionClient;
import com.waenhancer.core.transcription.TranscriptionLog;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * On-device transcription in the WAEX app process. The model stays loaded between voice notes
 * and is released after a few idle minutes, since even Small needs a few hundred MB of RAM.
 */
public final class LocalTranscriber {

    private static final long IDLE_RELEASE_MINUTES = 3;
    /** whisper contexts are not thread-safe; one transcription at a time. */
    private static final Object LOCK = new Object();
    private static final ScheduledExecutorService IDLE = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "WAEX-WhisperIdle");
        thread.setDaemon(true);
        return thread;
    });

    private static long sHandle;
    @Nullable private static String sLoadedPath;
    @Nullable private static ScheduledFuture<?> sPendingRelease;

    private LocalTranscriber() {}

    /**
     * The model to use: the user's choice, or the suggestion for this phone and language when
     * nothing was chosen yet. Never null.
     */
    @NonNull
    public static String effectiveModelId(@NonNull Context context, @Nullable String chosenId,
                                          @Nullable String language) {
        if (LocalModels.byId(chosenId) != null) return chosenId;
        return LocalModels.recommend(LocalModelStore.totalRam(context),
                Runtime.getRuntime().availableProcessors(), language).model.id;
    }

    /** The selected model when it is downloaded and this phone can run it, otherwise null. */
    @Nullable
    public static LocalModels.Model usableModel(@NonNull Context context, @Nullable String modelId) {
        LocalModels.Model model = LocalModels.byId(modelId);
        if (model == null || !LocalModelStore.isDownloaded(context, model)) return null;
        return WhisperNative.isSupported() ? model : null;
    }

    /** Returns the transcript (empty when no speech was found). Blocking. */
    @NonNull
    public static String transcribe(@NonNull Context context, @Nullable String modelId, @Nullable byte[] audio,
                                    @NonNull String fileName, @NonNull String language)
            throws TranscriptionClient.TranscriptionException {
        if (audio == null || audio.length == 0) throw new TranscriptionClient.TranscriptionException("Empty audio file");
        if (!WhisperNative.isSupported()) {
            throw new TranscriptionClient.TranscriptionException(
                    "On-device transcription unavailable: " + WhisperNative.unsupportedReason());
        }
        LocalModels.Model model = LocalModels.byId(modelId);
        if (model == null || !LocalModelStore.isDownloaded(context, model)) {
            throw new TranscriptionClient.TranscriptionException("No on-device model downloaded");
        }

        long decodeStart = System.currentTimeMillis();
        float[] samples = decode(context, audio, fileName);
        TranscriptionLog.add(context, "app", String.format(java.util.Locale.ROOT,
                "Decoded %.1f s of audio in %d ms", samples.length / (float) AudioDecoder.TARGET_RATE,
                System.currentTimeMillis() - decodeStart));
        if (samples.length < AudioDecoder.TARGET_RATE / 10) return ""; // Under 0.1 s: nothing to hear.

        synchronized (LOCK) {
            cancelPendingRelease();
            try {
                long loadStart = System.currentTimeMillis();
                boolean wasLoaded = sHandle != 0;
                long handle = load(LocalModelStore.fileOf(context, model).getAbsolutePath());
                if (!wasLoaded || System.currentTimeMillis() - loadStart > 50) {
                    TranscriptionLog.add(context, "app", "Loaded model " + model.id + " in "
                            + (System.currentTimeMillis() - loadStart) + " ms (CPU backend "
                            + WhisperNative.backendName() + ", " + threads() + " threads; "
                            + WhisperNative.cpuSummary() + ")");
                }
                long runStart = System.currentTimeMillis();
                byte[] text = WhisperNative.transcribe(handle, samples, language, threads());
                long runMs = System.currentTimeMillis() - runStart;
                TranscriptionLog.add(context, "app", String.format(java.util.Locale.ROOT,
                        "Inference %d ms (%.2fx real time)%s", runMs,
                        runMs / 1000f / Math.max(0.1f, samples.length / (float) AudioDecoder.TARGET_RATE),
                        text == null ? ", failed" : ""));
                if (text == null) throw new TranscriptionClient.TranscriptionException("On-device transcription failed");
                return new String(text, StandardCharsets.UTF_8).trim();
            } finally {
                scheduleRelease();
            }
        }
    }

    /** Frees the loaded model now (e.g. before deleting it). */
    public static void release() {
        synchronized (LOCK) {
            cancelPendingRelease();
            if (sHandle != 0) WhisperNative.free(sHandle);
            sHandle = 0;
            sLoadedPath = null;
        }
    }

    private static long load(String path) throws TranscriptionClient.TranscriptionException {
        if (sHandle != 0 && path.equals(sLoadedPath)) return sHandle;
        if (sHandle != 0) WhisperNative.free(sHandle);
        sHandle = WhisperNative.init(path);
        sLoadedPath = sHandle != 0 ? path : null;
        if (sHandle == 0) throw new TranscriptionClient.TranscriptionException("Could not load the on-device model");
        return sHandle;
    }

    private static float[] decode(Context context, byte[] audio, String fileName)
            throws TranscriptionClient.TranscriptionException {
        String safeName = fileName.replaceAll("[^A-Za-z0-9._-]", "_");
        File temp = new File(context.getCacheDir(), "waex-local-" + System.nanoTime() + "-" + safeName);
        try {
            try (FileOutputStream out = new FileOutputStream(temp)) {
                out.write(audio);
            }
            return AudioDecoder.decode(temp);
        } catch (IOException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            TranscriptionLog.add(context, "app", "Audio decode failed: " + cause.getClass().getSimpleName()
                    + (cause.getMessage() != null ? " (" + cause.getMessage() + ")" : ""));
            throw new TranscriptionClient.TranscriptionException("Could not decode the audio");
        } finally {
            if (!temp.delete()) temp.deleteOnExit();
        }
    }

    /** Big cores do the work; more threads than four mostly add contention on phones. */
    private static int threads() {
        return Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors()));
    }

    private static void scheduleRelease() {
        sPendingRelease = IDLE.schedule(LocalTranscriber::release, IDLE_RELEASE_MINUTES, TimeUnit.MINUTES);
    }

    private static void cancelPendingRelease() {
        if (sPendingRelease != null) sPendingRelease.cancel(false);
        sPendingRelease = null;
    }
}
