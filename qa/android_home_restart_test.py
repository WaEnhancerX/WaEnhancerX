#!/usr/bin/env python3
"""Exercise the complete home-header hook and preference bridge on Android lifecycle APIs.

Uses pinned Robolectric 4.17 on API 24, 28 and 34. Compiles the complete
HomeScreenHeaderActionsHook, BaseFeature and PreferenceBridgeClient. Android
Activity/View/Handler/ContentResolver APIs are real framework classes; host lookup,
menu sheets/resources and the process termination endpoint are explicit fixtures.
No process is restarted or killed. JDK 17+, Kotlin and Maven are required.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[1]
EXPECTED_TESTS = 57
SOURCES = [Path(p) for p in (
 'app/src/main/java/com/waenhancer/xposed/features/homescreen/HomeScreenHeaderActionsHook.java',
 'app/src/main/java/com/waenhancer/xposed/core/BaseFeature.kt',
 'app/src/main/java/com/waenhancer/xposed/bridge/client/PreferenceBridgeClient.java',
 'app/src/main/java/com/waenhancer/config/DndPreferencePolicy.java',
 'app/src/main/java/com/waenhancer/xposed/features/privacy/ArchivedChatsPreferencePolicy.java',
)]
SERVICES = {'androidx/annotation/NonNull.java': 'package androidx.annotation; public @interface NonNull {}',
 'androidx/annotation/Nullable.java': 'package androidx.annotation; public @interface Nullable {}',
 'de/robv/android/xposed/XC_MethodHook.java': 'package de.robv.android.xposed;\n'
                                              'public abstract class XC_MethodHook {\n'
                                              ' public static class MethodHookParam {public Object[] args; '
                                              'public Object thisObject; public void setResult(Object '
                                              'value){}}\n'
                                              ' protected void beforeHookedMethod(MethodHookParam p) throws '
                                              'Throwable {}\n'
                                              ' protected void afterHookedMethod(MethodHookParam p) throws '
                                              'Throwable {}\n'
                                              '}',
 'de/robv/android/xposed/XposedBridge.java': 'package de.robv.android.xposed;\n'
                                             'public class XposedBridge {\n'
                                             ' public static final java.util.List<String> logs = new '
                                             'java.util.concurrent.CopyOnWriteArrayList<>();\n'
                                             ' public static void log(String value){logs.add(value);}\n'
                                             ' public static void hookAllMethods(Class<?> c,String '
                                             'n,XC_MethodHook hook){throw new AssertionError("Host hook '
                                             'installation is outside this fixture");}\n'
                                             '}',
 'de/robv/android/xposed/XposedHelpers.java': 'package de.robv.android.xposed;\n'
                                              'public class XposedHelpers {\n'
                                              ' public static Class<?> findClassIfExists(String '
                                              'n,ClassLoader c){throw new AssertionError("Host lookup is '
                                              'outside this fixture");}\n'
                                              ' public static void findAndHookMethod(Class<?> c,String '
                                              'n,Object... a){throw new AssertionError("Host hook '
                                              'installation is outside this fixture");}\n'
                                              '}',
 'de/robv/android/xposed/XSharedPreferences.java': 'package de.robv.android.xposed;\n'
                                                   'public abstract class XSharedPreferences implements '
                                                   'android.content.SharedPreferences {public void '
                                                   'reload(){}}',
 'com/waenhancer/xposed/core/devkit/DexSearchEngine.java': 'package com.waenhancer.xposed.core.devkit;\n'
                                                           'public class DexSearchEngine {\n'
                                                           ' public static DexSearchEngine '
                                                           'getInstance(){throw new AssertionError("DexKit '
                                                           'is outside this fixture");}\n'
                                                           ' public Class<?> '
                                                           'findClassWithCache(android.content.Context '
                                                           'c,ClassLoader l,String '
                                                           'k,java.util.function.BiFunction<Bridge,ClassLoader,Class<?>> '
                                                           'f){return null;}\n'
                                                           ' public static class Bridge {public Results '
                                                           'findClass(org.luckypray.dexkit.query.FindClass '
                                                           'q){return null;}}\n'
                                                           ' public static class Results {public '
                                                           'org.luckypray.dexkit.result.ClassData '
                                                           'firstOrNull(){return null;}}\n'
                                                           '}',
 'org/luckypray/dexkit/query/FindClass.java': 'package org.luckypray.dexkit.query;\n'
                                              'public class FindClass {public static FindClass '
                                              'create(){return new FindClass();} public FindClass '
                                              'matcher(org.luckypray.dexkit.query.matchers.ClassMatcher '
                                              'm){return this;}}',
 'org/luckypray/dexkit/query/matchers/ClassMatcher.java': 'package org.luckypray.dexkit.query.matchers;\n'
                                                          'public class ClassMatcher {public static '
                                                          'ClassMatcher create(){return new ClassMatcher();} '
                                                          'public ClassMatcher className(String '
                                                          's,org.luckypray.dexkit.query.enums.StringMatchType '
                                                          't){return this;}}',
 'org/luckypray/dexkit/query/enums/StringMatchType.java': 'package org.luckypray.dexkit.query.enums; public '
                                                          'enum StringMatchType {EndsWith, Contains}',
 'org/luckypray/dexkit/result/ClassData.java': 'package org.luckypray.dexkit.result; public class ClassData '
                                               '{public Class<?> getInstance(ClassLoader l){return null;}}',
 'com/waenhancer/xposed/core/components/WaexBottomSheet.java': 'package '
                                                               'com.waenhancer.xposed.core.components;\n'
                                                               'public class WaexBottomSheet {\n'
                                                               ' public static '
                                                               'android.content.DialogInterface.OnClickListener '
                                                               'positive;\n'
                                                               ' public static android.app.Dialog dialog;\n'
                                                               ' public WaexBottomSheet(android.app.Activity '
                                                               'a){dialog=new android.app.Dialog(a);}\n'
                                                               ' public WaexBottomSheet '
                                                               'asBottomSheet(){return this;}\n'
                                                               ' public WaexBottomSheet setTitle(String '
                                                               's){return this;}\n'
                                                               ' public WaexBottomSheet setMessage(String '
                                                               's){return this;}\n'
                                                               ' public WaexBottomSheet '
                                                               'setPositiveButton(String '
                                                               's,android.content.DialogInterface.OnClickListener '
                                                               'l){positive=l; return this;}\n'
                                                               ' public WaexBottomSheet '
                                                               'setNegativeButton(String '
                                                               's,android.content.DialogInterface.OnClickListener '
                                                               'l){return this;}\n'
                                                               ' public android.app.Dialog show(){return '
                                                               'dialog;}\n'
                                                               ' public static void '
                                                               'confirm(){android.content.DialogInterface.OnClickListener '
                                                               'l=positive; android.app.Dialog d=dialog; '
                                                               'positive=null;dialog=null;l.onClick(d,-1);}\n'
                                                               ' public static void '
                                                               'reset(){positive=null;dialog=null;}\n'
                                                               '}',
 'com/waenhancer/xposed/features/homescreen/MenuIconLoader.java': 'package '
                                                                  'com.waenhancer.xposed.features.homescreen;\n'
                                                                  'public class MenuIconLoader {\n'
                                                                  ' public static '
                                                                  'android.graphics.drawable.Drawable '
                                                                  'logoIcon(android.content.Context '
                                                                  'c){return null;}\n'
                                                                  ' public static '
                                                                  'android.graphics.drawable.Drawable '
                                                                  'ghostIcon(android.content.Context '
                                                                  'c,boolean a){return null;}\n'
                                                                  ' public static '
                                                                  'android.graphics.drawable.Drawable '
                                                                  'freezeIcon(android.content.Context '
                                                                  'c,boolean a){return null;}\n'
                                                                  ' public static '
                                                                  'android.graphics.drawable.Drawable '
                                                                  'dndIcon(android.content.Context c,boolean '
                                                                  'a){return null;}\n'
                                                                  ' public static '
                                                                  'android.graphics.drawable.Drawable '
                                                                  'restartIcon(android.content.Context '
                                                                  'c){return null;}\n'
                                                                  ' public static '
                                                                  'android.graphics.drawable.Drawable '
                                                                  'settingsIcon(android.content.Context '
                                                                  'c){return null;}\n'
                                                                  '}',
 'com/waenhancer/xposed/features/homescreen/WaexQuickActionsSheet.java': 'package '
                                                                         'com.waenhancer.xposed.features.homescreen;\n'
                                                                         'public class WaexQuickActionsSheet '
                                                                         '{public static void '
                                                                         'show(android.app.Activity '
                                                                         'a,android.content.SharedPreferences '
                                                                         'p,Runnable g,Runnable f,Runnable '
                                                                         'd,Runnable r,Runnable s,Runnable '
                                                                         'i){throw new '
                                                                         'AssertionError("Quick-sheet '
                                                                         'rendering is outside this '
                                                                         'fixture");}}',
 'com/waenhancer/xposed/utils/WhatsAppProcessRestarter.java': 'package com.waenhancer.xposed.utils;\n'
                                                              'public class WhatsAppProcessRestarter {\n'
                                                              ' public static final '
                                                              'java.util.List<java.lang.ref.WeakReference<android.app.Activity>> '
                                                              'calls=new java.util.ArrayList<>();\n'
                                                              ' public static boolean fail;\n'
                                                              ' public static Runnable onRestart;\n'
                                                              ' public static void '
                                                              'restart(android.app.Activity a){calls.add(new '
                                                              'java.lang.ref.WeakReference<>(a));if(onRestart!=null)onRestart.run();if(fail)throw '
                                                              'new IllegalStateException("Injected restart '
                                                              'endpoint failure");}\n'
                                                              ' public static void '
                                                              'reset(){calls.clear();fail=false;onRestart=null;}\n'
                                                              '}'}
POM = '''<project xmlns="http://maven.apache.org/POM/4.0.0">
<modelVersion>4.0.0</modelVersion><groupId>org.waex.qa</groupId>
<artifactId>home-restart-qa-only</artifactId><version>1</version>
<repositories><repository><id>google</id><url>https://dl.google.com/dl/android/maven2/</url></repository></repositories>
<dependencies>
<dependency><groupId>org.robolectric</groupId><artifactId>robolectric</artifactId><version>4.17</version></dependency>
<dependency><groupId>org.robolectric</groupId><artifactId>android-all</artifactId><version>14-robolectric-10818077</version></dependency>
<dependency><groupId>junit</groupId><artifactId>junit</artifactId><version>4.13.2</version></dependency>
</dependencies></project>
'''


def run(command, log, allow_failure=False, timeout=600):
    with log.open('w') as output:
        result = subprocess.run(command, stdout=output, stderr=subprocess.STDOUT, timeout=timeout)
    print(log.name, 'exit=' + str(result.returncode), flush=True)
    if result.returncode and not allow_failure:
        print(log.read_text()[-18000:])
        raise RuntimeError('Command failed: ' + command[0])
    return result.returncode


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source-root', type=Path, default=ROOT)
    parser.add_argument('--out', type=Path, required=True)
    parser.add_argument('--maven', default='mvn')
    parser.add_argument('--maven-repo', type=Path)
    parser.add_argument('--deps', type=Path)
    parser.add_argument('--sdk-jars', type=Path,
                        help='Optional flat directory of previously downloaded android-all-instrumented JARs; uses Robolectric offline')
    parser.add_argument('--expect-assertion-failures', type=int, default=0,
                        help='Require exactly this many assertion failures on the full suite (for a known baseline only)')
    args = parser.parse_args()
    root, out = args.source_root.resolve(), args.out.resolve()
    out.mkdir(parents=True, exist_ok=False)
    deps = args.deps.resolve() if args.deps else out / 'deps'
    deps.mkdir(parents=True, exist_ok=True)
    if not args.deps:
        (out / 'pom.xml').write_text(POM)
        command = [args.maven, '-B', '-f', str(out / 'pom.xml'),
                   'org.apache.maven.plugins:maven-dependency-plugin:3.8.1:copy-dependencies',
                   '-DoutputDirectory=' + str(deps)]
        if args.maven_repo:
            command.append('-Dmaven.repo.local=' + str(args.maven_repo.resolve()))
        from maven_dependencies import resolve_dependencies
        resolve_dependencies(command, out / 'dependencies.log', timeout=600)
        for archive in deps.glob('*.aar'):
            with zipfile.ZipFile(archive) as bundle:
                for name in bundle.namelist():
                    if name == 'classes.jar' or (name.startswith('libs/') and name.endswith('.jar')):
                        (deps / (archive.stem + '-' + name.replace('/', '_'))).write_bytes(bundle.read(name))
    (out / 'dependency-sha256.json').write_text(json.dumps({
        path.name: hashlib.sha256(path.read_bytes()).hexdigest()
        for path in sorted(deps.iterdir()) if path.is_file()}, indent=2))
    stage, classes = out / 'sources', out / 'classes'
    classes.mkdir(parents=True, exist_ok=True)
    sources = []
    identities = {}
    service_identities = {}
    for relative in SOURCES:
        source, dest = root / relative, stage / relative
        dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, dest)
        sources.append(dest)
        identities[str(relative)] = hashlib.sha256(source.read_bytes()).hexdigest()
    for relative, content in SERVICES.items():
        dest = stage / 'external-service-fixtures' / relative
        dest.parent.mkdir(parents=True, exist_ok=True)
        dest.write_text(content)
        sources.append(dest)
        service_identities[relative] = hashlib.sha256(dest.read_bytes()).hexdigest()
    from localization_support import write_display_adapter
    display_sources = write_display_adapter(ROOT, stage / 'external-service-fixtures')
    sources.extend(display_sources)
    for path in display_sources:
        service_identities[str(path.relative_to(stage / 'external-service-fixtures'))] = hashlib.sha256(path.read_bytes()).hexdigest()
    test_source = ROOT / 'qa/fixtures/HomeRestartLifecycleTest.java'
    test_dest = stage / test_source.name
    shutil.copyfile(test_source, test_dest)
    identities['qa/fixtures/HomeRestartLifecycleTest.java'] = hashlib.sha256(test_source.read_bytes()).hexdigest()
    sources.append(test_dest)
    cp = os.pathsep.join(str(p) for p in sorted(deps.glob('*.jar')))
    compiler = shutil.which('kotlinc')
    if not compiler:
        raise RuntimeError('Kotlin compiler required for the complete BaseFeature')
    kotlin_runtime = Path(compiler).resolve().parents[1] / 'lib/kotlin-stdlib.jar'
    if not any(re.fullmatch(r'kotlin-stdlib-[0-9].*\.jar', p.name) for p in deps.glob('*.jar')):
        cp += os.pathsep + str(kotlin_runtime)
    android = deps / 'android-all-14-robolectric-10818077.jar'
    production_cp = os.pathsep.join([str(android), str(kotlin_runtime)])
    run([compiler, '-jvm-target', '17', '-classpath', production_cp, '-d', str(classes),
         *[str(p) for p in sources if p.suffix == '.kt']], out / 'kotlinc.log')
    run(['javac', '--release', '17', '-cp', str(classes) + os.pathsep + cp, '-d', str(classes),
         *[str(p) for p in sources if p.suffix == '.java']], out / 'javac.log')
    opens = ['java.base/java.lang', 'java.base/java.util', 'java.base/java.io', 'java.base/java.net',
             'java.base/java.security', 'java.base/java.text', 'java.base/jdk.internal.access',
             'java.desktop/java.awt.font', 'jdk.compiler/com.sun.tools.javac.api']
    command = ['java', '-Xmx1g', *['--add-opens=' + p + '=ALL-UNNAMED' for p in opens],
               '-Drobolectric.dependency.repo.url=https://repo.maven.apache.org/maven2',
               '-cp', str(classes) + os.pathsep + cp, 'org.junit.runner.JUnitCore',
               'com.waenhancer.xposed.features.homescreen.HomeRestartLifecycleTest']
    if args.maven_repo:
        command.insert(1, '-Dmaven.repo.local=' + str(args.maven_repo.resolve()))
    if args.sdk_jars:
        command[1:1] = ['-Drobolectric.offline=true',
                        '-Drobolectric.dependency.dir=' + str(args.sdk_jars.resolve())]
        (out / 'sdk-sha256.json').write_text(json.dumps({
            path.name: hashlib.sha256(path.read_bytes()).hexdigest()
            for path in sorted(args.sdk_jars.glob('*.jar'))}, indent=2))
    result = run(command, out / 'junit.log', allow_failure=True)
    if args.maven_repo and not args.sdk_jars:
        sdk_root = args.maven_repo.resolve() / 'org/robolectric/android-all-instrumented'
        (out / 'sdk-sha256.json').write_text(json.dumps({
            path.name: hashlib.sha256(path.read_bytes()).hexdigest()
            for path in sorted(sdk_root.glob('*/*.jar'))}, indent=2))
    junit = (out / 'junit.log').read_text()
    passed = re.search(r'OK \((\d+) tests?\)', junit)
    failed = re.search(r'Tests run: (\d+),\s+Failures: (\d+)', junit)
    tests = int(passed.group(1)) if passed else int(failed.group(1)) if failed else 0
    failures = int(failed.group(2)) if failed else 0
    # JUnit's string equality assertion is an AssertionError subclass. Count it
    # explicitly without accepting arbitrary runtime or infrastructure failures.
    assertion_types = re.findall(
        r'^(java\.lang\.AssertionError|org\.junit\.ComparisonFailure)(?::|$)', junit, re.M)
    assertions = len(assertion_types)
    expected_failures = args.expect_assertion_failures
    valid = tests == EXPECTED_TESTS and failures == expected_failures
    if expected_failures:
        valid = valid and result != 0 and assertions == expected_failures
    else:
        valid = valid and result == 0 and passed is not None
    summary = {'source_root': str(root), 'junit_exit_code': result, 'source_sha256': identities,
               'external_service_fixture_sha256': service_identities,
               'tests': tests, 'failures': failures, 'assertion_failures': assertions,
               'assertion_failure_types': {name: assertion_types.count(name) for name in sorted(set(assertion_types))},
               'expected_assertion_failures': expected_failures, 'accepted_result': valid,
               'limits': 'Complete production HomeScreenHeaderActionsHook, BaseFeature, PreferenceBridgeClient and policies on Robolectric API 24/28/34. Android Activity, View, Handler and ContentResolver are framework implementations; targeted callback-post failure injection is declared in the fixture. Host lookup, confirmation presentation, menu resources, localization and process termination are explicit fixtures. No app is restarted; no host APK or LSPosed execution.'}
    (out / 'result.json').write_text(json.dumps(summary, indent=2))
    if not valid:
        print(junit[-18000:])
        raise RuntimeError('Unexpected Android restart lifecycle test result: ' + json.dumps(summary))
    print(json.dumps(summary, indent=2))


if __name__ == '__main__':
    main()
