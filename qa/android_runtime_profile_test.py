#!/usr/bin/env python3
"""Compile the complete UID inspector against Android and run API 24/28/34 tests.

Resolves pinned Robolectric/Android dependencies, or reuses --deps for offline runs.
Android framework Context, PackageManager, ApplicationInfo, Process and SystemClock run through
Robolectric; UID/package metadata use standard shadows. AppRestartHelper is the
only controlled production endpoint. No su, pidof, /proc or work profile runs.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tomllib
import zipfile

ROOT = Path(__file__).resolve().parents[1]
BASE = Path('app/src/main/java/com/waenhancer/utils')
PRODUCTION = [BASE / name for name in
              ('AndroidUserScope.java', 'InjectionEvidence.kt', 'XposedRuntimeInspector.kt')]
UNIT_TEST = Path('app/src/test/java/com/waenhancer/utils/WhatsAppPackageDetectorTest.kt')
EXPECTED_TESTS = 30


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def execute(command, log):
    with log.open('w') as output:
        result = subprocess.run(command, stdout=output, stderr=subprocess.STDOUT, timeout=60)
    print(log.name, 'exit=' + str(result.returncode), flush=True)
    return result.returncode


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source-root', type=Path, default=ROOT)
    parser.add_argument('--deps', type=Path)
    parser.add_argument('--maven', default='mvn')
    parser.add_argument('--maven-repo', type=Path)
    parser.add_argument('--sdk-jars', type=Path)
    parser.add_argument('--out', type=Path, required=True)
    parser.add_argument('--expect-assertion-failures', type=int, default=0)
    args = parser.parse_args()
    root, out = args.source_root.resolve(), args.out.resolve()
    out.mkdir(parents=True, exist_ok=False)
    stage, classes = out / 'sources', out / 'classes'
    stage.mkdir(); classes.mkdir()
    deps = args.deps.resolve() if args.deps else out / 'deps'
    if not args.deps:
        deps.mkdir()
        kotlin = tomllib.loads((ROOT / 'gradle/libs.versions.toml').read_text())['versions']['kotlin']
        pom = '''<project xmlns="http://maven.apache.org/POM/4.0.0">
<modelVersion>4.0.0</modelVersion><groupId>org.waex.qa</groupId>
<artifactId>runtime-profile-qa-only</artifactId><version>1</version>
<repositories><repository><id>google</id><url>https://dl.google.com/dl/android/maven2/</url></repository></repositories>
<dependencies>
<dependency><groupId>org.robolectric</groupId><artifactId>robolectric</artifactId><version>4.17</version></dependency>
<dependency><groupId>org.robolectric</groupId><artifactId>android-all</artifactId><version>14-robolectric-10818077</version></dependency>
<dependency><groupId>junit</groupId><artifactId>junit</artifactId><version>4.13.2</version></dependency>
<dependency><groupId>org.jetbrains.kotlin</groupId><artifactId>kotlin-stdlib</artifactId><version>KOTLIN</version></dependency>
</dependencies></project>
'''.replace('KOTLIN', kotlin)
        (out / 'pom.xml').write_text(pom)
        from maven_dependencies import resolve_dependencies, DEPENDENCY_GOAL
        command = [args.maven, '-B', '-f', str(out / 'pom.xml'), DEPENDENCY_GOAL,
                   '-DoutputDirectory=' + str(deps)]
        if args.maven_repo:
            command.append('-Dmaven.repo.local=' + str(args.maven_repo.resolve()))
        resolve_dependencies(command, out / 'dependencies.log', timeout=600)
        for archive in deps.glob('*.aar'):
            with zipfile.ZipFile(archive) as bundle:
                for name in bundle.namelist():
                    if name == 'classes.jar' or (name.startswith('libs/') and name.endswith('.jar')):
                        (deps / (archive.stem + '-' + name.replace('/', '_'))).write_bytes(bundle.read(name))
    jars = sorted(deps.glob('*.jar'))
    compiler = shutil.which('kotlinc')
    if not compiler or not any(p.name.startswith('android-all-') for p in jars):
        raise RuntimeError('Kotlin compiler and an Android-all compile jar are required')
    if not any(re.fullmatch(r'kotlin-stdlib-[0-9].*\.jar', p.name) for p in jars):
        jars.append(Path(compiler).resolve().parents[1] / 'lib/kotlin-stdlib.jar')
    (out / 'dependency-sha256.json').write_text(json.dumps(
        {str(p): digest(p) for p in jars}, indent=2) + '\n')
    identities = {}
    for relative in PRODUCTION:
        source = root / relative
        shutil.copyfile(source, stage / source.name)
        identities[str(relative)] = digest(source)
    fixture_root = ROOT / 'qa/fixtures/runtime_profile'
    fixtures = {}
    for name in ('AppRestartHelper.java', 'RuntimeProfileInspectorTest.java'):
        source = fixture_root / name
        shutil.copyfile(source, stage / name)
        fixtures[str(source.relative_to(ROOT))] = digest(source)

    # Compile the existing Gradle unit test as an API-consumer gate. Its small
    # detector delegate is an exact production fragment; the full detector also
    # depends on Compose and is outside this isolated framework harness.
    detector = root / BASE / 'WhatsAppPackageDetector.kt'
    detector_text = detector.read_text()
    start = detector_text.index('internal fun hasCurrentInjection(')
    end = detector_text.index('\n\nobject WhatsAppPackageDetector', start)
    delegate = 'package com.waenhancer.utils\n\n' + detector_text[start:end] + '\n'
    (stage / 'CurrentInjectionDelegate.kt').write_text(delegate)
    shutil.copyfile(root / UNIT_TEST, stage / UNIT_TEST.name)
    fixtures[str(UNIT_TEST)] = digest(root / UNIT_TEST)
    delegate_identity = {'source_path': str(BASE / 'WhatsAppPackageDetector.kt'),
                         'source_sha256': digest(detector),
                         'fragment_sha256': hashlib.sha256(delegate.encode()).hexdigest()}

    # Production Kotlin sees Android APIs and the project Kotlin runtime only.
    android_cp = os.pathsep.join(str(p) for p in jars if p.name.startswith(
        ('android-all-', 'kotlin-stdlib')))
    commands = [
        ['javac', '--release', '17', '-cp', android_cp, '-d', str(classes),
         str(stage / 'AndroidUserScope.java'), str(stage / 'AppRestartHelper.java')],
        [compiler, '-language-version', '2.0', '-jvm-target', '17',
         '-classpath', str(classes) + os.pathsep + android_cp, '-d', str(classes),
         str(stage / 'InjectionEvidence.kt'), str(stage / 'XposedRuntimeInspector.kt')],
    ]
    runtime_cp = os.pathsep.join([str(classes), *map(str, jars)])
    # The unit source imports JUnit only. Do not put unrelated Robolectric
    # transitive Kotlin metadata on this project's Kotlin compilation classpath.
    unit_cp = os.pathsep.join([str(classes), android_cp, *[
        str(p) for p in jars if p.name.startswith(('junit-', 'hamcrest-'))]])
    commands.append(['javac', '--release', '17', '-cp', runtime_cp, '-d', str(classes),
                     str(stage / 'RuntimeProfileInspectorTest.java')])
    commands.append([compiler, '-language-version', '2.0', '-jvm-target', '17',
                     '-classpath', unit_cp, '-Xfriend-paths=' + str(classes),
                     '-d', str(out / 'unit-test-classes'),
                     str(stage / 'CurrentInjectionDelegate.kt'), str(stage / UNIT_TEST.name)])
    for command, name in zip(commands, ('javac-production.log', 'kotlinc-production.log',
                                       'javac-tests.log', 'kotlinc-unit-test.log')):
        if execute(command, out / name):
            print((out / name).read_text())
            raise RuntimeError(name + ' failed')

    opens = ['java.base/java.lang', 'java.base/java.util', 'java.base/java.io', 'java.base/java.net',
             'java.base/java.security', 'java.base/java.text', 'java.base/jdk.internal.access',
             'java.desktop/java.awt.font', 'jdk.compiler/com.sun.tools.javac.api']
    command = ['java', '-Xmx1g', *['--add-opens=' + item + '=ALL-UNNAMED' for item in opens]]
    if args.maven_repo:
        command.append('-Dmaven.repo.local=' + str(args.maven_repo.resolve()))
    if args.sdk_jars:
        sdk = out / 'sdk-cache'; sdk.mkdir()
        sdk_ids = {}
        for jar in args.sdk_jars.resolve().rglob('android-all-instrumented-*.jar'):
            target = sdk / jar.name
            if not target.exists(): target.symlink_to(jar)
            sdk_ids[jar.name] = digest(jar)
        (out / 'sdk-sha256.json').write_text(json.dumps(sdk_ids, indent=2) + '\n')
        command += ['-Drobolectric.offline=true', '-Drobolectric.dependency.dir=' + str(sdk)]
    command += ['-Drobolectric.dependency.repo.url=https://repo.maven.apache.org/maven2',
                '-cp', runtime_cp, 'org.junit.runner.JUnitCore',
                'com.waenhancer.utils.RuntimeProfileInspectorTest']
    commands.append(command)
    result = execute(command, out / 'junit.log')
    junit = (out / 'junit.log').read_text()
    passed = re.search(r'OK \((\d+) tests?\)', junit)
    failed = re.search(r'Tests run: (\d+),\s+Failures: (\d+)', junit)
    tests = int(passed.group(1)) if passed else int(failed.group(1)) if failed else 0
    failures = int(failed.group(2)) if failed else 0
    assertions = len(re.findall(r'^(?:java\.lang\.AssertionError|org\.junit\.ComparisonFailure)(?::|$)', junit, re.M))
    expected = args.expect_assertion_failures
    accepted = tests == EXPECTED_TESTS and failures == expected
    accepted = accepted and (result == 0 and passed is not None if not expected
                             else result != 0 and assertions == expected)
    summary = {
        'source_root': str(root), 'source_sha256': identities,
        'fixture_sha256': fixtures, 'harness_sha256': digest(Path(__file__)),
        'existing_gradle_unit_test_compiled': str(UNIT_TEST),
        'detector_delegate': delegate_identity,
        'existing_gradle_unit_test_executed': False,
        'commands': commands, 'junit_exit': result,
        'tests': tests, 'failures': failures, 'assertion_failures': assertions,
        'expected_assertion_failures': expected, 'accepted_result': accepted,
        'limits': 'Complete AndroidUserScope.java, InjectionEvidence.kt and XposedRuntimeInspector.kt compiled against Android-all and Kotlin runtime. Robolectric 4.17 exercises Android API 24/28/34 Application, Context, PackageManager, ApplicationInfo, Process and SystemClock; UID and installed package metadata are supplied by standard shadows, not actual managed profiles. AppRestartHelper is the only controlled production endpoint. Root output is a fixture; no su, pidof, /proc, Android kernel, LSPosed or physical-device acceptance.'}
    (out / 'result.json').write_text(json.dumps(summary, indent=2) + '\n')
    print(json.dumps({k: summary[k] for k in ('tests', 'failures', 'assertion_failures', 'accepted_result')}))
    if not accepted:
        print(junit[-18000:])
        raise SystemExit(1)


if __name__ == '__main__':
    main()
