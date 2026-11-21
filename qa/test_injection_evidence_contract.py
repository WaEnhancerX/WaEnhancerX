#!/usr/bin/env python3
"""Execute the production PID/boot injection classifier; guard the live UI event path.

There is no wall-clock expiry or periodic Compose refresh in this implementation.
The standalone Kotlin execution does not establish actual LSPosed injection.
"""
from pathlib import Path
import argparse
import hashlib
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]

if not __debug__:
    raise SystemExit('BLOCKED: source guards require Python assertions; do not run with -O')

CHECKS = r'''package com.waenhancer.utils
private fun checkEvidence(
    wanted: InjectionEvidence, active: Boolean = true, recordedBoot: Int = 7,
    currentBoot: Int = 7, pid: Int = 123, pids: Set<Int>? = setOf(123),
    timestamp: Long = 1L
) {
    val actual = classifyInjection(active, recordedBoot, currentBoot, pid, pids, timestamp)
    check(actual == wanted) { "Expected $wanted but got $actual" }
}
fun main() {
    checkEvidence(InjectionEvidence.CURRENT)
    checkEvidence(InjectionEvidence.MODULE_INACTIVE, active = false)
    checkEvidence(InjectionEvidence.BOOT_UNKNOWN, currentBoot = -1)
    checkEvidence(InjectionEvidence.NO_REPORT, pid = 0)
    checkEvidence(InjectionEvidence.NO_REPORT, timestamp = 0)
    checkEvidence(InjectionEvidence.PREVIOUS_BOOT, recordedBoot = 6)
    checkEvidence(InjectionEvidence.PROCESS_OBSERVATION_UNAVAILABLE, pids = null)
    checkEvidence(InjectionEvidence.PROCESS_STOPPED, pids = emptySet())
    checkEvidence(InjectionEvidence.PROCESS_CHANGED, pids = setOf(124))
    // An old report is valid only with the same PID and boot, not by timestamp alone.
    checkEvidence(InjectionEvidence.CURRENT, timestamp = 1)
    println("PASS: 10 executed PID/boot/evidence controls from unchanged production classifier")
}
'''


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--root', type=Path, default=ROOT)
    args = parser.parse_args()
    root = args.root.resolve()
    java = root / 'app/src/main/java/com/waenhancer'
    classifier = java / 'utils/InjectionEvidence.kt'
    manager = (java / 'utils/TargetAppManager.kt').read_text()
    navigation = (java / 'ui/navigation/Navigation.kt').read_text()
    producer = (java / 'utils/WhatsAppPackageDetector.kt').read_text()
    assert classifier.is_file(), classifier
    assert 'reportedPid !in runningPids' in classifier.read_text()
    assert 'ACTION_TARGET_ACTIVE = "com.waenhancer.ACTION_TARGET_APP_ACTIVE"' in manager
    assert 'context.sendBroadcast(intent)' in producer
    assert 'context.sendBroadcast(Intent("com.waenhancer.ACTION_TARGET_APP_ACTIVE")' in producer
    assert 'appContext.registerReceiver(targetReceiver, filter' in manager
    assert 'unregisterReceiver(targetReceiver)' in manager
    assert 'if (context != null) refresh(context.applicationContext)' in manager
    assert 'Lifecycle.Event.ON_RESUME -> TargetAppManager.attach(context)' in navigation
    assert 'Lifecycle.Event.ON_PAUSE -> TargetAppManager.detach(context)' in navigation
    assert 'rerunRequested.getAndSet(false)' in manager
    assert '_refreshEpoch.value = System.currentTimeMillis()' in manager

    compiler = shutil.which('kotlinc')
    runtime = shutil.which('java')
    if not compiler or not runtime:
        raise RuntimeError('Real kotlinc and java are required for executable classifier acceptance')
    source_sha = hashlib.sha256(classifier.read_bytes()).hexdigest()
    with tempfile.TemporaryDirectory(prefix='waex-injection-evidence-') as tmp:
        out = Path(tmp)
        copied = out / 'InjectionEvidence.kt'
        copied.write_bytes(classifier.read_bytes())
        assert hashlib.sha256(copied.read_bytes()).hexdigest() == source_sha
        checks = out / 'Checks.kt'
        checks.write_text(CHECKS)
        jar = out / 'checks.jar'
        subprocess.run(
            [compiler, '-nowarn', '-language-version', '2.0', str(copied), str(checks),
             '-include-runtime', '-d', str(jar)],
            check=True, timeout=90
        )
        subprocess.run([runtime, '-jar', str(jar)], check=True, timeout=30)
    print('PRODUCTION_SHA256=' + source_sha)
    print('PASS: executable classifier plus event-driven source guards; Android/LSPosed NOT_VERIFIED')


if __name__ == '__main__':
    main()
