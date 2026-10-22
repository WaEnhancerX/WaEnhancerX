#!/usr/bin/env python3
"""Run the complete MainHook and preference bridge with explicit JVM platform substitutes.

Captured Xposed callbacks execute unchanged. The detector transport, feature registry, Android
looper/Binder and private UI are substitutes; this is not LSPosed or an Android startup benchmark.
Blocking I/O uses latches, and a behavioral timeout is reported separately from compilation.
"""
import argparse
import ast
import hashlib
import json
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / 'app/src/main/java/com/waenhancer'

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--out', type=Path, required=True)
    parser.add_argument('--main-source', type=Path, default=SRC/'xposed/MainHook.java')
    parser.add_argument('--bridge-source', type=Path, default=SRC/'xposed/bridge/client/PreferenceBridgeClient.java')
    parser.add_argument('--dispatcher-source', type=Path, default=SRC/'xposed/core/RuntimeReportDispatcher.java')
    parser.add_argument('--scenario', default='all', choices=['all','package','hooks','update','heartbeat','preferences','initial_sync','interrupted_initial_sync','provider_fallback','unsupported','initialization_failure','fallback','duplicate','optional_failure'])
    args = parser.parse_args()
    out = args.out.resolve(); out.mkdir(parents=True, exist_ok=True)
    tree = ast.parse((ROOT/'qa/test_bridge_contract.py').read_text())
    stubs = ast.literal_eval(next(n.value for n in tree.body if isinstance(n, ast.Assign)
        and any(isinstance(t, ast.Name) and t.id == 'STUBS' for t in n.targets)))
    stubs.pop('test/BridgeContract.java')
    stubs.update(SUBSTITUTES)
    ids = sorted(set(re.findall(r'R\.string\.(\w+)', args.main_source.read_text())))
    stubs['com/waenhancer/app/R.java'] = 'package com.waenhancer.app; public final class R { public static final class string { '+''.join(f'public static final int {key}={i};' for i,key in enumerate(ids))+' } }'
    for name, text in stubs.items():
        path = out/'src'/name; path.parent.mkdir(parents=True, exist_ok=True); path.write_text(text)
    fixture = ROOT/'qa/fixtures/InjectionIoRuntimeChecks.java'
    source = out/'src/fixture/InjectionIoRuntimeChecks.java'; source.write_text(fixture.read_text())
    sources = [args.main_source, args.bridge_source, args.dispatcher_source]
    compiled = subprocess.run(['javac','--release','17','-d',str(out/'classes'),
        *map(str, sorted((out/'src').rglob('*.java'))), *map(str,sources)], text=True, capture_output=True, timeout=60)
    (out/'compile.log').write_text(compiled.stdout+compiled.stderr)
    if compiled.returncode:
        raise SystemExit('INFRASTRUCTURE_FAILURE: compile; see '+str(out/'compile.log'))
    cases = ['package','hooks','update','heartbeat','preferences','initial_sync','interrupted_initial_sync','provider_fallback','unsupported',
             'initialization_failure','fallback','duplicate','optional_failure'] if args.scenario == 'all' else [args.scenario]
    results = []
    for case in cases:
        result = subprocess.run(['java','-cp',str(out/'classes'),'fixture.InjectionIoRuntimeChecks',case],
            text=True,capture_output=True,timeout=20)
        (out/(case+'.log')).write_text(result.stdout+result.stderr)
        classification = ('passed' if result.returncode == 0 else 'behavioral_failure') if result.stdout.startswith('RESULT ') else 'infrastructure_failure'
        record = {'scenario':case,'exit':result.returncode,'classification':classification,'output':result.stdout.strip()}
        results.append(record); print(json.dumps(record),flush=True)
    manifest = {'sources':{str(p):hashlib.sha256(p.read_bytes()).hexdigest() for p in sources},
        'fixture_sha256':hashlib.sha256(fixture.read_bytes()).hexdigest(), 'results':results,
        'scope':'complete MainHook/PreferenceBridgeClient; explicit platform/detector/registry substitutes; not device benchmark'}
    (out/'result.json').write_text(json.dumps(manifest,indent=2)+'\n')
    raise SystemExit(1 if any(r['exit'] for r in results) else 0)

SUBSTITUTES = {
 'android/annotation/SuppressLint.java':'package android.annotation; public @interface SuppressLint {String[] value();}',
 'android/content/Context.java':'''package android.content;
import java.util.*;
public abstract class Context {
 public static final int RECEIVER_EXPORTED=2; public static final String CLIPBOARD_SERVICE="clipboard";
 public final Map<String,BroadcastReceiver> receivers=new HashMap<>();
 public Context getApplicationContext(){return this;} public String getPackageName(){return "com.whatsapp";}
 public abstract ContentResolver getContentResolver();
 public Intent registerReceiver(BroadcastReceiver r,IntentFilter f,String permission,android.os.Handler h){receivers.put(f.action,r);return null;}
 public Intent registerReceiver(BroadcastReceiver r,IntentFilter f,String p,android.os.Handler h,int flags){return registerReceiver(r,f,p,h);}
 public Object getSystemService(String name){return null;}
}''',
 'android/content/ContextWrapper.java':'package android.content; public abstract class ContextWrapper extends Context {}',
 'android/content/IntentFilter.java':'package android.content; public class IntentFilter {public final String action;public IntentFilter(String a){action=a;}}',
 'android/content/BroadcastReceiver.java':'package android.content; public abstract class BroadcastReceiver {public abstract void onReceive(Context c,Intent i);}',
 'android/content/Intent.java':'''package android.content;
public class Intent {public String value; public Intent(String a){} public String getStringExtra(String k){return value;}}
''',
 'android/content/ClipboardManager.java':'package android.content; public class ClipboardManager {public void setPrimaryClip(ClipData c){}}',
 'android/content/ClipData.java':'package android.content; public class ClipData {public static ClipData newPlainText(String a,String b){return new ClipData();}}',
 'android/content/DialogInterface.java':'package android.content; public interface DialogInterface {void dismiss(); interface OnClickListener {void onClick(DialogInterface d,int which);}}',
 'android/app/Application.java':'package android.app; public abstract class Application extends android.content.Context {}',
 'android/app/Activity.java':'''package android.app; public abstract class Activity extends android.content.Context {
public boolean isFinishing(){return false;} public boolean isDestroyed(){return false;} public void runOnUiThread(Runnable r){r.run();}}''',
 'android/os/Handler.java':'''package android.os; import java.util.concurrent.*;
public class Handler {public static final BlockingQueue<Runnable> delayed=new LinkedBlockingQueue<>();
public Handler(Looper l){} public boolean post(Runnable r){r.run();return true;}
public boolean postDelayed(Runnable r,long d){delayed.add(r);return true;}}''',
 'android/os/Build.java':'package android.os; public class Build {public static class VERSION {public static int SDK_INT=34;}}',
 'android/os/Process.java':'package android.os; public class Process {public static int killed;public static int myPid(){return 99;}public static void killProcess(int p){killed=p;}}',
 'android/widget/Toast.java':'package android.widget; public class Toast {public static final int LENGTH_SHORT=0;public static Toast makeText(android.content.Context c,String s,int d){return new Toast();}public void show(){}}',
 'de/robv/android/xposed/IXposedHookLoadPackage.java':'package de.robv.android.xposed; public interface IXposedHookLoadPackage {void handleLoadPackage(callbacks.XC_LoadPackage.LoadPackageParam p) throws Throwable;}'.replace('callbacks.XC_LoadPackage','de.robv.android.xposed.callbacks.XC_LoadPackage'),
 'de/robv/android/xposed/IXposedHookZygoteInit.java':'package de.robv.android.xposed; public interface IXposedHookZygoteInit {class StartupParam {public String modulePath;}void initZygote(StartupParam p) throws Throwable;}',
 'de/robv/android/xposed/callbacks/XC_LoadPackage.java':'package de.robv.android.xposed.callbacks; public class XC_LoadPackage {public static class LoadPackageParam {public String packageName,processName;public ClassLoader classLoader;}}',
 'de/robv/android/xposed/XC_MethodHook.java':'''package de.robv.android.xposed;
public abstract class XC_MethodHook {public static class MethodHookParam {public Object thisObject;public Object[] args;public void setResult(Object r){}}
protected void afterHookedMethod(MethodHookParam p)throws Throwable{} protected void beforeHookedMethod(MethodHookParam p)throws Throwable{}
public void runAfter(Object owner,Object...args) {MethodHookParam p=new MethodHookParam();p.thisObject=owner;p.args=args;
try{afterHookedMethod(p);}catch(Throwable t){throw new RuntimeException(t);}}}''',
 'de/robv/android/xposed/XC_MethodReplacement.java':'package de.robv.android.xposed; public abstract class XC_MethodReplacement extends XC_MethodHook {public static XC_MethodHook returnConstant(Object v){return new XC_MethodHook(){};}}',
 'de/robv/android/xposed/XposedHelpers.java':'''package de.robv.android.xposed; import java.util.*;
public class XposedHelpers {public static final Map<String,XC_MethodHook> hooks=new HashMap<>();
public static Object findAndHookMethod(String c,ClassLoader l,String n,Object...a){hooks.put(c+"#"+n,(XC_MethodHook)a[a.length-1]);return null;}}''',
 'de/robv/android/xposed/XposedBridge.java':'package de.robv.android.xposed; public class XposedBridge {public static void log(String s){System.err.println(s);}public static int getXposedVersion(){return 100;}}',
 'de/robv/android/xposed/XSharedPreferences.java':'package de.robv.android.xposed; public class XSharedPreferences extends fixture.TestPrefs {public XSharedPreferences(String p,String n){}public void reload(){}}',
 'fixture/TestPrefs.java':'''package fixture; import java.util.*; import android.content.SharedPreferences;
public class TestPrefs implements SharedPreferences {
 public Map<String,?>getAll(){return Map.of("fresh",false,"show_hook_toast",false);} public boolean getBoolean(String k,boolean d){return false;}
 public String getString(String k,String d){return d;} public Set<String>getStringSet(String k,Set<String>d){return d;}
 public int getInt(String k,int d){return d;} public long getLong(String k,long d){return d;}public float getFloat(String k,float d){return d;}
 public boolean contains(String k){return false;}public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l){}
 public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l){}
 public Editor edit(){return (Editor)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{Editor.class},(p,m,a)->m.getName().equals("commit")?Boolean.TRUE:m.getReturnType()==void.class?null:p);}
}''',
 'com/waenhancer/config/PreferenceStores.java':'package com.waenhancer.config; public class PreferenceStores {public static android.content.SharedPreferences publicStore(android.content.Context c){return new fixture.TestPrefs();}}',
 'com/waenhancer/utils/UiText.java':'package com.waenhancer.utils; public class UiText {public static void initialize(android.content.Context c,android.content.SharedPreferences p){}public static String get(int id,String text){return text;}}',
 'com/waenhancer/utils/WhatsAppPackageDetector.java':'''package com.waenhancer.utils;
import android.content.Context;import android.os.Bundle;import android.net.Uri;
public class WhatsAppPackageDetector {
 public static boolean isWhatsAppPackageName(String p){return "com.whatsapp".equals(p);}
 public static void registerHookedPackage(Context c,String p){c.getContentResolver().call(Uri.parse("content://fixture"),"register_hooked_package",p,null);}
 public static void reportRuntimeHealth(Context c,String p,String status,String bridge,String err){reportRuntimeHealth(c,p,status,bridge,err,System.currentTimeMillis());}
 public static void reportRuntimeHealth(Context c,String p,String status,String bridge,String err,long observed){Bundle b=new Bundle();b.putString("runtime_status",status);b.putString("bridge_status",bridge);b.putLong("timestamp",observed);c.getContentResolver().call(Uri.parse("content://fixture"),"report_runtime_health",p,b);}
}''',
 'com/waenhancer/xposed/utils/ModuleStatus.java':'package com.waenhancer.xposed.utils; public class ModuleStatus {}',
 'com/waenhancer/xposed/utils/ActivityTracker.java':'''package com.waenhancer.xposed.utils;
public class ActivityTracker {public interface ActivityListener {void onActivityResumed(android.app.Activity a);}
 public static void install(android.app.Application a){} public static void addListener(ActivityListener l){}
 public static void removeListener(ActivityListener l){} public static android.app.Activity getCurrentActivity(){return null;}}
''',
 'com/waenhancer/xposed/core/FeatureRegistry.java':'''package com.waenhancer.xposed.core;
import android.content.*;import java.util.concurrent.atomic.AtomicInteger;
public class FeatureRegistry {public static final AtomicInteger attempted=new AtomicInteger(),initialized=new AtomicInteger();public static boolean fail,expectedFresh=true;private final SharedPreferences p;
 public FeatureRegistry(Context c,ClassLoader l,SharedPreferences p){this.p=p;}
 public void initializeAll(){attempted.incrementAndGet();if(p.getBoolean("fresh",false)!=expectedFresh)throw new AssertionError("unexpected initial preference snapshot");initialized.incrementAndGet();if(fail)throw new IllegalStateException("fixture initialization");}
 public boolean hasFailures(){return false;}
 public void reportInjectedHooks(Context c,String p){c.getContentResolver().call(android.net.Uri.parse("content://fixture"),"register_injected_hooks",p,null);}}
''',
 'com/waenhancer/xposed/core/RestartPromptController.java':'package com.waenhancer.xposed.core;public class RestartPromptController {public static void initialize(android.content.SharedPreferences p){}}',
 'com/waenhancer/xposed/core/VersionGuard.java':'package com.waenhancer.xposed.core;public class VersionGuard {public static boolean allowed=true;public static boolean verifyAndGuard(android.content.Context c,ClassLoader l,android.content.SharedPreferences p){return allowed;}public static String runtimeStatus(){return "UNSUPPORTED_VERSION";}}',
 'com/waenhancer/xposed/features/updates/WhatsAppUpdateSheet.java':'package com.waenhancer.xposed.features.updates;public class WhatsAppUpdateSheet {public static void init(android.content.SharedPreferences p){}}',
 'com/waenhancer/xposed/core/components/WaexBottomSheet.java':'''package com.waenhancer.xposed.core.components;
public class WaexBottomSheet {public WaexBottomSheet(android.app.Activity a){}public WaexBottomSheet asBottomSheet(){return this;}
public WaexBottomSheet setTitle(String s){return this;}public WaexBottomSheet setMessage(String s){return this;}
public WaexBottomSheet setPositiveButton(String s,android.content.DialogInterface.OnClickListener l){return this;}
public WaexBottomSheet setNegativeButton(String s,android.content.DialogInterface.OnClickListener l){return this;}
public void show(){}}
''',
}

if __name__ == '__main__': main()
