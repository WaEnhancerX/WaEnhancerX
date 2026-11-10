package com.waenhancer.utils;

import android.icu.text.BreakIterator;

import java.util.Locale;

/** Validates the one reaction emoji used by the double-tap feature. */
public final class EmojiInputPolicy {
    public static final String DEFAULT_EMOJI = "\uD83D\uDC4D";

    private EmojiInputPolicy() {
    }

    public static String singleEmojiOrDefault(String value) {
        String candidate = value == null ? "" : value.trim();
        return isSingleEmoji(candidate) ? candidate : DEFAULT_EMOJI;
    }

    public static boolean isSingleEmoji(String value) {
        if (value == null || value.isEmpty()) return false;

        BreakIterator iterator = BreakIterator.getCharacterInstance(Locale.ROOT);
        iterator.setText(value);
        if (iterator.first() == BreakIterator.DONE || iterator.next() == BreakIterator.DONE) return false;
        return iterator.next() == BreakIterator.DONE && containsEmojiCodePoint(value);
    }

    private static boolean containsEmojiCodePoint(String value) {
        for (int index = 0; index < value.length();) {
            int codePoint = value.codePointAt(index);
            if ((codePoint >= 0x1F000 && codePoint <= 0x1FAFF)
                    || (codePoint >= 0x2600 && codePoint <= 0x27BF)
                    || codePoint == 0x00A9 || codePoint == 0x00AE
                    || codePoint == 0x203C || codePoint == 0x2049
                    || codePoint == 0x2122 || codePoint == 0x2139
                    || codePoint == 0x3030 || codePoint == 0x303D
                    || codePoint == 0x3297 || codePoint == 0x3299
                    || codePoint == 0x20E3) {
                return true;
            }
            index += Character.charCount(codePoint);
        }
        return false;
    }
}
