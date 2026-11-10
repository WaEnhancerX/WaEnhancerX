package com.waenhancer.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PreferenceRestartTrackerTest {
    @Test
    public void publicFeatureSettingsRequireRestart() {
        assertTrue(PreferenceRestartTracker.requiresRestart("anti_revoke"));
        assertTrue(PreferenceRestartTracker.requiresRestart("floating_bottom_bar"));
        assertTrue(PreferenceRestartTracker.requiresRestart("separate_groups_tabs"));
    }

    @Test
    public void everyCatalogedFeatureAutomaticallyRequiresRestart() {
        for (FeatureItem feature : FeatureCatalog.INSTANCE.getALL_FEATURES()) {
            if (!PreferenceRestartTracker.SHOW_RESTART_SHEET.equals(feature.getKey())) {
                assertTrue(
                        "Cataloged feature was omitted from restart tracking: " + feature.getKey(),
                        PreferenceRestartTracker.requiresRestart(feature.getKey()));
            }
        }
    }

    @Test
    public void runtimeAndPrivateStateDoesNotRequireRestart() {
        assertFalse(PreferenceRestartTracker.requiresRestart("need_restart"));
        assertFalse(PreferenceRestartTracker.requiresRestart("pending_restart_changes"));
        assertFalse(PreferenceRestartTracker.requiresRestart("show_restart_sheet"));
        assertFalse(PreferenceRestartTracker.requiresRestart("active_xposed_api_version"));
        assertFalse(PreferenceRestartTracker.requiresRestart("assemblyai_key"));
        assertFalse(PreferenceRestartTracker.requiresRestart("unknown_key"));
    }

    @Test
    public void usesFeatureLabelsAndReadableFallbacks() {
        assertEquals("Anti Revoke",
                PreferenceRestartTracker.displayName("anti_revoke"));
        assertEquals("Toastdeleted",
                PreferenceRestartTracker.displayName("toastdeleted"));
        assertEquals("Floating Bottom Bar Radius",
                PreferenceRestartTracker.displayName("floating_bottom_bar_radius"));
        assertEquals("Separate Groups Tabs",
                PreferenceRestartTracker.displayName("separate_groups_tabs"));
        assertEquals("Novaconfig",
                PreferenceRestartTracker.displayName("novaconfig"));
        assertEquals("DPI Mode", PreferenceRestartTracker.displayName("dpi_mode"));
    }

    @Test
    public void targetPackageAndUnscopedKeyExtraction() {
        assertEquals("com.whatsapp", PreferenceRestartTracker.targetPackage("com.whatsapp:anti_revoke"));
        assertEquals("com.whatsapp.w4b", PreferenceRestartTracker.targetPackage("com.whatsapp.w4b:chatfilter"));
        assertNull(PreferenceRestartTracker.targetPackage("anti_revoke"));
        assertNull(PreferenceRestartTracker.targetPackage(null));

        assertEquals("anti_revoke", PreferenceRestartTracker.unscopedKey("com.whatsapp:anti_revoke"));
        assertEquals("chatfilter", PreferenceRestartTracker.unscopedKey("com.whatsapp.w4b:chatfilter"));
        assertEquals("anti_revoke", PreferenceRestartTracker.unscopedKey("anti_revoke"));
        assertNull(PreferenceRestartTracker.unscopedKey(null));

        assertEquals("com.whatsapp:need_restart", PreferenceRestartTracker.scopedKey("com.whatsapp", "need_restart"));
        assertEquals("need_restart", PreferenceRestartTracker.scopedKey(null, "need_restart"));
        assertEquals("need_restart", PreferenceRestartTracker.scopedKey("", "need_restart"));
    }

    @Test
    public void perAppConfigTracksRestartPerTargetApp() {
        FakeSharedPreferences prefs = new FakeSharedPreferences();
        PreferenceRestartTracker.onPreferenceChanged(prefs, "com.whatsapp:anti_revoke");

        assertTrue(prefs.getBoolean("com.whatsapp:need_restart", false));
        assertEquals(java.util.Collections.singleton("anti_revoke"),
                prefs.getStringSet("com.whatsapp:pending_restart_changes", null));

        // Universal restart keys should not be affected
        assertFalse(prefs.contains("need_restart"));
        assertFalse(prefs.contains("pending_restart_changes"));

        // Other target apps should not be affected
        assertFalse(prefs.contains("com.whatsapp.w4b:need_restart"));
        assertFalse(prefs.contains("com.whatsapp.w4b:pending_restart_changes"));

        // Changing setting for WA Business only affects WA Business
        PreferenceRestartTracker.onPreferenceChanged(prefs, "com.whatsapp.w4b:floating_bottom_bar");
        assertTrue(prefs.getBoolean("com.whatsapp.w4b:need_restart", false));
        assertEquals(java.util.Collections.singleton("floating_bottom_bar"),
                prefs.getStringSet("com.whatsapp.w4b:pending_restart_changes", null));
    }

    @Test
    public void universalModeTracksRestartUniversally() {
        FakeSharedPreferences prefs = new FakeSharedPreferences();
        PreferenceRestartTracker.onPreferenceChanged(prefs, "anti_revoke");

        assertTrue(prefs.getBoolean("need_restart", false));
        assertEquals(java.util.Collections.singleton("anti_revoke"),
                prefs.getStringSet("pending_restart_changes", null));

        assertFalse(prefs.contains("com.whatsapp:need_restart"));
        assertFalse(prefs.contains("com.whatsapp.w4b:need_restart"));
    }

    private static class FakeSharedPreferences implements android.content.SharedPreferences {
        private final java.util.Map<String, Object> map = new java.util.HashMap<>();

        @Override
        public java.util.Map<String, ?> getAll() {
            return new java.util.HashMap<>(map);
        }

        @Override
        public String getString(String key, String defValue) {
            Object v = map.get(key);
            return v instanceof String ? (String) v : defValue;
        }

        @Override
        public java.util.Set<String> getStringSet(String key, java.util.Set<String> defValues) {
            Object v = map.get(key);
            return v instanceof java.util.Set ? new java.util.HashSet<>((java.util.Set<String>) v) : defValues;
        }

        @Override
        public int getInt(String key, int defValue) {
            Object v = map.get(key);
            return v instanceof Integer ? (Integer) v : defValue;
        }

        @Override
        public long getLong(String key, long defValue) {
            Object v = map.get(key);
            return v instanceof Long ? (Long) v : defValue;
        }

        @Override
        public float getFloat(String key, float defValue) {
            Object v = map.get(key);
            return v instanceof Float ? (Float) v : defValue;
        }

        @Override
        public boolean getBoolean(String key, boolean defValue) {
            Object v = map.get(key);
            return v instanceof Boolean ? (Boolean) v : defValue;
        }

        @Override
        public boolean contains(String key) {
            return map.containsKey(key);
        }

        @Override
        public Editor edit() {
            return new FakeEditor();
        }

        @Override
        public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {}

        @Override
        public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {}

        private class FakeEditor implements Editor {
            private final java.util.Map<String, Object> changes = new java.util.HashMap<>();
            private final java.util.Set<String> removals = new java.util.HashSet<>();
            private boolean clearAll = false;

            @Override
            public Editor putString(String key, String value) {
                changes.put(key, value);
                removals.remove(key);
                return this;
            }

            @Override
            public Editor putStringSet(String key, java.util.Set<String> values) {
                changes.put(key, values == null ? null : new java.util.HashSet<>(values));
                removals.remove(key);
                return this;
            }

            @Override
            public Editor putInt(String key, int value) {
                changes.put(key, value);
                removals.remove(key);
                return this;
            }

            @Override
            public Editor putLong(String key, long value) {
                changes.put(key, value);
                removals.remove(key);
                return this;
            }

            @Override
            public Editor putFloat(String key, float value) {
                changes.put(key, value);
                removals.remove(key);
                return this;
            }

            @Override
            public Editor putBoolean(String key, boolean value) {
                changes.put(key, value);
                removals.remove(key);
                return this;
            }

            @Override
            public Editor remove(String key) {
                removals.add(key);
                changes.remove(key);
                return this;
            }

            @Override
            public Editor clear() {
                clearAll = true;
                return this;
            }

            @Override
            public boolean commit() {
                apply();
                return true;
            }

            @Override
            public void apply() {
                if (clearAll) map.clear();
                for (String r : removals) map.remove(r);
                map.putAll(changes);
            }
        }
    }
}
