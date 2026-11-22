package test;

import android.content.*;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Bundle;
import com.waenhancer.config.PreferenceSchema;
import com.waenhancer.xposed.bridge.ProviderSnapshotFixture;
import com.waenhancer.xposed.bridge.client.PreferenceBridgeClient;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.*;

/** Independent expectations over real provider snapshot code and the complete client. */
public final class BridgeResolvedSnapshotChecks {
    private static final String PERSONAL = "com.whatsapp";
    private static final String BUSINESS = "com.whatsapp.w4b";
    private static final String NEED = "need_restart";
    private static final String PENDING = "pending_restart_changes";
    private static int scenarios;

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static final class Prefs implements SharedPreferences {
        final Map<String, Object> data = new LinkedHashMap<>();
        @Override public synchronized Map<String, ?> getAll() { return new LinkedHashMap<>(data); }
        @Override public synchronized boolean contains(String key) { return data.containsKey(key); }
        @Override public synchronized String getString(String key, String value) { return (String) data.getOrDefault(key, value); }
        @Override public synchronized boolean getBoolean(String key, boolean value) { return (Boolean) data.getOrDefault(key, value); }
        @Override public synchronized int getInt(String key, int value) { return (Integer) data.getOrDefault(key, value); }
        @Override public synchronized long getLong(String key, long value) { return (Long) data.getOrDefault(key, value); }
        @Override public synchronized float getFloat(String key, float value) { return (Float) data.getOrDefault(key, value); }
        @SuppressWarnings("unchecked")
        @Override public synchronized Set<String> getStringSet(String key, Set<String> value) {
            Object found = data.get(key); return found == null ? value : new LinkedHashSet<>((Set<String>) found);
        }
        @Override public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) { throw new AssertionError("Unexpected disk listener"); }
        @Override public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) { throw new AssertionError("Unexpected disk listener removal"); }
        @Override public Editor edit() {
            return new Editor() {
                final Map<String, Object> changed = new LinkedHashMap<>();
                boolean clear;
                @Override public Editor putString(String k, String v) { changed.put(k,v); return this; }
                @Override public Editor putStringSet(String k, Set<String> v) { changed.put(k,v == null ? null : new LinkedHashSet<>(v)); return this; }
                @Override public Editor putBoolean(String k, boolean v) { changed.put(k,v); return this; }
                @Override public Editor putInt(String k, int v) { changed.put(k,v); return this; }
                @Override public Editor putLong(String k, long v) { changed.put(k,v); return this; }
                @Override public Editor putFloat(String k, float v) { changed.put(k,v); return this; }
                @Override public Editor remove(String k) { changed.put(k,null); return this; }
                @Override public Editor clear() { clear=true; return this; }
                @Override public boolean commit() { synchronized (Prefs.this) {
                    if (clear) data.clear();
                    changed.forEach((k,v) -> { if (v == null) data.remove(k); else data.put(k,v); });
                    return true;
                } }
                @Override public void apply() { commit(); }
            };
        }
    }

    private static final class Ctx extends Context {
        final String pkg;
        final Resolver resolver;
        Ctx(String pkg, Prefs prefs) { this.pkg=pkg; this.resolver=new Resolver(this,prefs); }
        @Override public String getPackageName() { return pkg; }
        @Override public ContentResolver getContentResolver() { return resolver; }
    }

    private static final class Resolver extends ContentResolver {
        final Ctx context;
        final Prefs prefs;
        final List<ContentObserver> observers = new CopyOnWriteArrayList<>();
        volatile boolean available = true;
        volatile CountDownLatch writeEntered, writeRelease;
        Resolver(Ctx context, Prefs prefs) { this.context=context; this.prefs=prefs; }
        @Override public void registerContentObserver(Uri uri, boolean descendants, ContentObserver observer) {
            check(uri.path.endsWith("/preferences"), "Unexpected observer URI"); observers.add(observer);
        }
        @Override public Bundle call(Uri uri, String method, String arg, Bundle extras) {
            check("content://com.waenhancer.hookprovider".equals(uri.path), "Unexpected provider URI");
            if (!available) throw new IllegalStateException("Synthetic transport outage");
            if ("get_all_preferences".equals(method)) return new ProviderSnapshotFixture(context).snapshot(prefs);
            if (!"apply_preferences".equals(method)) throw new AssertionError("Unexpected IPC: "+method);
            if (writeEntered != null) {
                writeEntered.countDown();
                try { check(writeRelease.await(4,TimeUnit.SECONDS), "write gate timeout"); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
            }
            check(!extras.getBoolean("clear",false), "Target clear must not be silently accepted");
            SharedPreferences.Editor editor = prefs.edit();
            for (Bundle op : extras.getParcelableArrayList("operations")) {
                String key=op.getString("key"), type=op.getString("type");
                check(key.indexOf(':') < 0 && !PreferenceSchema.isGlobalOnly(key), "Unexpected target write key");
                check(NEED.equals(key) || PENDING.equals(key), "Fixture writes are restricted to restart state");
                if ("Per Target App".equals(prefs.getString("module_config_mode","Universal"))) key=context.pkg+":"+key;
                switch (type) {
                    case "boolean": editor.putBoolean(key,op.getBoolean("value",false)); break;
                    case "remove": editor.remove(key); break;
                    default: throw new AssertionError("Unexpected restart write type: "+type);
                }
            }
            check(editor.commit(), "Fake storage failed");
            for (ContentObserver observer : observers) observer.onChange(false);
            Bundle result = new Bundle(); result.putBoolean("success",true); return result;
        }
    }

    private static Prefs scoped(boolean restart) {
        Prefs prefs=new Prefs();
        prefs.data.put("module_config_mode","Per Target App");
        prefs.data.put("imagequality",false);
        prefs.data.put("bypass_version_check",false);
        prefs.data.put(NEED,true);
        prefs.data.put(PENDING,Set.of("universal_change"));
        prefs.data.put(PERSONAL+":target_seeded",true);
        prefs.data.put(PERSONAL+":imagequality",true);
        prefs.data.put(PERSONAL+":bypass_version_check",true);
        prefs.data.put(PERSONAL+":"+NEED,restart);
        if (restart) prefs.data.put(PERSONAL+":"+PENDING,Set.of("imagequality"));
        prefs.data.put(BUSINESS+":target_seeded",true);
        prefs.data.put(BUSINESS+":imagequality",false);
        prefs.data.put(BUSINESS+":"+NEED,false);
        return prefs;
    }

    private static ExecutorService executor(PreferenceBridgeClient bridge) throws Exception {
        Field f=PreferenceBridgeClient.class.getDeclaredField("ioExecutor"); f.setAccessible(true);
        return (ExecutorService)f.get(bridge);
    }
    private static void settle(PreferenceBridgeClient bridge) throws Exception {
        executor(bridge).submit(() -> {}).get(4,TimeUnit.SECONDS);
        executor(bridge).submit(() -> {}).get(4,TimeUnit.SECONDS);
    }
    private static void close(PreferenceBridgeClient bridge) throws Exception {
        settle(bridge); executor(bridge).shutdownNow();
    }

    private static void providerSnapshotPreservesRestart() throws Exception {
        Prefs prefs=scoped(true); Ctx context=new Ctx(PERSONAL,prefs);
        Bundle transport=new ProviderSnapshotFixture(context).snapshot(prefs);
        check(transport.getBundle("values").getBoolean(NEED,false), "Production provider did not resolve restart");
        for (String key:transport.getBundle("values").keySet()) check(key.indexOf(':')<0,"raw target namespace escaped provider");
        for (String key:transport.getBundle("sets").keySet()) check(key.indexOf(':')<0,"raw target set escaped provider");
        PreferenceBridgeClient bridge=new PreferenceBridgeClient(context,null);
        try {
            check(bridge.getBoolean(NEED,false),"provider restart request was discarded");
            check(bridge.getStringSet(PENDING,Set.of()).equals(Set.of("imagequality")),"provider pending changes were discarded");
            check(bridge.getBoolean("imagequality",false),"provider feature override lost");
            check(!bridge.getBoolean("bypass_version_check",true),"stale scoped global-only setting won");
            bridge.getStringSet(PENDING,Set.of()).clear();
            check(bridge.getStringSet(PENDING,Set.of()).equals(Set.of("imagequality")),"mutable set escaped cache");
            scenarios++;
        } finally { close(bridge); }
    }

    private static void refreshAndConsumption() throws Exception {
        Prefs prefs=scoped(false); Ctx context=new Ctx(PERSONAL,prefs);
        PreferenceBridgeClient bridge=new PreferenceBridgeClient(context,null);
        try {
            List<String> changes=new CopyOnWriteArrayList<>();
            bridge.registerOnSharedPreferenceChangeListener((p,key) -> changes.add(key));
            prefs.edit().putBoolean(PERSONAL+":"+NEED,true).putStringSet(PERSONAL+":"+PENDING,Set.of("imagequality")).commit();
            bridge.syncPreferences();
            check(bridge.getBoolean(NEED,false) && changes.contains(NEED),"refresh did not deliver restart notification");
            check(bridge.edit().putBoolean(NEED,false).remove(PENDING).commit(),"restart consumption rejected");
            settle(bridge);
            check(!bridge.getBoolean(NEED,true) && !bridge.contains(PENDING),"consumed restart reappeared");
            check(!prefs.getBoolean(PERSONAL+":"+NEED,true),"target consumption not persisted");
            check(prefs.getBoolean(NEED,false),"target consumed universal restart state");
            scenarios++;
        } finally { close(bridge); }
    }

    private static void targetIsolation() throws Exception {
        for (String target:List.of(BUSINESS,"com.whatsapp.clone")) {
            Ctx context=new Ctx(target,scoped(true));
            PreferenceBridgeClient bridge=new PreferenceBridgeClient(context,null);
            try {
                check(!bridge.getBoolean(NEED,true),"target inherited another restart request");
                check(!bridge.contains(PENDING),"target inherited another pending change set");
                check(!bridge.getBoolean("imagequality",true),"target inherited personal override");
                scenarios++;
            } finally { close(bridge); }
        }
    }

    private static void diskFallbackAndRecovery() throws Exception {
        Prefs disk=scoped(true); Ctx context=new Ctx(PERSONAL,scoped(false)); context.resolver.available=false;
        PreferenceBridgeClient bridge=new PreferenceBridgeClient(context,disk);
        try {
            check(bridge.getBoolean("imagequality",false),"disk fallback did not select the target override");
            check(bridge.getBoolean(NEED,false),"disk fallback lost own restart request");
            check(bridge.getStringSet(PENDING,Set.of()).equals(Set.of("imagequality")),"disk fallback lost own pending changes");
            check(!bridge.getBoolean("bypass_version_check",true),"disk fallback selected stale global override");
            check("DISK_FALLBACK".equals(bridge.getLastSyncSource()),"fallback source not reported");
            context.resolver.available=true; bridge.syncPreferences();
            check(!bridge.getBoolean(NEED,true) && !bridge.contains(PENDING),"recovered provider did not replace stale disk restart state");
            check("PROVIDER".equals(bridge.getLastSyncSource()),"recovered provider source not reported");
            scenarios++;
        } finally { close(bridge); }
        Ctx absent=new Ctx("com.whatsapp.clone",scoped(true)); absent.resolver.available=false;
        PreferenceBridgeClient noOverride=new PreferenceBridgeClient(absent,scoped(true));
        try {
            check(!noOverride.getBoolean(NEED,true) && !noOverride.contains(PENDING),"disk fallback inherited universal restart state");
            scenarios++;
        } finally { close(noOverride); }
    }

    private static void optimisticEditOnDiskSnapshot() throws Exception {
        Prefs disk=scoped(true); Ctx context=new Ctx(PERSONAL,scoped(true)); context.resolver.available=false;
        PreferenceBridgeClient bridge=new PreferenceBridgeClient(context,disk);
        context.resolver.available=true;
        context.resolver.writeEntered=new CountDownLatch(1); context.resolver.writeRelease=new CountDownLatch(1);
        try {
            bridge.edit().putBoolean(NEED,false).remove(PENDING).apply();
            check(context.resolver.writeEntered.await(4,TimeUnit.SECONDS),"optimistic write did not reach gate");
            check(!bridge.getBoolean(NEED,true) && !bridge.contains(PENDING),"raw disk keys overwrote optimistic consumption");
            context.resolver.writeRelease.countDown(); settle(bridge);
            check(!bridge.getBoolean(NEED,true) && !bridge.contains(PENDING),"confirmed restart consumption changed after refresh");
            scenarios++;
        } finally { context.resolver.writeRelease.countDown(); close(bridge); }
    }

    private static void universalMode() throws Exception {
        Prefs prefs=scoped(false); prefs.data.put("module_config_mode","Universal");
        Ctx context=new Ctx(PERSONAL,prefs); PreferenceBridgeClient bridge=new PreferenceBridgeClient(context,null);
        try {
            check(bridge.getBoolean(NEED,false),"universal restart changed");
            check(bridge.getStringSet(PENDING,Set.of()).equals(Set.of("universal_change")),"universal pending set changed");
            check(!bridge.getBoolean("imagequality",true),"universal mode selected target override");
            scenarios++;
        } finally { close(bridge); }
    }

    public static void main(String[] args) throws Exception {
        providerSnapshotPreservesRestart(); refreshAndConsumption(); targetIsolation();
        diskFallbackAndRecovery(); optimisticEditOnDiskSnapshot(); universalMode();
        System.out.println("PASS: "+scenarios+" provider/client/disk restart scenarios");
    }
}
