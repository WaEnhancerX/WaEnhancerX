#!/usr/bin/env python3
"""Compile complete style/property hooks against real DexKit; exercise callbacks on JVM.

Android, preference storage, DexSearchEngine cache transport and Xposed dispatch are
explicit fixtures. The native DexKit bridge and WhatsApp/LSPosed are not executed.
"""
import argparse
from datetime import datetime, timezone
from email.utils import parsedate_to_datetime
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
import zipfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
BASE = Path('app/src/main/java/com/waenhancer/xposed/features/customization')
SOURCES = [BASE / name for name in ('PropsOverrideHook.java', 'StatusStyleHook.java', 'NewSettingsStyleHook.java')]
FIXTURE = Path('qa/fixtures/status_style/StatusStyleHookChecks.java')
DEXKIT_SHA256 = '195979d9576ba6d68a1c60bdd3e38cd3aed068d4e0c5c6c99caa50bc25fcb9f1'
DEXKIT_AAR_SHA256 = 'cf4488da9f721750ce5ff4311e88356cbfcb1d4444c2b519b12d8bdb5f33f2c6'
DEXKIT_URL = 'https://repo.maven.apache.org/maven2/org/luckypray/dexkit/2.2.0/dexkit-2.2.0.aar'
EXPECTED_CHECKS = 83
DEXKIT_ATTEMPTS = 3
DEXKIT_ATTEMPT_SECONDS = 30
DEXKIT_BUDGET_SECONDS = 90
DEXKIT_TRANSIENT_HTTP = frozenset((408, 429, 500, 502, 503, 504))

STUBS = {
 'androidx/annotation/NonNull.java': 'package androidx.annotation; public @interface NonNull {}',
 'android/content/SharedPreferences.java': '''package android.content;
public interface SharedPreferences { String getString(String key,String fallback); boolean getBoolean(String key,boolean fallback); }''',
 'android/content/Context.java': '''package android.content;
public class Context { public android.content.res.Resources getResources(){return new android.content.res.Resources();} public String getPackageName(){return "fixture.whatsapp";} }''',
 'android/content/res/Resources.java': '''package android.content.res;
public class Resources { public int getIdentifier(String name,String type,String pkg){return 0;} }''',
 'android/app/Activity.java': '''package android.app;
// Real Android Activity invokes the supplied work on the UI thread and exposes
// an options-menu invalidation entry point. The fixture runs on one JVM thread.
public class Activity extends android.content.Context {
    private int menuInvalidations;
    public void runOnUiThread(Runnable action) {
        if (action == null) throw new NullPointerException("action");
        action.run();
    }
    public void invalidateOptionsMenu() { menuInvalidations++; }
    public int getMenuInvalidationsForFixture() { return menuInvalidations; }
}''',
 'android/view/Menu.java': 'package android.view; public interface Menu { MenuItem findItem(int id); }',
 'android/view/MenuItem.java': 'package android.view; public interface MenuItem { int SHOW_AS_ACTION_ALWAYS=2; void setShowAsAction(int action); }',
 'com/waenhancer/xposed/core/BaseFeature.java': '''package com.waenhancer.xposed.core;
import android.content.*;
public abstract class BaseFeature { protected final Context context; protected final ClassLoader classLoader; protected final SharedPreferences prefs;
protected BaseFeature(Context c,ClassLoader l,SharedPreferences p){context=c;classLoader=l;prefs=p;}
public abstract void hook() throws Throwable; public abstract String getName(); public String getCategory(){return "General";} }''',
 'com/waenhancer/xposed/core/devkit/DexSearchEngine.java': '''package com.waenhancer.xposed.core.devkit;
import android.content.Context; import java.lang.reflect.Method; import java.util.*; import org.luckypray.dexkit.DexKitBridge;
public final class DexSearchEngine { public static final Map<String,Method> methods=new HashMap<>(); private static final DexSearchEngine INSTANCE=new DexSearchEngine();
public static DexSearchEngine getInstance(){return INSTANCE;}
public Method findMethodWithCache(Context c,ClassLoader l,String key,DexKitQuery q){return methods.get(key);}
public Class<?> findClassWithCache(Context c,ClassLoader l,String key,DexKitClassQuery q){return null;}
public interface DexKitQuery { Method execute(DexKitBridge bridge,ClassLoader loader) throws Throwable; }
public interface DexKitClassQuery { Class<?> execute(DexKitBridge bridge,ClassLoader loader) throws Throwable; } }''',
 'com/waenhancer/xposed/utils/ActivityTracker.java': '''package com.waenhancer.xposed.utils;
import android.app.Activity;
import java.lang.ref.WeakReference;
// Default lifecycle state: there is no resumed host Activity in this JVM fixture.
public final class ActivityTracker {
    private static WeakReference<Activity> current = new WeakReference<>(null);
    private ActivityTracker() {}
    public static Activity getCurrentActivity() { return current.get(); }
    public static void setCurrentActivityForFixture(Activity activity) {
        current = new WeakReference<>(activity);
    }
}''',
 'com/waenhancer/licensing/customprops/CustomAbPropsRuntime.java': '''package com.waenhancer.licensing.customprops;
// The private observation policy is unavailable. Use the conservative
// non-logging default; property value selection is exercised by production code.
public final class CustomAbPropsRuntime {
    private CustomAbPropsRuntime() {}
    public static boolean shouldLogObservation(int propertyId) { return false; }
}''',
 'de/robv/android/xposed/callbacks/XCallback.java': 'package de.robv.android.xposed.callbacks; public class XCallback { public static final int PRIORITY_HIGHEST=10000; }',
 'de/robv/android/xposed/XC_MethodHook.java': '''package de.robv.android.xposed;
import java.lang.reflect.Member;
// Explicit dispatcher fixture: setResult clears Throwable, as in the public Xposed API.
public class XC_MethodHook { public final int priority; public XC_MethodHook(){priority=50;} public XC_MethodHook(int value){priority=value;}
protected void afterHookedMethod(MethodHookParam param) throws Throwable {}
public final void runAfter(MethodHookParam param) throws Throwable {afterHookedMethod(param);}
public static final class MethodHookParam { public Object[] args; public Object thisObject; public Member method; private Object result; private Throwable throwable;
public Object getResult(){return result;} public Throwable getThrowable(){return throwable;} public boolean hasThrowable(){return throwable!=null;}
public void setResult(Object value){result=value;throwable=null;} public void setThrowable(Throwable value){result=null;throwable=value;} } }''',
 'de/robv/android/xposed/XposedBridge.java': '''package de.robv.android.xposed;
import java.lang.reflect.Method; import java.util.*;
public final class XposedBridge { public static final Map<Method,XC_MethodHook> hooks=new LinkedHashMap<>();
public static void hookMethod(Method method,XC_MethodHook hook){hooks.put(method,hook);}
public static void hookAllMethods(Class<?> c,String name,XC_MethodHook hook){} public static void log(String text){} }''',
}

def sha(path): return hashlib.sha256(path.read_bytes()).hexdigest()

def _dexkit_attempt(prefix):
    """Internal child: one fixed public URL, bounded bytes, no compilation."""
    result = {'url': DEXKIT_URL, 'retryable': False}
    try:
        with urllib.request.urlopen(DEXKIT_URL, timeout=DEXKIT_ATTEMPT_SECONDS) as response:
            data = response.read(4 * 1024 * 1024 + 1)
        with prefix.with_suffix('.payload').open('xb') as output:
            output.write(data)
        result.update(status='downloaded', bytes=len(data),
                      sha256=hashlib.sha256(data).hexdigest())
    except urllib.error.HTTPError as error:
        result.update(status='http_error', error_type=type(error).__name__,
                      detail=str(error), http_status=error.code,
                      retry_after=error.headers.get('Retry-After') if error.headers else None,
                      retryable=error.code in DEXKIT_TRANSIENT_HTTP)
        error.close()
    except Exception as error:
        reason = error.reason if isinstance(error, urllib.error.URLError) else error
        result.update(status='transport_error', error_type=type(error).__name__,
                      detail=str(error),
                      retryable=isinstance(reason, (TimeoutError, ConnectionResetError)))
    with prefix.with_suffix('.json').open('x', encoding='utf-8') as output:
        json.dump(result, output, indent=2)
        output.write('\n')
    return 0 if result['status'] == 'downloaded' else 1


def _dexkit_retry_after(value):
    if value is None:
        return 0.0
    try:
        if re.fullmatch(r'[0-9]+', value.strip()):
            # Any such value already exceeds this download's entire budget.
            digits = value.strip().lstrip('0') or '0'
            if len(digits) > 6:
                return float('inf')
            return float(int(digits))
        parsed = parsedate_to_datetime(value)
        if parsed.tzinfo is None:
            parsed = parsed.replace(tzinfo=timezone.utc)
        # HTTP's obsolete RFC 850 form uses a moving 50-year interpretation,
        # unlike the email parser's fixed two-digit-year pivot.
        old_date = re.fullmatch(r'[A-Za-z]+, [0-9]{2}-[A-Za-z]{3}-([0-9]{2}) '
                               r'[0-9]{2}:[0-9]{2}:[0-9]{2} GMT', value.strip())
        now = time.time()
        if old_date:
            current = datetime.fromtimestamp(now, timezone.utc)
            latest_year = current.year + 50
            year = latest_year - (latest_year - int(old_date[1])) % 100
            if year == latest_year and parsed.timetuple()[1:6] > current.timetuple()[1:6]:
                year -= 100
            parsed = parsed.replace(year=year)
        return max(0.0, parsed.timestamp() - now)
    except (TypeError, ValueError, OverflowError):
        pass
    return 0.0


def dexkit(out):
    # A socket timeout alone does not bound a slow response body. The child has
    # no descendants; subprocess.run kills and reaps it when its deadline expires.
    receipt = out / 'dexkit-download.json'
    if receipt.exists() or any(out.glob('dexkit-download-attempt-*')):
        raise RuntimeError('DexKit download evidence already exists; use a fresh output directory')
    started = time.monotonic()
    deadline = started + DEXKIT_BUDGET_SECONDS
    report = {'url': DEXKIT_URL, 'expected_aar_sha256': DEXKIT_AAR_SHA256,
              'expected_jar_sha256': DEXKIT_SHA256, 'max_attempts': DEXKIT_ATTEMPTS,
              'budget_seconds': DEXKIT_BUDGET_SECONDS, 'attempts': [], 'status': 'pending'}

    def save(status, mode='w'):
        report['status'] = status
        report['elapsed_seconds'] = round(time.monotonic() - started, 6)
        with receipt.open(mode, encoding='utf-8') as output:
            output.write(json.dumps(report, indent=2) + '\n')

    save('pending', 'x')
    try:
        data = None
        for number in range(1, DEXKIT_ATTEMPTS + 1):
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise TimeoutError('DexKit download time budget exhausted')
            prefix = out / ('dexkit-download-attempt-' + str(number))
            attempt = {'number': number, 'timeout_seconds': min(DEXKIT_ATTEMPT_SECONDS, remaining),
                       'evidence': prefix.with_suffix('.json').name,
                       'log': prefix.with_suffix('.log').name}
            report['attempts'].append(attempt)
            save('running')
            with prefix.with_suffix('.log').open('x', encoding='utf-8') as log:
                try:
                    remaining = deadline - time.monotonic()
                    if remaining <= 0:
                        raise TimeoutError('DexKit download time budget exhausted')
                    attempt['timeout_seconds'] = min(DEXKIT_ATTEMPT_SECONDS, remaining)
                    completed = subprocess.run(
                        [sys.executable, str(Path(__file__).resolve()),
                         '--dexkit-download-attempt', str(prefix)],
                        stdout=log, stderr=subprocess.STDOUT,
                        timeout=attempt['timeout_seconds'])
                    attempt['exit_code'] = completed.returncode
                except subprocess.TimeoutExpired:
                    attempt.update(status='timeout', retryable=True, exit_code=None)
                else:
                    detail = json.loads(prefix.with_suffix('.json').read_text(encoding='utf-8'))
                    attempt.update(detail)
                    if completed.returncode == 0 and detail.get('status') == 'downloaded':
                        with prefix.with_suffix('.payload').open('rb') as payload:
                            data = payload.read(4 * 1024 * 1024 + 1)
                        save('downloaded')
                        break
                    if completed.returncode != 1 or detail.get('status') == 'downloaded':
                        raise RuntimeError('Unexpected DexKit download child result')
            save('attempt_failed')
            if not attempt.get('retryable') or number == DEXKIT_ATTEMPTS:
                raise RuntimeError('DexKit download failed; see ' + str(receipt))
            delay = max(float(number), _dexkit_retry_after(attempt.get('retry_after')))
            if delay >= deadline - time.monotonic():
                raise TimeoutError('DexKit retry delay exceeds the remaining time budget')
            attempt['retry_delay_seconds'] = delay
            save('retrying')
            time.sleep(delay)
        if data is None:
            raise RuntimeError('DexKit download produced no artifact')
        # Integrity failures are definitive, outside the transport retry loop.
        if len(data) > 4 * 1024 * 1024 or hashlib.sha256(data).hexdigest() != DEXKIT_AAR_SHA256:
            raise RuntimeError('DexKit AAR size or identity mismatch')
        aar = out / 'dexkit-2.2.0.aar'; aar.write_bytes(data)
        with zipfile.ZipFile(aar) as archive:
            if archive.namelist().count('classes.jar') != 1 or archive.getinfo('classes.jar').file_size > 2 * 1024 * 1024:
                raise RuntimeError('DexKit classes.jar missing, duplicated or oversized')
            classes = archive.read('classes.jar')
        if hashlib.sha256(classes).hexdigest() != DEXKIT_SHA256:
            raise RuntimeError('Pinned real DexKit 2.2.0 classes.jar required')
        jar = out / 'dexkit-2.2.0.jar'; jar.write_bytes(classes)
        save('verified')
        return jar
    except Exception as error:
        report.update(error_type=type(error).__name__, detail=str(error))
        save('failed')
        raise

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source-root', type=Path, default=ROOT)
    parser.add_argument('--out', type=Path)
    parser.add_argument('--dexkit-jar', type=Path)
    parser.add_argument('--kotlin-stdlib', type=Path)
    args = parser.parse_args()
    root = args.source_root.resolve()
    with tempfile.TemporaryDirectory(prefix='waex_style_hook_') as temporary:
        out = (args.out or Path(temporary)).resolve(); out.mkdir(parents=True, exist_ok=True)
        sources = out / 'sources'; classes = out / 'classes'; classes.mkdir(exist_ok=True)
        files = []
        for relative in SOURCES + [FIXTURE]:
            origin = root / relative if relative in SOURCES else ROOT / relative
            target = sources / relative; target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(origin.read_bytes()); files.append(target)
        for relative, source in STUBS.items():
            target = sources / 'explicit-fixtures' / relative; target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(source + '\n'); files.append(target)
        identity = {str(path): sha(root / path) for path in SOURCES}
        identity[str(FIXTURE)] = sha(ROOT / FIXTURE)
        identity['qa/test_status_style_hook_contract.py'] = sha(Path(__file__))
        arrays = root / 'app/src/main/res/values/arrays.xml'
        identity['app/src/main/res/values/arrays.xml'] = sha(arrays)
        (out / 'source-sha256.json').write_text(json.dumps(identity, indent=2) + '\n')
        (out / 'explicit-fixture-sha256.json').write_text(json.dumps(
            {str(p.relative_to(sources)): sha(p) for p in files if 'explicit-fixtures' in p.parts}, indent=2) + '\n')
        values = ET.parse(arrays).find("string-array[@name='status_style_values']")
        if values is None or [item.text for item in values] != ['1', '0', '3', '9']:
            raise RuntimeError('Published choices changed; review the hook acceptance matrix')
        try:
            jar = args.dexkit_jar.resolve() if args.dexkit_jar else dexkit(out)
            if not jar.is_file() or sha(jar) != DEXKIT_SHA256:
                raise RuntimeError('Pinned real DexKit 2.2.0 classes.jar required')
            compiler = shutil.which('kotlinc')
            stdlib = args.kotlin_stdlib
            if stdlib is None:
                if not compiler: raise RuntimeError('Kotlin runtime unavailable: supply --kotlin-stdlib')
                stdlib = Path(compiler).resolve().parents[1] / 'lib/kotlin-stdlib.jar'
            if not stdlib.is_file(): raise RuntimeError('Kotlin stdlib file required')
        except Exception as error:
            (out / 'result.json').write_text(json.dumps({'failure_stage': 'dependency bootstrap',
                'tests_executed': 0, 'error_type': type(error).__name__, 'detail': str(error)}, indent=2) + '\n')
            raise
        (out / 'dependency-sha256.json').write_text(json.dumps({jar.name: sha(jar), stdlib.name: sha(stdlib)}, indent=2) + '\n')
        classpath = os.pathsep.join(map(str, (jar, stdlib)))
        with (out / 'compile.log').open('w') as log:
            compile_run = subprocess.run(['javac', '--release', '17', '-cp', classpath, '-d', str(classes), *map(str, files)],
                                         stdout=log, stderr=subprocess.STDOUT, timeout=90)
        result = {'compile_exit_code': compile_run.returncode, 'complete_production_classes': 3,
                  'native_dexkit_or_lsposed_executed': False, 'dexkit_types_stubbed': False}
        if compile_run.returncode == 0:
            with (out / 'behavior.log').open('w') as log:
                run = subprocess.run(['java', '-cp', str(classes) + os.pathsep + classpath,
                        'com.waenhancer.xposed.features.customization.StatusStyleHookChecks'],
                        stdout=log, stderr=subprocess.STDOUT, timeout=60)
            content = (out / 'behavior.log').read_text()
            counts = re.search(r'RESULT checks=(\d+) failures=(\d+)', content)
            result.update({'behavior_exit_code': run.returncode,
                           'checks': int(counts[1]) if counts else 0,
                           'failures': int(counts[2]) if counts else None})
        (out / 'result.json').write_text(json.dumps(result, indent=2) + '\n')
        print(json.dumps(result), flush=True)
        if compile_run.returncode or result.get('behavior_exit_code') or result.get('checks') != EXPECTED_CHECKS or result.get('failures') != 0:
            for name in ('compile.log', 'behavior.log'):
                if (out / name).exists(): print((out / name).read_text())
            return 1
        return 0

if __name__ == '__main__':
    if len(sys.argv) == 3 and sys.argv[1] == '--dexkit-download-attempt':
        raise SystemExit(_dexkit_attempt(Path(sys.argv[2])))
    raise SystemExit(main())
