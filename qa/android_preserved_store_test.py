#!/usr/bin/env python3
"""Compile the complete preserved store and exercise Android's database upgrade lifecycle.

Pinned Robolectric 4.17, Android API 24/28/34 and real SQLite files; no WhatsApp,
Xposed, private licensing checkout or Android SDK is required. Maven and network
are needed for the first dependency download, plus a Kotlin compiler and JDK 17+.
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
SOURCES = [
    Path('app/src/main/java/com/waenhancer/utils/ContactIdentityPolicy.java'),
    Path('app/src/main/java/com/waenhancer/xposed/core/db/PreservedMessageStore.kt'),
]
EXPECTED_TESTS = 33
POM = '''<project xmlns="http://maven.apache.org/POM/4.0.0">
<modelVersion>4.0.0</modelVersion><groupId>org.waex.qa</groupId>
<artifactId>preserved-store-qa-only</artifactId><version>1</version>
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
    parser.add_argument('--sdk-jars', type=Path, help='Use previously downloaded instrumented Android JARs offline')
    parser.add_argument('--expect-assertion-failures', type=int, default=0,
                        help='Require the full suite and exactly this many assertion failures for a known baseline or mutant')
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
        for path in sorted(deps.iterdir()) if path.is_file()}, indent=2) + '\n')
    compiler = shutil.which('kotlinc')
    if not compiler:
        raise RuntimeError('Kotlin compiler is required')
    kotlin_lib = Path(compiler).resolve().parents[1] / 'lib'
    kotlin_runtime = kotlin_lib / 'kotlin-stdlib.jar'
    if not kotlin_runtime.is_file():
        raise RuntimeError('Kotlin standard library was not found beside the compiler')
    stage, classes = out / 'sources', out / 'classes'
    classes.mkdir()
    identities, staged = {}, []
    for relative in SOURCES:
        source, dest = root / relative, stage / relative
        dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, dest)
        staged.append(dest)
        identities[str(relative)] = hashlib.sha256(source.read_bytes()).hexdigest()
    # Compile the exact single expression used by the production Compose mapper.
    # This executes its row-key semantics without pretending to render Compose.
    screen = Path('app/src/main/java/com/waenhancer/ui/screens/privacy/DeletedMessagesVaultScreen.kt')
    screen_text = (root / screen).read_text()
    keys = re.findall(r'val msg = PreservedMessage\(\s*(?://[^\n]*\n\s*)*id = ([^\n]+),', screen_text)
    if len(keys) != 1:
        raise RuntimeError('Cannot identify exactly one production vault row-key expression')
    row_key = stage / 'VaultRowKey.kt'
    row_key.write_text('''package com.waenhancer.xposed.core.db
object VaultRowKey {
    @JvmStatic fun forRecord(rec: PreservedMessageStore.StoredMessage): String = ''' + keys[0] + '\n}\n')
    identities[str(screen)] = hashlib.sha256((root / screen).read_bytes()).hexdigest()
    identities['extracted-production-expression/VaultRowKey.kt'] = hashlib.sha256(row_key.read_bytes()).hexdigest()
    test_source = ROOT / 'qa/fixtures/PreservedStoreCompatibilityTest.java'
    test_dest = stage / test_source.name
    shutil.copyfile(test_source, test_dest)
    identities['qa/fixtures/PreservedStoreCompatibilityTest.java'] = hashlib.sha256(test_source.read_bytes()).hexdigest()
    jars = sorted(deps.glob('*.jar'))
    if not any(re.fullmatch(r'kotlin-stdlib-[0-9].*\.jar', p.name) for p in jars):
        jars.append(kotlin_runtime)
    cp = os.pathsep.join(map(str, jars))
    classpath = str(classes) + os.pathsep + cp
    android = deps / 'android-all-14-robolectric-10818077.jar'
    if not android.is_file():
        raise RuntimeError('Pinned Android compilation API is missing')
    # Compile production only against its own API dependencies. Robolectric test
    # internals may use a newer Kotlin metadata version than the app compiler.
    production_cp = os.pathsep.join([str(classes), str(android), str(kotlin_runtime)])
    run(['javac', '--release', '17', '-cp', cp, '-d', str(classes), str(staged[0])], out / 'javac-production.log')
    run([compiler, '-jvm-target', '17', '-classpath', production_cp, '-d', str(classes), str(staged[1]), str(row_key)], out / 'kotlinc-production.log')
    run(['javac', '--release', '17', '-cp', classpath, '-d', str(classes), str(test_dest)], out / 'javac-tests.log')
    opens = ['java.base/java.lang', 'java.base/java.util', 'java.base/java.io', 'java.base/java.net',
             'java.base/java.security', 'java.base/java.text', 'java.base/jdk.internal.access',
             'java.desktop/java.awt.font', 'jdk.compiler/com.sun.tools.javac.api']
    command = ['java', *['--add-opens=' + p + '=ALL-UNNAMED' for p in opens],
               '-Drobolectric.dependency.repo.url=https://repo.maven.apache.org/maven2',
               '-cp', classpath, 'org.junit.runner.JUnitCore',
               'com.waenhancer.xposed.core.db.PreservedStoreCompatibilityTest']
    if args.maven_repo:
        command.insert(1, '-Dmaven.repo.local=' + str(args.maven_repo.resolve()))
    if args.sdk_jars:
        command[1:1] = ['-Drobolectric.offline=true',
                        '-Drobolectric.dependency.dir=' + str(args.sdk_jars.resolve())]
        (out / 'sdk-sha256.json').write_text(json.dumps({
            path.name: hashlib.sha256(path.read_bytes()).hexdigest()
            for path in sorted(args.sdk_jars.glob('*.jar'))}, indent=2) + '\n')
    result = run(command, out / 'junit.log', allow_failure=True)
    if args.maven_repo and not args.sdk_jars:
        sdk_root = args.maven_repo.resolve() / 'org/robolectric/android-all-instrumented'
        (out / 'sdk-sha256.json').write_text(json.dumps({
            path.name: hashlib.sha256(path.read_bytes()).hexdigest()
            for path in sorted(sdk_root.glob('*/*.jar'))}, indent=2) + '\n')
    junit = (out / 'junit.log').read_text()
    success = re.search(r'OK \((\d+) tests?\)', junit)
    failed = re.search(r'Tests run: (\d+),\s+Failures: (\d+)', junit)
    count = int(success.group(1)) if success else int(failed.group(1)) if failed else 0
    failures = int(failed.group(2)) if failed else 0
    assertions = junit.count('\njava.lang.AssertionError:')
    expected_failures = args.expect_assertion_failures
    valid = count == EXPECTED_TESTS and failures == expected_failures
    if expected_failures:
        valid = valid and result != 0 and assertions == expected_failures
    else:
        valid = valid and result == 0 and success is not None
    summary = {'source_root': str(root), 'junit_exit_code': result, 'passed_tests': count - failures,
               'expected_tests': EXPECTED_TESTS, 'source_sha256': identities,
               'tests': count, 'failures': failures, 'assertion_failures': assertions,
               'expected_assertion_failures': expected_failures, 'accepted_result': valid,
               'limits': 'Complete production store and Android SQLiteOpenHelper on Robolectric API 24/28/34 with real SQLite files. Exact row-key expression extracted from the production Compose mapper and compiled separately. Synthetic rows and historical schemas; no WhatsApp, capture hook, LSPosed, or full Compose vault screen.'}
    (out / 'result.json').write_text(json.dumps(summary, indent=2) + '\n')
    if not valid:
        print(junit[-18000:])
        raise RuntimeError('Unexpected preserved-store framework result: ' + json.dumps(summary))
    print(json.dumps(summary, indent=2))


if __name__ == '__main__':
    main()
