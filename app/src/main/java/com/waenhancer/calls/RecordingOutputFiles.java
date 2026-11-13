package com.waenhancer.calls;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;

/** Names and exclusively reserves output files without overwriting previous calls. */
public final class RecordingOutputFiles {
    private RecordingOutputFiles() {}

    /** A nonce distinguishes different sessions even when they start in the same second. */
    public static String newName(String extension) {
        return formatName(new Date(), UUID.randomUUID(), extension);
    }

    static String formatName(Date startedAt, UUID nonce, String extension) {
        if (!".m4a".equals(extension) && !".opus".equals(extension)) {
            throw new IllegalArgumentException("Unsupported recording extension");
        }
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(startedAt);
        return "WhatsApp_Call_" + timestamp + "_"
                + nonce.toString().replace("-", "") + extension;
    }

    /** Keep filename normalization identical for creation and deletion. */
    public static String safeName(String requestedName) {
        if (requestedName == null) return null;
        String safe = requestedName.replaceAll("[^A-Za-z0-9._-]", "_");
        if (safe.isEmpty() || ".".equals(safe) || "..".equals(safe)) return null;
        return safe;
    }

    /**
     * createNewFile is atomic: an existing recording is never opened for writing
     * when a duplicate name is requested. The caller owns the new empty file.
     */
    public static File reserve(File directory, String requestedName) throws IOException {
        String safeName = safeName(requestedName);
        if (directory == null || safeName == null) return null;
        if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory()) return null;
        File output = new File(directory, safeName);
        return output.createNewFile() ? output : null;
    }
}
