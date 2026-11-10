package com.waenhancer.xposed.features.media;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Creates a non-reversible identity for one completed status media file. */
final class StatusDownloadIdentity {
    private StatusDownloadIdentity() {}

    static String forFile(File source) {
        if (source == null || !source.isFile() || !source.canRead()) return null;
        long expected = source.length();
        if (expected <= 0 || expected > GalleryMediaExporter.MAX_BYTES) return null;
        try (BufferedInputStream input = new BufferedInputStream(new FileInputStream(source), 32 * 1024)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[32 * 1024];
            long readTotal = 0;
            for (int read; (read = input.read(buffer)) != -1;) {
                readTotal += read;
                if (readTotal > expected) return null;
                digest.update(buffer, 0, read);
            }
            // A changing source must not produce a saved identity for different bytes.
            if (readTotal != expected || source.length() != expected) return null;
            byte[] hash = digest.digest();
            StringBuilder encoded = new StringBuilder(hash.length * 2);
            for (byte value : hash) {
                int unsigned = value & 0xff;
                encoded.append(Character.forDigit(unsigned >>> 4, 16));
                encoded.append(Character.forDigit(unsigned & 0x0f, 16));
            }
            return encoded.toString();
        } catch (IOException | NoSuchAlgorithmException | RuntimeException ignored) {
            return null;
        }
    }
}
