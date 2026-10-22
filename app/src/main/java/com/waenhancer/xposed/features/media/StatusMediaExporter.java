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
import java.io.OutputStream;
import java.util.Locale;
import java.util.UUID;

/** Storage for the status file already selected by the host hook. */
final class StatusMediaExporter {
    private StatusMediaExporter() {}

    /** Returns only after the output closes and the owned destination is published. */
    static void export(Context context, File source) throws IOException {
        if (source == null || !source.isFile() || !source.canRead()) {
            throw new IOException("Status source is not a readable regular file");
        }
        // Inspect and copy one open descriptor, so path replacement cannot change the source.
        try (FileInputStream file = new FileInputStream(source);
             BufferedInputStream input = new BufferedInputStream(file, 32 * 1024)) {
            long expected = file.getChannel().size();
            if (expected <= 0 || expected > GalleryMediaExporter.MAX_BYTES) {
                throw new IOException("Invalid status media size");
            }
            Format format = inspect(input, source.getName());
            String name = "WAEX_STATUS_" + UUID.randomUUID() + format.extension;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                exportScoped(context.getContentResolver(), input, expected, name, format);
            } else {
                exportLegacy(context, input, expected, name, format);
            }
        }
    }

    private static String directory(Kind kind) {
        return kind == Kind.VIDEO ? Environment.DIRECTORY_MOVIES
                : kind == Kind.AUDIO ? Environment.DIRECTORY_MUSIC : Environment.DIRECTORY_PICTURES;
    }

    private static void exportScoped(ContentResolver resolver, BufferedInputStream input,
                                     long expected, String name, Format format) throws IOException {
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        values.put(MediaStore.MediaColumns.MIME_TYPE, format.mimeType);
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, directory(format.kind) + "/WAEX");
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri collection = format.kind == Kind.VIDEO ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                : format.kind == Kind.AUDIO ? MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                : MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        Uri row = resolver.insert(collection, values);
        if (row == null) throw new IOException("MediaStore did not create a status destination");
        boolean published = false;
        try {
            try (OutputStream output = resolver.openOutputStream(row, "w")) {
                if (output == null) throw new IOException("MediaStore did not open the status destination");
                GalleryMediaExporter.copy(input, output, expected);
            }
            ContentValues ready = new ContentValues();
            ready.put(MediaStore.MediaColumns.IS_PENDING, 0);
            if (resolver.update(row, ready, null, null) != 1) {
                throw new IOException("MediaStore did not publish the status destination");
            }
            published = true;
        } finally {
            // Only this invocation's inserted row is eligible for rollback.
            if (!published) resolver.delete(row, null, null);
        }
    }

    private static void exportLegacy(Context context, BufferedInputStream input,
                                     long expected, String name, Format format) throws IOException {
        File folder = new File(Environment.getExternalStoragePublicDirectory(directory(format.kind)), "WAEX");
        if (!folder.isDirectory() && !folder.mkdirs() && !folder.isDirectory()) {
            throw new IOException("Cannot create status export directory");
        }
        File temporary = File.createTempFile(".waex-status-", ".part", folder);
        File destination = new File(folder, name);
        boolean published = false;
        try {
            try (OutputStream output = new FileOutputStream(temporary)) {
                GalleryMediaExporter.copy(input, output, expected);
            }
            if (destination.exists() || !temporary.renameTo(destination)) {
                throw new IOException("Cannot publish status export file");
            }
            published = true;
        } finally {
            if (!published && temporary.exists() && !temporary.delete()) {
                throw new IOException("Cannot remove incomplete status export file");
            }
        }
        // Indexing is asynchronous; indexing failure must not remove a completed export.
        try {
            MediaScannerConnection.scanFile(context, new String[]{destination.getAbsolutePath()},
                    new String[]{format.mimeType}, null);
        } catch (RuntimeException ignored) {}
    }

    static Format inspect(File file) throws IOException {
        try (BufferedInputStream input = new BufferedInputStream(new FileInputStream(file))) {
            return inspect(input, file.getName());
        }
    }

    private static Format inspect(BufferedInputStream input, String fileName) throws IOException {
        byte[] header = new byte[16];
        input.mark(header.length);
        int count = 0;
        while (count < header.length) {
            int read = input.read(header, count, header.length - count);
            if (read < 0) break;
            if (read == 0) throw new IOException("Status header made no progress");
            count += read;
        }
        input.reset();
        String name = fileName.toLowerCase(Locale.ROOT);
        if (count >= 12 && header[4] == 'f' && header[5] == 't' && header[6] == 'y' && header[7] == 'p') {
            if (name.endsWith(".m4a") || name.endsWith(".aac")) {
                return new Format(Kind.AUDIO, ".m4a", "audio/mp4");
            }
            return new Format(Kind.VIDEO, ".mp4", "video/mp4");
        }
        if (count >= 4 && header[0] == 0x1A && header[1] == 0x45 && header[2] == (byte) 0xDF && header[3] == (byte) 0xA3) {
            return new Format(Kind.VIDEO, ".mkv", "video/x-matroska");
        }
        if (count >= 12 && header[0] == 'R' && header[1] == 'I' && header[2] == 'F' && header[3] == 'F'
                && header[8] == 'W' && header[9] == 'E' && header[10] == 'B' && header[11] == 'P') {
            return new Format(Kind.IMAGE, ".webp", "image/webp");
        }
        if (count >= 8 && header[0] == (byte) 0x89 && header[1] == 'P' && header[2] == 'N' && header[3] == 'G') {
            return new Format(Kind.IMAGE, ".png", "image/png");
        }
        if (count >= 3 && header[0] == (byte) 0xFF && header[1] == (byte) 0xD8 && header[2] == (byte) 0xFF) {
            return new Format(Kind.IMAGE, ".jpg", "image/jpeg");
        }
        if (count >= 6 && header[0] == 'G' && header[1] == 'I' && header[2] == 'F') {
            return new Format(Kind.IMAGE, ".gif", "image/gif");
        }
        if (count >= 4 && header[0] == 'O' && header[1] == 'g' && header[2] == 'g' && header[3] == 'S') {
            return new Format(Kind.AUDIO, ".ogg", "audio/ogg");
        }
        if (count >= 3 && header[0] == 'I' && header[1] == 'D' && header[2] == '3') {
            return new Format(Kind.AUDIO, ".mp3", "audio/mpeg");
        }
        if (count >= 2 && header[0] == (byte) 0xFF
                && (header[1] == (byte) 0xF1 || header[1] == (byte) 0xF9)) {
            return new Format(Kind.AUDIO, ".aac", "audio/aac");
        }

        if (name.endsWith(".mp4") || name.endsWith(".3gp") || name.endsWith(".mov")) {
            return new Format(Kind.VIDEO, ".mp4", "video/mp4");
        }
        if (name.endsWith(".mkv")) {
            return new Format(Kind.VIDEO, ".mkv", "video/x-matroska");
        }
        if (name.endsWith(".opus") || name.endsWith(".ogg")) {
            return new Format(Kind.AUDIO, ".ogg", "audio/ogg");
        }
        if (name.endsWith(".mp3")) {
            return new Format(Kind.AUDIO, ".mp3", "audio/mpeg");
        }
        if (name.endsWith(".m4a")) {
            return new Format(Kind.AUDIO, ".m4a", "audio/mp4");
        }
        if (name.endsWith(".aac")) {
            return new Format(Kind.AUDIO, ".aac", "audio/aac");
        }
        throw new IOException("Unsupported or incomplete status media format");
    }

    enum Kind { IMAGE, VIDEO, AUDIO }

    static final class Format {
        final Kind kind;
        final String extension;
        final String mimeType;

        Format(Kind kind, String extension, String mimeType) {
            this.kind = kind;
            this.extension = extension;
            this.mimeType = mimeType;
        }
    }

}
