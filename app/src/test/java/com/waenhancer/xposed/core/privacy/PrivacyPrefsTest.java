package com.waenhancer.xposed.core.privacy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.SharedPreferences;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.junit.Test;

public class PrivacyPrefsTest {

    private final MapPrefs store = new MapPrefs();
    private final PrivacyPrefs privacy = new PrivacyPrefs(store, "com.whatsapp");

    @Test public void togglesAreOffByDefault() {
        for (PrivacyToggle toggle : PrivacyToggle.values()) {
            assertFalse(toggle.name(), privacy.isOn(toggle));
            assertFalse(toggle.name(), privacy.isAvailable(toggle));
        }
    }

    @Test public void legacyAliasesTurnTogglesOn() {
        store.values.put("hideread", true);
        store.values.put("hidereceipt", true);
        store.values.put("hidestatusview", true);
        assertTrue(privacy.isOn(PrivacyToggle.HIDE_READ));
        assertTrue(privacy.isOn(PrivacyToggle.HIDE_DELIVERY));
        assertTrue(privacy.isOn(PrivacyToggle.STEALTH_STATUS));
        assertFalse("group read switch is separate", privacy.isOn(PrivacyToggle.HIDE_READ_GROUP));
    }

    @Test public void legacyStringAndNumberValuesAreRead() {
        store.values.put("stealth_status_view", "1");
        store.values.put("hideonceseen", 1);
        store.values.put("hide_typing", "false");
        assertTrue(privacy.isOn(PrivacyToggle.STEALTH_STATUS));
        assertTrue(privacy.isOn(PrivacyToggle.HIDE_VIEW_ONCE_OPENED));
        assertFalse(privacy.isOn(PrivacyToggle.HIDE_TYPING));
    }

    @Test public void ghostModeCoversOnlyGhostCoveredToggles() {
        store.values.put("ghostmode", true);
        store.values.put("ghostmode_active", true);
        assertTrue(privacy.isOn(PrivacyToggle.GHOST_MODE));
        for (PrivacyToggle toggle : PrivacyToggle.values()) {
            if (toggle.isGhostCovered()) assertTrue(toggle.name(), privacy.isOn(toggle));
        }
        assertFalse(privacy.isOn(PrivacyToggle.ANTI_VIEW_ONCE));
        assertFalse(privacy.isOn(PrivacyToggle.DND));
        assertFalse("ghost hides presence, never forces online", privacy.isOn(PrivacyToggle.ALWAYS_ONLINE));
    }

    @Test public void ghostActiveFlagIgnoredAfterGhostModeWasDisabled() {
        store.values.put("ghostmode", false);
        store.values.put("ghostmode_active", true);
        assertFalse(privacy.isOn(PrivacyToggle.GHOST_MODE));
        assertFalse(privacy.isOn(PrivacyToggle.HIDE_READ));
    }

    @Test public void ghostActiveFlagAloneWorksForSetupsWithoutAvailabilityKey() {
        store.values.put("ghostmode_active", true);
        assertTrue(privacy.isOn(PrivacyToggle.GHOST_MODE));
    }

    @Test public void availableGhostModeInstallsCoveredHooksBeforeActivation() {
        store.values.put("ghostmode", true);
        assertFalse(privacy.isOn(PrivacyToggle.GHOST_MODE));
        assertTrue(privacy.isAvailable(PrivacyToggle.HIDE_READ));
        assertTrue(privacy.isAvailable(PrivacyToggle.FREEZE_LAST_SEEN));
        assertTrue(privacy.isAvailable(PrivacyToggle.HIDE_TYPING));
        assertFalse(privacy.isAvailable(PrivacyToggle.ANTI_VIEW_ONCE));
    }

    @Test public void quickActionAvailabilityKeysInstallWithoutTurningOn() {
        store.values.put("freezelastseen", true);
        store.values.put("show_dndmode", true);
        assertTrue(privacy.isAvailable(PrivacyToggle.FREEZE_LAST_SEEN));
        assertFalse(privacy.isOn(PrivacyToggle.FREEZE_LAST_SEEN));
        assertTrue(privacy.isAvailable(PrivacyToggle.DND));
        assertFalse(privacy.isOn(PrivacyToggle.DND));
    }

    @Test public void runtimeSwitchAppliesImmediately() {
        store.values.put("freezelastseen", true);
        privacy.set(PrivacyToggle.FREEZE_LAST_SEEN, true);
        assertEquals(Boolean.TRUE, store.values.get("freeze_last_seen_active"));
        assertTrue(privacy.isOn(PrivacyToggle.FREEZE_LAST_SEEN));
        privacy.set(PrivacyToggle.FREEZE_LAST_SEEN, false);
        assertFalse(privacy.isOn(PrivacyToggle.FREEZE_LAST_SEEN));
    }

    @Test public void runtimeSwitchWritesTheScopeTheUserUses() {
        store.values.put("com.whatsapp:ghostmode_active", false);
        privacy.set(PrivacyToggle.GHOST_MODE, true);
        assertEquals(Boolean.TRUE, store.values.get("com.whatsapp:ghostmode_active"));
        assertNull(store.values.get("ghostmode_active"));
    }

    @Test public void scopedKeysOfThisTargetOnlyAreRead() {
        store.values.put("com.whatsapp:hide_read_receipts", true);
        store.values.put("com.whatsapp.w4b:hide_delivery_receipts", true);
        assertTrue(privacy.isOn(PrivacyToggle.HIDE_READ));
        assertFalse("another target's key must not leak", privacy.isOn(PrivacyToggle.HIDE_DELIVERY));
    }

    @Test public void alwaysOnlineYieldsToFrozenPresence() {
        store.values.put("always_online", true);
        assertTrue(privacy.isOn(PrivacyToggle.ALWAYS_ONLINE));
        store.values.put("freeze_last_seen", true);
        assertFalse(privacy.isOn(PrivacyToggle.ALWAYS_ONLINE));
    }

    @Test public void dndFollowsCanonicalKeyOverLegacy() {
        store.values.put("dndmode", true);
        assertTrue(privacy.isOn(PrivacyToggle.DND));
        store.values.put("dnd_mode", false);
        assertFalse("explicit current OFF overrides legacy ON", privacy.isOn(PrivacyToggle.DND));
    }

    @Test public void contactRulesAreDetectedButUnrelatedPrivacyKeysAreNot() {
        store.values.put("call_privacy", "0");
        store.values.put("custom_privacy_type", "1");
        assertFalse(privacy.hasContactRules());
        store.values.put("15551234567_privacy", "{\"HideSeen\":true}");
        assertTrue(privacy.hasContactRules());
        store.values.put("custom_privacy", false);
        assertFalse("rules are inert while per-contact privacy is off", privacy.hasContactRules());
    }

    @Test public void runtimeKeysMapBackToToggles() {
        for (PrivacyToggle toggle : PrivacyToggle.values()) {
            assertEquals(toggle, PrivacyToggle.fromRuntimeKey(toggle.runtimeKey()));
        }
        assertNull(PrivacyToggle.fromRuntimeKey("unknown"));
    }

    /** Minimal in-memory SharedPreferences that keeps stored value types as they are. */
    static final class MapPrefs implements SharedPreferences {
        final Map<String, Object> values = new HashMap<>();

        @Override public Map<String, ?> getAll() { return values; }
        @Override public String getString(String key, String defValue) {
            Object v = values.get(key);
            if (v == null) return defValue;
            if (v instanceof String) return (String) v;
            throw new ClassCastException(key);
        }
        @Override public Set<String> getStringSet(String key, Set<String> defValues) { return defValues; }
        @Override public int getInt(String key, int defValue) {
            Object v = values.get(key);
            if (v == null) return defValue;
            if (v instanceof Integer) return (Integer) v;
            throw new ClassCastException(key);
        }
        @Override public long getLong(String key, long defValue) { return defValue; }
        @Override public float getFloat(String key, float defValue) { return defValue; }
        @Override public boolean getBoolean(String key, boolean defValue) {
            Object v = values.get(key);
            if (v == null) return defValue;
            if (v instanceof Boolean) return (Boolean) v;
            throw new ClassCastException(key);
        }
        @Override public boolean contains(String key) { return values.containsKey(key); }
        @Override public Editor edit() { return new MapEditor(); }
        @Override public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) {}
        @Override public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) {}

        final class MapEditor implements Editor {
            private final Map<String, Object> pending = new HashMap<>();
            @Override public Editor putString(String key, String value) { pending.put(key, value); return this; }
            @Override public Editor putStringSet(String key, Set<String> v) { pending.put(key, v); return this; }
            @Override public Editor putInt(String key, int value) { pending.put(key, value); return this; }
            @Override public Editor putLong(String key, long value) { pending.put(key, value); return this; }
            @Override public Editor putFloat(String key, float value) { pending.put(key, value); return this; }
            @Override public Editor putBoolean(String key, boolean value) { pending.put(key, value); return this; }
            @Override public Editor remove(String key) { pending.put(key, null); return this; }
            @Override public Editor clear() { values.clear(); return this; }
            @Override public boolean commit() { apply(); return true; }
            @Override public void apply() {
                for (Map.Entry<String, Object> e : pending.entrySet()) {
                    if (e.getValue() == null) values.remove(e.getKey());
                    else values.put(e.getKey(), e.getValue());
                }
            }
        }
    }
}
