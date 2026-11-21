package com.waenhancer.core.emojis;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Parses independently valid official cloud-font entries without losing the whole catalog. */
public final class EmojiCloudCatalog {
    private EmojiCloudCatalog() {}

    public static final class Entry {
        public final String filename;
        public final long size;
        public final String downloadUrl;
        public final String previewUrl;

        private Entry(String filename, long size, String downloadUrl, String previewUrl) {
            this.filename = filename;
            this.size = size;
            this.downloadUrl = downloadUrl;
            this.previewUrl = previewUrl;
        }
    }

    public static List<Entry> parse(JSONObject root) {
        List<Entry> entries = new ArrayList<>();
        if (root == null) return entries;
        JSONArray array = root.optJSONArray("emojis");
        if (array == null) return entries;
        for (int i = 0; i < array.length(); i++) {
            JSONObject object = array.optJSONObject(i);
            if (object == null) continue;
            String filename = object.optString("filename", "");
            long size = object.optLong("size", 0L);
            String downloadUrl = object.optString("download_url", "");
            if (!EmojiCloudStorage.isSafeFontName(filename)
                    || !EmojiCloudStorage.isAllowedDeclaredSize(size)
                    || !EmojiCloudStorage.isApprovedDownloadUrl(downloadUrl)) {
                continue;
            }
            String preview = object.optString("preview", "");
            entries.add(new Entry(filename, size, downloadUrl,
                    preview.isEmpty() ? null : preview));
        }
        return entries;
    }
}
