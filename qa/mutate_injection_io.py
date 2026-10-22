#!/usr/bin/env python3
"""Reject restored blocking, stale reporting, and skipped or interrupted initial sync.

Compiles each complete mutated production class. A compile failure, absent RESULT marker or
timeout is an infrastructure failure and is never counted as a rejected behavioral mutant.
"""
import argparse
import json
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT/'app/src/main/java/com/waenhancer/xposed'

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--out', required=True, type=Path)
    args = parser.parse_args()
    out = args.out.resolve(); out.mkdir(parents=True, exist_ok=True)
    main_source = (SRC/'MainHook.java').read_text()
    queue_source = (SRC/'core/RuntimeReportDispatcher.java').read_text()
    bridge_source = (SRC/'bridge/client/PreferenceBridgeClient.java').read_text()
    constructor = '''this(Executors.newSingleThreadExecutor(task -> {
            Thread worker = new Thread(task, "WAEX-RuntimeReports");
            worker.setDaemon(true);
            return worker;
        }), failureReporter);'''
    mutations = [
        ('inline_optional_io','RuntimeReportDispatcher.java',queue_source,constructor,
         'this(Runnable::run, failureReporter);','package','blocked Application.onCreate'),
        ('old_pending_order','RuntimeReportDispatcher.java',queue_source,
         '            pending.remove(kind);\n','','package','READY overtook queued hook inventory'),
        ('delivery_timestamp','MainHook.java',main_source,
         'state.bridge, state.error, observedAtMillis));','state.bridge, state.error, System.currentTimeMillis()));',
         'package','delayed report replaced observation time'),
        ('synchronous_live_refresh','MainHook.java',main_source,
         'bridgeClient.requestRefresh();','bridgeClient.syncPreferences();','preferences','broadcast blocked the main looper'),
        ('skip_initial_sync','MainHook.java',main_source,
         'resolvedBridge.awaitInitialSync();','','initial_sync','before authoritative initial read finished'),
        ('lose_initial_sync_interrupt','PreferenceBridgeClient.java',bridge_source,
         'Thread.currentThread().interrupt();\n            throw new IllegalStateException("Initial preference sync interrupted", interrupted);',
         'throw new IllegalStateException("Initial preference sync interrupted", interrupted);',
         'interrupted_initial_sync','startup consumed the thread interruption'),
        ('continue_interrupted_initialization','PreferenceBridgeClient.java',bridge_source,
         'throw new IllegalStateException("Initial preference sync interrupted", interrupted);','',
         'interrupted_initial_sync','attempted features before initial sync completed'),
    ]
    results = []
    for name, filename, original, old, new, scenario, expected in mutations:
        assert original.count(old) == 1, (name,'mutation anchor must be unique')
        case = out/name; case.mkdir(exist_ok=True)
        changed = case/filename; changed.write_text(original.replace(old,new,1))
        option = {'MainHook.java':'--main-source', 'RuntimeReportDispatcher.java':'--dispatcher-source',
                  'PreferenceBridgeClient.java':'--bridge-source'}[filename]
        run = subprocess.run([sys.executable,str(ROOT/'qa/test_injection_io_runtime.py'),
            '--out',str(case/'run'),'--scenario',scenario,option,str(changed)],
            capture_output=True,text=True,timeout=60)
        (case/'driver.log').write_text(run.stdout+run.stderr)
        record_path = case/'run/result.json'
        records = json.loads(record_path.read_text())['results'] if record_path.exists() else []
        behavioral = (run.returncode == 1 and len(records) == 1
            and records[0]['classification'] == 'behavioral_failure' and expected in records[0]['output'])
        entry = {'mutation':name,'scenario':scenario,'behaviorally_rejected':behavioral,
                 'expected_invariant':expected,'driver_exit':run.returncode}
        results.append(entry); print(json.dumps(entry),flush=True)
        if not behavioral:
            (out/'result.json').write_text(json.dumps(results,indent=2)+'\n')
            raise SystemExit('Mutation survived or infrastructure failed: '+name)
    (out/'result.json').write_text(json.dumps(results,indent=2)+'\n')

if __name__ == '__main__': main()
