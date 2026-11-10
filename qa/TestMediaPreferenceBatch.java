package com.waenhancer.core.preferences;

import java.util.Set;
import java.util.LinkedHashSet;
import java.util.Arrays;
import java.util.HashSet;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.net.Uri;
import com.waenhancer.ui.screens.media.MediaQuickSettingPolicy;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/** Android-free stub integration test; compiles the real WaexPreferenceManager. */
public final class TestMediaPreferenceBatch {
    private static int checks;
    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    private static final class FakeStore implements SharedPreferences {
        final Map<String, Object> values = new HashMap<>();
        int commits;
        boolean failCommit;
        boolean throwCommit;
        boolean applyBeforeFailure;
        boolean throwEdit;

        @Override public boolean getBoolean(String key, boolean fallback) {
            Object value = values.get(key);
            if (value == null) return fallback;
            if (!(value instanceof Boolean)) throw new ClassCastException();
            return (Boolean) value;
        }
        @Override public String getString(String key, String fallback) {
            Object v = values.get(key);
            return v == null ? fallback : (String) v;
        }
        @Override @SuppressWarnings("unchecked")
        public Set<String> getStringSet(String key, Set<String> fallback) {
            Object value = values.get(key);
            if (value == null) return fallback;
            if (!(value instanceof Set)) throw new ClassCastException();
            return (Set<String>) value;
        }
        @Override public int getInt(String key, int fallback) {
            Object v = values.get(key);
            return v == null ? fallback : (Integer) v;
        }
        @Override public float getFloat(String key, float fallback) {
            Object v = values.get(key);
            return v == null ? fallback : (Float) v;
        }
        @Override public Map<String, ?> getAll() { return Collections.unmodifiableMap(new HashMap<>(values)); }
        @Override public SharedPreferences.Editor edit() {
            if (throwEdit) throw new IllegalStateException("Editor unavailable");
            return new Batch();
        }

        private final class Batch implements Editor {
            private final Map<String, Object> adds = new HashMap<>();
            @Override public Editor putBoolean(String key, boolean value) { adds.put(key, value); return this; }
            @Override public Editor putString(String key, String value) { adds.put(key, value); return this; }
            @Override public Editor putStringSet(String key, Set<String> value) {
                // Retain the reference deliberately: the manager must snapshot its input.
                adds.put(key, value); return this;
            }
            @Override public Editor putInt(String key, int value) { adds.put(key, value); return this; }
            @Override public Editor putFloat(String key, float value) { adds.put(key, value); return this; }
            @Override public Editor remove(String key) { adds.put(key, null); return this; }
            @Override public boolean commit() {
                commits++;
                if (throwCommit) throw new IllegalStateException("Storage unavailable");
                if (failCommit && !applyBeforeFailure) return false;
                for (Map.Entry<String, Object> entry : adds.entrySet()) {
                    if (entry.getValue() == null) values.remove(entry.getKey());
                    else values.put(entry.getKey(), entry.getValue());
                }
                return !failCommit;
            }
        }
    }

    private static final class FakeResolver extends ContentResolver {
        int notifications;
        @Override public void notifyChange(Uri uri, Object observer) { notifications++; }
    }
    private static final class FakeContext extends Context {
        final FakeStore publicStore = new FakeStore();
        final FakeStore privateStore = new FakeStore();
        final FakeResolver resolver = new FakeResolver();
        final ApplicationInfo info = new ApplicationInfo();
        FakeContext() { info.dataDir = "/path/which/does/not/exist"; }
        @Override public SharedPreferences getSharedPreferences(String name, int mode) {
            return name.equals("private_config") ? privateStore : publicStore;
        }
        @Override public ContentResolver getContentResolver() { return resolver; }
        @Override public ApplicationInfo getApplicationInfo() { return info; }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void testTypedWrites() {
        FakeContext c = new FakeContext();
        WaexPreferenceManager m = new WaexPreferenceManager(c);
        String[][] inputs = {{"css_theme", "#title {color: #112233;}"},
                {"change_dpi", "320"}, {"chatfilter", "1"}, {"waex_menu_style", "icons"}};
        for (String[] input : inputs) {
            int commits = c.publicStore.commits, notifications = c.resolver.notifications;
            check(m.putPublicString(input[0], input[1]), "valid typed string rejected");
            check(input[1].equals(m.getString(input[0], "")), "typed string not persisted");
            check(c.publicStore.commits == commits + 1, "typed string commits exactly once");
            check(c.resolver.notifications == notifications + 1, "typed string notification");
        }
        Set<String> tabs = new LinkedHashSet<>(Arrays.asList("300", "600"));
        check(m.putPublicStringSet("hidetabs", tabs), "valid typed set rejected");
        tabs.add("400");
        check(!m.getStringSet("hidetabs", Collections.emptySet()).contains("400"), "caller mutated stored set");
        Set<String> read = m.getStringSet("hidetabs", Collections.emptySet());
        read.clear();
        check(m.getStringSet("hidetabs", Collections.emptySet()).size() == 2, "read leaked mutable storage");
        check(m.putPublicStringSet("hidetabs", Collections.emptySet()), "empty typed set rejected");
        check(m.getStringSet("hidetabs", tabs).isEmpty(), "empty set did not reset tabs");
        check(m.putPublicString("css_theme", ""), "empty CSS reset rejected");
        int commits = c.publicStore.commits, notifications = c.resolver.notifications;
        for (String key : new String[]{null, "", "unknown_typed_setting", "imagequality", "hidetabs", "app_theme_mode"}) {
            check(!m.putPublicString(key, "invalid"), "unknown, private or wrong-type string accepted: " + key);
        }
        check(!m.putPublicString("css_theme", null), "null string accepted");
        for (String key : new String[]{null, "", "unknown_typed_setting", "css_theme", "imagequality", "app_theme_mode"}) {
            check(!m.putPublicStringSet(key, tabs), "unknown, private or wrong-type set accepted: " + key);
        }
        check(!m.putPublicStringSet("hidetabs", null), "null set accepted");
        check(!m.putPublicStringSet("hidetabs", new HashSet<>(Arrays.asList("300", null))), "null element accepted");
        Set raw = new HashSet(); raw.add(Integer.valueOf(300));
        check(!m.putPublicStringSet("hidetabs", raw), "non-string element accepted");
        check(c.publicStore.commits == commits, "rejected data reached storage");
        check(c.privateStore.commits == 0, "typed writer reached private store");
        check(c.resolver.notifications == notifications, "rejected input notified");
        for (boolean mutateMemory : new boolean[]{false, true}) {
            c.publicStore.failCommit = true; c.publicStore.applyBeforeFailure = mutateMemory;
            check(!m.putPublicString("css_theme", "failed-save"), "failed string commit reported success");
            check(!m.putPublicStringSet("hidetabs", tabs), "failed set commit reported success");
            check(c.resolver.notifications == notifications, "failed commit notified");
            if (mutateMemory) check("failed-save".equals(m.getString("css_theme", "")),
                    "test must model Android memory-before-disk failure; rollback is not guaranteed");
        }
        c.publicStore.failCommit = false; c.publicStore.applyBeforeFailure = false;
        c.publicStore.throwCommit = true;
        check(!m.putPublicString("css_theme", "error"), "commit exception escaped string writer");
        check(!m.putPublicStringSet("hidetabs", tabs), "commit exception escaped set writer");
        c.publicStore.throwCommit = false; c.publicStore.throwEdit = true;
        check(!m.putPublicString("css_theme", "error"), "editor exception escaped string writer");
        check(!m.putPublicStringSet("hidetabs", tabs), "editor exception escaped set writer");
        check(c.resolver.notifications == notifications, "exception triggered notification");
        c.publicStore.throwEdit = false;
        check(m.putPublicString("css_theme", "retry"), "string retry failed");
        check(m.putPublicStringSet("hidetabs", tabs), "set retry failed");
        check(c.resolver.notifications == notifications + 2, "successful retries not notified");
    }

    public static void main(String[] args) {
        FakeContext context = new FakeContext();
        WaexPreferenceManager manager = new WaexPreferenceManager(context);
        check(manager.putBooleans(MediaQuickSettingPolicy.changesFor("media_upload_quality", true)),
                "bulk HD update failed");
        check(context.publicStore.commits == 1, "must use ONE commit for all three keys");
        check(context.resolver.notifications == 1, "must notify once after a successful commit");
        check(manager.getBoolean("imagequality", false), "image hook flag");
        check(manager.getBoolean("video_maxfps", false), "fps hook flag");
        check(manager.getBoolean("media_upload_quality", false), "legacy UI alias retained");

        check(manager.putBooleans(MediaQuickSettingPolicy.changesFor("call_recording", true)),
                "recorder update failed");
        check(context.publicStore.commits == 2, "recorder should be a single commit");
        check(context.resolver.notifications == 2, "recorder should notify once");
        check(manager.getBoolean("call_recording_enabled", false), "canonical recorder not set");
        check(manager.getBoolean("call_recording", false), "legacy recorder flag absent");

        Map<String, Boolean> malformed = new LinkedHashMap<>();
        malformed.put("imagequality", false);
        malformed.put("admin_emoji", true); // Schema declares STRING; reject entire batch.
        check(!manager.putBooleans(malformed), "non-Boolean schema entry was accepted");
        check(manager.getBoolean("imagequality", false), "partially applied invalid update");
        check(context.publicStore.commits == 2, "invalid data reached editor");
        check(context.resolver.notifications == 2, "invalid data triggered notification");

        malformed.clear();
        malformed.put("imagequality", false);
        malformed.put("update_auto_check", true); // Private Boolean; must remain private.
        check(!manager.putBooleans(malformed), "private key accepted in public batch");
        check(manager.getBoolean("imagequality", false), "partially wrote with private key");
        check(context.publicStore.commits == 2, "private key reached editor");

        check(!manager.putBooleans(Collections.emptyMap()), "empty batch accepted");
        check(!manager.putBooleans(null), "null batch accepted");
        malformed.clear(); malformed.put("video_maxfps", null);
        check(!manager.putBooleans(malformed), "null value accepted");

        context.publicStore.failCommit = true;
        check(!manager.putBooleans(MediaQuickSettingPolicy.changesFor("media_upload_quality", false)),
                "failed commit reported success");
        check(manager.getBoolean("imagequality", false), "failed commit changed data");
        check(context.resolver.notifications == 2, "failed commit notified observers");
        context.publicStore.failCommit = false;
        context.publicStore.throwCommit = true;
        check(!manager.putBooleans(MediaQuickSettingPolicy.changesFor("call_recording", false)),
                "storage exception escaped or reported success");
        check(manager.getBoolean("call_recording_enabled", false), "exception changed data");
        check(context.resolver.notifications == 2, "storage exception notified observers");
        context.publicStore.throwCommit = false;

        check(manager.putBooleans(MediaQuickSettingPolicy.changesFor("media_upload_quality", false)),
                "disable HD update failed");
        check(!manager.getBoolean("imagequality", true), "image hook was not disabled");
        check(!manager.getBoolean("video_maxfps", true), "fps hook was not disabled");
        check(context.resolver.notifications == 3, "valid update not notified");
        testTypedWrites();
        System.out.println("PASS: " + checks + " production manager/schema/failure checks (Android doubles)");
    }
}
