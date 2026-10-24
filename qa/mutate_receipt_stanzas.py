#!/usr/bin/env python3
"""Require concrete stanza, status and privacy regressions to fail the runtime fixture."""
import argparse
from contextlib import redirect_stdout
import hashlib
import io
import json
import os
from pathlib import Path
import shutil
import tempfile

from test_receipt_stanza_runtime import AREA, PRODUCTION, ROOT, run_contracts


MUTANTS = [
    ('rewrite_control_receipts', 'HideReceiptsHook.java',
     'if (type != null && !isReadReceipt) return;',
     '// negative control: allow unrelated protocol receipts through privacy mutation',
     'hide_delivery_receipts/preserve-control/retry'),
    ('strip_delivery_timestamp_for_read_privacy', 'HideReceiptsHook.java',
     'boolean hideRead = isReadReceipt && shouldHideReadReceipt(target);',
     'boolean hideRead = shouldHideReadReceipt(target);',
     'hide_read_receipts/ordinary-delivery'),
    ('treat_broadcast_list_as_status', 'HideReceiptsHook.java',
     'return "status@broadcast".equals(jid) || "status@broadcast".equals(participant);',
     'return (jid != null && jid.contains("broadcast")) || "status@broadcast".equals(participant);',
     'numeric-broadcast-is-not-status-stanza'),
    ('disable_requested_read_privacy', 'HideReceiptsHook.java',
     'boolean hideRead = isReadReceipt && shouldHideReadReceipt(target);',
     'boolean hideRead = false;',
     'hide_read_receipts/read'),
    ('explicit_read_grant_overrides_delivery_privacy', 'ExplicitReadReceiptPolicy.java',
     'return !hideDelivery && "read".equals(type) && grants != null',
     'return "read".equals(type) && grants != null',
     'exact-explicit-read-grant'),
]


def validate(out, kotlinc):
    out.mkdir(parents=True, exist_ok=True)
    with redirect_stdout(io.StringIO()):
        status = run_contracts(ROOT, out / 'candidate', kotlinc)
    if status:
        raise AssertionError('Candidate failed before mutations; see candidate/runtime.log')
    rows = []
    paths = [AREA / name for name in PRODUCTION]
    paths.append(Path('app/src/main/java/com/waenhancer/xposed/core/BaseFeature.kt'))
    for label, name, before, after, expected in MUTANTS:
        snapshot = out / 'sources' / label
        for rel in paths:
            target = snapshot / rel
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(ROOT / rel, target)
        target = snapshot / AREA / name
        original = target.read_text(encoding='utf-8')
        if original.count(before) != 1:
            raise AssertionError('Mutation anchor must occur exactly once: ' + label)
        target.write_text(original.replace(before, after), encoding='utf-8')
        with redirect_stdout(io.StringIO()):
            code = run_contracts(snapshot, out / label, kotlinc)
        report = json.loads((out / label / 'result.json').read_text(encoding='utf-8'))
        rejected = code != 0 and ('FAIL ' + expected + ': AssertionError:') in report['output']
        if not rejected:
            raise AssertionError('Mutation was not rejected at its intended runtime invariant: ' + label)
        rows.append({'mutation': label, 'file': str(AREA / name),
                     'mutant_source_sha256': hashlib.sha256(target.read_bytes()).hexdigest(),
                     'expected_failure': expected, 'runtime_rejected': rejected,
                     'result_json': str((out / label / 'result.json').relative_to(out))})
        print('PASS negative control: ' + label + ' rejected at ' + expected)
    summary = {'result': 'PASS', 'candidate_passed': True, 'mutants_rejected': len(rows),
               'mutants': rows, 'scope': 'Full production hook JVM callbacks; explicit platform/DEX-cache doubles, no device or network'}
    (out / 'result.json').write_text(json.dumps(summary, indent=2) + '\n', encoding='utf-8')
    print('PASS: all ' + str(len(rows)) + ' targeted production mutations rejected by runtime behavior')


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
        with tempfile.TemporaryDirectory(prefix='waex_receipt_mutations_') as temp:
            validate(Path(temp), args.kotlinc)


if __name__ == '__main__':
    main()
