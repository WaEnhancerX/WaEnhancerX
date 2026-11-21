#!/usr/bin/env python3
"""Deterministic interleavings against the complete production preference bridge.

Provider/Android/Handler are explicit substitutes; no device persistence claim.
Compile errors, timeouts and missing tools are test infrastructure failures.
"""
from pathlib import Path
import ast
import argparse
import subprocess
import tempfile
ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--repeat', type=int, default=1)
args = parser.parse_args()
if not 1 <= args.repeat <= 1000: parser.error('repeat must be 1..1000')
module = ast.parse((ROOT/'qa/test_bridge_contract.py').read_text())
stubs = ast.literal_eval(next(n.value for n in module.body if isinstance(n, ast.Assign)
    and any(isinstance(t, ast.Name) and t.id == 'STUBS' for t in n.targets)))
stubs.pop('test/BridgeContract.java')
stubs['test/BridgeOrderingChecks.java'] = (ROOT/'qa/java/BridgeOrderingChecks.java').read_text()
with tempfile.TemporaryDirectory(prefix='waex_bridge_order_') as directory:
    out = Path(directory)
    for name, content in stubs.items():
        p = out/name
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(content)
    classes = out/'classes'
    classes.mkdir()
    source = ROOT/'app/src/main/java/com/waenhancer/xposed/bridge/client/PreferenceBridgeClient.java'
    schema = ROOT/'app/src/main/java/com/waenhancer/config/PreferenceSchema.java'
    errors = ROOT/'app/src/main/java/com/waenhancer/xposed/utils/HookErrors.java'
    subprocess.run(['javac', '-d', str(classes), *map(str, out.rglob('*.java')),
                    str(source), str(schema), str(errors)], check=True, timeout=45)
    print('COMPILE_OK', flush=True)
    subprocess.run(['java', '-cp', str(classes), 'test.BridgeOrderingChecks', str(args.repeat)], check=True, timeout=45)
