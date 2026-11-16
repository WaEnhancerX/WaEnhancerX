#!/usr/bin/env python3
"""Compile the real backup codec/schema/store against strict Android doubles.

Uses the existing hash-pinned Android 14 org.json source verifier, then runs
independent scoped backup cases and the unchanged complete JSON regression suite.
This is JVM behavior, not Android filesystem durability or a complete APK build.
"""
from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import platform
import shutil
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parents[1]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source-root', type=Path, default=ROOT)
    parser.add_argument('--json-source-dir', type=Path,
                        default=os.environ.get('WAEX_AOSP_JSON_SOURCE_DIR'))
    parser.add_argument('--kotlin-home', type=Path,
                        help='Kotlin CLI installation (containing bin and lib)')
    parser.add_argument('--out', type=Path, required=True)
    parser.add_argument('--mutant', choices=['drop-scoped', 'accept-scoped-secret'])
    args = parser.parse_args()
    output = args.out.resolve()
    output.mkdir(parents=True, exist_ok=False)
    result = {'status': 'BLOCKED', 'kind': 'host-JVM / strict Android doubles / AOSP JSON',
              'platform': platform.platform(), 'mutant': args.mutant, 'commands': [], 'inputs': {}}
    start = time.monotonic()
    try:
        spec = importlib.util.spec_from_file_location('waex_aosp_json', ROOT / 'qa/test_settings_backup_json.py')
        loader = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(loader)
        source = args.source_root.resolve() / 'app/src/main/java/com/waenhancer'
        directory = (args.json_source_dir or Path.home() / '.cache/waex-qa/android-json-14').resolve()
        aosp = loader.json_sources(directory, allow_download=args.json_source_dir is None)
        java, javac = shutil.which('java'), shutil.which('javac')
        compiler = (str(args.kotlin_home / 'bin' / ('kotlinc.bat' if os.name == 'nt' else 'kotlinc'))
                    if args.kotlin_home else shutil.which('kotlinc'))
        if not java or not javac or not compiler or not Path(compiler).is_file():
            raise FileNotFoundError('JDK and Kotlin CLI are required')
        kotlin_home = args.kotlin_home or Path(compiler).resolve().parents[1]
        stdlib = (kotlin_home / 'lib/kotlin-stdlib.jar').resolve()
        if not stdlib.is_file():
            raise FileNotFoundError(f'Kotlin standard library unavailable: {stdlib}')
        doubles = loader.android_doubles(ROOT)
        doubles['android/content/Context.java'] = '''package android.content;
public abstract class Context {
 public static final int MODE_PRIVATE = 0;
 public abstract SharedPreferences getSharedPreferences(String name, int mode);
 public android.content.pm.ApplicationInfo getApplicationInfo(){throw new AssertionError("Unexpected application info access");}
 public java.io.File getFilesDir(){throw new AssertionError("Unexpected filesystem access");}
}'''
        doubles['android/content/pm/ApplicationInfo.java'] = '''package android.content.pm;
public final class ApplicationInfo { public String dataDir; }'''
        # Reject unknown preference stores instead of materializing them silently.
        old = 'return stores.computeIfAbsent(name,k->new Prefs());'
        new = 'if(mode!=MODE_PRIVATE || !stores.containsKey(name)) throw new AssertionError("Unexpected preference store");return stores.get(name);'
        assert doubles['android/content/TestContext.java'].count(old) == 1
        doubles['android/content/TestContext.java'] = doubles['android/content/TestContext.java'].replace(old, new)
        sources = []
        for name, content in doubles.items():
            path = output / 'sources' / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content, encoding='utf-8')
            sources.append(path)
        codec = source / 'core/preferences/SettingsBackupCodec.kt'
        if args.mutant:
            text = codec.read_text(encoding='utf-8')
            if args.mutant == 'drop-scoped':
                old, new = 'val separator = key.indexOf(\':\')', 'return null\n        val separator = key.indexOf(\':\')'
            else:
                old, new = 'entry != null && entry.isExportable()', 'entry != null'
            if text.count(old) != 1:
                raise ValueError('Mutation target is missing or ambiguous')
            codec = output / 'SettingsBackupCodec-mutant.kt'
            codec.write_text(text.replace(old, new), encoding='utf-8')
        production = [source / 'config/PreferenceSchema.java', source / 'config/PreferenceStores.java']
        fixtures = [ROOT / 'qa/kotlin/TestScopedSettingsBackup.kt', ROOT / 'qa/kotlin/TestSettingsBackupJson.kt']
        for path in [codec, *production, *fixtures, *aosp, stdlib]:
            result['inputs'][str(path)] = hashlib.sha256(path.read_bytes()).hexdigest()
        java_out, kotlin_out = output / 'java', output / 'kotlin'
        commands = [
            ('java-version', [java, '-version']),
            ('kotlin-version', [compiler, '-version']),
            ('javac', [javac, '--release', '17', '-d', str(java_out),
                       *map(str, [*sources, *aosp, *production])]),
            ('kotlinc', [compiler, '-jvm-target', '17', '-cp', str(java_out), '-d', str(kotlin_out),
                         str(codec), *map(str, fixtures)]),
        ]
        runtime_cp = os.pathsep.join(map(str, [java_out, kotlin_out, stdlib]))
        for name in ['TestScopedSettingsBackupKt', 'TestSettingsBackupJsonKt']:
            commands.append((name, [java, '-Xmx192m', '-Xss1m', '-cp', runtime_cp,
                                    'com.waenhancer.core.preferences.' + name]))
        compile_ok, runtime_ok = True, True
        for label, command in commands:
            tick = time.monotonic()
            completed = subprocess.run(command, capture_output=True, text=True, encoding='utf-8',
                                       errors='replace', timeout=180)
            (output / f'{label}.log').write_text(completed.stdout + completed.stderr, encoding='utf-8')
            result['commands'].append({'label': label, 'command': command, 'exit_code': completed.returncode,
                                       'seconds': round(time.monotonic() - tick, 3)})
            print(f'{label}: exit {completed.returncode}', flush=True)
            if label.startswith('Test'):
                print(completed.stdout, end='', flush=True)
                runtime_ok &= completed.returncode == 0
            elif completed.returncode:
                compile_ok = False
                break
        result['status'] = 'PASS' if compile_ok and runtime_ok else 'FAIL' if compile_ok else 'BLOCKED'
        result['compiled'] = compile_ok
        return 0 if result['status'] == 'PASS' else 1 if result['status'] == 'FAIL' else 2
    except (OSError, ValueError, subprocess.TimeoutExpired, AssertionError) as error:
        result['error'] = f'{type(error).__name__}: {error}'
        print(result['error'], file=sys.stderr)
        return 2
    finally:
        result['seconds'] = round(time.monotonic() - start, 3)
        (output / 'result.json').write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')


if __name__ == '__main__':
    sys.exit(main())
