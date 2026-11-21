#!/usr/bin/env python3
"""Actual AAPT2 compilation/linking of Chinese strings; not a complete app build."""
from pathlib import Path
from copy import deepcopy
import argparse
import hashlib
import json
import os
import re
import subprocess
import xml.etree.ElementTree as ET
from test_chinese_localization import ROOT, RES, LOCALES, catalog, text


def inspect_dump(dump, expected):
    assert 'Package name=com.waenhancer id=7f' in dump, 'Unexpected resource owner'
    seen = set()
    count = 0
    for m in re.finditer(r'^    resource 0x[0-9a-f]+ string/([^\n]+)\n(.*?)(?=^    resource |^  type |\Z)', dump, re.M | re.S):
        key, body = m.groups()
        assert key not in seen, ('duplicate compiled resource', key)
        seen.add(key)
        configurations = {}
        references = set()
        for v in re.finditer(r'^      \(([^)]*)\) (.*?)(?=^      \(|\Z)', body, re.M | re.S):
            qualifier, content = v.groups()
            assert qualifier not in configurations, (key, qualifier)
            content = content.rstrip('\n')
            if re.fullmatch(r'@string/[a-zA-Z0-9_]+', content):
                references.add(qualifier)
                configurations[qualifier] = content
            else:
                assert content.startswith('"') and content.endswith('"'), (key, qualifier, 'invalid dump string')
                configurations[qualifier] = content[1:-1].replace('\n      ', '\n')
        assert '' in configurations, (key, 'missing default')
        for locale in LOCALES:
            if key in expected[locale]:
                assert configurations.get(locale) == expected[locale][key], (key, locale, configurations.get(locale), expected[locale][key])
                assert (locale in references) == expected[locale][key].startswith('@string/'), (key, locale, 'alias/literal mismatch')
                count += 1
            else:
                assert locale not in configurations, (key, 'non-translatable resource overridden')
    base_keys = {k for k, (n, _) in catalog('values').items() if n.tag == 'string'}
    assert seen == base_keys, ('compiled coverage mismatch', base_keys - seen, seen - base_keys)
    p = re.search(r'plurals/waex_recordings_deleted\n(.*?)(?=^    resource |^  type |\Z)', dump, re.M | re.S)
    assert p, 'Missing recording plural'
    for locale in ('',) + LOCALES:
        assert f'({locale})' in p.group(1), ('missing plural locale', locale)
    assert p.group(1).count('one=') == 4 and p.group(1).count('other=') == 4, 'Missing plural selectors'
    return count


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--aapt2', help='Real Android AAPT2 executable; defaults to installed SDK build-tools')
    parser.add_argument('--out', required=True, help='New directory for resource-table evidence')
    args = parser.parse_args()
    sdk = os.environ.get('ANDROID_SDK_ROOT') or os.environ.get('ANDROID_HOME')
    tools = sorted((Path(sdk) / 'build-tools').glob('*/aapt2')) if sdk else []
    aapt2 = Path(args.aapt2) if args.aapt2 else (tools[-1] if tools else None)
    if aapt2 is None or not aapt2.is_file():
        parser.error('A real Android SDK AAPT2 is required')
    out = Path(args.out).resolve()
    out.mkdir(parents=True, exist_ok=False)
    identities, commands = {}, []
    def run(argv, filename):
        result = subprocess.run([str(v) for v in argv], text=True, stdout=subprocess.PIPE,
                                stderr=subprocess.STDOUT, timeout=300)
        (out / filename).write_text(result.stdout)
        commands.append({'command': [str(v) for v in argv], 'exit': result.returncode})
        if result.returncode:
            raise RuntimeError(f'{filename}: {result.stdout}')
        return result.stdout
    run([aapt2, 'version'], 'aapt2-version.txt')
    run([aapt2, 'compile', '--dir', RES, '-o', out / 'all-resources.zip'], 'compile-all.log')
    fixture = out / 'res'
    for folder in ('values',) + tuple('values-' + l for l in LOCALES):
        for path in sorted((RES / folder).glob('*.xml')):
            selected = [deepcopy(n) for n in ET.parse(path).getroot() if n.tag in ('string', 'plurals')]
            if not selected:
                continue
            root = ET.Element('resources')
            root.extend(selected)
            target = fixture / folder / path.name
            target.parent.mkdir(parents=True, exist_ok=True)
            ET.ElementTree(root).write(target, encoding='utf-8', xml_declaration=True)
            identities[str(path.relative_to(ROOT))] = hashlib.sha256(path.read_bytes()).hexdigest()
    manifest = out / 'AndroidManifest.xml'
    manifest.write_text('<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.waenhancer"><application /></manifest>\n')
    run([aapt2, 'compile', '--dir', fixture, '-o', out / 'compiled.zip'], 'compile-fixture.log')
    run([aapt2, 'link', '-o', out / 'resources-only.apk', '--manifest', manifest,
         '--java', out / 'generated', '--custom-package', 'com.waenhancer.app', out / 'compiled.zip'], 'link-fixture.log')
    dump = run([aapt2, 'dump', 'resources', out / 'resources-only.apk'], 'resources-table.txt')
    expected = {l: {k: text(n.text) for k, (n, _) in catalog('values-' + l).items() if n.tag == 'string'} for l in LOCALES}
    count = inspect_dump(dump, expected)
    controls = [dump.replace('(zh-rHK)', '(fr)'), dump.replace('Package name=com.waenhancer id=7f', 'Package name=wrong id=7f'),
                dump.replace('one=', 'zero=', 1), dump.replace('網絡', 'NETWORK', 1),
                dump.replace('(zh-rHK) @string/interface_size_desc', '(zh-rHK) "@string/interface_size_desc"', 1)]
    for bad in controls:
        try:
            inspect_dump(bad, expected)
        except AssertionError:
            pass
        else:
            raise AssertionError('Negative compiled-table control was accepted')
    report = {'compiled_translated_values': count, 'locales': LOCALES, 'plural_locale_configurations': 4,
              'negative_controls': len(controls), 'commands': commands, 'identities': identities,
              'scope': 'Real AAPT2 resource compilation/linking and exact translated-value verification; not a complete APK or device test'}
    (out / 'result.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    print(f'PASS: {count} exact compiled Chinese values; 4 plural configurations; {len(controls)} negative controls. NOT a full app build.')


if __name__ == '__main__':
    main()
