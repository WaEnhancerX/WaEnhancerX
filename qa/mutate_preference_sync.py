#!/usr/bin/env python3
"""Reject configuration feedback, false persistence success and lost authorization/refresh."""
import argparse
from contextlib import redirect_stdout
import hashlib
import io
import json
import os
from pathlib import Path
import shutil
import tempfile

from test_preference_sync_runtime import BASE, ROOT, SOURCES, run_contracts


MUTANTS = [
    ('unfiltered_runtime_listener', 'app/WaexApp.kt', None,
     'if (PreferenceSyncPolicy.shouldNotifyHooks(key)) {', 'if (true) {',
     'synchronous/report_runtime_health/telemetry'),
    ('provider_runtime_configuration_feedback', 'xposed/bridge/HookProvider.java', 'report_runtime_health',
     'Uri.parse("content://" + AUTHORITY + "/runtime_health"), null);',
     'Uri.parse("content://" + AUTHORITY + "/runtime_health"), null);\n'
     '                        context.getContentResolver().notifyChange(Uri.parse("content://" + AUTHORITY + "/preferences"), null);',
     'synchronous/report_runtime_health/telemetry'),
    ('drop_whole_store_refresh', 'app/WaexApp.kt', None,
     'if (PreferenceSyncPolicy.shouldNotifyHooks(key)) {',
     'if (key != null && PreferenceSyncPolicy.shouldNotifyHooks(key)) {',
     'synchronous/null-clear-keeps-configuration-refresh'),
    ('pretend_package_registration_persisted', 'xposed/bridge/HookProvider.java', 'register_hooked_package',
     'result.putBoolean("success", committed);', 'result.putBoolean("success", true);',
     'synchronous/register_hooked_package/disk-failure'),
    ('pretend_hook_registration_persisted', 'xposed/bridge/HookProvider.java', 'register_injected_hooks',
     'result.putBoolean("success", committed);', 'result.putBoolean("success", true);',
     'synchronous/register_injected_hooks/disk-failure'),
    ('drop_entitlement_configuration_refresh', 'app/WaexApp.kt', None,
     'if (PreferenceSyncPolicy.shouldNotifyHooks(key)) {',
     'if (PreferenceSyncPolicy.shouldNotifyHooks(key) && key != "pro_entitlement_audio_to_voice_status") {',
     'synchronous/listener-configuration/pro_entitlement_audio_to_voice_status'),
    ('trust_unscoped_binder_caller', 'xposed/bridge/CallerScopePolicy.java', None,
     'if (packagesForUid == null || supported == null) return false;',
     'if (callerUid != moduleUid) return true;',
     'synchronous/get_all_preferences/untrusted-binder'),
]


def replace_once(source, before, after, route):
    if route:
        start = source.index('if ("' + route + '".equals(method)')
        next_route = source.find('\n            if ("', start + 1)
        end = len(source) if next_route < 0 else next_route
        fragment = source[start:end]
        if fragment.count(before) != 1:
            raise AssertionError('Route mutation anchor must occur exactly once: ' + route)
        return source[:start] + fragment.replace(before, after) + source[end:]
    if source.count(before) != 1:
        raise AssertionError('Mutation anchor must occur exactly once')
    return source.replace(before, after)


def validate(out, kotlinc):
    out.mkdir(parents=True, exist_ok=True)
    with redirect_stdout(io.StringIO()):
        code = run_contracts(ROOT, out / 'candidate', kotlinc)
    if code:
        raise AssertionError('Candidate failed before mutations; inspect candidate/runtime.log')
    files = [BASE / name for name in SOURCES + ['app/WaexApp.kt']]
    files.append(Path('app/src/main/res/values/arrays.xml'))
    rows = []
    for label, relative, route, before, after, expected in MUTANTS:
        snapshot = out / 'sources' / label
        for relative_path in files:
            target = snapshot / relative_path
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(ROOT / relative_path, target)
        source = snapshot / BASE / relative
        source.write_text(replace_once(source.read_text(encoding='utf-8'), before, after, route), encoding='utf-8')
        with redirect_stdout(io.StringIO()):
            status = run_contracts(snapshot, out / label, kotlinc)
        report = json.loads((out / label / 'result.json').read_text(encoding='utf-8'))
        rejected = status != 0 and ('FAIL ' + expected + ': AssertionError:') in report['output']
        if not rejected:
            raise AssertionError('Mutation survived or failed outside its expected runtime assertion: ' + label)
        rows.append({'mutation': label, 'file': str(BASE / relative), 'route': route,
                     'mutant_sha256': hashlib.sha256(source.read_bytes()).hexdigest(),
                     'expected_failure': expected, 'runtime_rejected': True,
                     'result_json': str((out / label / 'result.json').relative_to(out))})
        print('PASS negative control: ' + label + ' rejected at ' + expected)
    (out / 'result.json').write_text(json.dumps({'result': 'PASS', 'candidate_passed': True,
        'mutants_rejected': len(rows), 'mutants': rows,
        'scope': 'Complete app/provider JVM fixture with platform/licensing boundaries; no real Binder or injected host'}, indent=2) + '\n', encoding='utf-8')
    print('PASS: all ' + str(len(rows)) + ' targeted preference-sync mutations rejected by runtime behavior')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--out', type=Path)
    parser.add_argument('--kotlinc', default=os.environ.get('KOTLINC') or shutil.which('kotlinc'))
    args = parser.parse_args()
    if not args.kotlinc:
        parser.error('Supply --kotlinc or add the Kotlin compiler to PATH')
    if args.out:
        validate(args.out.resolve(), args.kotlinc)
    else:
        with tempfile.TemporaryDirectory(prefix='waex_preference_sync_mutations_') as temp:
            validate(Path(temp), args.kotlinc)


if __name__ == '__main__':
    main()
