package com.waenhancer.utils;

import androidx.annotation.NonNull;
import com.waenhancer.app.R;
import com.waenhancer.config.PreferenceKeyAliases;
import java.lang.reflect.Field;
import java.util.Locale;

/** Resolves preference keys through the same title resources used by the settings UI. */
public final class PreferenceLabels {
    private static final String TITLE_PREFIX = "feat_";
    private static final String TITLE_SUFFIX = "_title";

    private PreferenceLabels() {}

    @NonNull
    public static String text(String key) {
        String normalized = key == null ? "" : key.trim();
        if (normalized.isEmpty()) return "";

        String canonical = PreferenceKeyAliases.canonical(normalized);
        String candidate = canonical;
        while (!candidate.isEmpty()) {
            Integer resourceId = titleResource(TITLE_PREFIX + candidate + TITLE_SUFFIX);
            if (resourceId != null) {
                return UiText.get(resourceId, humanize(candidate));
            }
            int separator = candidate.lastIndexOf('_');
            if (separator < 0) break;
            candidate = candidate.substring(0, separator);
        }
        return humanize(canonical);
    }

    private static Integer titleResource(String fieldName) {
        try {
            Field field = R.string.class.getField(fieldName);
            return field.getInt(null);
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    private static String humanize(String key) {
        String[] words = key.replace('-', '_').split("_+");
        StringBuilder result = new StringBuilder();
        for (String word : words) {
            if (word.isEmpty()) continue;
            if (result.length() > 0) result.append(' ');
            if ("ai".equalsIgnoreCase(word) || "hd".equalsIgnoreCase(word)
                    || "dpi".equalsIgnoreCase(word) || "dnd".equalsIgnoreCase(word)) {
                result.append(word.toUpperCase(Locale.ROOT));
            } else {
                result.append(Character.toUpperCase(word.charAt(0)));
                if (word.length() > 1) result.append(word.substring(1));
            }
        }
        return result.length() == 0 ? key : result.toString();
    }
}
