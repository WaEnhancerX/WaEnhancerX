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
    ("ignore_module_generation", " || !MODULE_CACHE_GENERATION.equals(cachedModuleGeneration)", "", True, 1),
    ("invalidate_on_every_launch",
     "if (cachedHostVersion != hostVersionCode || !MODULE_CACHE_GENERATION.equals(cachedModuleGeneration)) {",
     "if (true) {", True, 1),
    ("ignore_module_version_name", '+ BuildConfig.VERSION_CODE + ":" + BuildConfig.VERSION_NAME;',
     '+ BuildConfig.VERSION_CODE + ":";', True, 1),
    ("trust_unvalidated_disk", "mPersistentCacheValid ? mPrefs.getString(key, null) : null",
     "mPrefs.getString(key, null)", False, 3),
]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--evidence", type=Path)
    args = parser.parse_args()
    if args.evidence:
        args.evidence.mkdir(parents=True, exist_ok=True)
    source = SOURCE.read_text()
    results = []
    for name, old, new, generation_only, count in MUTANTS:
        if source.count(old) != count:
            raise AssertionError(f"Unexpected mutation anchor count for {name}")
        with tempfile.TemporaryDirectory(prefix="waex_cache_generation_mutant_") as directory:
            mutant = Path(directory) / "DexCacheManager.java"
            mutant.write_text(source.replace(old, new))
            command = [sys.executable, str(ROOT / "qa/test_dex_negative_cache.py"),
                       "--baseline-manager", str(mutant)]
            if generation_only:
                command.append("--generation-only")
            run = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                 text=True, timeout=60)
            killed = run.returncode != 0 and "java.lang.AssertionError" in run.stdout and "COMPILE_OK" in run.stdout
            if args.evidence:
                (args.evidence / f"{name}.log").write_text(run.stdout)
            results.append({"name": name, "behaviorally_killed": killed, "returncode": run.returncode})
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
