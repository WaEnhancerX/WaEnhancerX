package com.waenhancer.core.emojis;

import android.system.Os;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;

/** Executable contract checks for the production file/URL/replace policy. */
public final class EmojiCloudStorageChecks {
    private static int checks;

    private static void check(boolean condition, String reason) {
        checks++;
        if (!condition) throw new AssertionError(reason);
    }

    private static void rejected(CheckedAction action, String reason) {
        checks++;
        try {
            action.run();
        } catch (IOException expected) {
            return;
        }
        throw new AssertionError(reason);
    }

    @FunctionalInterface
    private interface CheckedAction { void run() throws IOException; }

    private static void font(File file, int signature) throws IOException {
        try (DataOutputStream out = new DataOutputStream(new FileOutputStream(file))) {
            out.writeInt(signature);
            out.writeShort(1);  // one table; validates the 12-byte sfnt header shape
            out.writeShort(16);
            out.writeShort(0);
            out.writeShort(0);
            out.write(new byte[16]); // one sfnt table directory entry
        }
    }

    private static void ttc(File file, int fontCount) throws IOException {
        try (DataOutputStream out = new DataOutputStream(new FileOutputStream(file))) {
            out.writeInt(0x74746366); // 'ttcf'
            out.writeInt(0x00010000); // TTC v1.0
            out.writeInt(fontCount);
            if (fontCount == 1) out.writeInt(16); // first font offset
        }
    }

    public static void main(String[] args) throws Exception {
        Path scratch = Files.createTempDirectory("waex-emoji-storage-");
        try {
            File root = Files.createDirectory(scratch.resolve("custom_emojis")).toFile();
            File outside = scratch.resolve("outside.ttf").toFile();
            Files.write(outside.toPath(), new byte[] { 9, 8, 7 });

            // Every official entry observed on 2026-11-19, plus Unicode/case controls.
            for (String valid : Arrays.asList("Android 9 Pie.ttf", "Apple (iOS).ttf",
                    "Facebook.ttf", "LG.ttf", "Phantom.ttf", "Pixel Art.ttf", "Samsung.ttf",
                    "Twitter (Twemoji).ttf", "Windows 3D (Fluent Emoji).ttf", "Windows.ttf",
                    "Emoji 漢字.ttf", "Samsung.TTF")) {
                check(EmojiCloudStorage.isSafeFontName(valid), "rejects valid catalog-compatible name: " + valid);
                check(EmojiCloudStorage.checkedFontFile(root, valid).getCanonicalFile().getParentFile()
                        .equals(root.getCanonicalFile()), "valid font escaped its directory");
            }
            for (String invalid : Arrays.asList("../outside.ttf", "sub/a.ttf", "sub\\a.ttf",
                    "C:\\outside.ttf", "https://evil/font.ttf", "other.zip", "../config.xml",
                    ".hidden.ttf", " trailing.ttf", "trailing.ttf ", "bad\u0000.ttf",
                    "x".repeat(242) + ".ttf", "bad\uD800.ttf")) {
                check(!EmojiCloudStorage.isSafeFontName(invalid), "accepted unsafe font name");
                rejected(() -> EmojiCloudStorage.checkedFontFile(root, invalid), "resolved unsafe font path");
            }
            check(Files.readAllBytes(outside.toPath())[0] == 9, "unrelated file touched by validation");

            check(EmojiCloudStorage.isApprovedDownloadUrl(
                    "https://emoji-fonts.waex.mubashar.dev/Apple%20(iOS).ttf"), "current official font host rejected");
            for (String invalid : Arrays.asList(
                    "http://emoji-fonts.waex.mubashar.dev/font.ttf",
                    "https://evil.example/font.ttf", "https://emoji-fonts.waex.mubashar.dev.evil.example/a.ttf",
                    "https://user@emoji-fonts.waex.mubashar.dev/a.ttf",
                    "https://emoji-fonts.waex.mubashar.dev:80/a.ttf",
                    "https://emoji-fonts.waex.mubashar.dev/a.ttf#fragment", "file:///data/font.ttf")) {
                check(!EmojiCloudStorage.isApprovedDownloadUrl(invalid), "accepted unsafe source URL");
            }
            check(EmojiCloudStorage.isAllowedDeclaredSize(0L), "unknown size must be allowed with streaming bound");
            check(EmojiCloudStorage.isAllowedDeclaredSize(27_287_540L), "current official catalog size rejected");
            check(EmojiCloudStorage.isAllowedDeclaredSize(87_758_820L), "largest current official pack rejected");
            check(!EmojiCloudStorage.isAllowedDeclaredSize(-1L), "negative advertised size accepted");
            check(!EmojiCloudStorage.isAllowedDeclaredSize(EmojiCloudStorage.MAX_FONT_BYTES + 1L),
                    "oversized advertised font accepted");

            File original = EmojiCloudStorage.checkedFontFile(root, "Apple (iOS).ttf");
            Files.write(original.toPath(), "previous-good-font".getBytes());
            File incomplete = EmojiCloudStorage.createStageFile(root);
            font(incomplete, 0x00010000);
            rejected(() -> EmojiCloudStorage.validateDownloadedFile(incomplete, 28L, 29L, 28L),
                    "accepted truncated catalog download");
            check(new String(Files.readAllBytes(original.toPath())).equals("previous-good-font"),
                    "validation failure destroyed installed font");
            Files.delete(incomplete.toPath());

            File malformed = EmojiCloudStorage.createStageFile(root);
            font(malformed, 0x3c68746d);  // fake HTML content with matching advertised length
            rejected(() -> EmojiCloudStorage.validateDownloadedFile(malformed, 28L, 28L, 28L),
                    "accepted non-font HTTP body");
            Files.delete(malformed.toPath());

            // Exercise the TTC-only branch; an API-26 Integer helper is not
            // available on WAEX's Android 24 minimum and must not be used.
            File validTtc = EmojiCloudStorage.createStageFile(root);
            ttc(validTtc, 1);
            EmojiCloudStorage.validateDownloadedFile(validTtc, 16L, 16L, 16L);
            check(validTtc.length() == 16L, "valid TTC fixture was not preserved");
            Files.delete(validTtc.toPath());
            File badTtc = EmojiCloudStorage.createStageFile(root);
            ttc(badTtc, -1); // unsigned count 4,294,967,295 must be rejected
            rejected(() -> EmojiCloudStorage.validateDownloadedFile(badTtc, 12L, 12L, 12L),
                    "accepted impossible unsigned TTC font count");
            Files.delete(badTtc.toPath());

            File stage = EmojiCloudStorage.createStageFile(root);
            File concurrentStage = EmojiCloudStorage.createStageFile(root);
            check(!stage.equals(concurrentStage), "concurrent downloads shared a staging file");
            font(stage, 0x00010000);
            EmojiCloudStorage.validateDownloadedFile(stage, 28L, 28L, 28L);
            EmojiCloudStorage.validateDownloadedFile(stage, 28L, 0L, -1L);
            rejected(() -> EmojiCloudStorage.validateDownloadedFile(stage, 28L, 28L, 27L),
                    "ignored HTTP body length mismatch");
            rejected(() -> EmojiCloudStorage.validateDownloadedFile(stage, 28L, 4L, 28L),
                    "ignored catalog size mismatch");
            EmojiCloudStorage.replaceCompletedFile(stage, original);
            check(!stage.exists(), "stage still present after promotion");
            check(original.isFile() && original.length() == 28L, "old font was not replaced by valid download");
            Files.delete(concurrentStage.toPath());

            File failedStage = EmojiCloudStorage.createStageFile(root);
            font(failedStage, 0x4f54544f);
            byte[] prior = Files.readAllBytes(original.toPath());
            Os.failNextRename = true;
            rejected(() -> EmojiCloudStorage.replaceCompletedFile(failedStage, original),
                    "rename failure was reported as success");
            check(Arrays.equals(prior, Files.readAllBytes(original.toPath())),
                    "rename failure corrupted the previously installed font");
            check(failedStage.exists(), "rename failure removed the staged data unexpectedly");
            Files.delete(failedStage.toPath());
            File alienStage = scratch.resolve("alien.part").toFile();
            font(alienStage, 0x74746366);
            rejected(() -> EmojiCloudStorage.replaceCompletedFile(alienStage, original),
                    "cross-directory replacement was accepted");
            check(Arrays.equals(prior, Files.readAllBytes(original.toPath())),
                    "cross-directory attempt damaged the old font");

            System.out.println("PASS " + checks + " independent EmojiCloudStorage contract assertions");
        } finally {
            try (var paths = Files.walk(scratch)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); }
                    catch (IOException e) { throw new RuntimeException(e); }
                });
            }
        }
    }
}
