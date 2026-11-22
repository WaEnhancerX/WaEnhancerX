#!/usr/bin/env python3
"""Execute Global Privacy's actual update/placement callbacks and preference manager.

Android preferences, resources, notifications and Toast are explicit JVM doubles.
This does not render Compose, access Android storage, import a backup or send receipts.
The optional mutants must compile and fail behavior; compiler failures are BLOCKED.
"""
import argparse
import ast
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import time
from xml.etree import ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
SCREEN = 'app/src/main/java/com/waenhancer/ui/screens/privacy/GlobalPrivacySettingsScreen.kt'
PREFIX = 'app/src/main/java/com/waenhancer/'


def block(text, marker):
    if text.count(marker) != 1:
        raise ValueError('Expected one production marker: ' + marker)
    start = text.index(marker)
    opening = text.index('{', start)
    state, depth, i = 'code', 0, opening
    while i < len(text):
        char, pair = text[i], text[i:i + 2]
        if state == 'line':
            if char == '\n':
                state = 'code'
        elif state == 'comment':
            if pair == '*/':
                state = 'code'
                i += 1
        elif state in ('string', 'char'):
            if char == '\\':
                i += 1
            elif char == ('"' if state == 'string' else "'"):
                state = 'code'
        elif pair == '//':
            state = 'line'
            i += 1
        elif pair == '/*':
            state = 'comment'
            i += 1
        elif char == '"':
            state = 'string'
        elif char == "'":
            state = 'char'
        elif char == '{':
            depth += 1
        elif char == '}':
            depth -= 1
            if depth == 0:
                return text[start:i + 1]
        i += 1
    raise ValueError('Unclosed production block: ' + marker)


FAKE_CONTEXT = r'''
package fixture;
import android.content.*;
import android.content.pm.ApplicationInfo;
import java.io.File;
import java.util.*;
public final class FakeContext extends Context {
 public final Prefs prefs = new Prefs();
 public int notifications;
 public SharedPreferences getSharedPreferences(String name, int mode) {
  if (!"waex_prefs".equals(name) || mode != 0) throw new AssertionError("Unexpected preference store");
  return prefs;
 }
 public ApplicationInfo getApplicationInfo() {
  ApplicationInfo info = new ApplicationInfo();
  info.dataDir = new File(System.getProperty("java.io.tmpdir"), "waex-no-android-store-fixture").getAbsolutePath();
  if (new File(info.dataDir).exists()) throw new AssertionError("Fixture path unexpectedly exists");
  return info;
 }
 public File getFilesDir() { throw new AssertionError("Unexpected Android files request"); }
 public String getString(int id) { return com.waenhancer.utils.UiText.get(id, "Unable to save setting"); }
 public ContentResolver getContentResolver() { return new ContentResolver() {
  public void notifyChange(android.net.Uri uri, Object observer) {
   if (!uri.toString().equals("content://com.waenhancer.hookprovider/preferences")) {
    prefs.unexpected.add("Unexpected notification URI: " + uri);
   }
   notifications++;
  }
 }; }
 public static final class Prefs implements SharedPreferences {
  public final Map<String,Object> memory = new LinkedHashMap<>(), disk = new LinkedHashMap<>();
  public final List<String> unexpected = new ArrayList<>();
  public int mode, seenCommits, readsAfterFailedSeenCommit;
  public boolean failedSeenCommit;
  public void seed(String initial) {
   memory.put("seentick", initial); memory.put("hide_read_receipts", true); memory.put("blueonreply", true);
   disk.putAll(memory);
  }
  public void resetObservation() {
   seenCommits=0; readsAfterFailedSeenCommit=0; failedSeenCommit=false; unexpected.clear();
  }
  public boolean contains(String key) { return memory.containsKey(key); }
  public Map<String,?> getAll() { return new LinkedHashMap<>(memory); }
  public String getString(String key, String fallback) {
   if (failedSeenCommit && "seentick".equals(key)) readsAfterFailedSeenCommit++;
   return (String) memory.getOrDefault(key, fallback);
  }
  public boolean getBoolean(String key, boolean fallback) { return (Boolean) memory.getOrDefault(key, fallback); }
  public int getInt(String key, int fallback) { throw new AssertionError("Unexpected integer read"); }
  public float getFloat(String key, float fallback) { throw new AssertionError("Unexpected float read"); }
  public Set<String> getStringSet(String key, Set<String> fallback) { throw new AssertionError("Unexpected set read"); }
  public Editor edit() {
   if (mode == 4) throw new IllegalStateException("editor fixture");
   return new Editor() {
    final Map<String,Object> pending = new LinkedHashMap<>();
    public Editor putString(String key, String value) {
     if (!"seentick".equals(key)) throw new AssertionError("Unexpected string write: " + key);
     pending.put(key,value); return this;
    }
    public Editor putBoolean(String key, boolean value) {
     if (!"hide_read_receipts".equals(key) && !"blueonreply".equals(key)) throw new AssertionError("Unexpected Boolean write: " + key);
     pending.put(key,value); return this;
    }
    public Editor putStringSet(String key, Set<String> value) { throw new AssertionError("Unexpected set write"); }
    public Editor putInt(String key, int value) { throw new AssertionError("Unexpected integer write"); }
    public Editor putLong(String key, long value) { throw new AssertionError("Unexpected long write"); }
    public Editor putFloat(String key, float value) { throw new AssertionError("Unexpected float write"); }
    public Editor remove(String key) { throw new AssertionError("Unexpected remove"); }
    public void apply() { throw new AssertionError("Unexpected asynchronous apply"); }
    public boolean commit() {
     boolean seen = pending.containsKey("seentick");
     if (seen) {
      seenCommits++;
      if (mode != 0) failedSeenCommit = true;
      if (mode == 2) throw new IllegalStateException("commit fixture");
      if (mode == 3) memory.putAll(pending); // Android may change memory before a failed disk write.
      if (mode != 0) return false;
     }
     memory.putAll(pending); disk.clear(); disk.putAll(memory); return true;
    }
   };
  }
 }
}
'''

KOTLIN = r'''
import fixture.FakeContext
import android.widget.Toast
import com.waenhancer.app.R
import com.waenhancer.utils.UiText
import com.waenhancer.core.preferences.WaexPreferenceManager
import com.waenhancer.config.PreferenceRestartTracker
import com.waenhancer.ui.screens.settings.ManualSeenTickPreferencePolicy
import com.waenhancer.ui.screens.media.MediaQuickSettingPolicy

object Screen { const val CustomEmojis = "custom-emojis" }
class Navigation { fun navigateTo(destination: String) { error("Unexpected navigation: $destination") } }
class Ui(val context: FakeContext, initial: String) {
 val preferenceManager = WaexPreferenceManager(context)
 val settingsState = mutableMapOf("seentick" to (initial == "1" || initial == "2"),
     "hide_read_receipts" to true, "blueonreply" to true)
 var seenTickPlacement = initial
 val navController = Navigation()
 fun readViewOnceDownload(): Boolean = error("Unexpected view-once access")
 __HELPER__
 __UPDATE__
 val choosePlacement: (String) -> Unit = { value -> __PLACEMENT__ }
}

data class CaseResult(val name: String, val errors: MutableList<String> = mutableListOf())
fun escape(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") + "\""
fun main() {
 val results = mutableListOf<CaseResult>()
 val fidelity = CaseResult("shared-preferences:later-commit-persists-failed-memory")
 run {
  fun expect(ok: Boolean, message: String) { if (!ok) fidelity.errors.add(message) }
  val context = FakeContext(); context.prefs.seed("2")
  context.prefs.mode = 3
  expect(!context.prefs.edit().putString("seentick", "0").commit(), "memory-first write did not fail")
  expect(context.prefs.memory["seentick"] == "0" && context.prefs.disk["seentick"] == "2",
      "failed write did not separate memory from durable state")
  context.prefs.mode = 0
  expect(context.prefs.edit().putBoolean("hide_read_receipts", false).commit(), "later write did not succeed")
  expect(context.prefs.disk["seentick"] == "0", "later successful commit did not persist earlier memory change")
  expect(context.prefs.disk == context.prefs.memory, "successful commit did not persist the complete memory map")
 }
 fun runCase(initial: String, action: String, requested: String, mode: Int) {
  val result = CaseResult("$action:initial=$initial:requested=$requested:mode=$mode")
  results.add(result)
  fun expect(ok: Boolean, message: String) { if (!ok) result.errors.add(message) }
  val context = FakeContext(); context.prefs.seed(initial)
  val ui = Ui(context, initial)
  // Do not inject an editor failure into the manager's unrelated construction migration.
  context.prefs.resetObservation(); context.prefs.mode = mode
  PreferenceRestartTracker.changes.clear(); Toast.shown = 0; UiText.reads = 0
  val enabledBefore = ui.settingsState["seentick"]
  val placementBefore = ui.seenTickPlacement
  val durableBefore = context.prefs.disk["seentick"]
  val target = when (action) {
   "toggle" -> if (requested == "false") "0" else if (initial == "2") "2" else "1"
   "placement" -> requested
   "hide-read-off" -> "0"
   else -> initial
  }
  fun invoke() {
   when (action) {
    "toggle" -> ui.updatePreference("seentick", requested == "true")
    "placement" -> ui.choosePlacement(requested)
    "hide-read-off" -> ui.updatePreference("hide_read_receipts", false)
    "abandon-before-action" -> Unit // No callback is accepted after this simulated abandonment.
    else -> error("Unknown fixture action")
   }
  }
  try { invoke() } catch (failure: Throwable) { result.errors.add("callback threw: ${failure.javaClass.simpleName}: ${failure.message}") }
  if (action == "abandon-before-action") {
   expect(context.prefs.seenCommits == 0, "abandonment wrote a preference")
   expect(ui.seenTickPlacement == initial && context.prefs.disk["seentick"] == initial, "abandonment changed state")
   expect(Toast.shown == 0 && UiText.reads == 0, "abandonment produced save feedback")
  } else if (mode == 0) {
   expect(context.prefs.disk["seentick"] == target, "durable value/placement differs from independent expectation")
   expect(ui.seenTickPlacement == target, "successful placement not confirmed")
   expect(ui.settingsState["seentick"] == (target == "1" || target == "2"), "successful switch not confirmed")
   expect(PreferenceRestartTracker.changes.count { it == "seentick" } == 1, "successful target change not notified exactly once")
   expect(Toast.shown == 0 && UiText.reads == 0, "success produced failure feedback")
  } else {
   expect(context.prefs.disk["seentick"] == durableBefore, "fixture failure changed durable state")
   expect(ui.settingsState["seentick"] == enabledBefore, "failed write confirmed the switch")
   expect(ui.seenTickPlacement == placementBefore, "failed write confirmed placement")
   expect(Toast.shown == 1 && UiText.reads == 1, "failure lacks exactly one localized feedback")
   expect(PreferenceRestartTracker.changes.none { it == "seentick" }, "failure notified seentick as saved")
   expect(context.prefs.readsAfterFailedSeenCommit == 0, "failed memory was reread as confirmation")
   if (mode == 3) expect(context.prefs.memory["seentick"] == target, "memory-first result differs from requested target")
   context.prefs.mode = 0; context.prefs.resetObservation()
   PreferenceRestartTracker.changes.clear(); Toast.shown=0; UiText.reads=0
   try { invoke() } catch (failure: Throwable) { result.errors.add("explicit retry threw: ${failure.javaClass.simpleName}") }
   expect(context.prefs.disk["seentick"] == target && ui.seenTickPlacement == target, "explicit retry did not persist requested placement")
   expect(ui.settingsState["seentick"] == (target == "1" || target == "2"), "retry switch is inconsistent")
   expect(Toast.shown == 0, "successful retry retained failure feedback")
  }
  expect(context.prefs.unexpected.isEmpty(), "unexpected fixture accesses: ${context.prefs.unexpected}")
  expect(!context.prefs.memory.containsKey("seen_tick_customization"), "obsolete Boolean alias written")
 }
 for (initial in listOf("0", "1", "2")) for (requested in listOf("false", "true")) for (mode in 0..4)
  runCase(initial, "toggle", requested, mode)
 for (initial in listOf("1", "2")) for (requested in listOf("1", "2")) for (mode in 0..4)
  runCase(initial, "placement", requested, mode)
 // The unrelated Boolean setters run first; inject failures only at the seentick commit here.
 for (initial in listOf("1", "2")) for (mode in 0..3) runCase(initial, "hide-read-off", "false", mode)
 for (initial in listOf("0", "1", "2")) runCase(initial, "abandon-before-action", "none", 0)
 val failures = results.count { it.errors.isNotEmpty() } + if (fidelity.errors.isEmpty()) 0 else 1
 fun caseJson(item: CaseResult): String = "{\"name\":" + escape(item.name) + ",\"status\":" + escape(if(item.errors.isEmpty()) "PASS" else "FAIL") + ",\"errors\":[" + item.errors.joinToString(",") { e -> escape(e) } + "]}"
 val cases = results.joinToString(",") { caseJson(it) }
 println("{\"status\":\"" + (if(failures == 0) "PASS" else "FAIL") + "\",\"cases\":[" + cases + "],\"fixture_fidelity\":[" + caseJson(fidelity) + "]}")
 if (failures != 0) kotlin.system.exitProcess(1)
}
'''


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source-root', type=Path, default=ROOT)
    parser.add_argument('--out', type=Path, required=True)
    parser.add_argument('--java', default=shutil.which('java'))
    parser.add_argument('--javac', default=shutil.which('javac'))
    parser.add_argument('--java-release', type=int, default=17,
                        help='Compiler release contract; default17. Record any explicit lab override.')
    parser.add_argument('--java-source-target', type=int,
                        help='Explicit syntax/bytecode mode for a JRE with compiler but without ct.sym; does not prove that release API surface.')
    parser.add_argument('--kotlin-home', type=Path)
    parser.add_argument('--kotlinc', default=shutil.which('kotlinc'))
    parser.add_argument('--mutant', choices=('optimistic-confirmation', 'unchecked-placement', 'force-toolbar', 'pending-only-double'))
    args = parser.parse_args()
    source = args.source_root.resolve(strict=True)
    out = args.out.resolve()
    out.mkdir(parents=True, exist_ok=False)
    result = dict(status='BLOCKED', source_root=str(source), mutant=args.mutant,
        runner_sha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
        java_release=None if args.java_source_target else args.java_release,
        java_source_target=args.java_source_target, stages=[],
        scope='Actual extracted Global Privacy callbacks and complete real preference manager; explicit JVM doubles, no Compose/Android/IPC/WhatsApp acceptance')
    started = time.monotonic()
    def run(name, command, timeout=90):
        then = time.monotonic()
        try:
            completed = subprocess.run(command, capture_output=True, text=True, encoding='utf-8', errors='replace', timeout=timeout)
        except subprocess.TimeoutExpired as failure:
            for suffix, value in (('stdout', failure.stdout), ('stderr', failure.stderr)):
                if isinstance(value, bytes):
                    value = value.decode('utf-8', errors='replace')
                (out / (name + '.' + suffix + '.log')).write_text(value or '', encoding='utf-8')
            result['stages'].append(dict(name=name, command=command, exit_code=None, timed_out=True,
                                        duration_s=round(time.monotonic()-then, 3)))
            raise
        (out / (name + '.stdout.log')).write_text(completed.stdout, encoding='utf-8')
        (out / (name + '.stderr.log')).write_text(completed.stderr, encoding='utf-8')
        result['stages'].append(dict(name=name, command=command, exit_code=completed.returncode,
                                    duration_s=round(time.monotonic()-then, 3)))
        return completed
    try:
        if not args.java or not args.javac or (not args.kotlin_home and not args.kotlinc):
            raise RuntimeError('Java, javac and Kotlin compiler are required')
        run('java-version', [args.java, '-version'])
        run('javac-version', [args.javac, '--version'])
        text = (source / SCREEN).read_text(encoding='utf-8')
        update = block(text, 'val updatePreference: (String, Boolean) -> Unit = update@')
        helper = block(text, 'fun saveSeenTickPlacement(') if 'fun saveSeenTickPlacement(' in text else ''
        section = block(text, 'if (item.key == "seentick" && settingsState["seentick"] == true)')
        click = block(section, '.clickable {')
        placement = click[click.index('{')+1:-1]
        if args.mutant == 'optimistic-confirmation':
            needle = 'if (preferenceManager.putPublicString("seentick", nextPlacement)) {'
            if helper.count(needle) != 1:
                raise RuntimeError('Optimistic mutation anchor unavailable')
            helper = helper.replace(needle, 'seenTickPlacement = nextPlacement\nsettingsState["seentick"] = nextPlacement != "0"\n' + needle)
        elif args.mutant == 'unchecked-placement':
            if placement.count('saveSeenTickPlacement(value)') != 1:
                raise RuntimeError('Placement mutation anchor unavailable')
            placement = placement.replace('saveSeenTickPlacement(value)', 'seenTickPlacement = value\npreferenceManager.putString("seentick", value)')
        elif args.mutant == 'force-toolbar':
            needle = 'ManualSeenTickPreferencePolicy.valueAfterExplicitToggle(value, seenTickPlacement)'
            if update.count(needle) != 1:
                raise RuntimeError('Toolbar mutation anchor unavailable')
            update = update.replace(needle, 'if (value) "1" else "0"')
        stubs_tree = ast.parse((source / 'qa/test_media_preference_sync.py').read_text(encoding='utf-8'))
        stubs = next(ast.literal_eval(node.value) for node in stubs_tree.body if isinstance(node, ast.Assign)
            and any(isinstance(target, ast.Name) and target.id == 'STUBS' for target in node.targets))
        stubs['fixture/FakeContext.java'] = FAKE_CONTEXT
        if args.mutant == 'pending-only-double':
            needle = 'memory.putAll(pending); disk.clear(); disk.putAll(memory); return true;'
            if FAKE_CONTEXT.count(needle) != 1:
                raise RuntimeError('Preference-map fidelity mutation anchor unavailable')
            stubs['fixture/FakeContext.java'] = FAKE_CONTEXT.replace(needle,
                'memory.putAll(pending); disk.putAll(pending); return true;')
        for annotation in ('NonNull', 'Nullable'):
            stubs['androidx/annotation/'+annotation+'.java'] = 'package androidx.annotation; public @interface '+annotation+' {}'
        stubs['com/waenhancer/utils/DiagnosticsRecorder.java'] = '''package com.waenhancer.utils; import android.content.Context;
public final class DiagnosticsRecorder {
 public static void recordSettingsTransfer(Context c,String a,int n){throw new AssertionError("Unexpected settings transfer");}
 public static void recordPreferenceChange(Context c,String key,boolean scoped,String operation){
  if(c==null||scoped||!"PUT".equals(operation)||!(key.equals("seentick")||key.equals("blueonreply")||key.equals("hide_read_receipts")))throw new AssertionError("Unexpected diagnostic event");
 }
}'''
        stubs['com/waenhancer/app/R.java'] = '''package com.waenhancer.app; public final class R { public static final class string {
 public static final int waex_ui_unable_to_save_setting=701; public static final int view_once_setting_save_failed=702;
}}'''
        stubs['com/waenhancer/utils/UiText.java'] = '''package com.waenhancer.utils; public final class UiText {
 public static int reads;
 public static String get(int id,String fallback){if(id!=701)throw new AssertionError("Unexpected feedback resource"); reads++;return "Unable to save setting";}
}'''
        stubs['android/widget/Toast.java'] = '''package android.widget; import android.content.Context; public final class Toast {
 public static int shown; public static final int LENGTH_SHORT=0;
 public static Toast makeText(Context c,String value,int length){if(c==null||length!=0||!"Unable to save setting".equals(value))throw new AssertionError("Unexpected Toast");return new Toast();}
 public void show(){shown++;}
}'''
        # The presentation double must refer to an existing, nonempty production resource.
        resources = {}
        resource_paths = sorted((source / 'app/src/main/res/values').glob('*.xml'))
        for xml in resource_paths:
            for node in ET.parse(xml).getroot():
                if node.tag == 'string':
                    resources[node.get('name')] = ''.join(node.itertext())
        if not resources.get('waex_ui_unable_to_save_setting'):
            raise RuntimeError('Existing feedback resource missing')
        classes = out / 'classes'
        classes.mkdir()
        java_sources = []
        for name, content in stubs.items():
            path = out / 'fixtures' / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content, encoding='utf-8')
            java_sources.append(str(path))
        production = ['config/PreferenceSchema.java','config/PreferenceStores.java','config/ExclusivePreferences.java',
            'core/preferences/WaexPreferenceManager.java','ui/screens/settings/ManualSeenTickPreferencePolicy.java',
            'ui/screens/media/MediaQuickSettingPolicy.java']
        java_sources += [str(source / PREFIX / rel) for rel in production]
        result['source_sha256'] = {str(path.relative_to(source)): hashlib.sha256(path.read_bytes()).hexdigest()
            for path in [source / SCREEN, *[source / PREFIX / rel for rel in production], source / 'qa/test_media_preference_sync.py', *resource_paths]}
        result['fixture_java_sha256'] = {str(Path(path).relative_to(out)): hashlib.sha256(Path(path).read_bytes()).hexdigest()
            for path in java_sources if Path(path).is_relative_to(out)}
        java_mode = (['-source', str(args.java_source_target), '-target', str(args.java_source_target)]
                     if args.java_source_target else ['--release', str(args.java_release)])
        compilation = run('javac', [args.javac, *java_mode, '-d', str(classes), *java_sources])
        if compilation.returncode:
            raise RuntimeError('Java compilation failed; not a behavioral negative result')
        kotlin = KOTLIN.replace('__HELPER__',helper).replace('__UPDATE__',update).replace('__PLACEMENT__',placement)
        kt = out / 'TestGlobalPrivacy.kt'
        kt.write_text(kotlin, encoding='utf-8')
        result['extracted_fixture_sha256'] = hashlib.sha256(kt.read_bytes()).hexdigest()
        if args.kotlin_home:
            lib = args.kotlin_home.resolve(strict=True) / 'lib'
            stdlib = sorted(lib.glob('kotlin-stdlib*.jar'))
            if not stdlib:
                raise RuntimeError('Kotlin standard library missing')
            run('kotlin-version', [args.java, '-cp', str(lib / '*'),
                'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-version'], timeout=30)
            kotlin_command = [args.java, '-cp', str(lib / '*'), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
                '-no-stdlib', '-no-reflect', '-classpath', os.pathsep.join([str(classes), *map(str,stdlib)]),
                '-jvm-target','17','-d',str(classes),str(kt)]
            runtime = os.pathsep.join([str(classes), *map(str,stdlib)])
        else:
            jar = out / 'test.jar'
            run('kotlin-version', [args.kotlinc, '-version'], timeout=30)
            kotlin_command = [args.kotlinc, str(kt), '-classpath', str(classes), '-jvm-target','17','-include-runtime','-d',str(jar)]
            runtime = os.pathsep.join([str(classes), str(jar)])
        compilation = run('kotlinc', kotlin_command)
        if compilation.returncode:
            raise RuntimeError('Kotlin compilation failed; not a behavioral negative result')
        executed = run('behavior', [args.java, '-cp', runtime, 'TestGlobalPrivacyKt'], timeout=60)
        payload = json.loads(executed.stdout.strip())
        failures = [case for case in payload['cases'] if case['status'] != 'PASS']
        fidelity = payload['fixture_fidelity']
        if len(payload['cases']) != 61 or len({case['name'] for case in payload['cases']}) != 61:
            raise RuntimeError('The directed matrix must contain exactly 61 unique cases')
        if len(fidelity) != 1 or fidelity[0]['name'] != 'shared-preferences:later-commit-persists-failed-memory':
            raise RuntimeError('The separate preference-double fidelity control is missing')
        result.update(status=payload['status'], cases=payload['cases'], unique_case_count=len(payload['cases']),
                      failed_case_count=len(failures), behavior_exit_code=executed.returncode,
                      fixture_fidelity=fidelity, fixture_fidelity_count=len(fidelity),
                      failed_fixture_fidelity_count=sum(case['status'] != 'PASS' for case in fidelity))
        if (payload['status']=='PASS') != (executed.returncode == 0):
            raise RuntimeError('Behavior status/exit-code mismatch')
        if args.mutant:
            expected = {
                'optimistic-confirmation': ('production_callbacks', 'toggle:initial=0:requested=true:mode=1', 'failed write confirmed the switch'),
                'unchecked-placement': ('production_callbacks', 'placement:initial=1:requested=2:mode=1', 'failed write confirmed placement'),
                'force-toolbar': ('production_callbacks', 'toggle:initial=2:requested=true:mode=0', 'durable value/placement differs from independent expectation'),
                'pending-only-double': ('fixture_fidelity', 'shared-preferences:later-commit-persists-failed-memory', 'later successful commit did not persist earlier memory change'),
            }[args.mutant]
            observed = fidelity if expected[0] == 'fixture_fidelity' else failures
            detected = any(case['status']=='FAIL' and case['name']==expected[1] and expected[2] in case['errors'] for case in observed)
            result['negative_control'] = dict(scope=expected[0], expected_case=expected[1], expected_failure=expected[2], detected=detected)
            if not detected:
                raise RuntimeError('Deliberate regression did not produce its required behavioral failure')
    except Exception as failure:
        result.update(status='BLOCKED', error=dict(type=type(failure).__name__, message=str(failure)))
    finally:
        result['duration_s'] = round(time.monotonic()-started,3)
        (out / 'RESULT.json').write_text(json.dumps(result,indent=2)+'\n',encoding='utf-8')
        print(json.dumps({key:value for key,value in result.items() if key not in ('cases','stages','source_sha256','fixture_java_sha256')}))
    return 0 if result['status']=='PASS' else 1 if result['status']=='FAIL' else 2


if __name__ == '__main__':
    raise SystemExit(main())
