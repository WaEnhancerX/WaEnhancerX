#!/usr/bin/env python3
"""Execute the complete HideReceiptsHook and BaseFeature with explicit JVM boundaries.

Android, Xposed, DexKit's positive lookup cache, BlueOnReply's capture sink and
contact preferences are doubles. The complete production hook, reflection
bootstrap, callback registration, stanza transformations, predicates and receipt
allowance/job policies are compiled unchanged. No host APK, native DEX matching,
network connection or Android injection is exercised.
"""
import argparse
import hashlib
import json
import re
import os
from pathlib import Path
import shutil
import subprocess
import tempfile


ROOT = Path(__file__).resolve().parents[1]
AREA = Path('app/src/main/java/com/waenhancer/xposed/features/privacy')
PRODUCTION = [
    'HideReceiptsHook.java', 'ReceiptJobExecutionPolicy.java',
    'PlayedReceiptDispatchPolicy.java', 'ReceiptJidPolicy.java',
    'BoundedReceiptAllowance.java', 'RecipientReceiptAllowance.java',
    'ExplicitReadReceiptPolicy.java',
]

STUBS = {'android/content/Context.java': 'package android.content; public class Context { public String '
                                 'getPackageName(){return "com.whatsapp";} }',
 'android/content/SharedPreferences.java': 'package android.content;\n'
                                           'public interface SharedPreferences {\n'
                                           ' boolean getBoolean(String key, boolean fallback);\n'
                                           ' default String getString(String key,String fallback){Object '
                                           'v=getAll().get(key);return v==null?fallback:(String)v;}\n'
                                           ' boolean contains(String key); java.util.Map<String,?> getAll();\n'
                                           '}',
 'android/os/Message.java': 'package android.os;\n'
                            'public class Message { public int what, arg1, arg2; public Object obj; }',
 'android/os/SystemClock.java': 'package android.os;\n'
                                'public final class SystemClock { public static long elapsedRealtime(){return '
                                '1_000_000L;} }',
 'androidx/annotation/NonNull.java': 'package androidx.annotation; public @interface NonNull {}',
 'org/json/JSONObject.java': 'package org.json;\n'
                             'public class JSONObject {\n'
                             ' public final java.util.Map<String,Boolean> values = new java.util.HashMap<>();\n'
                             ' public JSONObject put(String key, boolean value){values.put(key,value);return this;}\n'
                             ' public boolean optBoolean(String key, boolean fallback){return '
                             'values.getOrDefault(key,fallback);}\n'
                             '}',
 'com/waenhancer/xposed/features/privacy/CustomPrivacyHook.java': 'package com.waenhancer.xposed.features.privacy;\n'
                                                                  'import android.content.SharedPreferences; import '
                                                                  'org.json.JSONObject;\n'
                                                                  'public final class CustomPrivacyHook {\n'
                                                                  ' public static final '
                                                                  'java.util.Map<String,JSONObject> contacts = new '
                                                                  'java.util.HashMap<>();\n'
                                                                  ' public static int lookups;\n'
                                                                  ' public static JSONObject '
                                                                  'getContactPrivacy(SharedPreferences ignored,String '
                                                                  'jid){lookups++;return contacts.get(jid);}\n'
                                                                  '}',
 'com/waenhancer/xposed/features/privacy/BlueOnReplyHook.java': 'package com.waenhancer.xposed.features.privacy;\n'
                                                                'import java.util.*; import java.lang.reflect.*;\n'
                                                                'public final class BlueOnReplyHook {\n'
                                                                ' public static final List<String> captured=new '
                                                                'ArrayList<>(),prepared=new ArrayList<>();\n'
                                                                ' static final Map<Object,String> staged=new '
                                                                'IdentityHashMap<>();\n'
                                                                ' public static boolean '
                                                                'isManualStatusSeenEnabled(android.content.SharedPreferences '
                                                                'p){return false;}\n'
                                                                ' public static boolean isManualStatusReceipt(Object '
                                                                'job){return false;}\n'
                                                                ' public static final Set<Object> '
                                                                'manualChats=Collections.newSetFromMap(new '
                                                                'IdentityHashMap<>());\n'
                                                                ' public static boolean isManualChatReceipt(Object '
                                                                'job){return manualChats.contains(job);}\n'
                                                                ' public static void '
                                                                'captureSuppressedStatusReadJob(Constructor<?> '
                                                                'c,Object[] a){throw new AssertionError("Manual status '
                                                                'outside fixture");}\n'
                                                                ' public static void prepareSuppressedReadJob(String '
                                                                'jid,Constructor<?> c,Object[] args,Object '
                                                                'job){prepared.add(jid);staged.put(job,jid);}\n'
                                                                ' public static void recordSuppressedReadJob(Object '
                                                                'job){String '
                                                                'jid=staged.get(job);if(jid!=null)captured.add(jid);}\n'
                                                                ' public static Set<String> '
                                                                'readReceiptMessageIds(Object job){try{return new '
                                                                'LinkedHashSet<>(Arrays.asList((String[])job.getClass().getField("ids").get(job)));}catch(ReflectiveOperationException '
                                                                'e){throw new AssertionError(e);}}\n'
                                                                ' public static void recordObservedReceipt(String '
                                                                'jid,Object typed,String id){throw new '
                                                                'AssertionError("Plaintext capture outside stanza '
                                                                'fixture");}\n'
                                                                '}',
 'de/robv/android/xposed/XC_MethodHook.java': 'package de.robv.android.xposed;\n'
                                              'import java.lang.reflect.Member;\n'
                                              'public class XC_MethodHook {\n'
                                              ' protected void beforeHookedMethod(MethodHookParam param) throws '
                                              'Throwable {}\n'
                                              ' protected void afterHookedMethod(MethodHookParam param) throws '
                                              'Throwable {}\n'
                                              ' public final class Unhook {\n'
                                              '  final Member member; public Unhook(Member m){member=m;} public Member '
                                              'getHookedMethod(){return member;}\n'
                                              '  public XC_MethodHook getCallback(){return XC_MethodHook.this;}\n'
                                              '  public void unhook(){java.util.List<XC_MethodHook> '
                                              'list=XposedBridge.hooks.get(member);if(list!=null)list.remove(XC_MethodHook.this);}\n'
                                              ' }\n'
                                              ' public static class MethodHookParam {\n'
                                              '  public Member method; public Object thisObject; public Object[] '
                                              'args;\n'
                                              '  Object result; Throwable throwable; boolean early;\n'
                                              '  public Object getResult(){return result;} public boolean '
                                              'hasThrowable(){return throwable!=null;}\n'
                                              '  public Throwable getThrowable(){return throwable;}\n'
                                              '  public void setResult(Object '
                                              'value){result=value;throwable=null;early=true;}\n'
                                              ' }\n'
                                              '}',
 'de/robv/android/xposed/XposedBridge.java': 'package de.robv.android.xposed;\n'
                                             'import java.lang.reflect.*; import java.util.*;\n'
                                             'public final class XposedBridge {\n'
                                             ' public static final Map<Member,List<XC_MethodHook>> hooks=new '
                                             'HashMap<>();\n'
                                             ' public static final List<String> logs=new ArrayList<>();\n'
                                             ' public static void log(String value){logs.add(value);}\n'
                                             ' public static XC_MethodHook.Unhook hookMethod(Member '
                                             'method,XC_MethodHook callback){hooks.computeIfAbsent(method,k->new '
                                             'ArrayList<>()).add(callback);return callback.new Unhook(method);}\n'
                                             ' public static Set<XC_MethodHook.Unhook> hookAllConstructors(Class<?> '
                                             'type,XC_MethodHook callback){Set<XC_MethodHook.Unhook> r=new '
                                             'LinkedHashSet<>();for(Constructor<?> '
                                             'c:type.getDeclaredConstructors())r.add(hookMethod(c,callback));return '
                                             'r;}\n'
                                             ' public static Set<XC_MethodHook.Unhook> hookAllMethods(Class<?> '
                                             'type,String name,XC_MethodHook cb){Set<XC_MethodHook.Unhook> r=new '
                                             'LinkedHashSet<>();for(Method '
                                             'm:type.getDeclaredMethods())if(m.getName().equals(name))r.add(hookMethod(m,cb));return '
                                             'r;}\n'
                                             ' public static void log(Throwable t){java.io.StringWriter w=new '
                                             'java.io.StringWriter();t.printStackTrace(new '
                                             'java.io.PrintWriter(w));logs.add(w.toString());}\n'
                                             ' public static Object invoke(Method method,Object receiver,Object... '
                                             'args)throws Throwable{\n'
                                             '  XC_MethodHook.MethodHookParam p=new '
                                             'XC_MethodHook.MethodHookParam();p.method=method;p.thisObject=receiver;p.args=args;\n'
                                             '  List<XC_MethodHook> callbacks=new '
                                             'ArrayList<>(hooks.getOrDefault(method,List.of()));int executed=0;\n'
                                             '  for(XC_MethodHook '
                                             'callback:callbacks){callback.beforeHookedMethod(p);executed++;if(p.early)break;}\n'
                                             '  '
                                             'if(!p.early){try{p.result=method.invoke(receiver,p.args);}catch(InvocationTargetException '
                                             'e){p.throwable=e.getCause();}}\n'
                                             '  for(int i=executed-1;i>=0;i--)callbacks.get(i).afterHookedMethod(p);\n'
                                             '  if(p.throwable!=null)throw p.throwable;return p.result;\n'
                                             ' }\n'
                                             ' public static Object construct(Constructor<?> constructor,Object... '
                                             'args)throws Throwable{\n'
                                             '  XC_MethodHook.MethodHookParam p=new '
                                             'XC_MethodHook.MethodHookParam();p.method=constructor;p.args=args;\n'
                                             '  p.thisObject=constructor.newInstance(args);p.result=p.thisObject;\n'
                                             '  for(XC_MethodHook '
                                             'callback:hooks.getOrDefault(constructor,List.of()))callback.afterHookedMethod(p);\n'
                                             '  return p.thisObject;\n'
                                             ' }\n'
                                             '}',
 'de/robv/android/xposed/XposedHelpers.java': 'package de.robv.android.xposed;\n'
                                              'import java.lang.reflect.*; import java.util.*;\n'
                                              'public final class XposedHelpers {\n'
                                              ' private static final Map<Object,Map<String,Object>> extras=new '
                                              'IdentityHashMap<>();\n'
                                              ' public static Class<?> findClassIfExists(String name,ClassLoader '
                                              'ignored){return null;}\n'
                                              ' public static Object getObjectField(Object t,String n){try{Class<?> '
                                              'c=t.getClass();while(c!=null){try{Field '
                                              'f=c.getDeclaredField(n);f.setAccessible(true);return '
                                              'f.get(t);}catch(NoSuchFieldException e){c=c.getSuperclass();}}throw new '
                                              'IllegalArgumentException(n);}catch(ReflectiveOperationException '
                                              'e){throw new IllegalArgumentException(e);}}\n'
                                              ' public static Object callMethod(Object t,String '
                                              'n,Object...args){try{return '
                                              't.getClass().getMethod(n).invoke(t,args);}catch(ReflectiveOperationException '
                                              'e){throw new IllegalArgumentException(e);}}\n'
                                              ' public static Object setAdditionalInstanceField(Object target,String '
                                              'name,Object value){return extras.computeIfAbsent(target,k->new '
                                              'HashMap<>()).put(name,value);}\n'
                                              ' public static Object getAdditionalInstanceField(Object target,String '
                                              'name){return extras.getOrDefault(target,Map.of()).get(name);}\n'
                                              ' public static Object removeAdditionalInstanceField(Object '
                                              'target,String name){Map<String,Object> m=extras.get(target);return '
                                              'm==null?null:m.remove(name);}\n'
                                              ' public static Class<?> findClass(String n,ClassLoader l){try{return '
                                              'Class.forName(n,false,l);}catch(Exception e){throw new '
                                              'IllegalArgumentException(e);}}\n'
                                              ' public static Field findField(Class<?> c,String n){try{Field '
                                              'f=c.getDeclaredField(n);f.setAccessible(true);return f;}catch(Exception '
                                              'e){throw new IllegalArgumentException(e);}}\n'
                                              ' public static Field findFieldIfExists(Class<?> c,String n){try{return '
                                              'findField(c,n);}catch(RuntimeException e){return null;}}\n'
                                              ' public static Method findMethodExact(Class<?> c,String n,Object... '
                                              'p){try{Class<?>[] types=Arrays.copyOf(p,p.length,Class[].class);Method '
                                              'm=c.getDeclaredMethod(n,types);m.setAccessible(true);return '
                                              'm;}catch(Exception e){throw new IllegalArgumentException(e);}}\n'
                                              ' public static Method findMethodExactIfExists(Class<?> c,String '
                                              'n,Object... p){try{return '
                                              'findMethodExact(c,n,p);}catch(RuntimeException e){return null;}}\n'
                                              ' public static Method findMethodBestMatch(Class<?> c,String n,Object... '
                                              'p){throw new AssertionError("Best-match outside stanza fixture");}\n'
                                              ' public static XC_MethodHook.Unhook findAndHookMethod(Class<?> c,String '
                                              'n,Object... p){Object[] types=Arrays.copyOf(p,p.length-1);return '
                                              'XposedBridge.hookMethod(findMethodExact(c,n,types),(XC_MethodHook)p[p.length-1]);}\n'
                                              ' public static XC_MethodHook.Unhook findAndHookMethod(String '
                                              'c,ClassLoader l,String n,Object... p){return '
                                              'findAndHookMethod(findClass(c,l),n,p);}\n'
                                              '}\n',
 'com/waenhancer/xposed/core/devkit/DexSearchEngine.java': 'package com.waenhancer.xposed.core.devkit;\n'
                                                           'import android.content.Context; import '
                                                           'java.lang.reflect.Method; import java.util.*; import '
                                                           'java.util.function.BiFunction;\n'
                                                           'import org.luckypray.dexkit.DexKitBridge;\n'
                                                           '/** Explicit positive-cache boundary: selectors are '
                                                           'compiled but native queries do not run. */\n'
                                                           'public final class DexSearchEngine {\n'
                                                           ' public interface DexKitQuery {Method execute(DexKitBridge '
                                                           'b,ClassLoader l)throws Throwable;}\n'
                                                           ' public interface DexKitClassQuery {Class<?> '
                                                           'execute(DexKitBridge b,ClassLoader l)throws Throwable;}\n'
                                                           ' private static final DexSearchEngine INSTANCE=new '
                                                           'DexSearchEngine();\n'
                                                           ' public static final Map<String,Class<?>> classes=new '
                                                           'HashMap<>();\n'
                                                           ' public static final Map<String,Method> methods=new '
                                                           'HashMap<>();\n'
                                                           ' public void initialize(Context c){} public DexKitBridge '
                                                           'getBridge(){return null;}\n'
                                                           ' public static DexSearchEngine getInstance(){return '
                                                           'INSTANCE;}\n'
                                                           ' public Class<?> findClassWithCache(Context c,ClassLoader '
                                                           'loader,String key,DexKitClassQuery finder){return '
                                                           'classes.get(key);}\n'
                                                           ' public Method findMethodWithCache(Context c,ClassLoader '
                                                           'loader,String key,DexKitQuery finder){return '
                                                           'methods.get(key);}\n'
                                                           '}',
 'android/view/View.java': 'package android.view; public class View { protected void onAttachedToWindow(){} protected '
                           'void onDetachedFromWindow(){} private void setFlags(int f,int m){} }',
 'com/waenhancer/xposed/features/customization/PropsOverrideHook.java': 'package '
                                                                        'com.waenhancer.xposed.features.customization; '
                                                                        'public final class PropsOverrideHook { public '
                                                                        'static final java.util.Map<Integer,Boolean> '
                                                                        'BOOLEANS=new java.util.HashMap<>(); }',
 'com/waenhancer/xposed/core/db/HideSeenStore.java': 'package com.waenhancer.xposed.core.db;\n'
                                                     'public final class HideSeenStore {\n'
                                                     ' public static final int TYPE_READ=0; private static final '
                                                     'HideSeenStore INSTANCE=new HideSeenStore();\n'
                                                     ' public static HideSeenStore getInstance(android.content.Context '
                                                     'c){return INSTANCE;}\n'
                                                     ' public static boolean viewed;public static int inserts;\n'
                                                     ' public boolean isAlreadyViewed(String jid,String id,int '
                                                     'type){return viewed;}\n'
                                                     ' public void insertHideSeenMessageAsync(String jid,String id,int '
                                                     'type,boolean viewed){if(viewed)throw new AssertionError("Viewed '
                                                     'store outside fixture");inserts++;}\n'
                                                     ' public void insertHideSeenMessagesAsync(String '
                                                     'jid,java.util.Set<String> ids,int type,boolean '
                                                     'viewed){for(String '
                                                     'id:ids)insertHideSeenMessageAsync(jid,id,type,viewed);}\n'
                                                     '}',
 'com/whatsapp/infra/core/jid/Jid.java': 'package com.whatsapp.infra.core.jid; public class Jid {private final String '
                                         'raw; public Jid(String raw){this.raw=raw;} public String '
                                         'getRawString(){return raw;} public String toString(){return raw;}}'}


def run_contracts(source_root, out, kotlinc):
    source_root, out = Path(source_root).resolve(), Path(out).resolve()
    out.mkdir(parents=True, exist_ok=True)
    generated, classes = out / 'generated', out / 'classes'
    generated.mkdir(exist_ok=True)
    classes.mkdir(exist_ok=True)
    stubs = []
    for name, content in STUBS.items():
        target = generated / name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(content, encoding='utf-8')
        stubs.append(target)
    import urllib.request, zipfile, io
    dexkit=out/'dexkit-2.2.0.jar'
    cached=os.environ.get('WAEX_QA_DEXKIT_JAR')
    if cached: shutil.copyfile(cached,dexkit)
    else:
        url='https://repo.maven.apache.org/maven2/org/luckypray/dexkit/2.2.0/dexkit-2.2.0.aar'
        with urllib.request.urlopen(url,timeout=45) as response:body=response.read(8*1024*1024+1)
        if hashlib.sha256(body).hexdigest()!='cf4488da9f721750ce5ff4311e88356cbfcb1d4444c2b519b12d8bdb5f33f2c6':raise RuntimeError('DexKit AAR mismatch')
        with zipfile.ZipFile(io.BytesIO(body)) as archive:dexkit.write_bytes(archive.read('classes.jar'))
    if hashlib.sha256(dexkit.read_bytes()).hexdigest()!='195979d9576ba6d68a1c60bdd3e38cd3aed068d4e0c5c6c99caa50bc25fcb9f1':raise RuntimeError('DexKit JAR mismatch')
    kotlin_home=Path(kotlinc).resolve().parents[1];stdlib=kotlin_home/'lib/kotlin-stdlib.jar'
    if not stdlib.is_file():raise RuntimeError('Complete Kotlin distribution required')
    classpath=os.pathsep.join(map(str,[classes,stdlib,dexkit]))
    def run(command):
        completed=subprocess.run(command,capture_output=True,timeout=90)
        with (out/'compile.log').open('ab') as log:log.write(completed.stdout+completed.stderr)
        if completed.returncode:raise RuntimeError('COMPILE_FAILED '+(completed.stdout+completed.stderr).decode('utf-8',errors='replace')[-12000:])
    run(['javac','--release','17','-encoding','UTF-8','-cp',classpath,'-d',str(classes),*map(str,stubs)])
    base=source_root/'app/src/main/java/com/waenhancer/xposed/core/BaseFeature.kt'
    lifecycle=[base.parent/n for n in ('HookRegistrar.java','ViewHookDispatcher.java')]
    run(['java','-cp',str(kotlin_home/'lib/*'),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-kotlin-home',str(kotlin_home),'-jvm-target','17','-classpath',classpath,'-d',str(classes),str(base),*map(str,lifecycle)])
    production=[source_root/AREA/name for name in PRODUCTION]
    fixture=ROOT/'qa/fixtures/ReceiptStanzaRuntimeChecks.java'
    run(['javac','--release','17','-encoding','UTF-8','-cp',classpath,'-d',str(classes),*map(str,lifecycle),*map(str,production),str(fixture)])
    result = subprocess.run(['java', '-ea', '-cp', classpath,
                             'com.waenhancer.xposed.features.privacy.ReceiptStanzaRuntimeChecks'],
                            text=True, capture_output=True, timeout=60)
    (out / 'runtime.log').write_text(result.stdout + result.stderr, encoding='utf-8')
    summary=re.search(r'Receipt stanza runtime: (\d+) scenarios, (\d+) checks, (\d+) failed scenarios',result.stdout)
    completed=summary is not None and int(summary[1])==101
    accepted=completed and result.returncode==0 and int(summary[3])==0
    report = {
        'completed_matrix':completed,'expected_scenarios':101,
        'scenarios':int(summary[1]) if summary else 0,
        'checks':int(summary[2]) if summary else 0,
        'failed_scenarios':int(summary[3]) if summary else None,
        'explicit_fixture_sha256':{str(p.relative_to(out)):hashlib.sha256(p.read_bytes()).hexdigest() for p in stubs},
        'result': 'PASS' if accepted else 'FAIL',
        'exit_code': result.returncode,
        'production_sources': {str(p.relative_to(source_root)): hashlib.sha256(p.read_bytes()).hexdigest()
                               for p in [*production, base, *lifecycle]},
        'fixture_sha256': hashlib.sha256(fixture.read_bytes()).hexdigest(),
        'dexkit_jar_sha256': hashlib.sha256(dexkit.read_bytes()).hexdigest(), 'complete_lifecycle_compiled': True, 'service_boundaries': ['BlueOnReplyHook', 'HideSeenStore', 'PropsOverrideHook', 'CustomPrivacyHook'], 'complete_hook_compiled': True, 'complete_base_feature_compiled': True,
        'dexkit_native_matching_executed': False, 'android_device_executed': False,
        'output': result.stdout + result.stderr,
    }
    (out / 'result.json').write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
    print(result.stdout, end='')
    if result.stderr:
        print(result.stderr, end='')
    return 0 if accepted else (result.returncode or 1)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source-root', type=Path, default=ROOT)
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
            return run_contracts(args.source_root, args.out, args.kotlinc)
        with tempfile.TemporaryDirectory(prefix='waex_receipt_stanzas_') as temp:
            return run_contracts(args.source_root, Path(temp), args.kotlinc)
    except subprocess.CalledProcessError as error:
        raise SystemExit((error.stdout or '') + (error.stderr or ''))


if __name__ == '__main__':
    raise SystemExit(main())
