package com.waenhancer.core.transcription.local;

import android.app.ActivityManager;
import android.content.Context;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Downloaded whisper models in the WAEX app's private storage. Downloads run on one app-wide
 * background thread, so they continue when the settings screen closes; the screen polls
 * {@link #progress}. A file only gets its final name after its size and SHA-256 match.
 */
public final class LocalModelStore {

    /** Download state of one model; {@code error} is set when the last attempt failed. */
    public static final class Progress {
        public final long downloadedBytes;
        public final long totalBytes;
        public final boolean running;
        @Nullable public final String error;

        Progress(long downloadedBytes, long totalBytes, boolean running, @Nullable String error) {
            this.downloadedBytes = downloadedBytes;
            this.totalBytes = totalBytes;
            this.running = running;
            this.error = error;
        }
    }

    private static final Map<String, Progress> PROGRESS = new ConcurrentHashMap<>();
    private static final Map<String, Boolean> CANCELLED = new ConcurrentHashMap<>();
    private static final ExecutorService DOWNLOADS = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "WAEX-ModelDownload");
        thread.setDaemon(true);
        return thread;
    });
    private static final long FREE_SPACE_MARGIN = 100L * 1024 * 1024;

    private LocalModelStore() {}

    @NonNull
    static File directory(@NonNull Context context) {
        File dir = new File(context.getFilesDir(), "whisper-models");
        if (!dir.isDirectory() && !dir.mkdirs()) dir.mkdirs();
        return dir;
    }

    @NonNull
    public static File fileOf(@NonNull Context context, @NonNull LocalModels.Model model) {
        return new File(directory(context), model.fileName);
    }

    public static boolean isDownloaded(@NonNull Context context, @NonNull LocalModels.Model model) {
        File file = fileOf(context, model);
        return file.isFile() && file.length() == model.sizeBytes;
    }

    @Nullable
    public static Progress progress(@NonNull String modelId) {
        return PROGRESS.get(modelId);
    }

    public static void delete(@NonNull Context context, @NonNull LocalModels.Model model) {
        LocalTranscriber.release(); // The loaded model may be the one being deleted.
        File file = fileOf(context, model);
        if (file.exists() && !file.delete()) file.deleteOnExit();
        PROGRESS.remove(model.id);
    }

    public static void cancel(@NonNull String modelId) {
        CANCELLED.put(modelId, Boolean.TRUE);
    }

    /** Starts a download unless one for this model is running. */
    public static void download(@NonNull Context context, @NonNull LocalModels.Model model) {
        Progress current = PROGRESS.get(model.id);
        if (current != null && current.running) return;
        Context app = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        CANCELLED.remove(model.id);
        PROGRESS.put(model.id, new Progress(0, model.sizeBytes, true, null));
        DOWNLOADS.execute(() -> {
            try {
                fetch(app, model);
                PROGRESS.put(model.id, new Progress(model.sizeBytes, model.sizeBytes, false, null));
            } catch (Throwable t) {
                String message = t instanceof IOException && t.getMessage() != null
                        ? t.getMessage() : "Download failed (" + t.getClass().getSimpleName() + ")";
                PROGRESS.put(model.id, new Progress(0, model.sizeBytes, false, message));
            } finally {
                CANCELLED.remove(model.id);
            }
        });
    }

    private static void fetch(Context context, LocalModels.Model model) throws Exception {
        File dir = directory(context);
        if (dir.getUsableSpace() < model.sizeBytes + FREE_SPACE_MARGIN) {
            throw new IOException("Not enough free storage (" + model.sizeLabel() + " needed)");
        }
        File part = new File(dir, model.fileName + ".part");
        HttpURLConnection connection = (HttpURLConnection) new URL(LocalModels.DOWNLOAD_BASE + model.fileName)
                .openConnection();
        connection.setConnectTimeout(20_000);
        connection.setReadTimeout(60_000);
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long downloaded = 0;
        try {
            int code = connection.getResponseCode();
            if (code / 100 != 2) throw new IOException("Server error " + code);
            byte[] buffer = new byte[64 * 1024];
            long lastReport = 0;
            try (InputStream in = connection.getInputStream(); OutputStream out = new FileOutputStream(part)) {
                int read;
                while ((read = in.read(buffer)) != -1) {
                    if (CANCELLED.containsKey(model.id)) throw new IOException("Cancelled");
                    out.write(buffer, 0, read);
                    digest.update(buffer, 0, read);
                    downloaded += read;
                    if (downloaded > model.sizeBytes) throw new IOException("Unexpected file size");
                    if (downloaded - lastReport >= 512 * 1024) {
                        lastReport = downloaded;
                        PROGRESS.put(model.id, new Progress(downloaded, model.sizeBytes, true, null));
                    }
                }
            }
        } catch (Exception e) {
            if (!part.delete()) part.deleteOnExit();
            throw e;
        } finally {
            connection.disconnect();
        }
        String actual = toHex(digest.digest());
        if (downloaded != model.sizeBytes || !actual.equals(model.sha256)) {
            if (!part.delete()) part.deleteOnExit();
            throw new IOException("Download corrupted, please try again");
        }
        File target = fileOf(context, model);
        if (!part.renameTo(target)) throw new IOException("Could not save the model");
    }

    private static String toHex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) out.append(String.format(Locale.ROOT, "%02x", b));
        return out.toString();
    }

    /** Total RAM of this phone, for {@link LocalModels#recommend}. */
    public static long totalRam(@NonNull Context context) {
        ActivityManager manager = context.getSystemService(ActivityManager.class);
        if (manager == null) return 0;
        ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
        manager.getMemoryInfo(info);
        return info.totalMem;
    }
}
