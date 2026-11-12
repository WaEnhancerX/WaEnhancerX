#!/usr/bin/env python3
"""JDK-only contract regression test for PreferenceBridgeClient.

Runs without Android SDK / Gradle. Compiles the *actual current-upstream* bridge source against
minimal fake platform types, then executes persistence, caching and observer tests.
This is not a device or LSPosed integration test.
"""
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
BRIDGE = ROOT / 'app/src/main/java/com/waenhancer/xposed/bridge/client/PreferenceBridgeClient.java'
STUBS = {
    'androidx/annotation/NonNull.java': 'package androidx.annotation; public @interface NonNull {}',
    'androidx/annotation/Nullable.java': 'package androidx.annotation; public @interface Nullable {}',
    'android/net/Uri.java': '''package android.net; public class Uri {
        public final String path; private Uri(String p) { path = p; }
        public static Uri parse(String p) { return new Uri(p); }
    }''',
    'android/os/Looper.java': '''package android.os; public class Looper {
        public static Looper getMainLooper() { return new Looper(); }
    }''',
    'android/os/Handler.java': '''package android.os; public class Handler {
        public Handler(Looper looper) {}
        public boolean post(Runnable task) { task.run(); return true; }
    }''',
    'android/os/Bundle.java': '''package android.os;
        import java.util.*;
        public class Bundle implements java.io.Serializable {
            private final Map<String,Object> entries = new HashMap<>();
            public void putString(String k, String v) { entries.put(k, v); }
            public String getString(String k) { return (String) entries.get(k); }
            public void putBoolean(String k, boolean v) { entries.put(k, v); }
            public boolean getBoolean(String k, boolean d) { Object v = entries.get(k); return v instanceof Boolean ? (Boolean) v : d; }
            public void putInt(String k, int v) { entries.put(k, v); }
            public int getInt(String k) { return (int) entries.get(k); }
            public void putLong(String k, long v) { entries.put(k, v); }
            public long getLong(String k) { return (long) entries.get(k); }
            public void putFloat(String k, float v) { entries.put(k, v); }
            public float getFloat(String k) { return (float) entries.get(k); }
            public void putStringArrayList(String k, ArrayList<String> v) { entries.put(k, v); }
            @SuppressWarnings("unchecked")
            public ArrayList<String> getStringArrayList(String k) { return (ArrayList<String>) entries.get(k); }
            public void putParcelableArrayList(String k, ArrayList<Bundle> v) { entries.put(k, v); }
            @SuppressWarnings("unchecked")
            public ArrayList<Bundle> getParcelableArrayList(String k) { return (ArrayList<Bundle>) entries.get(k); }
            public void putSerializable(String k, java.io.Serializable v) { entries.put(k, v); }
            public java.io.Serializable getSerializable(String k) { return (java.io.Serializable) entries.get(k); }
            public void putBundle(String k, Bundle v) { entries.put(k, v); }
            public Bundle getBundle(String k) { return (Bundle) entries.get(k); }
            public Set<String> keySet() { return entries.keySet(); }
            public Object get(String k) { return entries.get(k); }
        }''',
    'android/database/ContentObserver.java': '''package android.database;
        public abstract class ContentObserver {
            public ContentObserver(android.os.Handler h) {}
            public void onChange(boolean selfChange) {}
        }''',
    'android/content/SharedPreferences.java': '''package android.content;
        import java.util.*;
        public interface SharedPreferences {
            Map<String,?> getAll();
            String getString(String key, String def);
            Set<String> getStringSet(String key, Set<String> def);
            int getInt(String key, int def);
            long getLong(String key, long def);
            float getFloat(String key, float def);
            boolean getBoolean(String key, boolean def);
            boolean contains(String key);
            Editor edit();
            void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener);
            void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener);
            interface OnSharedPreferenceChangeListener { void onSharedPreferenceChanged(SharedPreferences p, String key); }
            interface Editor {
                Editor putString(String k, String v);
                Editor putStringSet(String k, Set<String> v);
                Editor putInt(String k, int v);
                Editor putLong(String k, long v);
                Editor putFloat(String k, float v);
                Editor putBoolean(String k, boolean v);
                Editor remove(String k);
                Editor clear();
                boolean commit();
                void apply();
            }
        }''',
    'android/content/Context.java': '''package android.content;
        public abstract class Context {
            public Context getApplicationContext() { return this; }
            public abstract ContentResolver getContentResolver();
            public abstract String getPackageName();
        }''',
    'com/waenhancer/config/PreferenceRestartTracker.java': '''package com.waenhancer.config;
        public final class PreferenceRestartTracker {
            private PreferenceRestartTracker() {}
            public static final String NEED_RESTART = "need_restart";
            public static final String PENDING_CHANGES = "pending_restart_changes";
            public static final String SHOW_RESTART_SHEET = "show_restart_sheet";
        }''',
    'android/content/ContentResolver.java': '''package android.content;
        public abstract class ContentResolver {
            public abstract android.os.Bundle call(android.net.Uri uri, String method, String arg, android.os.Bundle extras);
            public abstract void registerContentObserver(android.net.Uri uri, boolean descendants,
                android.database.ContentObserver observer);
        }''',
    'de/robv/android/xposed/XposedBridge.java': '''package de.robv.android.xposed;
        public class XposedBridge { public static void log(String s) { System.err.println(s); } }''',
    'de/robv/android/xposed/XSharedPreferences.java': '''package de.robv.android.xposed;
        public abstract class XSharedPreferences implements android.content.SharedPreferences {
            public void reload() {}
        }''',
    'test/BridgeContract.java': r'''package test;
        import android.content.*;
        import android.net.Uri;
        import android.os.Bundle;
        import android.database.ContentObserver;
        import com.waenhancer.xposed.bridge.client.PreferenceBridgeClient;
        import java.util.*;
        import java.util.concurrent.atomic.AtomicInteger;
        public final class BridgeContract {
            static class Resolver extends ContentResolver {
                volatile boolean available = true;
                final Map<String,Object> db = Collections.synchronizedMap(new HashMap<>());
                final List<ContentObserver> observers = new ArrayList<>();
                final AtomicInteger batchCount = new AtomicInteger();
                @Override public synchronized void registerContentObserver(Uri uri, boolean desc, ContentObserver obs) { observers.add(obs); }
                @Override public Bundle call(Uri uri, String method, String arg, Bundle extras) {
                    if (!available) throw new SecurityException("provider unavailable");
                    if ("get_all_preferences".equals(method)) {
                        Bundle result = new Bundle(), values = new Bundle(), sets = new Bundle();
                        synchronized (db) {
                            for (Map.Entry<String,Object> entry : db.entrySet()) {
                                String key = entry.getKey();
                                Object val = entry.getValue();
                                if (val instanceof String v) values.putString(key, v);
                                else if (val instanceof Boolean v) values.putBoolean(key, v);
                                else if (val instanceof Integer v) values.putInt(key, v);
                                else if (val instanceof Long v) values.putLong(key, v);
                                else if (val instanceof Float v) values.putFloat(key, v);
                                else if (val instanceof Set<?> v) {
                                    ArrayList<String> list = new ArrayList<>();
                                    for (Object item : v) if (item instanceof String) list.add((String) item);
                                    sets.putStringArrayList(key, list);
                                } else throw new AssertionError("Unsupported fake preference: " + key);
                            }
                        }
                        result.putBundle("values", values);
                        result.putBundle("sets", sets);
                        return result;
                    }
                    if ("apply_preferences".equals(method)) {
                        batchCount.incrementAndGet();
                        synchronized (db) {
                            if (extras.getBoolean("clear", false)) db.clear();
                            for (Bundle op : extras.getParcelableArrayList("operations")) {
                                String key = op.getString("key"), type = op.getString("type");
                                switch (type) {
                                  case "remove" -> db.remove(key);
                                  case "string" -> db.put(key, op.getString("value"));
                                  case "string_set" -> db.put(key, new HashSet<>(op.getStringArrayList("value")));
                                  case "boolean" -> db.put(key, op.getBoolean("value", false));
                                  case "int" -> db.put(key, op.getInt("value"));
                                  case "long" -> db.put(key, op.getLong("value"));
                                  case "float" -> db.put(key, op.getFloat("value"));
                                  default -> throw new AssertionError(type);
                                }
                            }
                        }
                        for (ContentObserver observer : observers) observer.onChange(false);
                        Bundle result = new Bundle(); result.putBoolean("success", true); return result;
                    }
                    return null;
                }
            }
            static class Ctx extends Context {
                final Resolver resolver = new Resolver();
                final String targetPackage;
                Ctx() { this("com.whatsapp"); }
                Ctx(String targetPackage) { this.targetPackage = targetPackage; }
                @Override public ContentResolver getContentResolver() { return resolver; }
                @Override public String getPackageName() { return targetPackage; }
            }
            static void check(boolean expected, String message) {
                if (!expected) throw new AssertionError(message);
            }
            static void await(java.util.function.BooleanSupplier condition, String message) throws Exception {
                long end = System.currentTimeMillis()+4000;
                while (!condition.getAsBoolean() && System.currentTimeMillis()<end) Thread.sleep(5);
                check(condition.getAsBoolean(), message);
            }
            public static void main(String[] ignored) throws Exception {
                Ctx ctx = new Ctx();
                ctx.resolver.db.put("enabled", false);
                ctx.resolver.db.put("int_as_string", "42");
                ctx.resolver.db.put("choices", new HashSet<>(List.of("a", "b")));
                PreferenceBridgeClient prefs = new PreferenceBridgeClient(ctx, null);
                check(!prefs.getBoolean("enabled", true), "startup hydration");
                check(prefs.getInt("int_as_string", 0)==42, "numeric compatibility");
                prefs.getStringSet("choices", null).clear();
                check(prefs.getStringSet("choices", null).size()==2, "defensive string sets");
                try {
                    prefs.getAll().clear();
                    throw new AssertionError("getAll exposed a mutable external snapshot");
                } catch (UnsupportedOperationException expected) {
                    check(prefs.contains("choices"), "read-only getAll changed the source cache");
                }
                AtomicInteger notifications = new AtomicInteger();
                SharedPreferences.OnSharedPreferenceChangeListener listener = (p, key) -> notifications.incrementAndGet();
                prefs.registerOnSharedPreferenceChangeListener(listener);
                check(prefs.edit().putBoolean("enabled", true).putStringSet("choices", Set.of("z"))
                      .putLong("last_seen", 7).remove("int_as_string").commit(), "atomic commit result");
                await(() -> ctx.resolver.batchCount.get() == 1, "one IPC for four changes");
                check(prefs.getBoolean("enabled", false), "committed boolean");
                check(!prefs.contains("int_as_string"), "removed key");
                check(prefs.getStringSet("choices", null).equals(Set.of("z")), "committed set");
                check(prefs.getLong("last_seen", 0)==7, "committed long");
                prefs.edit().clear().putString("retained", "yes").apply();
                check(!prefs.contains("last_seen") && "yes".equals(prefs.getString("retained", null)), "optimistic apply and clear");
                await(() -> ctx.resolver.batchCount.get()==2, "asynchronous batch persistence");
                await(() -> ctx.resolver.db.size()==1, "clear persisted");
                check(notifications.get()>0, "change listeners invoked");
                prefs.unregisterOnSharedPreferenceChangeListener(listener);
                ctx.resolver.available = false;
                int notificationsBefore = notifications.get();
                check(!prefs.edit().putString("failure", "x").commit(), "IPC failure returns false");
                check(!prefs.contains("failure"), "failed commit does not mutate cache");
                check(notifications.get()==notificationsBefore, "listener unregister works");
                // Per-target mode must select only this injected package's values.
                Ctx normal = new Ctx("com.whatsapp");
                normal.resolver.db.put("module_config_mode", "Per Target App");
                normal.resolver.db.put("imagequality", false);
                normal.resolver.db.put("com.whatsapp:imagequality", true);
                normal.resolver.db.put("com.whatsapp.w4b:imagequality", false);
                normal.resolver.db.put("need_restart", true);
                normal.resolver.db.put("com.whatsapp:need_restart", true);
                normal.resolver.db.put("com.whatsapp:pending_restart_changes", Set.of("imagequality"));
                normal.resolver.db.put("com.whatsapp.w4b:need_restart", false);
                PreferenceBridgeClient normalBridge = new PreferenceBridgeClient(normal, null);
                check(normalBridge.getBoolean("imagequality", false), "personal package override not applied");
                check(normalBridge.getBoolean("need_restart", false), "personal restart state not scoped");
                check(normalBridge.getStringSet("pending_restart_changes", null).equals(Set.of("imagequality")),
                    "personal pending restart set not scoped");
                Ctx business = new Ctx("com.whatsapp.w4b");
                business.resolver.db.putAll(normal.resolver.db);
                PreferenceBridgeClient businessBridge = new PreferenceBridgeClient(business, null);
                check(!businessBridge.getBoolean("imagequality", true), "Business package value crossed personal boundary");
                check(!businessBridge.getBoolean("need_restart", true), "Business package inherited personal restart");
                check(!businessBridge.contains("pending_restart_changes"),
                    "Business package inherited personal pending restart set");
                Ctx absent = new Ctx("com.whatsapp.clone");
                absent.resolver.db.putAll(normal.resolver.db);
                PreferenceBridgeClient absentBridge = new PreferenceBridgeClient(absent, null);
                check(!absentBridge.getBoolean("imagequality", true), "unknown package lost universal fallback");
                check(!absentBridge.getBoolean("need_restart", true), "unknown package inherited universal restart");
                System.out.println("OK: hydration, type coercion, snapshots, set-copy, atomic edits, clear, remove, async apply, failure and listeners");
            }
        }''',
}

# The lightweight restart-tracker fixture contains only constants used by the
# bridge. Fail if its values diverge from production; do not silently mock a
# different restart namespace.
restart_source = (ROOT / 'app/src/main/java/com/waenhancer/config/PreferenceRestartTracker.java').read_text()
for name, value in (('NEED_RESTART', 'need_restart'),
                    ('PENDING_CHANGES', 'pending_restart_changes'),
                    ('SHOW_RESTART_SHEET', 'show_restart_sheet')):
    if f'public static final String {name} = "{value}";' not in restart_source:
        raise AssertionError(f'Bridge fixture restart constant does not match production: {name}')

with tempfile.TemporaryDirectory(prefix='waex_test_') as path:
    out = Path(path)
    for name, content in STUBS.items():
        dest = out / name
        dest.parent.mkdir(parents=True, exist_ok=True)
        dest.write_text(content, encoding='utf-8')
    dest_classes = out / 'classes'
    dest_classes.mkdir()
    subprocess.run(['javac', '-Xlint:unchecked', '-d', str(dest_classes), *map(str, out.rglob('*.java')), str(BRIDGE)], check=True)
    subprocess.run(['java', '-cp', str(dest_classes), 'test.BridgeContract'], check=True)
