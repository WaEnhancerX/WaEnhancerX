package com.waenhancer.core.emojis;

import android.system.ErrnoException;
import android.system.Os;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Validates untrusted font metadata and replaces completed downloads without pre-deleting fonts. */
public final class EmojiCloudStorage {
    // The 2026-11-19 catalog includes an 87,758,820-byte (about 83.7 MiB) pack.
    // Keep headroom while bounding endless responses on limited-storage devices.
    public static final long MAX_FONT_BYTES = 128L * 1024L * 1024L;
    private static final int MAX_FILENAME_BYTES = 240;

    private EmojiCloudStorage() {}

    public static boolean isSafeFontName(String filename) {
        if (filename == null || filename.length() <= 4 || filename.startsWith(".")
                || !filename.trim().equals(filename)
                || !filename.toLowerCase(Locale.ROOT).endsWith(".ttf")
                || filename.getBytes(StandardCharsets.UTF_8).length > MAX_FILENAME_BYTES) {
            return false;
        }
        for (int i = 0; i < filename.length(); i++) {
            char c = filename.charAt(i);
            if (Character.isISOControl(c) || "/\\:*?\"<>|".indexOf(c) >= 0) return false;
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= filename.length() || !Character.isLowSurrogate(filename.charAt(i + 1))) {
                    return false;
                }
                i++;
            } else if (Character.isLowSurrogate(c)) {
                return false;
            }
        }
        return true;
    }

    public static boolean isApprovedDownloadUrl(String value) {
        if (value == null) return false;
        try {
            URI uri = new URI(value);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getRawUserInfo() != null
                    || uri.getRawFragment() != null || (uri.getPort() != -1 && uri.getPort() != 443)) {
                return false;
            }
            String host = uri.getHost();
            return host != null && (host.equalsIgnoreCase("emoji-fonts.waex.mubashar.dev")
                    || host.equalsIgnoreCase("waex.mubashar.dev"));
        } catch (URISyntaxException | IllegalArgumentException e) {
            return false;
        }
    }

    public static boolean isAllowedDeclaredSize(long size) {
        return size >= 0L && size <= MAX_FONT_BYTES;
    }

    public static File checkedFontFile(File directory, String filename) throws IOException {
        if (!isSafeFontName(filename)) throw new IOException("Unsafe emoji font filename");
        File root = directory.getCanonicalFile();
        File candidate = new File(root, filename);
        if (!root.equals(candidate.getCanonicalFile().getParentFile())) {
            throw new IOException("Emoji font must remain inside its storage directory");
        }
        return candidate;
    }

    public static File createStageFile(File directory) throws IOException {
        if (!directory.isDirectory()) throw new IOException("Emoji font storage is unavailable");
        return File.createTempFile(".waex-emoji-", ".part", directory);
    }

    public static void validateDownloadedFile(File stage, long bytes, long listedSize,
                                              long responseLength) throws IOException {
        if (bytes < 12L || bytes > MAX_FONT_BYTES || !isAllowedDeclaredSize(listedSize)
                || (listedSize > 0L && bytes != listedSize)
                || (responseLength >= 0L && bytes != responseLength)
                || !stage.isFile() || stage.length() != bytes) {
            throw new IOException("Emoji font transfer has invalid size or is incomplete");
        }
        try (DataInputStream in = new DataInputStream(new FileInputStream(stage))) {
            int magic = in.readInt();
            // TrueType/OpenType/TTC signatures, including historical Apple TrueType.
            if (magic != 0x00010000 && magic != 0x4f54544f /* OTTO */
                    && magic != 0x74746366 /* ttcf */ && magic != 0x74727565 /* true */
                    && magic != 0x74797031 /* typ1 */) {
                throw new IOException("Downloaded data is not a recognized TTF/OpenType font");
            }
            if (magic == 0x74746366) {
                in.readInt(); // TTC format version
                // Integer.toUnsignedLong is Android API 26+, while WAEX supports API 24.
                long count = in.readInt() & 0xffffffffL;
                if (count == 0L || count > 512L || bytes < 12L + count * 4L) {
                    throw new IOException("Incomplete TrueType collection header");
                }
            } else {
                int count = in.readUnsignedShort();
                if (count == 0 || count > 512 || bytes < 12L + count * 16L) {
                    throw new IOException("Incomplete font table directory");
                }
            }
        }
    }

    public static void replaceCompletedFile(File stage, File target) throws IOException {
        if (!stage.isFile() || !stage.getCanonicalFile().getParentFile()
                .equals(target.getCanonicalFile().getParentFile())) {
            throw new IOException("Emoji font staging path is invalid");
        }
        try {
            // Both files are within the same app-private directory. POSIX rename
            // replaces an existing target without an observable missing-file window.
            Os.rename(stage.getAbsolutePath(), target.getAbsolutePath());
        } catch (ErrnoException e) {
            // Crucially, the previous font is never unlinked on failure.
            throw new IOException("Could not replace the existing emoji font", e);
        }
    }
}
