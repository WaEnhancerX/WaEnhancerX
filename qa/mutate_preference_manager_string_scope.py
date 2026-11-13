#!/usr/bin/env python3
"""Reject compiling string-getter mutations with the existing behavioral fixture."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import time

ROOT = Path(__file__).resolve().parents[1]
MANAGER = Path("app/src/main/java/com/waenhancer/core/preferences/WaexPreferenceManager.java")
INPUTS = [MANAGER,
          Path("app/src/main/java/com/waenhancer/config/PreferenceSchema.java"),
          Path("app/src/main/java/com/waenhancer/config/PreferenceStores.java"),
          Path("qa/test_bridge_contract.py")]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", type=Path)
    args = parser.parse_args()
    out = args.out.resolve() if args.out else Path(tempfile.mkdtemp(prefix="waex-string-scope-mutants-"))
    if args.out:
        out.mkdir(parents=True, exist_ok=False)
    source = (ROOT / MANAGER).read_text(encoding="utf-8")
    anchor = "Object allVal = store.getAll().get(resolved);"
    begin = source.index("public String getString(")
    end = source.index("\n    @Override", begin)
    getter = source[begin:end]
    if getter.count(anchor) != 1:
        raise SystemExit("String getter mutation anchor changed; review the control")
    variants = {
        "global-fallback": ("Object allVal = store.getAll().get(key);", "target-int"),
        "reresolve-fallback": ("Object allVal = getStore(key).getAll().get(key(key));", "target-changes"),
    }
    report = {
        "scope": "Compiling mutations of the complete production preference manager",
        "inputs_sha256": {str(path): hashlib.sha256((ROOT / path).read_bytes()).hexdigest()
                         for path in INPUTS + [Path("qa/test_preference_manager_string_scope.py"),
                                               Path("qa/java/PreferenceManagerStringScopeChecks.java")]},
        "results": [],
    }
    for name, (replacement, expected_failure) in variants.items():
        work = out / name
        for path in INPUTS:
            destination = work / path
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(ROOT / path, destination)
        mutated = source[:begin] + getter.replace(anchor, replacement) + source[end:]
        (work / MANAGER).write_text(mutated,
                                   encoding="utf-8", newline="\n")
        command = [sys.executable, str(ROOT / "qa/test_preference_manager_string_scope.py"),
                   "--source-root", str(work)]
        started = time.monotonic()
        completed = subprocess.run(command, cwd=ROOT, text=True, encoding="utf-8",
                                   errors="replace", stdout=subprocess.PIPE,
                                   stderr=subprocess.STDOUT, timeout=60)
        (work / "run.log").write_text(completed.stdout, encoding="utf-8", newline="\n")
        match = re.search(r"RESULT: (\d+) cases, (\d+) assertions, (\d+) failures", completed.stdout)
        failed_cases = re.findall(r"^FAIL ([^:]+):", completed.stdout, flags=re.MULTILINE)
        detected = bool(completed.returncode != 0 and match
                        and int(match.group(1)) == 15 and int(match.group(3)) > 0
                        and expected_failure in failed_cases)
        report["results"].append({
            "mutation": name, "command": command, "exit_code": completed.returncode,
            "duration_seconds": round(time.monotonic() - started, 4),
            "manager_sha256": hashlib.sha256((work / MANAGER).read_bytes()).hexdigest(),
            "completed_fixture": bool(match), "failed_cases": failed_cases,
            "negative_control_detected": detected,
        })
    report["status"] = "PASS" if all(x["negative_control_detected"] for x in report["results"]) else "FAIL"
    (out / "results.json").write_text(json.dumps(report, indent=2) + "\n",
                                     encoding="utf-8", newline="\n")
    print(json.dumps(report, indent=2))
    return 0 if report["status"] == "PASS" else 1


if __name__ == "__main__":
    sys.exit(main())
