package com.waenhancer.core.transcription;

import android.content.Context;
import androidx.annotation.NonNull;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Deque;
import java.util.Locale;

/**
 * A short diagnostic log of transcription steps, kept in the WAEX app's private storage and
 * shown in its settings so users can copy it into a bug report.
 *
 * <p>Entries hold metadata only: provider, model, CPU backend, audio size and length, timings
 * and errors. Transcript text, message content and API keys are never written here.</p>
 */
public final class TranscriptionLog {

    private static final String FILE_NAME = "transcription.log";
    private static final int MAX_LINES = 300;
    private static final int MAX_LINE_CHARS = 300;
    private static final Object LOCK = new Object();
    private static Deque<String> sLines;

    private TranscriptionLog() {}

    /** Adds one line, stamped with the time and the side it came from ("app" or "whatsapp"). */
    public static void add(@NonNull Context context, @NonNull String source, @NonNull String message) {
        String clean = message.replace('\n', ' ').replace('\r', ' ').trim();
        if (clean.length() > MAX_LINE_CHARS) clean = clean.substring(0, MAX_LINE_CHARS) + "…";
        String line = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.ROOT).format(new Date())
                + " [" + source + "] " + clean;
        synchronized (LOCK) {
            Deque<String> lines = load(context);
            lines.addLast(line);
            while (lines.size() > MAX_LINES) lines.removeFirst();
            save(context, lines);
        }
    }

    @NonNull
    public static String read(@NonNull Context context) {
        synchronized (LOCK) {
            return String.join("\n", load(context));
        }
    }

    public static void clear(@NonNull Context context) {
        synchronized (LOCK) {
            sLines = new ArrayDeque<>();
            File file = file(context);
            if (file.exists() && !file.delete()) file.deleteOnExit();
        }
    }

    private static Deque<String> load(Context context) {
        if (sLines != null) return sLines;
        sLines = new ArrayDeque<>();
        File file = file(context);
        if (file.isFile()) {
            try {
                byte[] data = new byte[(int) Math.min(file.length(), 256 * 1024)];
                try (java.io.FileInputStream in = new java.io.FileInputStream(file)) {
                    int offset = 0;
                    int read;
                    while (offset < data.length && (read = in.read(data, offset, data.length - offset)) > 0) {
                        offset += read;
                    }
                    for (String line : new String(data, 0, offset, StandardCharsets.UTF_8).split("\n")) {
                        if (!line.isEmpty()) sLines.addLast(line);
                    }
                }
            } catch (IOException ignored) {}
        }
        return sLines;
    }

    private static void save(Context context, Deque<String> lines) {
        try (FileOutputStream out = new FileOutputStream(file(context))) {
            out.write(String.join("\n", lines).getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {}
    }

    private static File file(Context context) {
        Context app = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        return new File(app.getFilesDir(), FILE_NAME);
    }
}
