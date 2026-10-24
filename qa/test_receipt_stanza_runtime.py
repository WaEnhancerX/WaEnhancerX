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

STUBS = {
    'android/content/Context.java': 'package android.content; public class Context {}',
    'android/content/SharedPreferences.java': '''package android.content;
public interface SharedPreferences {
 boolean getBoolean(String key, boolean fallback);
 boolean contains(String key); java.util.Map<String,?> getAll();
}''',
    'android/os/Message.java': '''package android.os;
public class Message { public int what, arg1, arg2; public Object obj; }''',
    'android/os/SystemClock.java': '''package android.os;
public final class SystemClock { public static long elapsedRealtime(){return 1_000_000L;} }''',
    'androidx/annotation/NonNull.java': 'package androidx.annotation; public @interface NonNull {}',
    'org/json/JSONObject.java': '''package org.json;
public class JSONObject {
 public final java.util.Map<String,Boolean> values = new java.util.HashMap<>();
 public JSONObject put(String key, boolean value){values.put(key,value);return this;}
 public boolean optBoolean(String key, boolean fallback){return values.getOrDefault(key,fallback);}
}''',
    'com/waenhancer/xposed/features/privacy/CustomPrivacyHook.java': '''package com.waenhancer.xposed.features.privacy;
import android.content.SharedPreferences; import org.json.JSONObject;
public final class CustomPrivacyHook {
 public static final java.util.Map<String,JSONObject> contacts = new java.util.HashMap<>();
 public static int lookups;
 public static JSONObject getContactPrivacy(SharedPreferences ignored,String jid){lookups++;return contacts.get(jid);}
}''',
    'com/waenhancer/xposed/features/privacy/BlueOnReplyHook.java': '''package com.waenhancer.xposed.features.privacy;
public final class BlueOnReplyHook {
 public static final java.util.List<String> captured = new java.util.ArrayList<>();
 public static void captureSuppressedReadJob(String jid,java.lang.reflect.Constructor<?> ctor,Object[] args){captured.add(jid);}
}''',
    'de/robv/android/xposed/XC_MethodHook.java': '''package de.robv.android.xposed;
import java.lang.reflect.Member;
public class XC_MethodHook {
 protected void beforeHookedMethod(MethodHookParam param) throws Throwable {}
 protected void afterHookedMethod(MethodHookParam param) throws Throwable {}
 public static class MethodHookParam {
  public Member method; public Object thisObject; public Object[] args;
  Object result; Throwable throwable; boolean early;
  public Object getResult(){return result;} public boolean hasThrowable(){return throwable!=null;}
  public Throwable getThrowable(){return throwable;}
  public void setResult(Object value){result=value;throwable=null;early=true;}
 }
}''',
    'de/robv/android/xposed/XposedBridge.java': '''package de.robv.android.xposed;
import java.lang.reflect.*; import java.util.*;
public final class XposedBridge {
 public static final Map<Member,List<XC_MethodHook>> hooks=new HashMap<>();
 public static final List<String> logs=new ArrayList<>();
 public static void log(String value){logs.add(value);}
 public static void hookMethod(Member method,XC_MethodHook callback){hooks.computeIfAbsent(method,k->new ArrayList<>()).add(callback);}
 public static void hookAllConstructors(Class<?> type,XC_MethodHook callback){for(Constructor<?> c:type.getDeclaredConstructors())hookMethod(c,callback);}
 public static Object invoke(Method method,Object receiver,Object... args)throws Throwable{
  XC_MethodHook.MethodHookParam p=new XC_MethodHook.MethodHookParam();p.method=method;p.thisObject=receiver;p.args=args;
  List<XC_MethodHook> callbacks=hooks.getOrDefault(method,List.of());
  for(XC_MethodHook callback:callbacks){callback.beforeHookedMethod(p);if(p.early)break;}
  if(!p.early){try{p.result=method.invoke(receiver,p.args);}catch(InvocationTargetException e){p.throwable=e.getCause();}}
  for(int i=callbacks.size()-1;i>=0;i--)callbacks.get(i).afterHookedMethod(p);
  if(p.throwable!=null)throw p.throwable;return p.result;
 }
 public static Object construct(Constructor<?> constructor,Object... args)throws Throwable{
  XC_MethodHook.MethodHookParam p=new XC_MethodHook.MethodHookParam();p.method=constructor;p.args=args;
  p.thisObject=constructor.newInstance(args);p.result=p.thisObject;
  for(XC_MethodHook callback:hooks.getOrDefault(constructor,List.of()))callback.afterHookedMethod(p);
  return p.thisObject;
 }
}''',
    'de/robv/android/xposed/XposedHelpers.java': '''package de.robv.android.xposed;
import java.lang.reflect.*; import java.util.*;
public final class XposedHelpers {
 private static final Map<Object,Map<String,Object>> extras=new IdentityHashMap<>();
 public static Class<?> findClassIfExists(String name,ClassLoader ignored){return null;}
 public static Object getObjectField(Object target,String name)throws ReflectiveOperationException{
  Class<?> type=target.getClass();while(type!=null){try{Field f=type.getDeclaredField(name);f.setAccessible(true);return f.get(target);}
  catch(NoSuchFieldException ignored){type=type.getSuperclass();}}throw new NoSuchFieldException(name);
 }
 public static Object callMethod(Object target,String name,Object... args)throws ReflectiveOperationException{return target.getClass().getMethod(name).invoke(target,args);}
 public static void setAdditionalInstanceField(Object target,String name,Object value){extras.computeIfAbsent(target,k->new HashMap<>()).put(name,value);}
 public static Object getAdditionalInstanceField(Object target,String name){return extras.getOrDefault(target,Map.of()).get(name);}
}''',
    'com/waenhancer/xposed/core/devkit/DexSearchEngine.java': '''package com.waenhancer.xposed.core.devkit;
import android.content.Context; import java.lang.reflect.Method; import java.util.*; import java.util.function.BiFunction;
import org.luckypray.dexkit.DexKitBridge;
/** Explicit positive-cache boundary: selectors are compiled but native queries do not run. */
public final class DexSearchEngine {
 private static final DexSearchEngine INSTANCE=new DexSearchEngine();
 public static final Map<String,Class<?>> classes=new HashMap<>();
 public static final Map<String,Method> methods=new HashMap<>();
 public static DexSearchEngine getInstance(){return INSTANCE;}
 public Class<?> findClassWithCache(Context c,ClassLoader loader,String key,BiFunction<DexKitBridge,ClassLoader,Class<?>> finder){return classes.get(key);}
 public Method findMethodWithCache(Context c,ClassLoader loader,String key,BiFunction<DexKitBridge,ClassLoader,Method> finder){return methods.get(key);}
}''',
    'org/luckypray/dexkit/DexKitBridge.java': '''package org.luckypray.dexkit;
import org.luckypray.dexkit.query.*;import org.luckypray.dexkit.result.*;
public final class DexKitBridge {
 public ClassDataList findClass(FindClass q){throw new UnsupportedOperationException("Native DEX is outside this fixture");}
 public MethodDataList findMethod(FindMethod q){throw new UnsupportedOperationException("Native DEX is outside this fixture");}
}''',
    'org/luckypray/dexkit/query/enums/StringMatchType.java': 'package org.luckypray.dexkit.query.enums; public enum StringMatchType { Contains, Equals, EndsWith }',
    'org/luckypray/dexkit/query/matchers/ClassMatcher.java': '''package org.luckypray.dexkit.query.matchers;
import org.luckypray.dexkit.query.enums.StringMatchType;
public class ClassMatcher {
 public static ClassMatcher create(){return new ClassMatcher();}
 public ClassMatcher usingStrings(String... values){return this;}
 public ClassMatcher className(String name,StringMatchType mode){return this;}
}''',
    'org/luckypray/dexkit/query/matchers/MethodMatcher.java': '''package org.luckypray.dexkit.query.matchers;
import org.luckypray.dexkit.query.enums.StringMatchType;
public class MethodMatcher {
 public static MethodMatcher create(){return new MethodMatcher();}
 public MethodMatcher addUsingString(String value,StringMatchType mode){return this;}
 public MethodMatcher declaredClass(String value){return this;} public MethodMatcher name(String value){return this;}
 public MethodMatcher returnType(String value){return this;} public MethodMatcher paramCount(int... value){return this;}
 public MethodMatcher addParamType(String value){return this;} public MethodMatcher addInvoke(MethodMatcher value){return this;}
}''',
    'org/luckypray/dexkit/result/ClassData.java': '''package org.luckypray.dexkit.result;
public class ClassData { public Class<?> getInstance(ClassLoader loader){return null;}public String getName(){return "unused";} }''',
    'org/luckypray/dexkit/result/MethodData.java': '''package org.luckypray.dexkit.result;
public class MethodData { public java.lang.reflect.Method getMethodInstance(ClassLoader loader){return null;}
 public java.util.List<String> getParamTypeNames(){return java.util.List.of();} }''',
}
for kind in ('Class', 'Method'):
    STUBS[f'org/luckypray/dexkit/query/Find{kind}.java'] = f'''package org.luckypray.dexkit.query;
import org.luckypray.dexkit.query.matchers.{kind}Matcher;
public class Find{kind} {{public static Find{kind} create(){{return new Find{kind}();}}public Find{kind} matcher({kind}Matcher m){{return this;}}}}'''
    STUBS[f'org/luckypray/dexkit/result/{kind}DataList.java'] = f'''package org.luckypray.dexkit.result;
public class {kind}DataList extends java.util.ArrayList<{kind}Data> {{public {kind}Data firstOrNull(){{return isEmpty()?null:get(0);}}}}'''


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
    android = [p for p in stubs if p.relative_to(generated).parts[0] in ('android', 'androidx')]
    run = lambda command: subprocess.run(command, check=True, text=True, capture_output=True, timeout=60)
    run(['javac', '--release', '17', '-d', str(classes), *map(str, android)])
    kotlin_home = Path(kotlinc).resolve().parents[1]
    stdlib = kotlin_home / 'lib/kotlin-stdlib.jar'
    if not stdlib.is_file():
        raise RuntimeError('Cannot find Kotlin stdlib next to compiler: ' + str(stdlib))
    base = source_root / 'app/src/main/java/com/waenhancer/xposed/core/BaseFeature.kt'
    run([str(kotlinc), '-jvm-target', '17', '-classpath', str(classes), '-d', str(classes), str(base)])
    production = [source_root / AREA / name for name in PRODUCTION]
    fixture = ROOT / 'qa/fixtures/ReceiptStanzaRuntimeChecks.java'
    classpath = os.pathsep.join((str(classes), str(stdlib)))
    run(['javac', '--release', '17', '-cp', classpath, '-d', str(classes),
         *map(str, [p for p in stubs if p not in android]), *map(str, production), str(fixture)])
    result = subprocess.run(['java', '-ea', '-cp', classpath,
                             'com.waenhancer.xposed.features.privacy.ReceiptStanzaRuntimeChecks'],
                            text=True, capture_output=True, timeout=60)
    (out / 'runtime.log').write_text(result.stdout + result.stderr, encoding='utf-8')
    report = {
        'result': 'PASS' if result.returncode == 0 else 'FAIL',
        'exit_code': result.returncode,
        'production_sources': {str(p.relative_to(source_root)): hashlib.sha256(p.read_bytes()).hexdigest()
                               for p in [*production, base]},
        'fixture_sha256': hashlib.sha256(fixture.read_bytes()).hexdigest(),
        'complete_hook_compiled': True, 'complete_base_feature_compiled': True,
        'dexkit_native_matching_executed': False, 'android_device_executed': False,
        'output': result.stdout + result.stderr,
    }
    (out / 'result.json').write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
    print(result.stdout, end='')
    if result.stderr:
        print(result.stderr, end='')
    return result.returncode


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
