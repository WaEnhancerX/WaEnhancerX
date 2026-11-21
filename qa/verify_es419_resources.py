#!/usr/bin/env python3
"""Compile public resources and link an isolated locale table; NOT a WAEX APK build.

Requires a real caller-supplied AAPT2. No SDK/signing/private dependencies are replaced.
The fixture contains only module strings/plurals so linking needs no platform symbols.
"""
from pathlib import Path
import argparse
import hashlib
import json
import re
import shutil
import subprocess
import sys
import tempfile
from localization_support import entries, stage_string_resources

ROOT = Path(__file__).resolve().parents[1]


def verify_dump(text, expected, spanish):
    assert 'Package name=com.waenhancer id=7f' in text, 'Wrong resource owner'
    strings = {}
    for match in re.finditer(r'^    resource 0x[0-9a-f]+ string/([^\n]+)\n(.*?)(?=^    resource |\Z)', text, re.M | re.S):
        name, body = match.groups()
        assert name not in strings, f'Duplicate compiled string {name}'
        qualifiers = re.findall(r'^      \(([^)]*)\) ', body, re.M)
        for qualifier in ('', 'es', 'es-r419'):
            assert qualifiers.count(qualifier) == 1, (name, qualifier, qualifiers)
        # AAPT2 prints literal newlines with its six-space pretty-print indentation.
        for value in re.finditer(r'^      \(([^)]*)\) "(.*?)(?=^      \(|\Z)', body, re.M | re.S):
            qualifier, content = value.groups()
            if qualifier not in ('es', 'es-r419'): continue
            content = content.rstrip('\n')
            assert content.endswith('"'), (name, 'Malformed AAPT2 string output')
            content = content[:-1].replace('\n      ', '\n')
            assert content == spanish[name][0], (name, qualifier, 'Compiled Spanish value changed')
        strings[name] = body
    assert strings.keys() == expected.keys(), (len(strings), len(expected))
    plural = re.search(r'plurals/waex_recordings_deleted\n(.*?)(?=^    resource |^  type |\Z)', text, re.M | re.S)
    assert plural, 'Missing compiled recording plural'
    assert plural.group(1).count('one=') == 3 and plural.group(1).count('other=') == 3
    return len(strings)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--aapt2', required=True, help='Path to actual Android AAPT2 executable')
    parser.add_argument('--out', required=True, help='New directory for text evidence and temporary fixture')
    args = parser.parse_args()
    aapt2 = Path(args.aapt2).resolve()
    if not aapt2.is_file(): raise SystemExit('AAPT2 is missing')
    out = Path(args.out).resolve()
    out.mkdir(parents=True, exist_ok=False)
    commands=[]
    def run(argv, name):
        result = subprocess.run([str(a) for a in argv], text=True, encoding='utf-8', errors='replace',
                                stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=180)
        (out/name).write_text(result.stdout, encoding='utf-8')
        commands.append({'command':[str(a) for a in argv], 'exit':result.returncode})
        if result.returncode: raise RuntimeError(f'{name} failed: {result.returncode}')
        return result.stdout
    run([aapt2, 'version'], 'aapt2-version.txt')
    run([aapt2, 'compile', '--dir', ROOT/'app/src/main/res', '-o', out/'all-resources.zip'], 'compile-all.log')
    res=out/'res';res.mkdir()
    identities={}
    stage_string_resources(ROOT, res, identities)
    manifest=out/'AndroidManifest.xml'
    manifest.write_text('<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.waenhancer"><application /></manifest>\n', encoding="utf-8")
    run([aapt2,'compile','--dir',res,'-o',out/'compiled.zip'],'compile-fixture.log')
    run([aapt2,'link','-o',out/'resources-only.apk','--manifest',manifest,
         '--java',out/'generated','--custom-package','com.waenhancer.app',out/'compiled.zip'],'link-fixture.log')
    dump=run([aapt2,'dump','resources',out/'resources-only.apk'],'resources-table.txt')
    expected=entries(ROOT,'values')
    spanish=entries(ROOT,'values-b+es+419')
    count=verify_dump(dump,expected,spanish)
    controls = [dump.replace('(es-r419)', '(fr)'), dump.replace('Package name=com.waenhancer id=7f','Package name=other id=7f'),
                dump.replace('other="Se eliminaron', 'zero="Se eliminaron',1), dump.replace('(es) "Agregar"', '(es) "Add"', 1)]
    for bad in controls:
        try: verify_dump(bad,expected,spanish)
        except AssertionError: pass
        else: raise AssertionError('Bad compiled-table fixture accepted')
    (out/'result.json').write_text(json.dumps({'string_names':count,'configurations':['default','es','es-419'],
        'exact_compiled_spanish_values':2*count,'plural_resources':1,'plural_forms_per_configuration':2,'negative_parser_controls':len(controls),
        'identities':identities,'commands':commands,'scope':'AAPT2 actual compile/link of resource table only; not app build or runtime selection'},indent=2), encoding="utf-8")
    print(f'PASS: {count} compiled string names, all three locale configurations, {2*count} exact Spanish values, plural forms, {len(controls)} table-parser controls; NOT a full app build')

if __name__=='__main__':
    main()
