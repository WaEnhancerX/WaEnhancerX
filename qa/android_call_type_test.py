#!/usr/bin/env python3
"""Exercise the complete call selector and installer on real Android framework APIs.

Pinned Robolectric 4.17, Android 24/28/34, JUnit 4.13.2 and DexKit 2.2.0 API.
Compiles the complete production selector, host contract, discovery/cache wrappers,
phone mapper and their dependencies. Host classes and Xposed registration/original
invocation are explicit fixtures. DexKit JNI, live host injection and real calls
are not run. Every phone Intent is recorded by the fixture Activity.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import urllib.request
import zipfile

from localization_support import write_r

ROOT = Path(__file__).resolve().parents[1]
BASE = Path('app/src/main/java/com/waenhancer')
JAVA_FIRST = [BASE / path for path in (
    'utils/ContactIdentityPolicy.java', 'utils/DisplayLocalePolicy.java', 'utils/UiText.java',
    'xposed/core/devkit/DexNegativeLookupCache.java', 'xposed/core/devkit/DexCacheManager.java',
    'xposed/core/devkit/DexSearchEngine.java')]
KOTLIN = [BASE / path for path in (
    'xposed/core/BaseFeature.kt', 'xposed/core/db/PreservedMessageStore.kt',
    'utils/ContactNameResolver.kt')]
JAVA_HOOK = [BASE / 'xposed/features/privacy' / name for name in (
    'CallTypeHostContract.java', 'CallTypeControllerHook.java')]
FIXTURES = [Path('qa/fixtures/call_type') / name for name in (
    'XC_MethodHook.java', 'XposedBridge.java', 'XposedHelpers.java', 'CallTypeAndroidTest.java')]
DEXKIT_SHA256 = '195979d9576ba6d68a1c60bdd3e38cd3aed068d4e0c5c6c99caa50bc25fcb9f1'
DEXKIT_AAR_SHA256 = 'cf4488da9f721750ce5ff4311e88356cbfcb1d4444c2b519b12d8bdb5f33f2c6'
DEXKIT_URL = 'https://repo.maven.apache.org/maven2/org/luckypray/dexkit/2.2.0/dexkit-2.2.0.aar'
EXPECTED_TESTS = 87
POM = '''<project xmlns="http://maven.apache.org/POM/4.0.0">
<modelVersion>4.0.0</modelVersion><groupId>org.waex.qa</groupId>
<artifactId>call-type-qa-only</artifactId><version>1</version>
<repositories><repository><id>google</id><url>https://dl.google.com/dl/android/maven2/</url></repository></repositories>
<dependencies>
<dependency><groupId>org.robolectric</groupId><artifactId>robolectric</artifactId><version>4.17</version></dependency>
<dependency><groupId>org.robolectric</groupId><artifactId>android-all</artifactId><version>14-robolectric-10818077</version></dependency>
<dependency><groupId>junit</groupId><artifactId>junit</artifactId><version>4.13.2</version></dependency>
</dependencies></project>
'''


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def bootstrap_dexkit(out):
    """Fetch a pinned public API dependency; never load or extract native libraries."""
    limit = 4 * 1024 * 1024
    with urllib.request.urlopen(DEXKIT_URL, timeout=30) as response:
        length = response.headers.get('Content-Length')
        if length is not None and int(length) > limit:
            raise RuntimeError('DexKit archive exceeds the 4 MiB bootstrap limit')
        data = response.read(limit + 1)
    if len(data) > limit or hashlib.sha256(data).hexdigest() != DEXKIT_AAR_SHA256:
        raise RuntimeError('DexKit 2.2.0 public AAR identity mismatch')
    archive_path = out / 'dexkit-2.2.0.aar'
    archive_path.write_bytes(data)
    with zipfile.ZipFile(archive_path) as archive:
        if archive.namelist().count('classes.jar') != 1:
            raise RuntimeError('DexKit API JAR is missing or duplicated')
        info = archive.getinfo('classes.jar')
        if info.file_size > 2 * 1024 * 1024:
            raise RuntimeError('DexKit API JAR exceeds the 2 MiB extraction limit')
        classes = archive.read(info)  # Checks the ZIP entry CRC as well as the pinned SHA below.
    target = out / 'dexkit-2.2.0.jar'
    target.write_bytes(classes)
    if digest(target) != DEXKIT_SHA256:
        raise RuntimeError('DexKit 2.2.0 public classes.jar identity mismatch')
    (out / 'dexkit-bootstrap.json').write_text(json.dumps({
        'url': DEXKIT_URL, 'aar_bytes': len(data), 'aar_sha256': DEXKIT_AAR_SHA256,
        'classes_jar_bytes': len(classes), 'classes_jar_sha256': DEXKIT_SHA256,
        'native_libraries_extracted_or_loaded': False}, indent=2) + '\n')
    return target


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
    parser.add_argument('--dexkit', type=Path,
                        help='Offline unmodified classes.jar from DexKit 2.2.0; otherwise download the pinned public AAR')
    parser.add_argument('--maven', default='mvn')
    parser.add_argument('--maven-repo', type=Path)
    parser.add_argument('--deps', type=Path)
    parser.add_argument('--sdk-jars', type=Path, help='Directory of pinned instrumented Android JARs for offline tests')
    parser.add_argument('--allow-test-failure', action='store_true')
    args = parser.parse_args()
    root, out = args.source_root.resolve(), args.out.resolve()
    out.mkdir(parents=True, exist_ok=False)
    dexkit = args.dexkit.resolve() if args.dexkit else bootstrap_dexkit(out)
    if digest(dexkit) != DEXKIT_SHA256:
        raise RuntimeError('DexKit 2.2.0 classes.jar identity mismatch')
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
    compiler = shutil.which('kotlinc')
    if not compiler:
        raise RuntimeError('Kotlin compiler is required')
    kotlin_lib = Path(compiler).resolve().parents[1] / 'lib'
    kotlin_runtime = kotlin_lib / 'kotlin-stdlib.jar'
    if not kotlin_runtime.is_file():
        raise RuntimeError('Kotlin standard library missing beside compiler')
    stage, classes = out / 'sources', out / 'classes'
    classes.mkdir()
    identities, staged = {}, {}
    for relative in JAVA_FIRST + KOTLIN + JAVA_HOOK + FIXTURES:
        source = (root if relative not in FIXTURES else ROOT) / relative
        dest = stage / relative
        dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, dest)
        staged[relative] = dest
        identities[str(relative)] = digest(source)
    identities['qa/android_call_type_test.py'] = digest(Path(__file__).resolve())
    identities['qa/localization_support.py'] = digest(ROOT / 'qa/localization_support.py')
    declared_tests = len(re.findall(r'@Test\s+public void', staged[FIXTURES[-1]].read_text())) * 3
    if declared_tests != EXPECTED_TESTS:
        raise RuntimeError('Fixture count differs from the reviewed 87-case gate')
    r_source, _ = write_r(root, stage / 'generated')
    build_config = stage / 'generated/com/waenhancer/app/BuildConfig.java'
    build_config.parent.mkdir(parents=True, exist_ok=True)
    build_config.write_text('package com.waenhancer.app; public final class BuildConfig { '
                            'public static final int VERSION_CODE=20100; '
                            'public static final String VERSION_NAME="fixture"; }\n')
    identities['generated/BuildConfig.java'] = digest(build_config)
    # Compile-only annotation types; no behavior or framework API is substituted.
    annotations = []
    for name in ('NonNull', 'Nullable'):
        path = stage / 'generated/androidx/annotation' / (name + '.java')
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text('package androidx.annotation;\n'
                        '@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.CLASS)\n'
                        'public @interface ' + name + ' {}\n')
        annotations.append(path)
    jars = sorted(deps.glob('*.jar'))
    if not any(re.fullmatch(r'kotlin-stdlib-[0-9].*\.jar', p.name) for p in jars):
        jars.append(kotlin_runtime)
    jars.append(dexkit)
    cp = os.pathsep.join(map(str, jars))
    classpath = str(classes) + os.pathsep + cp
    android = deps / 'android-all-14-robolectric-10818077.jar'
    if not android.is_file():
        raise RuntimeError('Pinned Android compilation API is missing')
    production_cp = os.pathsep.join(map(str, (classes, android, kotlin_runtime, dexkit)))
    run(['javac', '--release', '17', '-cp', production_cp, '-d', str(classes),
         *map(str, annotations), str(r_source), str(build_config), *[str(staged[p]) for p in FIXTURES[:-1] + JAVA_FIRST]],
        out / 'javac-production-dependencies.log')
    run([compiler, '-jvm-target', '17', '-classpath', production_cp, '-d', str(classes),
         *[str(staged[p]) for p in KOTLIN]], out / 'kotlinc-production.log')
    run(['javac', '--release', '17', '-cp', production_cp, '-d', str(classes),
         *[str(staged[p]) for p in JAVA_HOOK]], out / 'javac-production-selector.log')
    run(['javac', '--release', '17', '-cp', classpath, '-d', str(classes), str(staged[FIXTURES[-1]])],
        out / 'javac-tests.log')
    opens = ['java.base/java.lang', 'java.base/java.util', 'java.base/java.io', 'java.base/java.net',
             'java.base/java.security', 'java.base/java.text', 'java.base/jdk.internal.access',
             'java.desktop/java.awt.font', 'jdk.compiler/com.sun.tools.javac.api']
    command = ['java', *['--add-opens=' + p + '=ALL-UNNAMED' for p in opens],
               '-Drobolectric.dependency.repo.url=https://repo.maven.apache.org/maven2',
               '-cp', classpath, 'org.junit.runner.JUnitCore',
               'com.waenhancer.xposed.features.privacy.CallTypeAndroidTest']
    if args.maven_repo:
        command.insert(1, '-Dmaven.repo.local=' + str(args.maven_repo.resolve()))
    if args.sdk_jars:
        command[1:1] = ['-Drobolectric.offline=true',
                        '-Drobolectric.dependency.dir=' + str(args.sdk_jars.resolve())]
    result = run(command, out / 'junit.log', allow_failure=args.allow_test_failure)
    log = (out / 'junit.log').read_text()
    success = re.search(r'OK \((\d+) tests?\)', log)
    count = int(success.group(1)) if success else None
    dependency_identities = {p.name: digest(p) for p in jars}
    if args.sdk_jars:
        dependency_identities.update({p.name: digest(p) for p in args.sdk_jars.resolve().glob('*.jar')})
    (out / 'dependency-sha256.json').write_text(json.dumps(dependency_identities, indent=2) + '\n')
    summary = {'source_root': str(root), 'junit_exit_code': result, 'passed_tests': count,
               'expected_tests': EXPECTED_TESTS, 'source_sha256': identities,
               'limits': 'Whole production selector, contract, DexSearchEngine, DexCacheManager, exact phone mapper and dependencies; real Android API 24/28/34 dialogs, Activity/View lifecycle, Looper and SQLite. Explicit Xposed registration/original-call fixtures and verified-shape host classes. Fixture Fragment uses platform Fragment while host uses AndroidX. Compile-only annotations and generated R IDs; resource behavior is audited separately with AAPT2. DexKit 2.2.0 API compilation and unavailable-JNI path, not a successful native DexKit search or LSPosed/device injection. No full module APK or external phone/WhatsApp call.'}
    (out / 'result.json').write_text(json.dumps(summary, indent=2) + '\n')
    if not args.allow_test_failure and (result or count != EXPECTED_TESTS):
        raise RuntimeError('Call selector Android tests did not all pass')
    print(json.dumps(summary, indent=2))


if __name__ == '__main__':
    main()
