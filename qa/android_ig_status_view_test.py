#!/usr/bin/env python3
"""Exercise the complete current IG status hook, view and adapter lifecycle.

Uses pinned Robolectric 4.17 on API 24, 28 and 34. JDK 17+, Kotlin, Maven and
network are needed on first use. Production DexKit/caching classes compile and
exercise the unavailable-native path; host/Xposed callback boundaries are named
fixtures. This does not prove native discovery, actual WhatsApp or LSPosed,
AndroidX Fragment internals, host resources, database schemas or playback.
"""
import argparse
import hashlib
import io
import urllib.request
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[1]
EXPECTED_TESTS = 96
VIEWS = Path('app/src/main/java/com/waenhancer/xposed/views')
SOURCES = [VIEWS / name for name in ('IGStatusView.java', 'HorizontalListView.java', 'IGStatusAdapter.java')] + [
    Path('app/src/main/java/com/waenhancer/xposed/features/customization/IGStatusHook.java'),
    Path('app/src/main/java/com/waenhancer/xposed/core/devkit/DexSearchEngine.java'),
    Path('app/src/main/java/com/waenhancer/xposed/core/devkit/DexCacheManager.java'),
    Path('app/src/main/java/com/waenhancer/xposed/core/devkit/DexNegativeLookupCache.java'),
    Path('app/src/main/java/com/waenhancer/xposed/core/BaseFeature.kt'),
]
DEXKIT_AAR_SHA256 = 'cf4488da9f721750ce5ff4311e88356cbfcb1d4444c2b519b12d8bdb5f33f2c6'
DEXKIT_JAR_SHA256 = '195979d9576ba6d68a1c60bdd3e38cd3aed068d4e0c5c6c99caa50bc25fcb9f1'

SERVICES = {
    'com/waenhancer/app/BuildConfig.java': 'package com.waenhancer.app; public final class BuildConfig { public static final int VERSION_CODE=20100; public static final String VERSION_NAME="fixture"; }',
    'androidx/annotation/NonNull.java': 'package androidx.annotation; public @interface NonNull {}',
    'androidx/annotation/Nullable.java': 'package androidx.annotation; public @interface Nullable {}',
    'androidx/core/content/ContextCompat.java': 'package androidx.core.content; public class ContextCompat {}',
    'com/waenhancer/xposed/core/components/WaexBottomSheet.java':
        'package com.waenhancer.xposed.core.components; public class WaexBottomSheet {}',
    'com/waenhancer/utils/ContactNameResolver.java': '''package com.waenhancer.utils;
public class ContactNameResolver {
 public static final ContactNameResolver INSTANCE = new ContactNameResolver();
 public String resolveName(android.content.Context c,String jid,Object a,Object b){return jid;}
}''',
    'com/waenhancer/xposed/utils/ActivityTracker.java': '''package com.waenhancer.xposed.utils;
public class ActivityTracker {public static android.app.Activity getCurrentActivity(){return null;}}''',
    'com/waenhancer/xposed/features/homescreen/MenuIconLoader.java': '''package com.waenhancer.xposed.features.homescreen;
public class MenuIconLoader {public static android.graphics.drawable.Drawable load(android.content.Context c,String n){return null;}}''',

}
POM = '''<project xmlns="http://maven.apache.org/POM/4.0.0">
<modelVersion>4.0.0</modelVersion><groupId>org.waex.qa</groupId>
<artifactId>ig-status-views-qa-only</artifactId><version>1</version>
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
    parser.add_argument('--dexkit', type=Path, help='Use a previously verified DexKit 2.2.0 classes JAR')
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
    if args.dexkit:
        dexkit = args.dexkit.resolve()
    else:
        dexkit = out / 'dexkit-2.2.0.jar'
        url = 'https://repo.maven.apache.org/maven2/org/luckypray/dexkit/2.2.0/dexkit-2.2.0.aar'
        with urllib.request.urlopen(url, timeout=30) as response:
            body = response.read(8 * 1024 * 1024 + 1)
        if len(body) > 8 * 1024 * 1024 or hashlib.sha256(body).hexdigest() != DEXKIT_AAR_SHA256:
            raise RuntimeError('DexKit public AAR identity mismatch')
        with zipfile.ZipFile(io.BytesIO(body)) as archive:
            dexkit.write_bytes(archive.read('classes.jar'))
    if hashlib.sha256(dexkit.read_bytes()).hexdigest() != DEXKIT_JAR_SHA256:
        raise RuntimeError('DexKit classes JAR identity mismatch')
    (out / 'dexkit-sha256.json').write_text(json.dumps({dexkit.name: DEXKIT_JAR_SHA256}, indent=2))
    compiler = shutil.which('kotlinc')
    if not compiler:
        raise RuntimeError('Kotlin compiler is required for the complete BaseFeature source')
    kotlin_runtime = Path(compiler).resolve().parents[1] / 'lib/kotlin-stdlib.jar'
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
    for fixture in sorted((ROOT / 'qa/fixtures/ig_status').rglob('*.java')):
        relative = fixture.relative_to(ROOT / 'qa/fixtures/ig_status')
        dest = stage / 'external-service-fixtures' / relative
        dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(fixture, dest)
        sources.append(dest)
        service_identities[str(relative)] = hashlib.sha256(fixture.read_bytes()).hexdigest()
        identities[str(fixture.relative_to(ROOT))] = hashlib.sha256(fixture.read_bytes()).hexdigest()
    for relative, content in SERVICES.items():
        dest = stage / 'external-service-fixtures' / relative
        dest.parent.mkdir(parents=True, exist_ok=True)
        dest.write_text(content)
        sources.append(dest)
        service_identities[relative] = hashlib.sha256(dest.read_bytes()).hexdigest()
    from localization_support import write_display_adapter
    display_sources = write_display_adapter(root, stage / 'external-service-fixtures')
    sources.extend(display_sources)
    for path in display_sources:
        service_identities[str(path.relative_to(stage / 'external-service-fixtures'))] = hashlib.sha256(path.read_bytes()).hexdigest()
    test_source = ROOT / 'qa/fixtures/IGStatusViewLifecycleTest.java'
    test_dest = stage / test_source.name
    shutil.copyfile(test_source, test_dest)
    identities['qa/fixtures/IGStatusViewLifecycleTest.java'] = hashlib.sha256(test_source.read_bytes()).hexdigest()
    sources.append(test_dest)
    jars = sorted(deps.glob('*.jar')) + [dexkit, kotlin_runtime]
    cp = os.pathsep.join(str(p) for p in jars)
    classpath = str(classes) + os.pathsep + cp
    kotlin_sources = [source for source in sources if source.suffix == '.kt']
    java_sources = [source for source in sources if source.suffix == '.java']
    production_cp = os.pathsep.join([str(deps / 'android-all-14-robolectric-10818077.jar'), str(kotlin_runtime)])
    run([compiler, '-jvm-target', '17', '-classpath', production_cp, '-d', str(classes), *map(str, kotlin_sources)], out / 'kotlinc.log')
    run(['javac', '--release', '17', '-cp', classpath, '-d', str(classes), *map(str, java_sources)], out / 'javac.log')
    opens = ['java.base/java.lang', 'java.base/java.util', 'java.base/java.io', 'java.base/java.net',
             'java.base/java.security', 'java.base/java.text', 'java.base/jdk.internal.access',
             'java.desktop/java.awt.font', 'jdk.compiler/com.sun.tools.javac.api']
    command = ['java', *['--add-opens=' + p + '=ALL-UNNAMED' for p in opens],
               '-Drobolectric.dependency.repo.url=https://repo.maven.apache.org/maven2',
               '-cp', str(classes) + os.pathsep + cp, 'org.junit.runner.JUnitCore',
               'com.waenhancer.xposed.views.IGStatusViewLifecycleTest']
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
    assertions = len(re.findall(r'^java\.lang\.AssertionError(?::|$)', junit, re.MULTILINE))
    expected_failures = args.expect_assertion_failures
    valid = tests == EXPECTED_TESTS and failures == expected_failures
    if expected_failures:
        valid = valid and result != 0 and assertions == expected_failures
    else:
        valid = valid and result == 0 and passed is not None
    summary = {'source_root': str(root), 'junit_exit_code': result, 'source_sha256': identities,
               'external_service_fixture_sha256': service_identities,
               'tests': tests, 'failures': failures, 'assertion_failures': assertions,
               'expected_assertion_failures': expected_failures, 'accepted_result': valid,
               'limits': 'Complete production IGStatusHook, IGStatusView, HorizontalListView, IGStatusAdapter, BaseFeature, DexSearchEngine and cache classes on Robolectric API24/28/34. Real Android Views, Activity lifecycle, Looper, gestures, layout and adapter binding. Explicit Xposed callback-dispatch, host Fragment/controller, contact, icon, localization and activity-tracker fixtures; native DexKit unavailable path and pinned API compilation only. No actual WhatsApp, AndroidX Fragment internals, LSPosed, device frame-time measurement or host playback.'}
    (out / 'result.json').write_text(json.dumps(summary, indent=2))
    if not valid:
        print(junit[-18000:])
        raise RuntimeError('Unexpected Android view test result: ' + json.dumps(summary))
    print(json.dumps(summary, indent=2))


if __name__ == '__main__':
    main()
