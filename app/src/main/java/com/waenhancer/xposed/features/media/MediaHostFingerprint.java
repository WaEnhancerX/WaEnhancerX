package com.waenhancer.xposed.features.media;

import android.content.Context;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * A deliberately narrow host profile for the inspected gallery and value-object
 * contracts. An opcode list is not a proof of operand/control-flow equivalence.
 * Unsupported APKs retain host behavior and the independent upstream AB route.
 */
final class MediaHostFingerprint {
    private static final String PACKAGE = "com.whatsapp";
    private static final long BYTES = 144362968L;
    private static final String SHA256 = "a1b3b262a86c8ed928bce8b777da69a2324607356bd03d814396a66bbc27da55";
    private static String memoPath;
    private static long memoLength, memoModified;
    private static boolean memoResult;
    private MediaHostFingerprint() {}

    static boolean accepts(Context context) {
        if (context == null) return false;
        try {
            return acceptsFile(context.getPackageName(), new File(context.getApplicationInfo().sourceDir));
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    /** Package-private for native acceptance against the actual installed APK. */
    static synchronized boolean acceptsFile(String packageName, File apk) {
        if (!PACKAGE.equals(packageName) || apk == null || !apk.isFile()) return false;
        long length = apk.length(), modified = apk.lastModified();
        if (length != BYTES) return false;
        try {
            String path = apk.getCanonicalPath();
            if (path.equals(memoPath) && length == memoLength && modified == memoModified) return memoResult;
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[65536];
            long consumed = 0;
            try (FileInputStream input = new FileInputStream(apk)) {
                int count;
                while ((count = input.read(buffer)) != -1) {
                    consumed += count;
                    if (consumed > BYTES) return false;
                    digest.update(buffer, 0, count);
                }
            }
            StringBuilder actual = new StringBuilder(64);
            for (byte value : digest.digest()) {
                actual.append(Character.forDigit((value >>> 4) & 15, 16));
                actual.append(Character.forDigit(value & 15, 16));
            }
            boolean unchanged = consumed == length && apk.length() == length && apk.lastModified() == modified;
            boolean accepted = unchanged && SHA256.contentEquals(actual);
            if (unchanged) {
                memoPath = path; memoLength = length; memoModified = modified; memoResult = accepted;
            }
            return accepted;
        } catch (IOException | NoSuchAlgorithmException | SecurityException unavailable) {
            return false;
        }
    }
}
