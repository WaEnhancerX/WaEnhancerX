#!/usr/bin/env python3
"""Execute the actual manager -> codec import path against strict platform doubles.

Real production manager/codec/schema/store/exclusivity policy and pinned AOSP JSON
are compiled together. Only Android IO/storage, annotations, diagnostics, restart
notification and the four-method private interface boundary are doubles. This is
not an Android build, disk-durability test or private licensing implementation.
"""
from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import platform
import shutil
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parents[1]


def replace_once(text: str, old: str, new: str) -> str:
    if text.count(old) != 1:
        raise ValueError('Fixture or mutation target missing/ambiguous: ' + old[:80])
    return text.replace(old, new)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source-root', type=Path, default=ROOT)
    parser.add_argument('--json-source-dir', type=Path, required=True)
    parser.add_argument('--kotlin-home', type=Path, required=True)
    parser.add_argument('--out', type=Path, required=True)
    parser.add_argument('--mutant', choices=['skip-normalization', 'skip-inheritance', 'omit-added-rollback'])
    args = parser.parse_args()
    output = args.out.resolve()
    output.mkdir(parents=True, exist_ok=False)
    result = {'status': 'BLOCKED', 'compiled': False, 'kind': 'host-JVM / strict Android doubles / AOSP JSON',
              'platform': platform.platform(), 'mutant': args.mutant, 'inputs': {}, 'commands': []}
    started = time.monotonic()
    try:
        spec = importlib.util.spec_from_file_location('waex_json', ROOT / 'qa/test_settings_backup_json.py')
        loader = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(loader)
        aosp = loader.json_sources(args.json_source_dir.resolve(), allow_download=False)
        doubles = loader.android_doubles(ROOT)
        for name in ('NonNull', 'Nullable'):
            doubles[f'androidx/annotation/{name}.java'] = f'package androidx.annotation; public @interface {name} {{}}'
        doubles['android/net/Uri.java'] = '''package android.net;
public final class Uri { public final String value; private Uri(String v){value=v;}
 public static Uri parse(String v){return new Uri(v);} }'''
        doubles['android/content/ContentResolver.java'] = '''package android.content;
public final class ContentResolver {
 public byte[] input; public int notifications;
 public java.io.InputStream openInputStream(android.net.Uri uri) {
  if(!"content://fixture/settings".equals(uri.value)||input==null)throw new AssertionError("Unexpected input");
  return new java.io.ByteArrayInputStream(input);
 }
 public java.io.OutputStream openOutputStream(android.net.Uri uri,String mode){throw new AssertionError("Unexpected export");}
 public void notifyChange(android.net.Uri uri,Object observer){
  if(!"content://com.waenhancer.hookprovider/preferences".equals(uri.value)||observer!=null)throw new AssertionError("Unexpected notification");
  notifications++;
 }
}'''
        doubles['android/content/Context.java'] = '''package android.content;
public abstract class Context {
 public static final int MODE_PRIVATE=0;
 public final ContentResolver resolver=new ContentResolver();
 public ContentResolver getContentResolver(){return resolver;}
 public abstract SharedPreferences getSharedPreferences(String name,int mode);
 public android.content.pm.ApplicationInfo getApplicationInfo(){throw new AssertionError("Unexpected app-info access");}
 public java.io.File getFilesDir(){throw new AssertionError("Unexpected file access");}
}'''
        shared = doubles['android/content/SharedPreferences.java']
        shared = replace_once(shared, 'Map<String, ?> getAll(); Editor edit();', '''Map<String, ?> getAll(); Editor edit();
 default boolean contains(String key){return getAll().containsKey(key);}
 default String getString(String key,String fallback){Object v=getAll().get(key);return v==null?fallback:(String)v;}
 default boolean getBoolean(String key,boolean fallback){Object v=getAll().get(key);return v==null?fallback:(Boolean)v;}
 default int getInt(String key,int fallback){Object v=getAll().get(key);return v==null?fallback:(Integer)v;}
 default float getFloat(String key,float fallback){Object v=getAll().get(key);return v==null?fallback:(Float)v;}
 @SuppressWarnings("unchecked") default Set<String> getStringSet(String key,Set<String> fallback){Object v=getAll().get(key);return v==null?fallback:(Set<String>)v;}
''')
        doubles['android/content/SharedPreferences.java'] = shared
        context = doubles['android/content/TestContext.java']
        context = replace_once(context, 'return stores.computeIfAbsent(name,k->new Prefs());',
                               'if(mode!=MODE_PRIVATE||!stores.containsKey(name))throw new AssertionError("Unexpected store");return stores.get(name);')
        context = replace_once(context, 'int failCount; boolean partialFailure;', 'int failCount; boolean partialFailure; int commitCalls; int failAt;')
        context = replace_once(context, 'boolean failed=failCount>0;', 'commitCalls++; boolean failed=failCount>0||commitCalls==failAt;')
        context = replace_once(context, 'if(failed) failCount--;', 'if(failCount>0) failCount--;')
        context = replace_once(context, 'public int count(){', '''public int commits(String store){return stores.get(store).commitCalls;}
 public void failAtCommit(String store,int ordinal,boolean memory){Prefs p=stores.get(store);p.failAt=ordinal;p.partialFailure=memory;}
 public int count(){''')
        doubles['android/content/TestContext.java'] = context
        for name in ('Inject', 'Singleton'):
            doubles[f'javax/inject/{name}.java'] = f'package javax.inject; public @interface {name} {{}}'
        doubles['dagger/hilt/android/qualifiers/ApplicationContext.java'] = 'package dagger.hilt.android.qualifiers; public @interface ApplicationContext {}'
        doubles['com/waenhancer/licensing/customprops/CustomAbPropsStore.java'] = '''package com.waenhancer.licensing.customprops;
public interface CustomAbPropsStore { boolean getBoolean(String k,boolean d); void putBoolean(String k,boolean v);
 String getString(String k,String d); void putString(String k,String v); }'''
        doubles['com/waenhancer/config/PreferenceRestartTracker.java'] = '''package com.waenhancer.config;
public final class PreferenceRestartTracker {
 public static void onPreferenceChanged(android.content.SharedPreferences p,String key){
  if(!key.endsWith("floating_bottom_bar")&&!key.endsWith("native_floating_navigation"))throw new AssertionError("Unexpected restart tracking");
 }
}'''
        doubles['com/waenhancer/utils/DiagnosticsRecorder.java'] = '''package com.waenhancer.utils;
public final class DiagnosticsRecorder {
 public static int imports; public static int lastCount;
 public static void reset(){imports=0;lastCount=0;}
 public static void recordSettingsTransfer(android.content.Context c,String operation,int count){
  if(!"IMPORT".equals(operation))throw new AssertionError("Unexpected transfer"); imports++;lastCount=count;
 }
 public static void recordPreferenceChange(android.content.Context c,String key,boolean scoped,String operation){
  if(!"PUT".equals(operation)||(!"floating_bottom_bar".equals(key)&&!"native_floating_navigation".equals(key)))throw new AssertionError("Unexpected preference diagnostic");
 }
}'''
        generated = []
        for name, content in doubles.items():
            path = output / 'sources' / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content, encoding='utf-8')
            generated.append(path)
        source = args.source_root.resolve() / 'app/src/main/java/com/waenhancer'
        codec = source / 'core/preferences/SettingsBackupCodec.kt'
        manager = source / 'core/preferences/WaexPreferenceManager.java'
        if args.mutant:
            mutations = {
                'skip-normalization': ('val writes = resolveExclusiveImportConflicts(records, publicBefore)', 'val writes = records'),
                'skip-inheritance': ('if (universalChanged) {', 'if (false && universalChanged) {'),
                'omit-added-rollback': ('else publicKeys.add(record.key)', 'else if (records.any { it.key == record.key }) publicKeys.add(record.key)'),
            }
            old, new = mutations[args.mutant]
            text = replace_once(codec.read_text(encoding='utf-8'), old, new)
            codec = output / 'SettingsBackupCodec-mutant.kt'
            codec.write_text(text, encoding='utf-8')
        production = [source / ('config/' + name + '.java') for name in ('PreferenceSchema', 'PreferenceStores', 'ExclusivePreferences')]
        fixture = ROOT / 'qa/kotlin/TestImportExclusivePreferences.kt'
        java, javac = shutil.which('java'), shutil.which('javac')
        compiler = args.kotlin_home / 'bin' / ('kotlinc.bat' if os.name == 'nt' else 'kotlinc')
        stdlib = args.kotlin_home / 'lib/kotlin-stdlib.jar'
        if not java or not javac or not compiler.is_file() or not stdlib.is_file():
            raise FileNotFoundError('JDK and complete Kotlin CLI are required')
        for path in [Path(__file__), codec, manager, *production, fixture, *generated, *aosp, stdlib]:
            result['inputs'][str(path)] = hashlib.sha256(path.read_bytes()).hexdigest()
        java_out, kotlin_out = output / 'java', output / 'kotlin'
        runtime_cp = os.pathsep.join(map(str, (java_out, kotlin_out, stdlib)))
        commands = [
            ('java-version', [java, '-version']),
            ('kotlin-version', [str(compiler), '-version']),
            ('javac-platform', [javac, '--release', '17', '-d', str(java_out), *map(str, [*generated, *aosp, *production])]),
            ('kotlinc', [str(compiler), '-jvm-target', '17', '-cp', str(java_out), '-d', str(kotlin_out), str(codec), str(manager), str(fixture)]),
            ('javac-manager', [javac, '--release', '17', '-Xlint:unchecked', '-cp', runtime_cp, '-d', str(java_out), str(manager)]),
            ('TestImportExclusivePreferencesKt', [java, '-Xmx192m', '-Xss1m', '-cp', runtime_cp,
                                                  'com.waenhancer.core.preferences.TestImportExclusivePreferencesKt']),
        ]
        for label, command in commands:
            tick = time.monotonic()
            completed = subprocess.run(command, capture_output=True, text=True, encoding='utf-8', errors='replace', timeout=180)
            (output / f'{label}.log').write_text(completed.stdout + completed.stderr, encoding='utf-8')
            result['commands'].append({'label': label, 'command': command, 'exit_code': completed.returncode,
                                       'seconds': round(time.monotonic() - tick, 3)})
            print(f'{label}: exit {completed.returncode}', flush=True)
            if label.startswith('Test'):
                result['compiled'] = True
                print(completed.stdout, end='', flush=True)
                result['status'] = 'PASS' if completed.returncode == 0 else 'FAIL'
            elif completed.returncode:
                break
        return 0 if result['status'] == 'PASS' else 1 if result['status'] == 'FAIL' else 2
    except (OSError, ValueError, subprocess.TimeoutExpired, AssertionError) as error:
        result['error'] = f'{type(error).__name__}: {error}'
        print(result['error'], file=sys.stderr)
        return 2
    finally:
        result['seconds'] = round(time.monotonic() - started, 3)
        (output / 'result.json').write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')


if __name__ == '__main__':
    sys.exit(main())
