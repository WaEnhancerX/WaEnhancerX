#!/usr/bin/env python3
"""Require compiled, fully executed scoped-backup behavioral negative controls."""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
EXPECTED = {
    'drop-scoped': 'FAIL: hand-authored binary imports exact keys independently of exporter',
    'accept-scoped-secret': 'FAIL: all rejected keys stay out of exports, including scoped secrets and runtime data',
}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--out', type=Path, required=True)
    parser.add_argument('--kotlin-home', type=Path)
    parser.add_argument('--json-source-dir', type=Path)
    args = parser.parse_args()
    output = args.out.resolve()
    output.mkdir(parents=True, exist_ok=False)
    results = []
    for mutant, required in EXPECTED.items():
        child = output / mutant
        command = [sys.executable, str(ROOT / 'qa/test_scoped_settings_backup.py'),
                   '--out', str(child), '--mutant', mutant]
        for option in ('kotlin_home', 'json_source_dir'):
            value = getattr(args, option)
            if value:
                command += ['--' + option.replace('_', '-'), str(value.resolve())]
        completed = subprocess.run(command, capture_output=True, text=True, encoding='utf-8',
                                   errors='replace', timeout=600)
        (output / f'{mutant}.log').write_text(completed.stdout + completed.stderr, encoding='utf-8')
        report = json.loads((child / 'result.json').read_text(encoding='utf-8')) if (child / 'result.json').is_file() else {}
        scope_log = (child / 'TestScopedSettingsBackupKt.log').read_text(encoding='utf-8') if (child / 'TestScopedSettingsBackupKt.log').is_file() else ''
        json_log = (child / 'TestSettingsBackupJsonKt.log').read_text(encoding='utf-8') if (child / 'TestSettingsBackupJsonKt.log').is_file() else ''
        commands = report.get('commands', [])
        detected = (completed.returncode == 1 and report.get('status') == 'FAIL'
                    and report.get('compiled') is True and len(commands) == 6
                    and all(step['exit_code'] == 0 for step in commands[:4])
                    and commands[4]['exit_code'] == 1 and commands[5]['exit_code'] == 0
                    and required in scope_log and 'SCOPED_RESULT:' in scope_log
                    and '/22 cases passed;' in scope_log
                    and 'RESULT: 105/105 tests passed; 0 failed' in json_log)
        results.append({'mutant': mutant, 'status': 'PASS' if detected else 'FAIL',
                        'command': command, 'child_exit': completed.returncode,
                        'expected_assertion': required})
        print(f'{mutant}: {"PASS (compiled behavior rejected)" if detected else "FAIL (not a valid behavioral detection)"}', flush=True)
    report = {'status': 'PASS' if all(item['status'] == 'PASS' for item in results) else 'FAIL',
              'controls': results}
    (output / 'result.json').write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
    return 0 if report['status'] == 'PASS' else 1


if __name__ == '__main__':
    sys.exit(main())
