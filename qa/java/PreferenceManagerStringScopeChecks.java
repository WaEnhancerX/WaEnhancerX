package test;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import com.waenhancer.config.PreferenceSchema;
import com.waenhancer.config.PreferenceStores;
import com.waenhancer.core.preferences.WaexPreferenceManager;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Actual manager/store/schema; strict in-memory preferences, not Android disk. */
public final class PreferenceManagerStringScopeChecks {
    static final String MODE = "module_config_mode", TARGET = "com.whatsapp", OTHER = "com.whatsapp.w4b";
    static int assertions;
    static void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }
    static final class Store implements SharedPreferences {
        final Map<String, Object> values = new LinkedHashMap<>();
        final List<String> strictReads = new ArrayList<>();
        final List<Map<String, ?>> allReads = new ArrayList<>();
        int commits;
        boolean failAll;
        String interceptKey;
        Runnable intercept;
        @Override public String getString(String key, String fallback) {
            strictReads.add(key);
            Object value = values.get(key);
            if (Objects.equals(interceptKey, key) && intercept != null) {
                Runnable action = intercept; intercept = null; action.run();
            }
            return value == null ? fallback : (String) value;
        }
        @Override public Map<String, ?> getAll() {
            if (failAll) throw new IllegalStateException("Synthetic getAll failure");
            Map<String, ?> snapshot = Collections.unmodifiableMap(new LinkedHashMap<>(values));
            allReads.add(snapshot);
            return snapshot;
        }
        @Override public boolean contains(String key) { return values.containsKey(key); }
        @Override public boolean getBoolean(String k, boolean d) { Object v = values.get(k); return v == null ? d : (Boolean) v; }
        @Override public int getInt(String k, int d) { Object v = values.get(k); return v == null ? d : (Integer) v; }
        @Override public long getLong(String k, long d) { Object v = values.get(k); return v == null ? d : (Long) v; }
        @Override public float getFloat(String k, float d) { Object v = values.get(k); return v == null ? d : (Float) v; }
        @Override @SuppressWarnings("unchecked") public Set<String> getStringSet(String k, Set<String> d) {
            Object v = values.get(k); return v == null ? d : new HashSet<>((Set<String>) v);
        }
        @Override public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) { throw new AssertionError("Unused listener"); }
        @Override public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) { throw new AssertionError("Unused listener"); }
        @Override public Editor edit() { return new Batch(); }
        final class Batch implements Editor {
            final Map<String, Object> pending = new LinkedHashMap<>();
            boolean clear;
            @Override public Editor putString(String k, String v) { pending.put(k, v); return this; }
            @Override public Editor putBoolean(String k, boolean v) { pending.put(k, v); return this; }
            @Override public Editor putInt(String k, int v) { pending.put(k, v); return this; }
            @Override public Editor putLong(String k, long v) { pending.put(k, v); return this; }
            @Override public Editor putFloat(String k, float v) { pending.put(k, v); return this; }
            @Override public Editor putStringSet(String k, Set<String> v) { pending.put(k, v == null ? null : new HashSet<>(v)); return this; }
            @Override public Editor remove(String k) { pending.put(k, null); return this; }
            @Override public Editor clear() { clear = true; return this; }
            @Override public boolean commit() {
                commits++;
                if (clear) values.clear();
                for (var e : pending.entrySet()) {
                    if (e.getValue() == null) values.remove(e.getKey());
                    else values.put(e.getKey(), e.getValue());
                }
                return true;
            }
            @Override public void apply() { throw new AssertionError("Getter fixture must not call apply"); }
        }
    }
    static final class Ctx extends Context {
        final Store pub = new Store(), priv = new Store();
        final List<String> stores = new ArrayList<>();
        int unexpected;
        @Override public SharedPreferences getSharedPreferences(String name, int mode) {
            if (mode != MODE_PRIVATE) { unexpected++; throw new AssertionError("Unexpected storage mode"); }
            stores.add(name);
            if (PreferenceStores.PUBLIC_NAME.equals(name)) return pub;
            if (PreferenceStores.PRIVATE_NAME.equals(name)) return priv;
            unexpected++; throw new AssertionError("Unknown preference store");
        }
        @Override public ContentResolver getContentResolver() { unexpected++; throw new AssertionError("Getter used resolver"); }
        @Override public android.content.pm.ApplicationInfo getApplicationInfo() { unexpected++; throw new AssertionError("Getter requested filesystem identity"); }
        @Override public java.io.File getFilesDir() { unexpected++; throw new AssertionError("Getter touched filesystem"); }
    }
    static WaexPreferenceManager manager(Ctx c) {
        WaexPreferenceManager m = new WaexPreferenceManager(c);
        m.setTargetPackage(TARGET);
        c.stores.clear(); c.pub.strictReads.clear(); c.priv.strictReads.clear();
        c.pub.allReads.clear(); c.priv.allReads.clear();
        return m;
    }
    static void legacyTarget(String key, Object legacy, String expected, boolean privateKey, boolean universalPresent) {
        Ctx c = new Ctx(); c.pub.values.put(MODE, "Per Target App");
        Store selected = privateKey ? c.priv : c.pub;
        Store other = privateKey ? c.pub : c.priv;
        if (universalPresent) selected.values.put(key, "UNIVERSAL-SYNTHETIC");
        selected.values.put(TARGET + ":" + key, legacy);
        selected.values.put(OTHER + ":" + key, "OTHER-TARGET-SYNTHETIC");
        other.values.put(TARGET + ":" + key, "WRONG-STORE-SYNTHETIC");
        WaexPreferenceManager m = manager(c);
        Map<String, Object> beforePub = new HashMap<>(c.pub.values), beforePrivate = new HashMap<>(c.priv.values);
        int commits = c.pub.commits + c.priv.commits;
        String actual = m.getString(key, "DEFAULT-SYNTHETIC");
        System.out.println("OBSERVE " + key + "/" + legacy.getClass().getSimpleName() + ": " + actual);
        check(expected.equals(actual), "Legacy conversion must use selected target/store for " + key);
        check(selected.strictReads.contains(TARGET + ":" + key), "Strict read must address target key");
        check(other.allReads.isEmpty(), "Conversion must not query the other store");
        check(beforePub.equals(c.pub.values) && beforePrivate.equals(c.priv.values), "Reading must not migrate or overwrite values");
        check(commits == c.pub.commits + c.priv.commits && c.unexpected == 0, "Reading must not write or reach unused platform surfaces");
    }
    static void absentAndValid(String fallback) {
        Ctx c = new Ctx(); c.pub.values.put(MODE, "Per Target App");
        c.pub.values.put("seentick", "UNIVERSAL-SYNTHETIC");
        c.pub.values.put(TARGET + ":seentick", "TARGET-SYNTHETIC");
        WaexPreferenceManager m = manager(c);
        check("TARGET-SYNTHETIC".equals(m.getString("seentick", fallback)), "String target must be returned directly");
        c.pub.values.remove(TARGET + ":seentick");
        check(Objects.equals(fallback, m.getString("seentick", fallback)), "Absent target must retain existing default contract, no new universal fallback");
        check(c.pub.allReads.isEmpty(), "A valid or absent strict string must not invoke legacy conversion");
        check(c.unexpected == 0, "Unexpected platform call");
    }
    static void universalOrNullTarget(boolean nullTarget) {
        Ctx c = new Ctx(); c.pub.values.put(MODE, nullTarget ? "Per Target App" : "Universal");
        c.pub.values.put("seentick", 7); c.pub.values.put(TARGET + ":seentick", 9);
        WaexPreferenceManager m = manager(c);
        if (nullTarget) m.setTargetPackage(null);
        check("7".equals(m.getString("seentick", null)), "Universal/null target legacy behavior must remain");
        check(c.unexpected == 0, "Unexpected platform call");
    }
    static void targetChangesDuringFailure(boolean modeInstead) {
        Ctx c = new Ctx(); c.pub.values.put(MODE, "Per Target App");
        c.pub.values.put("seentick", "UNIVERSAL-SYNTHETIC");
        c.pub.values.put(TARGET + ":seentick", 7); c.pub.values.put(OTHER + ":seentick", 9);
        WaexPreferenceManager m = manager(c);
        c.pub.interceptKey = TARGET + ":seentick";
        c.pub.intercept = () -> {
            if (modeInstead) c.pub.values.put(MODE, "Universal");
            else m.setTargetPackage(OTHER);
        };
        check("7".equals(m.getString("seentick", "DEFAULT-SYNTHETIC")),
                "Strict read and conversion must keep the same resolved key despite target/mode change");
        check(c.pub.intercept == null && c.unexpected == 0, "The discriminating interleaving must execute");
    }
    static void malformedModeAndReadErrors() {
        Ctx c = new Ctx(); c.pub.values.put(MODE, "Per Target App");
        c.pub.values.put(TARGET + ":seentick", 7);
        WaexPreferenceManager m = manager(c);
        c.pub.values.put(MODE, 123);
        check("FALLBACK".equals(m.getString("seentick", "FALLBACK")), "Malformed mode ClassCastException must still return default");
        check(m.getString("seentick", null) == null, "Malformed mode must retain null default");
        c.pub.values.put(MODE, "Per Target App"); c.pub.failAll = true;
        check("FALLBACK".equals(m.getString("seentick", "FALLBACK")), "Conversion snapshot error must return default");
        c.pub.failAll = false; c.pub.interceptKey = TARGET + ":seentick";
        c.pub.intercept = () -> { throw new IllegalStateException("Synthetic strict read failure"); };
        boolean propagated = false;
        try { m.getString("seentick", "FALLBACK"); }
        catch (IllegalStateException expected) { propagated = true; }
        check(propagated, "Do not broaden strict read catch to swallow unrelated runtime failures");
        check(c.unexpected == 0, "Unexpected platform call");
    }
    interface Scenario { void run(); }
    public static void main(String[] args) {
        check(PreferenceSchema.entry("seentick").type == PreferenceSchema.Type.STRING, "Use actual STRING schema");
        check(PreferenceSchema.entry("custom_transcription_model").store == PreferenceSchema.Store.PRIVATE, "Private fixture key must remain private");
        Map<String, Scenario> cases = new LinkedHashMap<>();
        cases.put("target-int", () -> legacyTarget("seentick", 7, "7", false, true));
        cases.put("target-boolean", () -> legacyTarget("seentick", true, "true", false, true));
        cases.put("target-long", () -> legacyTarget("seentick", 9L, "9", false, true));
        cases.put("target-float", () -> legacyTarget("seentick", 1.25f, "1.25", false, true));
        cases.put("target-without-universal", () -> legacyTarget("seentick", 7, "7", false, false));
        cases.put("private-target", () -> legacyTarget("custom_transcription_model", 7, "7", true, true));
        cases.put("private-without-universal", () -> legacyTarget("custom_transcription_model", false, "false", true, false));
        cases.put("unknown-public-key", () -> legacyTarget("fixture_string_key", 7, "7", false, true));
        cases.put("string-and-absent", () -> absentAndValid("DEFAULT-SYNTHETIC"));
        cases.put("null-default", () -> absentAndValid(null));
        cases.put("universal", () -> universalOrNullTarget(false));
        cases.put("null-target", () -> universalOrNullTarget(true));
        cases.put("target-changes", () -> targetChangesDuringFailure(false));
        cases.put("mode-changes", () -> targetChangesDuringFailure(true));
        cases.put("errors", PreferenceManagerStringScopeChecks::malformedModeAndReadErrors);
        int failures = 0;
        for (var entry : cases.entrySet()) {
            try { entry.getValue().run(); System.out.println("PASS " + entry.getKey()); }
            catch (Throwable failure) { failures++; System.out.println("FAIL " + entry.getKey() + ": " + failure); }
        }
        System.out.println("RESULT: " + cases.size() + " cases, " + assertions + " assertions, " + failures
                + " failures; actual manager/store/schema with strict JVM doubles; no Android persistence claim");
        if (failures != 0) throw new AssertionError("String scope regression");
    }
}
