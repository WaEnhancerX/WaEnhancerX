package com.waenhancer.core.emojis;

import android.os.Build;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Isolated app_process probe using Android's real android.system.Os.rename. */
public final class EmojiCloudStorageAndroidProbe {
    private static int checks;

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }

    private static void font(File output) throws IOException {
        try (DataOutputStream stream = new DataOutputStream(new FileOutputStream(output))) {
            stream.writeInt(0x00010000);
            stream.writeShort(1);
            stream.writeShort(16);
            stream.writeShort(0);
            stream.writeShort(0);
            stream.write(new byte[16]);
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1 || !args[0].matches("/data/local/tmp/waex-emoji-integrity-[a-f0-9]{32}")) {
            throw new IllegalArgumentException("An isolated test directory is required");
        }
        File dir = new File(args[0], "fonts");
        check(dir.mkdir(), "test directory not created");

        File original = EmojiCloudStorage.checkedFontFile(dir, "Apple (iOS).ttf");
        try (FileOutputStream stream = new FileOutputStream(original)) {
            stream.write("old-good-data".getBytes(StandardCharsets.UTF_8));
        }
        File first = EmojiCloudStorage.createStageFile(dir);
        File other = EmojiCloudStorage.createStageFile(dir);
        check(!first.equals(other), "staging names collide");
        font(first);
        EmojiCloudStorage.validateDownloadedFile(first, 28L, 28L, 28L);
        EmojiCloudStorage.replaceCompletedFile(first, original);
        check(!first.exists() && original.length() == 28L, "native rename did not replace old font");

        byte[] previous = new byte[28];
        try (FileInputStream stream = new FileInputStream(original)) {
            check(stream.read(previous) == previous.length, "replaced font bytes incomplete");
        }
        File corrupt = EmojiCloudStorage.createStageFile(dir);
        font(corrupt);
        boolean refused = false;
        try { EmojiCloudStorage.validateDownloadedFile(corrupt, 28L, 29L, 28L); }
        catch (IOException expected) { refused = true; }
        check(refused, "truncated download was accepted");
        byte[] after = new byte[28];
        try (FileInputStream stream = new FileInputStream(original)) {
            check(stream.read(after) == after.length, "original unreadable after failed validation");
        }
        check(Arrays.equals(previous, after), "invalid download replaced good font");

        refused = false;
        try { EmojiCloudStorage.checkedFontFile(dir, "../escape.ttf"); }
        catch (IOException expected) { refused = true; }
        check(refused, "traversal bypassed file validation");
        check(Build.VERSION.SDK_INT >= 24, "Android API below WAEX minSdk");
        check(corrupt.delete() && other.delete() && original.delete() && dir.delete(),
                "test artifacts could not be cleaned");
        System.out.println("PASS " + checks + " Android runtime checks; SDK " + Build.VERSION.SDK_INT);
    }
}
