#!/usr/bin/env python3
"""Execute exact CallsHub storage/permission fragments with real files and Android objects.

The full Compose screen is not compiled or rendered. Extracted state, load and
effect bodies are unchanged; explicit Compose/lifecycle adapters expose their
callbacks. Kotlin's bundled coroutines execute real IO dispatch. HookProvider's
unchanged create_call_recording branch supplies actual Android file descriptors.
FileProvider's real path strategy checks the roots declared in file_paths.xml;
XML inflation, activity sharing, provider authorization and Binder are boundaries.
No audio, WhatsApp call or host injection is performed.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import zipfile

from android_media_preview_test import download_pinned, find_kotlin, resolve_framework

ROOT = Path(__file__).resolve().parents[1]
SCREEN = Path('app/src/main/java/com/waenhancer/ui/screens/calls/CallsHubScreen.kt')
PROVIDER = Path('app/src/main/java/com/waenhancer/xposed/bridge/HookProvider.java')
PATHS = Path('app/src/main/res/xml/file_paths.xml')
CORE_SHA256 = 'd1f5a319a77555df7e23858ffb5ed45a95ebb4263fa1ab7a993510ceb53555a0'
CORE_AAR_SHA256 = '311d83ac67d394076ec21d12ed2d10a44b59cb2929b7dce00e5a90a93842e37d'
CORE_URL = 'https://dl.google.com/dl/android/maven2/androidx/core/core/1.18.0/core-1.18.0.aar'
EXPECTED = 84


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def resolve_core(args, out):
    if args.core_jar:
        core = args.core_jar.resolve()
    else:
        directory = out / 'public-apis'
        archive = download_pinned(CORE_URL, directory / 'core-1.18.0.aar', CORE_AAR_SHA256, 8 * 1024 * 1024)
        with zipfile.ZipFile(archive) as bundle:
            matches = [entry for entry in bundle.infolist() if entry.filename == 'classes.jar']
            if len(matches) != 1 or matches[0].file_size > 4 * 1024 * 1024:
                raise RuntimeError('Core AAR must contain one bounded classes.jar')
            core = directory / 'core-1.18.0.jar'
            core.write_bytes(bundle.read(matches[0]))
    if not core.is_file() or digest(core) != CORE_SHA256:
        raise RuntimeError('AndroidX Core 1.18.0 identity mismatch')
    return core


def block(source, marker, start_at=0):
    start = source.index(marker, start_at)
    opening = source.index('{', start)
    depth = 0
    for position in range(opening, len(source)):
        depth += (source[position] == '{') - (source[position] == '}')
        if depth == 0:
            return source[start:position + 1], [source.count('\n', 0, start) + 1,
                                                source.count('\n', 0, position) + 1]
    raise RuntimeError('Unclosed source block: ' + marker)


def run(command, log):
    with log.open('w') as output:
        result = subprocess.run(command, stdout=output, stderr=subprocess.STDOUT, timeout=180)
    print(log.name, 'exit=' + str(result.returncode), flush=True)
    if result.returncode:
        print(log.read_text()[-12000:])
    return result.returncode


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source-root', type=Path, default=ROOT)
    parser.add_argument('--out', type=Path, default=None)
    parser.add_argument('--deps', type=Path, help='Reuse a flat Robolectric dependency directory; default: public Maven bootstrap')
    parser.add_argument('--sdk-jars', type=Path, help='Offline instrumented SDKs; default: Robolectric public Maven resolution')
    parser.add_argument('--core-jar', type=Path, help='Reuse pinned Core classes.jar; default: verified public Google Maven AAR')
    parser.add_argument('--maven', default='mvn')
    parser.add_argument('--maven-repo', type=Path)
    parser.add_argument('--maven-settings', type=Path)
    parser.add_argument('--allow-test-failure', action='store_true')
    args = parser.parse_args()
    root = args.source_root.resolve()
    out = args.out or Path(tempfile.mkdtemp(prefix='waex-recordings-list-'))
    out.mkdir(parents=True, exist_ok=True)
    if any(out.iterdir()):
        raise RuntimeError('--out must be empty')
    deps = resolve_framework(args, out)
    core = resolve_core(args, out)
    kotlin, compiler = find_kotlin()
    coroutine = kotlin / 'lib/kotlinx-coroutines-core-jvm.jar'
    if not coroutine.is_file():
        raise RuntimeError('Kotlin distribution must include its coroutine QA runtime')
    stage, classes = out / 'sources', out / 'classes'
    stage.mkdir(); classes.mkdir()
    screen = (root / SCREEN).read_text()
    # Both baseline and candidate retain the declaration and function boundaries.
    start = screen.index('\n', screen.index('    var selectedTab by remember')) + 1
    end = screen.index('    // Call Blocker & Privacy State', start)
    state = screen[start:end]
    load_marker = 'suspend fun loadRecordings()' if 'suspend fun loadRecordings()' in screen else 'fun loadRecordings()'
    load, load_range = block(screen, load_marker)
    effect, effect_range = block(screen, 'LaunchedEffect(selectedTab, canReadLegacyRecordings')
    stop, stop_range = block(screen, 'fun stopAudio()')
    dispose, dispose_range = block(screen, 'DisposableEffect(Unit)')
    play, play_range = block(screen, 'onClick = {', screen.index('// Play / Pause Action Button'))
    delete, delete_range = block(screen, 'onClick = {', screen.index('} else IconButton('))
    play_body = play[play.index('{') + 1:-1]
    delete_body = delete[delete.index('{') + 1:-1]
    item_start = screen.index('data class RecordedCallItem(')
    item_end = screen.index('\n)', item_start) + 2
    template = (ROOT / 'qa/fixtures/CallRecordingsListProbe.kt').read_text()
    probe = (template.replace('@@STATE@@', state).replace('@@LOAD@@', load)
             .replace('@@EFFECT@@', effect).replace('@@ITEM@@', screen[item_start:item_end])
             .replace('@@STOP@@', stop).replace('@@AUDIO_DISPOSE@@', dispose)
             .replace('@@PLAY@@', play_body).replace('@@DELETE@@', delete_body))
    resources = sorted(set(re.findall(r'R\.string\.([A-Za-z_][A-Za-z_0-9]*)', probe)))
    probe = probe.replace('@@RESOURCE_IDS@@', '\n'.join('const val ' + name + ' = ' + str(index + 1)
                                                       for index, name in enumerate(resources)))
    (stage / 'CallRecordingsListProbe.kt').write_text(probe)
    create, create_range = block((root / PROVIDER).read_text(), 'if ("create_call_recording".equals(method))')
    provider = '''package com.waenhancer.qa.recordings;
import android.content.Context; import android.os.Bundle; import android.os.ParcelFileDescriptor;
import java.io.File; import java.io.FileNotFoundException;
public final class ProviderStorageProbe {
 public static Bundle create(Context context, String name) {
   String method="create_call_recording"; Bundle extras=new Bundle(); extras.putString("name", name);
   BODY
   throw new AssertionError("Unexpected operation");
 }
}
'''.replace('BODY', create)
    (stage / 'ProviderStorageProbe.java').write_text(provider)
    fixture = ROOT / 'qa/fixtures/CallRecordingsListAndroidTest.java'
    shutil.copyfile(fixture, stage / fixture.name)
    shutil.copyfile(root / PATHS, out / 'file_paths.xml')
    identities = {str(path): digest(root / path) for path in [SCREEN, PROVIDER, PATHS]}
    identities.update({str(path.relative_to(ROOT)): digest(path) for path in [
        Path(__file__).resolve(), fixture, ROOT / 'qa/fixtures/CallRecordingsListProbe.kt',
        ROOT / 'qa/android_media_preview_test.py', ROOT / 'qa/maven_dependencies.py']})
    fragments = {'state_and_permission': [screen.count('\n', 0, start) + 1, screen.count('\n', 0, end)],
                 'loadRecordings': load_range, 'LaunchedEffect': effect_range,
                 'stopAudio': stop_range, 'audio_disposal': dispose_range,
                 'play_onClick': play_range, 'delete_onClick': delete_range,
                 'provider_create_call_recording': create_range}
    (out / 'source-identity.json').write_text(json.dumps({'sha256': identities, 'fragments': fragments}, indent=2) + '\n')
    jars = sorted(deps.glob('*.jar'))
    stdlib = kotlin / 'lib/kotlin-stdlib.jar'
    # Only Android/Core and the Kotlin distribution API are needed to compile fragments.
    android = deps / 'android-all-14-robolectric-10818077.jar'
    production_cp = os.pathsep.join(map(str, [android, core, stdlib, coroutine]))
    runtime_jars = [p for p in jars if not re.fullmatch(r'kotlin-stdlib-[0-9].*\.jar', p.name)]
    runtime_jars += [core, stdlib, coroutine]
    classpath = os.pathsep.join(map(str, [classes, *runtime_jars]))
    if run([str(compiler), '-jvm-target', '17', '-classpath', production_cp,
            '-d', str(classes), str(stage / 'CallRecordingsListProbe.kt')], out / 'kotlinc-fragments.log'):
        raise RuntimeError('Exact CallsHub fragment compilation failed')
    if run(['javac', '--release', '17', '-cp', classpath, '-d', str(classes),
            str(stage / 'ProviderStorageProbe.java'), str(stage / fixture.name)], out / 'javac-fixtures.log'):
        raise RuntimeError('Provider branch or framework fixture compilation failed')
    opens = ['java.base/java.lang', 'java.base/java.util', 'java.base/java.io',
             'java.base/java.net', 'java.base/java.security', 'java.base/java.text',
             'java.base/jdk.internal.access', 'java.desktop/java.awt.font']
    command = ['java', *['--add-opens=' + name + '=ALL-UNNAMED' for name in opens],
               '-Drobolectric.dependency.repo.url=https://repo.maven.apache.org/maven2',
               '-Dwaex.recording.paths=' + str(out / 'file_paths.xml'),
               '-cp', classpath, 'org.junit.runner.JUnitCore',
               'com.waenhancer.qa.recordings.CallRecordingsListAndroidTest']
    if args.sdk_jars:
        command[1:1] = ['-Drobolectric.offline=true', '-Drobolectric.dependency.dir=' + str(args.sdk_jars.resolve())]
    if args.maven_repo:
        command.insert(1, '-Dmaven.repo.local=' + str(args.maven_repo.resolve()))
    (out / 'bootstrap.json').write_text(json.dumps({
        'framework': 'provided' if args.deps else 'public-maven',
        'core': 'provided' if args.core_jar else CORE_URL,
        'core_aar_sha256': CORE_AAR_SHA256, 'core_classes_sha256': CORE_SHA256,
        'sdk_mode': 'offline-directory' if args.sdk_jars else 'public-maven-resolver',
    }, indent=2) + '\n')
    result = run(command, out / 'junit.log')
    log = (out / 'junit.log').read_text()
    passed = re.search(r'OK \((\d+) tests?\)', log)
    failures = re.search(r'Tests run: (\d+),\s+Failures: (\d+)', log)
    actual = int(passed.group(1)) if passed else int(failures.group(1)) if failures else 0
    failed = 0 if passed else int(failures.group(2)) if failures else None
    summary = {'source_root': str(root), 'tests': actual, 'failures': failed, 'expected_tests': EXPECTED,
               'exit_code': result, 'sdk': [24, 28, 34], 'source_sha256': identities,
               'limitations': __doc__, 'coroutines_runtime': 'Kotlin 2.0.20 distribution QA runtime, not the app dependency graph'}
    (out / 'result.json').write_text(json.dumps(summary, indent=2) + '\n')
    dependencies = {p.name: digest(p) for p in runtime_jars}
    if args.sdk_jars:
        dependencies.update({p.name: digest(p) for p in args.sdk_jars.resolve().glob('*.jar')})
    (out / 'dependency-sha256.json').write_text(json.dumps(dependencies, indent=2) + '\n')
    print(json.dumps({k: summary[k] for k in ['tests', 'failures', 'expected_tests', 'exit_code']}))
    if actual != EXPECTED or (result and not args.allow_test_failure):
        raise RuntimeError('Recording-list framework gate failed')


if __name__ == '__main__':
    main()
