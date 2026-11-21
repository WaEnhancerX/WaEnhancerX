package com.waenhancer.xposed.features.media;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import com.waenhancer.xposed.utils.HookErrors;

/** Exports an already resolved local photo/video, without modifying or transcoding it.
 * Host message/file selection is intentionally outside this storage contract. */
public final class GalleryMediaExporter {
    private GalleryMediaExporter() {}
    static final long MAX_BYTES = 512L * 1024L * 1024L;
    private static final int BUFFER_BYTES = 32 * 1024;

    static final class Format {
        final String extension;
        final String mime;
        final boolean video;
        Format(String extension, String mime, boolean video) {
            this.extension = extension; this.mime = mime; this.video = video;
        }
    }

    /** Returns only after the file is closed and (on API 29+) the row is published. */
    public static void export(Context context, File source) throws IOException {
        if (source == null || !source.isFile() || !source.canRead()) {
            throw new IOException("Media source is not a readable regular file");
        }
        long expected = source.length();
        if (expected <= 0 || expected > MAX_BYTES) throw new IOException("Invalid media size");
        // Keep one descriptor for inspection and copying; do not reopen a replaced path.
        try (BufferedInputStream in = new BufferedInputStream(new FileInputStream(source), BUFFER_BYTES)) {
            Format format = inspect(in);
            String name = "WAEX_VO_" + UUID.randomUUID() + format.extension;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                exportScoped(context.getContentResolver(), in, expected, name, format);
            } else {
                exportLegacy(context, in, expected, name, format);
            }
        }
    }

    static Format inspect(BufferedInputStream in) throws IOException {
        byte[] header = new byte[32];
        in.mark(header.length);
        int size = 0;
        while (size < header.length) {
            int count = in.read(header, size, header.length - size);
            if (count < 0) break;
            if (count == 0) throw new IOException("Media header made no progress");
            size += count;
        }
        in.reset();
        if (size >= 3 && (header[0] & 255) == 255 && (header[1] & 255) == 216
                && (header[2] & 255) == 255) return new Format(".jpg", "image/jpeg", false);
        if (size >= 8 && (header[0] & 255) == 137 && text(header, 1, 3).equals("PNG")
                && header[4] == 13 && header[5] == 10 && header[6] == 26 && header[7] == 10)
            return new Format(".png", "image/png", false);
        if (size >= 6 && (text(header, 0, 6).equals("GIF87a") || text(header, 0, 6).equals("GIF89a")))
            return new Format(".gif", "image/gif", false);
        if (size >= 12 && text(header, 0, 4).equals("RIFF") && text(header, 8, 4).equals("WEBP"))
            return new Format(".webp", "image/webp", false);
        if (size >= 16 && text(header, 4, 4).equals("ftyp")) {
            String brand = text(header, 8, 4);
            if (brand.equals("qt  ")) return new Format(".mov", "video/quicktime", true);
            if (brand.startsWith("3gp")) return new Format(".3gp", "video/3gpp", true);
            if (brand.startsWith("3g2")) return new Format(".3g2", "video/3gpp2", true);
            if (brand.equals("isom") || brand.equals("iso2") || brand.equals("mp41")
                    || brand.equals("mp42") || brand.equals("avc1") || brand.equals("M4V ")
                    || brand.equals("MSNV")) return new Format(".mp4", "video/mp4", true);
        }
        // Unknown prefixes, including HEIF/AVIF, are rejected instead of defaulting to JPEG.
        // A recognized container brand is not a complete decoder or stream-type validation.
        throw new IOException("Unsupported media container");
    }

    private static String text(byte[] bytes, int offset, int length) {
        return new String(bytes, offset, length, StandardCharsets.US_ASCII);
    }

    private static void exportScoped(ContentResolver resolver, InputStream in, long expected,
                                      String name, Format format) throws IOException {
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        values.put(MediaStore.MediaColumns.MIME_TYPE, format.mime);
        values.put(MediaStore.MediaColumns.RELATIVE_PATH,
                (format.video ? Environment.DIRECTORY_MOVIES : Environment.DIRECTORY_PICTURES) + "/WAEX");
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri collection = format.video ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                : MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        Uri row = resolver.insert(collection, values);
        if (row == null) throw new IOException("MediaStore did not create a destination");
        boolean published = false;
        try {
            try (OutputStream out = resolver.openOutputStream(row, "w")) {
                if (out == null) throw new IOException("MediaStore did not open the destination");
                copy(in, out, expected);
            }
            ContentValues ready = new ContentValues();
            ready.put(MediaStore.MediaColumns.IS_PENDING, 0);
            if (resolver.update(row, ready, null, null) != 1)
                throw new IOException("MediaStore did not publish the destination");
            published = true;
        } finally {
            if (!published) {
                // A provider failure may leave a pending row; never claim success in that case.
                resolver.delete(row, null, null);
            }
        }
    }

    private static void exportLegacy(Context context, InputStream in, long expected,
                                     String name, Format format) throws IOException {
        File folder = new File(Environment.getExternalStoragePublicDirectory(
                format.video ? Environment.DIRECTORY_MOVIES : Environment.DIRECTORY_PICTURES), "WAEX");
        if (!folder.isDirectory() && !folder.mkdirs()) throw new IOException("Cannot create export directory");
        File temporary = File.createTempFile(".waex-", ".part", folder);
        File destination = new File(folder, name);
        boolean published = false;
        try {
            try (OutputStream out = new FileOutputStream(temporary)) { copy(in, out, expected); }
            if (destination.exists() || !temporary.renameTo(destination))
                throw new IOException("Cannot publish export file");
            published = true;
        } finally {
            if (!published && temporary.exists() && !temporary.delete())
                throw new IOException("Cannot remove incomplete export file");
        }
        // Storage succeeded; media indexing is asynchronous and must not delete the saved file.
        try { MediaScannerConnection.scanFile(context, new String[]{destination.getAbsolutePath()},
                new String[]{format.mime}, null); } catch (RuntimeException ignored) { HookErrors.swallowed("GalleryMediaExporter", ignored); }
    }

    static void copy(InputStream in, OutputStream out, long expected) throws IOException {
        if (expected <= 0 || expected > MAX_BYTES) throw new IOException("Invalid media size");
        byte[] buffer = new byte[BUFFER_BYTES];
        long copied = 0;
        int count;
        while ((count = in.read(buffer)) != -1) {
            if (count == 0) throw new IOException("Media stream made no progress");
            if (count > expected - copied) throw new IOException("Media source grew during copy");
            out.write(buffer, 0, count);
            copied += count;
        }
        if (copied != expected) throw new IOException("Media source was truncated during copy");
        out.flush();
    }
}
