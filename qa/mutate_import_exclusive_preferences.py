#!/usr/bin/env python3
"""Require compiled assertion failures from the exclusive-import negative controls.

Use --variant to run one control when the surrounding command transport has a
short timeout. A compile failure, timeout or incomplete fixture is never a pass.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
EXPECTED = {
    'skip-normalization': (8, 16, 'all scopes normalize independently of selected target=null format=binary'),
    'skip-inheritance': (23, 1, 'universal import considers inherited targets and preserves explicit false overrides'),
    'omit-added-rollback': (20, 4, 'failure restores synthesized partner absence memoryWrite=true'),
}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--out', type=Path, required=True)
    parser.add_argument('--json-source-dir', type=Path, required=True)
    parser.add_argument('--kotlin-home', type=Path, required=True)
    parser.add_argument('--variant', choices=tuple(EXPECTED))
    args = parser.parse_args()
    output = args.out.resolve()
    output.mkdir(parents=True, exist_ok=False)
    controls = []
    for variant in ([args.variant] if args.variant else EXPECTED):
        expected_pass, expected_fail, expected_name = EXPECTED[variant]
        child = output / variant
        command = [sys.executable, str(ROOT / 'qa/test_import_exclusive_preferences.py'),
                   '--json-source-dir', str(args.json_source_dir.resolve()),
                   '--kotlin-home', str(args.kotlin_home.resolve()),
                   '--out', str(child), '--mutant', variant]
        record = {'variant': variant, 'status': 'BLOCKED', 'command': command}
        try:
            completed = subprocess.run(command, capture_output=True, text=True,
                                       encoding='utf-8', errors='replace', timeout=600)
            (output / f'{variant}.log').write_text(completed.stdout + completed.stderr, encoding='utf-8')
            report = json.loads((child / 'result.json').read_text(encoding='utf-8'))
            log = (child / 'TestImportExclusivePreferencesKt.log').read_text(encoding='utf-8')
            steps = report.get('commands', [])
            summary = f'EXCLUSIVE_RESULT: {expected_pass}/24 cases passed; {expected_fail} failed'
            intended = f'FAIL: {expected_name} (IllegalStateException:'
            detected = (completed.returncode == 1 and report.get('status') == 'FAIL'
                        and report.get('compiled') is True and len(steps) == 6
                        and all(step['exit_code'] == 0 for step in steps[:5])
                        and steps[-1]['exit_code'] == 1 and summary in log and intended in log
                        and len(re.findall(r'^FAIL: ', log, re.MULTILINE)) == expected_fail)
            record.update(status='PASS' if detected else 'FAIL', child_exit=completed.returncode,
                          expected_assertion=expected_name, expected_product_failures=expected_fail)
        except (OSError, ValueError, subprocess.TimeoutExpired) as failure:
            record['error'] = f'{type(failure).__name__}: {failure}'
        controls.append(record)
        print(f'{variant}: {record["status"]}', flush=True)
    status = 'PASS' if all(control['status'] == 'PASS' for control in controls) else 'FAIL'
    (output / 'result.json').write_text(json.dumps({'status': status, 'controls': controls}, indent=2) + '\n', encoding='utf-8')
    return 0 if status == 'PASS' else 1


if __name__ == '__main__':
    sys.exit(main())
