#!/usr/bin/env python3
"""Complete recorder and schema with real Android preferences, Bundle and file descriptors.

Robolectric 4.17, Android 24/28/34, JUnit 4.13.2 and DexKit 2.2.0 API.
MediaRecorder's native boundary uses an explicit shadow: it does not capture sound,
write encoded audio or validate device routing. Host, Xposed, scheduler, storage
authorization and native DexKit discovery are fixtures. No APK or root execution.
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

from test_call_recording_lifecycle import STUBS

ROOT = Path(__file__).resolve().parents[1]
BASE = Path('app/src/main/java/com/waenhancer')
JAVA_FIRST = [BASE / name for name in ('config/PreferenceSchema.java', 'config/PreferenceStores.java')]
KOTLIN = [BASE / name for name in ('xposed/core/BaseFeature.kt', 'core/preferences/SettingsBackupCodec.kt')]
JAVA_HOOK = [BASE / name for name in (
    'core/preferences/WaexPreferenceManager.java',
    'xposed/features/media/CallRecordingHook.java',
    'xposed/features/media/CallRecordingEventContract.java')]
PRODUCTION = JAVA_FIRST + KOTLIN + JAVA_HOOK
FIXTURE = Path('qa/fixtures/CallRecordingAudioAndroidTest.java')
EXPECTED_TESTS = 78

DEXKIT_SHA256 = '195979d9576ba6d68a1c60bdd3e38cd3aed068d4e0c5c6c99caa50bc25fcb9f1'
DEXKIT_AAR_SHA256 = 'cf4488da9f721750ce5ff4311e88356cbfcb1d4444c2b519b12d8bdb5f33f2c6'
DEXKIT_URL = 'https://repo.maven.apache.org/maven2/org/luckypray/dexkit/2.2.0/dexkit-2.2.0.aar'
POM = '''<project xmlns="http://maven.apache.org/POM/4.0.0">
<modelVersion>4.0.0</modelVersion><groupId>org.waex.qa</groupId>
<artifactId>call-recording-audio-qa-only</artifactId><version>1</version>
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


def run(command, log, allow_failure=False):
    with log.open('w') as output:
        result = subprocess.run(command, stdout=output, stderr=subprocess.STDOUT, timeout=600)
    print(log.name + ': exit=' + str(result.returncode), flush=True)
    if result.returncode and not allow_failure:
        print(log.read_text()[-18000:])
        raise RuntimeError('Command failed: ' + command[0])
    return result.returncode


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source-root', type=Path, default=ROOT)
    parser.add_argument('--out', type=Path, required=True)
    parser.add_argument('--dexkit', type=Path)
    parser.add_argument('--deps', type=Path)
    parser.add_argument('--sdk-jars', type=Path)
    parser.add_argument('--maven', default='mvn')
    parser.add_argument('--maven-repo', type=Path)
    parser.add_argument('--allow-test-failure', action='store_true')
    args = parser.parse_args()
    root, out = args.source_root.resolve(), args.out.resolve()
    out.mkdir(parents=True, exist_ok=False)
    dexkit = args.dexkit.resolve() if args.dexkit else bootstrap_dexkit(out)
    if digest(dexkit) != DEXKIT_SHA256:
        raise RuntimeError('DexKit 2.2.0 API identity mismatch')
    deps = args.deps.resolve() if args.deps else out / 'deps'
    deps.mkdir(parents=True, exist_ok=True)
    if not args.deps:
        (out / 'pom.xml').write_text(POM)
        command = [args.maven, '-B', '-f', str(out / 'pom.xml'),
                   'org.apache.maven.plugins:maven-dependency-plugin:3.8.1:copy-dependencies',
                   '-DoutputDirectory=' + str(deps)]
        if args.maven_repo: command.append('-Dmaven.repo.local=' + str(args.maven_repo.resolve()))
        from maven_dependencies import resolve_dependencies
        resolve_dependencies(command, out / 'dependencies.log', timeout=600)
        for archive in deps.glob('*.aar'):
            with zipfile.ZipFile(archive) as bundle:
                for name in bundle.namelist():
                    if name == 'classes.jar' or (name.startswith('libs/') and name.endswith('.jar')):
                        (deps / (archive.stem + '-' + name.replace('/', '_'))).write_bytes(bundle.read(name))
    compiler = shutil.which('kotlinc')
    if not compiler: raise RuntimeError('Kotlin compiler is required')
    kotlin_runtime = Path(compiler).resolve().parents[1] / 'lib/kotlin-stdlib.jar'
    if not kotlin_runtime.is_file(): raise RuntimeError('Kotlin runtime is missing')
    android = deps / 'android-all-14-robolectric-10818077.jar'
    if not android.is_file(): raise RuntimeError('Pinned compilation Android API is missing')
    classes, stage = out / 'classes', out / 'sources'
    classes.mkdir()
    identities, staged = {}, {}
    for relative in PRODUCTION + [FIXTURE]:
        source = (ROOT if relative == FIXTURE else root) / relative
        dest = stage / relative; dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, dest); staged[relative] = dest; identities[str(relative)] = digest(source)
    for name in ('qa/android_call_recording_audio_test.py',
                 'qa/test_call_recording_lifecycle.py', 'qa/maven_dependencies.py'):
        identities[name] = digest(ROOT / name)
    (out / 'source-identity.json').write_text(json.dumps(identities, indent=2) + '\n')
    if len(re.findall(r'@Test\s+public void', staged[FIXTURE].read_text())) * 3 != EXPECTED_TESTS:
        raise RuntimeError('Reviewed fixture method count changed')
    fixtures = []
    for name, content in STUBS.items():
        if not (name.startswith(('de/robv/android/xposed/', 'com/whatsapp/'))
                or name in ('androidx/annotation/NonNull.java',
                            'com/waenhancer/xposed/core/devkit/DexSearchEngine.java',
                            'com/waenhancer/xposed/bridge/HookProvider.java')):
            continue
        if name.endswith('DexSearchEngine.java'):
            content = content.replace('return null;', 'throw new AssertionError("Native discovery is outside this fixture");')
        if name.endswith('XposedBridge.java'):
            content = content.replace('public final class XposedBridge {',
                    'public final class XposedBridge { public static boolean failLog;')
            content = content.replace('public static void reset(){', 'public static void reset(){failLog=false;')
            content = content.replace('public static void log(String s){logs.add(s);}',
                    'public static void log(String s){if(failLog)throw new IllegalStateException("fixture log failure");logs.add(s);}')
        path = stage / 'boundaries' / name
        path.parent.mkdir(parents=True, exist_ok=True); path.write_text(content); fixtures.append(path)
    for name in ('dagger/hilt/android/qualifiers/ApplicationContext',):
        package, annotation = name.rsplit('/', 1)
        path = stage / 'boundaries' / (name + '.java')
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text('package ' + package.replace('/', '.') + '; public @interface ' + annotation + ' {}\n')
        fixtures.append(path)
    jars = sorted(deps.glob('*.jar'))
    if not any(re.fullmatch(r'kotlin-stdlib-[0-9].*\.jar', p.name) for p in jars): jars.append(kotlin_runtime)
    jars.append(dexkit)
    cp = os.pathsep.join(map(str, jars))
    classpath = str(classes) + os.pathsep + cp
    injection_api = deps / 'javax.inject-1.jar'
    if not injection_api.is_file(): raise RuntimeError('Pinned javax.inject API is missing')
    production_cp = os.pathsep.join(map(str, (classes, android, kotlin_runtime, dexkit, injection_api)))
    run(['javac', '--release', '17', '-cp', production_cp, '-d', str(classes),
         *map(str, fixtures), *[str(staged[p]) for p in JAVA_FIRST]], out / 'javac-fixtures-schema.log')
    run([compiler, '-jvm-target', '17', '-classpath', production_cp, '-d', str(classes),
         *[str(staged[p]) for p in KOTLIN]], out / 'kotlinc-base-and-codec.log')
    run(['javac', '--release', '17', '-cp', production_cp, '-d', str(classes),
         *[str(staged[p]) for p in JAVA_HOOK]], out / 'javac-recorder.log')
    run(['javac', '--release', '17', '-cp', classpath, '-d', str(classes),
         str(staged[FIXTURE])], out / 'javac-tests.log')
    opens = ['java.base/java.lang', 'java.base/java.util', 'java.base/java.io', 'java.base/java.net',
             'java.base/java.security', 'java.base/java.text', 'java.base/jdk.internal.access',
             'java.desktop/java.awt.font', 'jdk.compiler/com.sun.tools.javac.api']
    command = ['java', *['--add-opens=' + name + '=ALL-UNNAMED' for name in opens],
               '-Drobolectric.dependency.repo.url=https://repo.maven.apache.org/maven2',
               '-cp', classpath, 'org.junit.runner.JUnitCore',
               'com.waenhancer.xposed.features.media.CallRecordingAudioAndroidTest']
    if args.sdk_jars:
        command[1:1] = ['-Drobolectric.offline=true',
                        '-Drobolectric.dependency.dir=' + str(args.sdk_jars.resolve())]
    if args.maven_repo: command.insert(1, '-Dmaven.repo.local=' + str(args.maven_repo.resolve()))
    result = run(command, out / 'junit.log', allow_failure=True)
    log = (out / 'junit.log').read_text()
    success = re.search(r'OK \((\d+) tests?\)', log)
    count = int(success.group(1)) if success else None
    dependencies = {p.name: digest(p) for p in jars}
    if args.sdk_jars: dependencies.update({p.name: digest(p) for p in args.sdk_jars.resolve().glob('*.jar')})
    (out / 'dependency-sha256.json').write_text(json.dumps(dependencies, indent=2) + '\n')
    summary = {'source_root': str(root), 'junit_exit_code': result, 'passed_tests': count,
               'expected_tests': EXPECTED_TESTS, 'methods_per_sdk': 26, 'sdk': [24, 28, 34],
               'source_sha256': identities,
               'limits': 'Seven complete production classes: recorder, event contract, BaseFeature, preference schema, stores, preference manager and backup codec. Real Android framework SharedPreferences, Bundle, ContentResolver and ParcelFileDescriptor on Robolectric 24/28/34. Native media operations use an explicit MediaRecorder shadow; no audio or encoded file bytes are generated. Host declarations, Xposed callbacks, scheduler, storage authorization and DexKit native discovery are explicit fixtures. Preference write-failure injection uses an explicit Context/Editor proxy. Real DexKit 2.2.0 API compilation. No complete CallsHub Compose render, full module APK, root, Samsung route, remote participant or real WhatsApp call was tested.'}
    (out / 'result.json').write_text(json.dumps(summary, indent=2) + '\n')
    if not args.allow_test_failure and (result or count != EXPECTED_TESTS):
        raise RuntimeError('Call audio regression gate failed')
    print(json.dumps(summary, indent=2))


if __name__ == '__main__': main()
