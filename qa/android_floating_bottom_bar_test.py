#!/usr/bin/env python3
"""Compile complete floating-bar or Meta AI/channel hooks for Android regressions.

Requires JDK 21, Kotlin 2.0.20 and Maven (or --deps/--dexkit-jar). Android Views,
Handler, Drawables and Intent objects execute on Robolectric 4.17 API 24/28/34.
Only host discovery/Xposed/host resource names are controlled fixtures. This is
not WhatsApp injection, GPU frost rendering, an APK build or a frame-rate test.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import runpy
import shutil
import subprocess
import tomllib
import zipfile

import android_media_preview_test as framework

ROOT = Path(__file__).resolve().parents[1]
JAVA = Path('app/src/main/java/com/waenhancer')
HOOK = JAVA/'xposed/features/customization/FloatingBottomBarHook.java'
BASE = JAVA/'xposed/core/BaseFeature.kt'
OVERLAY = JAVA/'app/OverlayPermissionLauncher.java'
META = JAVA/'xposed/features/homescreen/ChatListCustomizationsHook.java'
CHANNEL = JAVA/'xposed/features/customization/ChannelRecommendationsFilterHook.kt'
HIDER = JAVA/'xposed/utils/ViewVisibilityGuard.java'
SELECTOR = JAVA/'xposed/utils/ViewClassSelector.java'
FIXTURES = ROOT/'qa/fixtures/floating_bottom_bar'
CORE_VERSION = '1.18.0'
CORE_URL = 'https://dl.google.com/dl/android/maven2/androidx/core/core/1.18.0/core-1.18.0.aar'
CORE_AAR_SHA256 = '311d83ac67d394076ec21d12ed2d10a44b59cb2929b7dce00e5a90a93842e37d'
CORE_SHA256 = 'd1f5a319a77555df7e23858ffb5ed45a95ebb4263fa1ab7a993510ceb53555a0'


def run(command, log, allow_failure=False):
    return framework.run(command, log, allow_failure=allow_failure, timeout=240)


def resolve_core(args, out):
    version = tomllib.loads((ROOT/'gradle/libs.versions.toml').read_text())['versions']['androidxCore']
    if version != CORE_VERSION:
        raise RuntimeError('Update the pinned Core QA artifact for project version ' + version)
    if args.core_api_jar:
        core = args.core_api_jar.resolve()
    else:
        directory = out/'public-apis'
        archive = framework.download_pinned(CORE_URL, directory/'core-1.18.0.aar',
                                            CORE_AAR_SHA256, 8*1024*1024)
        with zipfile.ZipFile(archive) as bundle:
            matches = [entry for entry in bundle.infolist() if entry.filename == 'classes.jar']
            if len(matches) != 1 or matches[0].file_size > 4*1024*1024:
                raise RuntimeError('Core AAR must contain one bounded classes.jar')
            core = directory/'core-1.18.0.jar'
            core.write_bytes(bundle.read(matches[0]))
    return framework.verify_api(core, CORE_SHA256, 4*1024*1024)


def execute(root, out, dependencies, compiler, baseline):
    out.mkdir(parents=True)
    stage, classes = out/'sources', out/'classes'
    classes.mkdir()
    identities = {}
    if ARGS.view_visibility:
        paths = [META, CHANNEL, BASE] + ([] if baseline else [HIDER, SELECTOR])
    else:
        paths = [HOOK, BASE] if baseline else [HOOK, BASE, OVERLAY]
    for path in paths:
        destination = stage/path
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(root/path, destination)
        identities[str(path)] = hashlib.sha256(destination.read_bytes()).hexdigest()
    stubs_file = FIXTURES/'runtime_doubles.py'
    identities[str(stubs_file.relative_to(ROOT))] = hashlib.sha256(stubs_file.read_bytes()).hexdigest()
    stubs = runpy.run_path(str(stubs_file))['STUBS']
    if ARGS.view_visibility:
        extras = FIXTURES/'visibility_doubles.py'
        stubs.update(runpy.run_path(str(extras))['STUBS'])
        identities[str(extras.relative_to(ROOT))] = hashlib.sha256(extras.read_bytes()).hexdigest()
    ids = sorted(set(re.findall(r'R\.string\.(\w+)', '\n'.join((root/p).read_text() for p in paths))))
    stubs['com/waenhancer/app/R.java'] = ('package com.waenhancer.app; public final class R {'
        'public static final class string {' + ''.join(f'public static final int {key}={i+1};' for i, key in enumerate(ids)) + '}}')
    stub_sources = []
    for name, text in stubs.items():
        dest = stage/'doubles'/name
        dest.parent.mkdir(parents=True, exist_ok=True)
        dest.write_text(text)
        stub_sources.append(dest)
    cp = os.pathsep.join(map(str, dependencies))
    classpath = str(classes) + os.pathsep + cp
    # BaseFeature's actual Kotlin implementation, then the complete Java hook.
    # Compile Kotlin against its real Android API only: newer test-library Kotlin
    # metadata is not an app dependency, and no metadata compatibility flag is used.
    android_api = next(p for p in dependencies if p.name == 'android-all-14-robolectric-10818077.jar')
    run([str(compiler), '-jvm-target', '17', '-cp', str(android_api), '-d', str(classes), str(stage/BASE)], out/'kotlinc-production-base.log')
    run(['javac', '--release', '17', '-cp', classpath, '-d', str(classes),
         *map(str, stub_sources), *[str(stage/p) for p in paths if p.suffix == '.java']], out/'javac-complete-production.log')
    if ARGS.view_visibility:
        production_cp = os.pathsep.join(map(str, [classes, android_api] + [p for p in dependencies if p.name in ('dexkit-2.2.0.jar', 'kotlin-stdlib.jar')]))
        run([str(compiler), '-jvm-target', '17', '-cp', production_cp, '-d', str(classes), str(stage/CHANNEL)], out/'kotlinc-complete-channel-hook.log')
        tests = ['ViewVisibilityBindingTest.java'] + ([] if baseline else ['ViewClassSelectorTest.java'])
    else:
        tests = ['FloatingBottomBarLifecycleTest.java'] if baseline else ['FloatingBottomBarLifecycleTest.java', 'OverlayPermissionLauncherTest.java']
    for name in tests:
        identities['qa/fixtures/floating_bottom_bar/'+name] = hashlib.sha256((FIXTURES/name).read_bytes()).hexdigest()
        shutil.copyfile(FIXTURES/name, stage/name)
    run(['javac', '--release', '17', '-cp', classpath, '-d', str(classes), *[str(stage/name) for name in tests]], out/'javac-tests.log')
    opens = ['java.base/java.lang', 'java.base/java.util', 'java.base/java.util.concurrent',
             'java.base/java.io', 'java.base/java.net', 'java.base/java.security', 'java.base/java.text',
             'java.base/jdk.internal.access', 'java.desktop/java.awt.font', 'jdk.compiler/com.sun.tools.javac.api']
    command = ['java', '-Xmx1g', *['--add-opens='+p+'=ALL-UNNAMED' for p in opens],
               '-Drobolectric.dependency.repo.url=https://repo.maven.apache.org/maven2',
               '-cp', classpath, 'org.junit.runner.JUnitCore']
    command += [('com.waenhancer.app.' if name == 'OverlayPermissionLauncherTest.java'
        else 'com.waenhancer.xposed.features.customization.') + Path(name).stem for name in tests]
    if ARGS.sdk_jars:
        command[1:1] = ['-Drobolectric.offline=true', '-Drobolectric.dependency.dir='+str(ARGS.sdk_jars.resolve())]
    if ARGS.maven_repo:
        command.insert(1, '-Dmaven.repo.local='+str(ARGS.maven_repo.resolve()))
    result = run(command, out/'junit.log', allow_failure=baseline)
    log = (out/'junit.log').read_text()
    expected_tests = (36 if baseline else 51) if ARGS.view_visibility else (30 if baseline else 51)
    expected_failures = (30 if ARGS.view_visibility else 18) if baseline else 0
    if baseline:
        # Known behavioral regression assertions per SDK, not build/network failures.
        failures = re.findall(r'^\d+\) (.+)\n([^\n]+)', log, re.M)
        if result == 0 or f'Tests run: {expected_tests},  Failures: {expected_failures}' not in log or len(failures) != expected_failures or any(
                not error.startswith('java.lang.AssertionError') for _, error in failures):
            raise RuntimeError(f'V39 baseline must run all {expected_tests} scenarios and fail exactly {expected_failures} behavioral assertions')
    elif f'OK ({expected_tests} tests)' not in log:
        raise RuntimeError(f'Expected all {expected_tests} Android regression tests')
    identities['qa/android_floating_bottom_bar_test.py'] = hashlib.sha256(Path(__file__).read_bytes()).hexdigest()
    record = {'source_root': str(root), 'baseline': baseline, 'exit_code': result,
              'mode': 'view-visibility' if ARGS.view_visibility else 'floating-and-overlay',
              'tests': expected_tests, 'assertion_failures': expected_failures, 'sha256': identities}
    (out/'result.json').write_text(json.dumps(record, indent=2)+'\n')
    return record


def main():
    global ARGS
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source-root', type=Path, default=ROOT)
    parser.add_argument('--baseline-root', type=Path)
    parser.add_argument('--view-visibility', action='store_true', help='Run the complete Meta AI/channel hooks, local View guards and class-selector regressions')
    parser.add_argument('--out', type=Path, required=True)
    parser.add_argument('--deps', type=Path)
    parser.add_argument('--dexkit-jar', type=Path)
    parser.add_argument('--core-api-jar', type=Path, help='Reuse the SHA-pinned Core classes.jar; default: verified public Google Maven AAR')
    parser.add_argument('--sdk-jars', type=Path)
    parser.add_argument('--maven', default='mvn')
    parser.add_argument('--maven-repo', type=Path)
    parser.add_argument('--maven-settings', type=Path)
    ARGS = parser.parse_args()
    out = ARGS.out.resolve()
    if out.exists() and any(out.iterdir()):
        parser.error('--out must be empty; preserve previous logs and classes')
    out.mkdir(parents=True, exist_ok=True)
    # Maven cannot resolve Android Gradle variant metadata: Core's transitive
    # lifecycle/tracing coordinates otherwise request nonexistent JARs. Load its
    # complete, pinned classes.jar separately, as in the recordings framework QA.
    # This standalone test classpath does not certify the app dependency graph.
    framework.POM = framework.POM.replace('</dependencies>',
        '<dependency><groupId>androidx.annotation</groupId><artifactId>annotation-jvm</artifactId>'
        '<version>1.9.1</version></dependency></dependencies>')
    deps = framework.resolve_framework(ARGS, out)
    core = resolve_core(ARGS, out)
    dexkit = ARGS.dexkit_jar.resolve() if ARGS.dexkit_jar else out/'apis/dexkit-2.2.0.jar'
    if ARGS.dexkit_jar:
        framework.verify_api(dexkit, framework.DEXKIT_SHA256, 2*1024*1024)
    else:
        # Reuse the existing SHA-pinned public API bootstrap without any host APK.
        ARGS.http_api_jar = None
        dexkit, _ = framework.resolve_apis(ARGS, out)
    kotlin_home, compiler = framework.find_kotlin()
    dependencies = sorted(deps.glob('*.jar')) + [dexkit, core, kotlin_home/'lib/kotlin-stdlib.jar']
    (out/'bootstrap.json').write_text(json.dumps({
        'framework': 'provided' if ARGS.deps else 'public-maven',
        'core': 'provided' if ARGS.core_api_jar else CORE_URL,
        'core_aar_sha256': CORE_AAR_SHA256, 'core_classes_sha256': CORE_SHA256,
        'dexkit': 'provided' if ARGS.dexkit_jar else framework.DEXKIT_URL,
        'dexkit_classes_sha256': framework.DEXKIT_SHA256,
        'kotlin_compiler': str(compiler),
        'sdk_mode': 'offline-directory' if ARGS.sdk_jars else 'public-maven-resolver',
        'scope': 'Complete Core classes with standalone Robolectric dependencies; not the app dependency graph',
    }, indent=2)+'\n')
    (out/'dependency-sha256.json').write_text(json.dumps({str(p): hashlib.sha256(p.read_bytes()).hexdigest()
        for p in dependencies}, indent=2)+'\n')
    if ARGS.sdk_jars:
        (out/'sdk-sha256.json').write_text(json.dumps({p.name: hashlib.sha256(p.read_bytes()).hexdigest()
            for p in sorted(ARGS.sdk_jars.glob('*.jar'))}, indent=2)+'\n')
    results = []
    if ARGS.baseline_root:
        results.append(execute(ARGS.baseline_root.resolve(), out/'baseline', dependencies, compiler, True))
    results.append(execute(ARGS.source_root.resolve(), out/'candidate', dependencies, compiler, False))
    if not ARGS.sdk_jars:
        sdk_root = (ARGS.maven_repo.resolve() if ARGS.maven_repo else Path.home()/'.m2/repository')/'org/robolectric/android-all-instrumented'
        (out/'sdk-sha256.json').write_text(json.dumps({str(p): hashlib.sha256(p.read_bytes()).hexdigest()
            for p in sorted(sdk_root.glob('*/*.jar'))}, indent=2)+'\n')
    (out/'results.json').write_text(json.dumps({'results': results, 'limits': __doc__}, indent=2)+'\n')
    print('PASS: complete-hook compilation and Android View/Drawable/Intent regression cases; not an APK/LSPosed/GPU test.')


if __name__ == '__main__':
    try:
        main()
    except (OSError, ValueError, RuntimeError, StopIteration, subprocess.SubprocessError) as error:
        print('ERROR:', error, flush=True)
        raise SystemExit(2)
