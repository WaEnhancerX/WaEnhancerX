#!/usr/bin/env python3
"""Kill regressions in complete DexCacheManager generation/admission behavior.

Separate Java processes share a real temporary descriptor file. Android/Xposed
are boundary doubles; module identities are final constants compiled into each
manager variant. A compiler failure never counts as a killed mutant.
"""
from pathlib import Path
import argparse
import json
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "app/src/main/java/com/waenhancer/xposed/core/devkit/DexCacheManager.java"
MUTANTS = [
    ("ignore_module_generation", '!MODULE_CACHE_GENERATION.equals(mPrefs.getString(META_MODULE_GENERATION, null))', 'false', True, 1, 'module generation returned obsolete class array'),
    ("invalidate_on_every_launch", 'if (stale) {', 'if (true) {', True, 1, 'one resolution per kind only after generation change'),
    ("ignore_module_version_name", '+ BuildConfig.VERSION_CODE + ":" + BuildConfig.VERSION_NAME;', '+ BuildConfig.VERSION_CODE + ":";', True, 1, 'module generation returned obsolete class array'),
    ("trust_unvalidated_disk", 'mPersistentCacheValid ? mPrefs.getString(typedKey, null) : null', 'mPrefs.getString(typedKey, null)', False, 1, 'unvalidated host bypasses disk class descriptor'),
    ("lose_waiter_cooldown", '                if (mFailedKeys.shouldSkip(negativeKind, typedKey, SystemClock.elapsedRealtime())) return null;', '                // negative control: ignore a completed miss while waiting', False, 1, 'waiters repeated the failed resolver'),
    ("persist_unknown_host", 'if (mPersistentCacheValid) mPrefs.edit().putString(typedKey, codec.encode(value)).apply();', 'mPrefs.edit().putString(typedKey, codec.encode(value)).apply();', False, 1, 'unvalidated class result is not persisted'),
]



def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--evidence", type=Path)
    args = parser.parse_args()
    if args.evidence:
        args.evidence.mkdir(parents=True, exist_ok=True)
    healthy=subprocess.run([sys.executable,str(ROOT / "qa/test_dex_negative_cache.py")],capture_output=True,text=True,encoding="utf-8",errors="replace",timeout=120)
    if args.evidence:(args.evidence/"healthy.log").write_text(healthy.stdout+healthy.stderr,encoding="utf-8")
    if healthy.returncode:raise RuntimeError("Candidate must pass before evaluating negative controls")
    source = SOURCE.read_text(encoding="utf-8")
    results = []
    for name, old, new, generation_only, count, expected in MUTANTS:
        if source.count(old) != count:
            raise AssertionError(f"Unexpected mutation anchor count for {name}")
        with tempfile.TemporaryDirectory(prefix="waex_cache_generation_mutant_") as directory:
            mutant = Path(directory) / "DexCacheManager.java"
            mutant.write_text(source.replace(old, new),encoding="utf-8")
            command = [sys.executable, str(ROOT / "qa/test_dex_negative_cache.py"),
                       "--baseline-manager", str(mutant)]
            if generation_only:
                command.append("--generation-only")
            run = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                 text=True, encoding="utf-8", errors="replace", timeout=120)
            killed = run.returncode != 0 and ("java.lang.AssertionError: " + expected) in run.stdout and "COMPILE_OK" in run.stdout
            if args.evidence:
                (args.evidence / f"{name}.log").write_text(run.stdout)
            results.append({"name": name, "behaviorally_killed": killed, "returncode": run.returncode,"expected_assertion":expected})
            print(f"{'KILLED' if killed else 'SURVIVED/INVALID'} {name}")
            if not killed:
                print(run.stdout)
    if args.evidence:
        (args.evidence / "results.json").write_text(json.dumps(results, indent=2) + "\n")
    if not all(result["behaviorally_killed"] for result in results):
        raise AssertionError("Cache generation mutation gate failed")
    print(f"PASS: {len(results)} complete production cache mutants killed")


if __name__ == "__main__":
    main()
