#!/usr/bin/env python3
"""Exercise the production Kotlin codec with unmodified, hash-pinned AOSP JSON.

This is a host JVM test, not an Android device or disk-persistence test. Android
Context/SharedPreferences and annotation types are doubles; org.json is the real
Android 14 implementation. A missing compiler, unavailable source or hash mismatch
fails closed. Transient HTTP responses receive at most three fetch attempts;
source validation errors are never retried. For offline use, set
WAEX_AOSP_JSON_SOURCE_DIR to the verified six
.java files (or pass --json-source-dir). No private APK, licensing or SDK is used.
"""
from __future__ import annotations

import argparse
import ast
import base64
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
ORIGIN = ('https://android.googlesource.com/platform/libcore/+/'
          'refs/tags/android-14.0.0_r1/json/src/main/java/org/json')
SHA256 = {
    'JSON.java': 'a2cc5e4bf64bfe3edd73c6a14409f3200640454051d9947ee79f16a2396a19b1',
    'JSONArray.java': '24f10dbe57a9228b8aec8a891355c26285e7fc4e070a2f826a080300e3ca4180',
    'JSONException.java': '26e10aae9c209dabbc8f15b4156fe9fdc87f5d9d2cb2d2f078b475a61ba0df7d',
    'JSONObject.java': 'b7770e43fdad8415e8f5c37a7dc06a7c1ffb3a16c557887ded1479f3116c868f',
    'JSONStringer.java': '8772ac80610cd62e906330152b09864c471a0921f199f4b9d670d98050e3d86c',
    'JSONTokener.java': '744c9ffb50c2472d7269c8d64a7d8331ea1a1691fb6536619ac9590e9ebbbd4b',
}
TRANSIENT_HTTP_STATUS = frozenset((429, 502, 503, 504))
MAX_DOWNLOAD_ATTEMPTS = 3


def download_json_source(name: str, expected: str) -> bytes:
    request = urllib.request.Request(f'{ORIGIN}/{name}?format=TEXT',
                                     headers={'User-Agent': 'WAEX-JSON-regression'})
    for attempt in range(1, MAX_DOWNLOAD_ATTEMPTS + 1):
        try:
            with urllib.request.urlopen(request, timeout=45) as response:
                if not response.url.startswith('https://android.googlesource.com/'):
                    raise ValueError('Unexpected AOSP source redirect')
                encoded = response.read(512 * 1024 + 1)
            break
        except urllib.error.HTTPError as error:
            status = error.code
            error.close()
            if status not in TRANSIENT_HTTP_STATUS or attempt == MAX_DOWNLOAD_ATTEMPTS:
                raise
            # Only transient HTTP failures are retried. Invalid sources, local
            # cache corruption, redirects and other network errors still fail.
            print(f'AOSP source {name}: HTTP {status}; retry {attempt + 1}/'
                  f'{MAX_DOWNLOAD_ATTEMPTS} in {attempt}s', file=sys.stderr, flush=True)
            time.sleep(attempt)
    if len(encoded) > 512 * 1024:
        raise ValueError('AOSP source response exceeded bound')
    source = base64.b64decode(b''.join(encoded.split()), validate=True)
    if hashlib.sha256(source).hexdigest() != expected:
        raise ValueError(f'AOSP source digest mismatch: {name}')
    return source


def json_sources(directory: Path, allow_download: bool) -> list[Path]:
    directory.mkdir(parents=True, exist_ok=True)
    paths = []
    for name, expected in SHA256.items():
        path = directory / name
        if not path.exists():
            if not allow_download:
                raise FileNotFoundError(f'Missing pinned AOSP source: {path}')
            source = download_json_source(name, expected)
            # Atomic replacement avoids a half-written shared cache entry.
            with tempfile.NamedTemporaryFile(dir=directory, delete=False) as output:
                temporary = Path(output.name)
                output.write(source)
            try:
                temporary.replace(path)
            finally:
                temporary.unlink(missing_ok=True)
        if hashlib.sha256(path.read_bytes()).hexdigest() != expected:
            raise ValueError(f'AOSP source digest mismatch: {name}')
        paths.append(path)
    return paths


def android_doubles(root: Path) -> dict[str, str]:
    # Read the existing literal fixture without importing/executing its test runner.
    tree = ast.parse((root / 'qa/test_settings_backup_binary_roundtrip.py').read_text('utf-8'))
    assignments = [n for n in tree.body if isinstance(n, ast.Assign)
                   and any(isinstance(t, ast.Name) and t.id == 'STUBS' for t in n.targets)]
    if len(assignments) != 1:
        raise ValueError('Expected exactly one Android fixture definition')
    source = ast.literal_eval(assignments[0].value)
    doubles = {p: s for p, s in source.items() if p.startswith('android/content/')}
    doubles['android/compat/annotation/UnsupportedAppUsage.java'] = (
        'package android.compat.annotation; public @interface UnsupportedAppUsage {}')
    doubles['android/annotation/SystemApi.java'] = (
        'package android.annotation; public @interface SystemApi {'
        'enum Client{MODULE_LIBRARIES}; Client client(); }')
    for name in ('NonNull', 'Nullable'):
        doubles[f'libcore/util/{name}.java'] = (
            'package libcore.util; import java.lang.annotation.*; '
            '@Target({ElementType.TYPE_USE,ElementType.TYPE_PARAMETER}) '
            f'public @interface {name} {{}}')
    return doubles


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=ROOT)
    parser.add_argument('--codec', type=Path, help='Alternate codec for controlled negative tests')
    parser.add_argument('--json-source-dir', type=Path,
                        default=os.environ.get('WAEX_AOSP_JSON_SOURCE_DIR'))
    args = parser.parse_args()
    for tool in ('javac', 'kotlinc', 'kotlin'):
        if not shutil.which(tool):
            raise FileNotFoundError(f'Required JVM tool unavailable: {tool}')
    root = args.root.resolve()
    source = root / 'app/src/main/java/com/waenhancer'
    codec = (args.codec or source / 'core/preferences/SettingsBackupCodec.kt').resolve()
    directory = (args.json_source_dir or Path.home() / '.cache/waex-qa/android-json-14').resolve()
    aosp = json_sources(directory, allow_download=args.json_source_dir is None)
    print('AOSP JSON: Android 14 android-14.0.0_r1; 6/6 source digests verified', flush=True)
    print('Codec SHA-256:', hashlib.sha256(codec.read_bytes()).hexdigest(), flush=True)
    with tempfile.TemporaryDirectory(prefix='waex_aosp_json_') as workspace:
        work = Path(workspace)
        java_files = []
        for name, content in android_doubles(root).items():
            path = work / 'src' / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content, 'utf-8')
            java_files.append(path)
        out_java, out_kotlin = work / 'java', work / 'kotlin'
        commands = [
            ['javac', '--release', '17', '-d', str(out_java),
             *map(str, java_files + aosp), str(source / 'config/PreferenceSchema.java'),
             str(source / 'config/PreferenceStores.java')],
            ['kotlinc', '-jvm-target', '17', '-cp', str(out_java), '-d', str(out_kotlin),
             str(codec), str(ROOT / 'qa/kotlin/TestSettingsBackupJson.kt')],
            ['kotlin', '-J-Xmx128m', '-J-Xss1m', '-cp',
             os.pathsep.join(map(str, (out_java, out_kotlin))),
             'com.waenhancer.core.preferences.TestSettingsBackupJsonKt'],
        ]
        for index, command in enumerate(commands):
            completed = subprocess.run(command, cwd=work, timeout=90, check=False)
            if completed.returncode:
                return 1 if index == 2 else 2
    return 0


if __name__ == '__main__':
    try:
        sys.exit(main())
    except (OSError, ValueError, subprocess.TimeoutExpired) as error:
        print(f'JSON regression infrastructure failure: {type(error).__name__}: {error}',
              file=sys.stderr)
        sys.exit(2)
