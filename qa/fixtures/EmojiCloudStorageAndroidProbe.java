package com.waenhancer.core.emojis;

import android.os.Build;
import org.json.JSONObject;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

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

        File ttc = EmojiCloudStorage.createStageFile(dir);
        try (DataOutputStream output = new DataOutputStream(new FileOutputStream(ttc))) {
            output.writeInt(0x74746366); // ttcf
            output.writeInt(0x00010000);
            output.writeInt(1);
            output.writeInt(16);
        }
        EmojiCloudStorage.validateDownloadedFile(ttc, 16L, 16L, 16L);
        check(ttc.length() == 16L && ttc.delete(), "valid TTC header failed");

        // The real production parser must retain valid records when the same
        // JSON array also includes missing fields, invalid paths and nonobjects.
        JSONObject mixed = new JSONObject("{\"emojis\":["
                + "{\"filename\":\"Apple (iOS).ttf\",\"size\":719736,\"download_url\":"
                + "\"https://emoji-fonts.waex.mubashar.dev/Apple.ttf\"},"
                + "{\"filename\":\"No URL.ttf\"},"
                + "null,"
                + "{\"filename\":\"../escape.ttf\",\"download_url\":"
                + "\"https://emoji-fonts.waex.mubashar.dev/escape.ttf\"},"
                + "{\"filename\":\"Windows 3D (Fluent Emoji).ttf\",\"size\":87758820,"
                + "\"download_url\":\"https://emoji-fonts.waex.mubashar.dev/Windows.ttf\","
                + "\"preview\":\"https://example.invalid/p.png\"},"
                + "{\"filename\":\"Overlimit.ttf\",\"size\":134217729,"
                + "\"download_url\":\"https://emoji-fonts.waex.mubashar.dev/large.ttf\"}]}");
        List<EmojiCloudCatalog.Entry> entries = EmojiCloudCatalog.parse(mixed);
        check(entries.size() == 2, "valid catalog entries lost or invalid entries retained");
        check("Apple (iOS).ttf".equals(entries.get(0).filename), "valid first pack lost");
        check(entries.get(0).size == 719736L, "valid size changed");
        check(entries.get(0).previewUrl == null, "missing preview was not optional");
        check("Windows 3D (Fluent Emoji).ttf".equals(entries.get(1).filename), "valid later pack lost");
        check(entries.get(1).size == 87758820L, "large official pack rejected");
        check(entries.get(1).previewUrl != null, "valid optional preview lost");
        check(EmojiCloudCatalog.parse(new JSONObject("{\"emojis\":{}}" )).isEmpty(),
                "wrong-shape catalog was not handled");
        check(Build.VERSION.SDK_INT >= 24, "Android API below WAEX minSdk");
        check(corrupt.delete() && other.delete() && original.delete() && dir.delete(),
                "test artifacts could not be cleaned");
        System.out.println("PASS " + checks + " Android runtime checks; SDK " + Build.VERSION.SDK_INT);
    }
}
