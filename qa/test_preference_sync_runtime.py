#!/usr/bin/env python3
"""Run complete WaexApp/HookProvider with real schema, restart and authorization policies.

Platform services, feature-catalog lookup, localization, licensing and external
update/store/keybox side effects are explicit deterministic doubles. Binder
identity and SharedPreferences listener/disk-failure semantics are modeled;
this is not Android Binder or LSPosed instrumentation.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
BASE = Path('app/src/main/java/com/waenhancer')
SOURCES = [
    'xposed/bridge/HookProvider.java', 'xposed/bridge/CallerScopePolicy.java',
    'xposed/bridge/ProviderDerivedKeyPolicy.java', 'xposed/bridge/PreferenceWriteTypePolicy.java',
    'config/PreferenceSyncPolicy.java', 'config/PreferenceStores.java',
    'config/PreferenceSchema.java', 'config/PreferenceRestartTracker.java',
]

STUBS = {
    'androidx/annotation/NonNull.java': 'package androidx.annotation; public @interface NonNull {}',
    'androidx/annotation/Nullable.java': 'package androidx.annotation; public @interface Nullable {}',
    'dagger/hilt/android/HiltAndroidApp.java': 'package dagger.hilt.android; public @interface HiltAndroidApp {}',
    'android/net/Uri.java': '''package android.net;
public final class Uri { final String value; private Uri(String s){value=s;}public static Uri parse(String s){return new Uri(s);}
 public String toString(){return value;}public boolean equals(Object o){return o instanceof Uri&&value.equals(o.toString());}
 public int hashCode(){return value.hashCode();} }''',
    'android/content/Intent.java': '''package android.content;
public class Intent { public final String action; public String target; public final java.util.Map<String,Object> extras=new java.util.HashMap<>();
 public Intent(String action){this.action=action;}public Intent setPackage(String target){this.target=target;return this;}
 public Intent putExtra(String key,String value){extras.put(key,value);return this;} }''',
    'android/content/pm/ApplicationInfo.java': 'package android.content.pm; public class ApplicationInfo { public int uid=10100; }',
    'android/content/pm/PackageInfo.java': 'package android.content.pm; public class PackageInfo { public String versionName="fixture-version"; }',
    'android/content/pm/PackageManager.java': '''package android.content.pm;
public class PackageManager {
 public final java.util.Map<Integer,String[]> owners=new java.util.HashMap<>();public final java.util.List<Integer> queries=new java.util.ArrayList<>();
 public String[] getPackagesForUid(int uid){queries.add(uid);return owners.get(uid);}
 public PackageInfo getPackageInfo(String name,int flags){return new PackageInfo();}
}''',
    'android/content/ContentResolver.java': '''package android.content;
public class ContentResolver { public final java.util.List<String> notifications=new java.util.ArrayList<>();
 public void notifyChange(android.net.Uri uri,Object observer){notifications.add(uri.toString());} }''',
    'android/content/SharedPreferences.java': '''package android.content;
import java.util.*;
public interface SharedPreferences {
 Map<String,?> getAll();String getString(String key,String fallback);Set<String> getStringSet(String key,Set<String> fallback);
 int getInt(String key,int fallback);long getLong(String key,long fallback);float getFloat(String key,float fallback);
 boolean getBoolean(String key,boolean fallback);boolean contains(String key);Editor edit();
 void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener);
 void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener);
 interface OnSharedPreferenceChangeListener { void onSharedPreferenceChanged(SharedPreferences preferences,String key); }
 interface Editor { Editor putString(String key,String value);Editor putStringSet(String key,Set<String> value);
  Editor putInt(String key,int value);Editor putLong(String key,long value);Editor putFloat(String key,float value);
  Editor putBoolean(String key,boolean value);Editor remove(String key);Editor clear();boolean commit();void apply(); }
}''',
    'android/content/FixturePreferences.java': '''package android.content;
import java.util.*;
/** Updates memory before persistence; notifies even if commit returns false, like Android. */
public class FixturePreferences implements SharedPreferences {
 public final Map<String,Object> values=new LinkedHashMap<>();
 public final Set<OnSharedPreferenceChangeListener> listeners=new LinkedHashSet<>();
 public final Deque<Runnable> queued=new ArrayDeque<>();
 public boolean nextCommit=true,deferred=false;public int edits,commits;public final List<String> changed=new ArrayList<>();
 public Map<String,?> getAll(){return new LinkedHashMap<>(values);}public boolean contains(String k){return values.containsKey(k);}
 public String getString(String k,String f){Object v=values.get(k);return v==null?f:(String)v;}
 @SuppressWarnings("unchecked") public Set<String> getStringSet(String k,Set<String> f){Object v=values.get(k);return v==null?f:new LinkedHashSet<>((Set<String>)v);}
 public boolean getBoolean(String k,boolean f){Object v=values.get(k);return v==null?f:(Boolean)v;}
 public int getInt(String k,int f){Object v=values.get(k);return v==null?f:(Integer)v;}
 public long getLong(String k,long f){Object v=values.get(k);return v==null?f:(Long)v;}
 public float getFloat(String k,float f){Object v=values.get(k);return v==null?f:(Float)v;}
 public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l){listeners.add(l);}
 public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l){listeners.remove(l);}
 public void notifyKey(String key){changed.add(key);Runnable action=()->{for(var l:List.copyOf(listeners))l.onSharedPreferenceChanged(this,key);};
  if(deferred)queued.add(action);else action.run();}
 public void drain(){int guard=0;while(!queued.isEmpty()){if(++guard>10000)throw new AssertionError("Listener loop");queued.remove().run();}}
 public Editor edit(){edits++;return new Editor(){final Map<String,Object> writes=new LinkedHashMap<>();final Object removed=new Object();boolean clear;
  public Editor putString(String k,String v){writes.put(k,v==null?removed:v);return this;}
  public Editor putStringSet(String k,Set<String> v){writes.put(k,v==null?removed:new LinkedHashSet<>(v));return this;}
  public Editor putInt(String k,int v){writes.put(k,v);return this;}public Editor putLong(String k,long v){writes.put(k,v);return this;}
  public Editor putFloat(String k,float v){writes.put(k,v);return this;}public Editor putBoolean(String k,boolean v){writes.put(k,v);return this;}
  public Editor remove(String k){writes.put(k,removed);return this;}public Editor clear(){clear=true;return this;}
  public boolean commit(){commits++;boolean success=nextCommit;nextCommit=true;List<String> keys=new ArrayList<>();
   if(clear){values.clear();keys.add(null);}for(var e:writes.entrySet()){String k=e.getKey();Object v=e.getValue();
    if(v==removed){if(values.containsKey(k)){values.remove(k);keys.add(k);}}
    else if(!Objects.equals(values.get(k),v)||!values.containsKey(k)){values.put(k,v);keys.add(k);}}
   for(String key:keys)notifyKey(key);return success;}
  public void apply(){commit();}
 };}
}''',
    'android/content/Context.java': '''package android.content;
import java.io.File;import java.util.*;import android.content.pm.*;
public class Context {
 public static final int MODE_PRIVATE=0;public final Map<String,FixturePreferences> stores=new HashMap<>();
 public final ContentResolver resolver=new ContentResolver();public final PackageManager pm=new PackageManager();
 public final ApplicationInfo info=new ApplicationInfo();public final List<Intent> broadcasts=new ArrayList<>();
 public SharedPreferences getSharedPreferences(String name,int mode){return stores.computeIfAbsent(name,k->new FixturePreferences());}
 public ContentResolver getContentResolver(){return resolver;}public PackageManager getPackageManager(){return pm;}
 public ApplicationInfo getApplicationInfo(){return info;}public String getPackageName(){return "com.waenhancer";}
 public void sendBroadcast(Intent intent){broadcasts.add(intent);}public File getExternalFilesDir(String type){return null;}
 public File getFilesDir(){throw new UnsupportedOperationException("Filesystem writes are outside this fixture");}
}''',
    'android/app/Application.java': '''package android.app;
public class Application extends android.content.Context { protected void attachBaseContext(android.content.Context base){}public void onCreate(){} }''',
    'android/content/ContentValues.java': 'package android.content; public class ContentValues {}',
    'android/database/Cursor.java': 'package android.database; public interface Cursor {}',
    'android/content/ContentProvider.java': '''package android.content;
import android.net.Uri;import android.os.Bundle;import android.database.Cursor;
public abstract class ContentProvider {
 private Context context;public void fixtureAttach(Context c){context=c;}public Context getContext(){return context;}
 public abstract boolean onCreate();public Bundle call(String method,String arg,Bundle extras){return null;}
 public abstract Cursor query(Uri uri,String[] projection,String selection,String[] args,String order);
 public abstract String getType(Uri uri);public abstract Uri insert(Uri uri,ContentValues values);
 public abstract int delete(Uri uri,String selection,String[] args);public abstract int update(Uri uri,ContentValues values,String selection,String[] args);
}''',
    'android/os/Binder.java': '''package android.os;
import java.util.*;
public final class Binder {
 public static int uid=10200,pid=77,clearCount,restoreCount;static long sequence;
 static final Map<Long,int[]> tokens=new HashMap<>();
 public static int getCallingUid(){return uid;}public static int getCallingPid(){return pid;}
 public static long clearCallingIdentity(){clearCount++;long token=++sequence;tokens.put(token,new int[]{uid,pid});uid=10100;pid=1;return token;}
 public static void restoreCallingIdentity(long token){restoreCount++;int[] state=tokens.remove(token);if(state==null)throw new AssertionError("Unknown identity token");uid=state[0];pid=state[1];}
}''',
    'android/os/Bundle.java': '''package android.os;
import java.util.*;
public class Bundle {
 // Only needed to compile the legacy, unexercised message-store return path.
 public static final Bundle EMPTY=new Bundle();
 public final Map<String,Object> values=new HashMap<>();
 public void putString(String k,String v){values.put(k,v);}public String getString(String k){return (String)values.get(k);}
 public String getString(String k,String f){Object v=values.get(k);return v==null?f:(String)v;}
 public void putBoolean(String k,boolean v){values.put(k,v);}public boolean getBoolean(String k){return getBoolean(k,false);}
 public boolean getBoolean(String k,boolean f){Object v=values.get(k);return v==null?f:(Boolean)v;}
 public void putInt(String k,int v){values.put(k,v);}public int getInt(String k){return getInt(k,0);}
 public int getInt(String k,int f){Object v=values.get(k);return v==null?f:(Integer)v;}
 public void putLong(String k,long v){values.put(k,v);}public long getLong(String k){return getLong(k,0L);}
 public long getLong(String k,long f){Object v=values.get(k);return v==null?f:(Long)v;}
 public void putFloat(String k,float v){values.put(k,v);}public float getFloat(String k){Object v=values.get(k);return v==null?0f:(Float)v;}
 public void putParcelable(String k,Object v){values.put(k,v);}public void putBundle(String k,Bundle v){values.put(k,v);}
 public Bundle getBundle(String k){return (Bundle)values.get(k);}public void putStringArrayList(String k,ArrayList<String> v){values.put(k,v);}
 @SuppressWarnings("unchecked") public ArrayList<String> getStringArrayList(String k){return (ArrayList<String>)values.get(k);}
 public void putParcelableArrayList(String k,ArrayList<Bundle> v){values.put(k,v);}
 @SuppressWarnings("unchecked") public ArrayList<Bundle> getParcelableArrayList(String k){return (ArrayList<Bundle>)values.get(k);}
}''',
    'android/os/ParcelFileDescriptor.java': '''package android.os;
public class ParcelFileDescriptor {
 public static final int MODE_CREATE=1,MODE_TRUNCATE=2,MODE_READ_WRITE=4;
 public static ParcelFileDescriptor open(java.io.File file,int mode)throws java.io.FileNotFoundException{throw new java.io.FileNotFoundException("Fixture prevents external file writes");}
}''',
    'com/waenhancer/config/FeatureCatalog.java': '''package com.waenhancer.config;
public class FeatureCatalog { public static void initialize(android.content.Context c){}public static Object findByKey(String key){return null;} }''',
    'com/waenhancer/utils/LocaleHelper.java': '''package com.waenhancer.utils;
public class LocaleHelper {public static android.content.Context wrapContext(android.content.Context c){return c;}
 public static String getSelectedLanguageCode(android.content.Context c){return "system";}public static void applyLanguage(android.content.Context c,String lang){} }''',
    'com/waenhancer/utils/UiText.java': '''package com.waenhancer.utils;
public class UiText { public static void initialize(android.content.Context c,android.content.SharedPreferences p){} }''',
    'com/waenhancer/utils/WhatsAppPackageDetector.java': '''package com.waenhancer.utils;
public class WhatsAppPackageDetector { public static final java.util.Set<String> supported=new java.util.LinkedHashSet<>();
 public static java.util.Set<String> getSupportedPackages(android.content.Context c){return new java.util.LinkedHashSet<>(supported);} }''',
    'com/waenhancer/licensing/LicenseManager.java': '''package com.waenhancer.licensing;
public class LicenseManager { public static String status="INACTIVE";public static String getProStatus(android.content.Context c){return status;} }''',
    'com/waenhancer/licensing/features/BootloaderSpooferFeature.java': '''package com.waenhancer.licensing.features;
public class BootloaderSpooferFeature {
 public static final String ENABLED="bootloader_spoofer",CUSTOM_ENABLED="bootloader_spoofer_custom",CUSTOM_XML="bootloader_spoofer_xml",DEFAULT_XML="bootloader_spoofer_default_xml";
 public static void syncDefaultKeyboxAsync(android.content.Context c){} }''',
    'com/waenhancer/update/UpdateCheckCoordinator.java': '''package com.waenhancer.update;
public class UpdateCheckCoordinator { public static final String TRIGGER_WHATSAPP="whatsapp";
 public static void request(android.content.Context c,String trigger,boolean flag){throw new UnsupportedOperationException("External update requests are outside the fixture");} }''',
    'com/waenhancer/xposed/core/db/PreservedMessageStore.java': '''package com.waenhancer.xposed.core.db;
public class PreservedMessageStore {
 public static PreservedMessageStore getInstance(android.content.Context c){return new PreservedMessageStore();}
 public void insertPreservedMessage(String a,String b,String c,String d,String e,long timestamp,boolean fromMe,boolean group){throw new UnsupportedOperationException("External message store is outside the fixture");}
 public boolean tryInsertPreservedMessage(String a,String b,String c,String d,String e,long timestamp,boolean fromMe,boolean group){throw new UnsupportedOperationException("External message store is outside the fixture");}
}''',
}

GATES = {
    'AUDIO_TO_VOICE_STATUS': ('audio_to_voice_status', 'send_audio_as_voice_status'),
    'MESSAGE_BOMBER': ('message_bomber', 'message_bomber'),
    'STATUS_SPLITTER': ('status_splitter', 'status_video_splitter'),
    'FILE_SIZE_SPOOFER': ('file_size_spoofer', 'file_size_spoofer'),
    'FILTER_ITEMS': ('filter_items', 'filter_items'),
    'UNLOCK_WA_PLUS': ('unlock_wa_plus', 'unlock_premium_customization'),
}
gate_fields = []
for name, (value, preference) in GATES.items():
    gate_fields.append(f'public static final String {name}="{value}",{name}_PREF="{preference}",{name}_ENTITLEMENT="pro_entitlement_{value}";')
STUBS['com/waenhancer/licensing/ProFeatureGate.java'] = '''package com.waenhancer.licensing;
public class ProFeatureGate {
 public static final java.util.Set<String> entitled=new java.util.HashSet<>();
 public static boolean isEntitled(android.content.Context c,String feature){return entitled.contains(feature);}
''' + '\n'.join(gate_fields) + '\n}'


def run_contracts(source_root, out, kotlinc, baseline_root=None):
    root, out = Path(source_root).resolve(), Path(out).resolve()
    out.mkdir(parents=True, exist_ok=True)
    generated, classes = out / 'generated', out / 'classes'
    generated.mkdir(exist_ok=True); classes.mkdir(exist_ok=True)
    stub_paths = []
    for name, content in STUBS.items():
        path = generated / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding='utf-8'); stub_paths.append(path)
    paths = {name: root / BASE / name for name in SOURCES + ['app/WaexApp.kt']}
    if baseline_root:
        for name in ['app/WaexApp.kt', 'xposed/bridge/HookProvider.java']:
            paths[name] = Path(baseline_root).resolve() / name
    stdlib = Path(kotlinc).resolve().parents[1] / 'lib/kotlin-stdlib.jar'
    if not stdlib.is_file():
        raise RuntimeError('Kotlin stdlib missing next to compiler')
    cp = os.pathsep.join((str(classes), str(stdlib)))
    run = lambda command: subprocess.run(command, check=True, text=True, capture_output=True, timeout=60)
    run(['javac', '--release', '17', '-d', str(classes), *map(str, stub_paths),
         *[str(paths[name]) for name in SOURCES]])
    run([str(kotlinc), '-jvm-target', '17', '-classpath', str(classes), '-d', str(classes), str(paths['app/WaexApp.kt'])])
    fixture = ROOT / 'qa/fixtures/PreferenceSyncRuntimeChecks.java'
    run(['javac', '--release', '17', '-cp', cp, '-d', str(classes), str(fixture)])
    xml = ET.parse(root / 'app/src/main/res/values/arrays.xml').getroot()
    scopes = [i.text.strip() for a in xml.findall('string-array') if a.attrib.get('name') == 'xposed_scope'
              for i in a.findall('item') if i.text and i.text.strip()]
    if not scopes:
        raise AssertionError('No configured Xposed scope')
    result = subprocess.run(['java', '-ea', '-cp', cp, 'qa.PreferenceSyncRuntimeChecks', *scopes],
                            text=True, capture_output=True, timeout=60)
    output = result.stdout + result.stderr
    (out / 'runtime.log').write_text(output, encoding='utf-8')
    report = {'result': 'PASS' if not result.returncode else 'FAIL', 'exit_code': result.returncode,
              'production': {name: {'path': str(path), 'sha256': hashlib.sha256(path.read_bytes()).hexdigest()}
                             for name, path in paths.items()},
              'fixture_sha256': hashlib.sha256(fixture.read_bytes()).hexdigest(),
              'complete_app_and_provider_compiled': True,
              'complete_schema_restart_stores_and_scope_policies_compiled': True,
              'platform_and_licensing_doubles': True, 'device_instrumentation': False,
              'baseline_app_provider_override': bool(baseline_root), 'output': output}
    (out / 'result.json').write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
    print(output, end='')
    return result.returncode


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source-root', type=Path, default=ROOT)
    parser.add_argument('--baseline-root', type=Path)
    parser.add_argument('--out', type=Path)
    parser.add_argument('--kotlinc', default=os.environ.get('KOTLINC') or shutil.which('kotlinc'))
    args = parser.parse_args()
    for tool in ('javac', 'java'):
        if not shutil.which(tool):
            parser.error('Required executable missing from PATH: ' + tool)
    if not args.kotlinc:
        parser.error('Supply --kotlinc or add the Kotlin compiler to PATH')
    try:
        if args.out:
            return run_contracts(args.source_root, args.out, args.kotlinc, args.baseline_root)
        with tempfile.TemporaryDirectory(prefix='waex_preference_sync_') as temp:
            return run_contracts(args.source_root, Path(temp), args.kotlinc, args.baseline_root)
    except subprocess.CalledProcessError as error:
        raise SystemExit((error.stdout or '') + (error.stderr or ''))


if __name__ == '__main__':
    raise SystemExit(main())
