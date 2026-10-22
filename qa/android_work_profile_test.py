#!/usr/bin/env python3
"""Run manager restart/update profile regressions on real Android framework classes.

Compiles complete production restart helpers and UpdateDownloader on Robolectric
4.17 / API 24, 28 and 34 with the project's pinned OkHttp and Kotlin versions.
Context UID and package/archive metadata are explicit fixtures; no work profile
is created, no APK is installed, and no su/am/pm command or LSPosed hook is run.
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
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]
BASE = Path('app/src/main/java/com/waenhancer')
SOURCES = [BASE / name for name in (
    'xposed/utils/AppRestartHelper.java', 'xposed/utils/RestartTargetPolicy.java',
    'update/UpdateDownloader.kt', 'update/UpdateTransferPolicy.kt')]
OPTIONAL_BASELINE_SOURCE = BASE / 'utils/AndroidUserScope.java'
EXPECTED_TESTS = 87


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def run(command, path, allow_failure=False, timeout=600):
    with path.open('w') as output:
        result = subprocess.run(command, stdout=output, stderr=subprocess.STDOUT, timeout=timeout)
    print(path.name, 'exit=' + str(result.returncode), flush=True)
    if result.returncode and not allow_failure:
        print(path.read_text()[-18000:])
        raise RuntimeError('Command failed: ' + command[0])
    return result.returncode


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source-root', type=Path, default=ROOT)
    parser.add_argument('--out', type=Path, required=True)
    parser.add_argument('--maven', default='mvn')
    parser.add_argument('--maven-repo', type=Path)
    parser.add_argument('--deps', type=Path)
    parser.add_argument('--extra-deps', type=Path)
    parser.add_argument('--sdk-jars', type=Path)
    parser.add_argument('--expect-assertion-failures', type=int, default=0)
    args = parser.parse_args()
    root, out = args.source_root.resolve(), args.out.resolve()
    out.mkdir(parents=True, exist_ok=False)
    versions = tomllib.loads((root / 'gradle/libs.versions.toml').read_text())['versions']
    deps = args.deps.resolve() if args.deps else out / 'deps'
    deps.mkdir(parents=True, exist_ok=True)
    pom = '''<project xmlns="http://maven.apache.org/POM/4.0.0">
<modelVersion>4.0.0</modelVersion><groupId>org.waex.qa</groupId>
<artifactId>work-profile-qa-only</artifactId><version>1</version>
<repositories><repository><id>google</id><url>https://dl.google.com/dl/android/maven2/</url></repository></repositories>
<dependencies>
<dependency><groupId>org.robolectric</groupId><artifactId>robolectric</artifactId><version>4.17</version></dependency>
<dependency><groupId>org.robolectric</groupId><artifactId>android-all</artifactId><version>14-robolectric-10818077</version></dependency>
<dependency><groupId>junit</groupId><artifactId>junit</artifactId><version>4.13.2</version></dependency>
<dependency><groupId>com.squareup.okhttp3</groupId><artifactId>okhttp</artifactId><version>OKHTTP</version></dependency>
<dependency><groupId>org.jetbrains.kotlin</groupId><artifactId>kotlin-stdlib</artifactId><version>KOTLIN</version></dependency>
</dependencies></project>
'''.replace('OKHTTP', versions['okhttp']).replace('KOTLIN', versions['kotlin'])
    (out / 'pom.xml').write_text(pom)
    if not args.deps:
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
    jar_paths = sorted(deps.glob('*.jar'))
    if args.extra_deps:
        jar_paths.extend(sorted(args.extra_deps.resolve().glob('*.jar')))
    compiler = shutil.which('kotlinc')
    if not compiler:
        raise RuntimeError('Kotlin compiler required for complete production UpdateDownloader')
    kotlin_runtime = Path(compiler).resolve().parents[1] / 'lib/kotlin-stdlib.jar'
    if not any(re.fullmatch(r'kotlin-stdlib-[0-9].*\.jar', path.name) for path in jar_paths):
        jar_paths.append(kotlin_runtime)
    (out / 'dependency-sha256.json').write_text(json.dumps({
        str(path): digest(path) for path in jar_paths}, indent=2) + '\n')
    cp = os.pathsep.join(map(str, jar_paths))
    stage, classes = out / 'sources', out / 'classes'
    stage.mkdir()
    classes.mkdir()
    identities, fixtures = {}, {}
    java_sources, kotlin_sources = [], []
    production = list(SOURCES)
    if (root / OPTIONAL_BASELINE_SOURCE).exists():
        production.append(OPTIONAL_BASELINE_SOURCE)
    for relative in production:
        source, dest = root / relative, stage / relative
        dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, dest)
        identities[str(relative)] = digest(source)
        (java_sources if dest.suffix == '.java' else kotlin_sources).append(dest)

    def fixture(name, content):
        dest = stage / 'external-service-fixtures' / name
        dest.parent.mkdir(parents=True, exist_ok=True)
        dest.write_text(content)
        fixtures[name] = digest(dest)
        (java_sources if dest.suffix == '.java' else kotlin_sources).append(dest)

    fixture_source = ROOT / 'qa/fixtures/work_profile/RootCommandRunner.java'
    fixture('com/waenhancer/xposed/utils/RootCommandRunner.java', fixture_source.read_text())
    scope_path = root / 'app/src/main/res/values/arrays.xml'
    scope = [item.text.strip() for array in ET.parse(scope_path).getroot().findall('string-array')
             if array.get('name') == 'xposed_scope' for item in array.findall('item')
             if item.text and item.text.strip()]
    assert len(scope) > 10 and 'com.whatsapp' in scope and 'com.whatsapp.w4b' in scope
    identities[str(scope_path.relative_to(root))] = digest(scope_path)
    fixture('com/waenhancer/utils/WhatsAppPackageDetector.java',
            'package com.waenhancer.utils; public final class WhatsAppPackageDetector {'
            'public static java.util.Set<String> getSupportedPackages(android.content.Context context) {'
            'return new java.util.LinkedHashSet<>(java.util.Arrays.asList('
            + ','.join(json.dumps(pkg) for pkg in scope) + '));}}')
    fixture('androidx/core/content/FileProvider.java', '''package androidx.core.content;
public class FileProvider {
 public static android.net.Uri getUriForFile(android.content.Context c,String a,java.io.File f) {
  throw new AssertionError("External package installer is outside this fixture");
 }
}
''')
    release = root / BASE / 'update/ReleaseRepository.kt'
    normalization = re.findall(r'^fun normalizeVersion\(value: String\): String = .+$', release.read_text(), re.M)
    if len(normalization) != 1:
        raise RuntimeError('Version normalization adapter no longer matches its one-line production function')
    identities[str(release.relative_to(root))] = digest(release)
    fixture('com/waenhancer/update/Normalization.kt',
            'package com.waenhancer.update\n' + normalization[0] + '\n')
    from localization_support import write_display_adapter
    for path in write_display_adapter(root, stage / 'external-service-fixtures'):
        java_sources.append(path)
        fixtures[str(path.relative_to(stage / 'external-service-fixtures'))] = digest(path)
    test_source = ROOT / 'qa/fixtures/work_profile/WorkProfileRuntimeTest.java'
    test_dest = stage / test_source.name
    shutil.copyfile(test_source, test_dest)
    fixtures['WorkProfileRuntimeTest.java'] = digest(test_source)
    run(['javac', '--release', '17', '-cp', cp, '-d', str(classes),
         *map(str, java_sources)], out / 'javac-production.log')
    runtime_cp = str(classes) + os.pathsep + cp
    # Test libraries can use a newer Kotlin metadata version. Production compilation
    # must depend only on its Android/OkHttp APIs and Kotlin runtime, without skip flags.
    production_cp = os.pathsep.join([str(classes), *[str(path) for path in jar_paths
        if path.name.startswith(('android-all-', 'okhttp-', 'okio-', 'kotlin-stdlib'))]])
    run([compiler, '-jvm-target', '17', '-language-version', '2.0', '-classpath', production_cp,
         '-d', str(classes), *map(str, kotlin_sources)], out / 'kotlinc-production.log')
    run(['javac', '--release', '17', '-cp', runtime_cp, '-d', str(classes), str(test_dest)],
        out / 'javac-tests.log')
    opens = ['java.base/java.lang', 'java.base/java.util', 'java.base/java.io', 'java.base/java.net',
             'java.base/java.security', 'java.base/java.text', 'java.base/jdk.internal.access',
             'java.desktop/java.awt.font', 'jdk.compiler/com.sun.tools.javac.api']
    command = ['java', '-Xmx1g', *['--add-opens=' + item + '=ALL-UNNAMED' for item in opens],
               '-Drobolectric.dependency.repo.url=https://repo.maven.apache.org/maven2',
               '-cp', runtime_cp, 'org.junit.runner.JUnitCore',
               'com.waenhancer.xposed.utils.WorkProfileRuntimeTest']
    if args.maven_repo:
        command.insert(1, '-Dmaven.repo.local=' + str(args.maven_repo.resolve()))
    if args.sdk_jars:
        command[1:1] = ['-Drobolectric.offline=true',
                        '-Drobolectric.dependency.dir=' + str(args.sdk_jars.resolve())]
        (out / 'sdk-sha256.json').write_text(json.dumps({
            path.name: digest(path) for path in sorted(args.sdk_jars.glob('*.jar'))}, indent=2) + '\n')
    result = run(command, out / 'junit.log', allow_failure=True)
    if args.maven_repo and not args.sdk_jars:
        sdk_root = args.maven_repo.resolve() / 'org/robolectric/android-all-instrumented'
        (out / 'sdk-sha256.json').write_text(json.dumps({
            str(path.relative_to(sdk_root)): digest(path)
            for path in sorted(sdk_root.glob('*/*.jar'))}, indent=2) + '\n')
    junit = (out / 'junit.log').read_text()
    passed = re.search(r'OK \((\d+) tests?\)', junit)
    failed = re.search(r'Tests run: (\d+),\s+Failures: (\d+)', junit)
    tests = int(passed.group(1)) if passed else int(failed.group(1)) if failed else 0
    failures = int(failed.group(2)) if failed else 0
    assertion_types = re.findall(r'^(java\.lang\.AssertionError|org\.junit\.ComparisonFailure)(?::|$)', junit, re.M)
    expected = args.expect_assertion_failures
    accepted = tests == EXPECTED_TESTS and failures == expected
    accepted = accepted and (result == 0 and passed is not None if not expected
                             else result != 0 and len(assertion_types) == expected)
    summary = {
        'source_root': str(root), 'junit_exit_code': result,
        'source_sha256': identities, 'external_service_fixture_sha256': fixtures,
        'tests': tests, 'failures': failures, 'assertion_failures': len(assertion_types),
        'expected_assertion_failures': expected, 'accepted_result': accepted,
        'limits': 'Complete production AppRestartHelper, RestartTargetPolicy, UpdateDownloader, UpdateTransferPolicy and candidate AndroidUserScope. Robolectric 4.17 uses Android API 24/28/34 framework Context, Activity, Intent, PackageManager, Handler and Toast; per-profile UID metadata, package/archive resolution and privileged subprocess results are controlled fixtures. Two invalid-component cases inject only PackageManager.getLaunchIntentForPackage while other framework calls remain direct. Negative installer assertions wait for the actual production worker thread to finish. Real project-pinned OkHttp; no HTTP request is made. Version normalization copies its exact one-line production function; display localization, scope lookup, external package installer and root endpoints are explicit adapters. Bulk restart manager self-termination is not run. No profile is created, no APK is installed, no su/am/pm runs, no LSPosed or physical-device acceptance.'}
    (out / 'result.json').write_text(json.dumps(summary, indent=2) + '\n')
    print(json.dumps({key: summary[key] for key in ('tests', 'failures', 'assertion_failures', 'accepted_result')}, indent=2))
    if not accepted:
        print(junit[-18000:])
        raise RuntimeError('Unexpected work-profile framework result')


if __name__ == '__main__':
    main()
