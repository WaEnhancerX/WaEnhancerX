#!/usr/bin/env python3
"""Check Android24-33 class availability and cache contracts on complete production classes.

Uses the receipt fixture's explicit Android/Xposed boundaries and full WaJid,
HideReceiptsHook and BaseFeature sources. This is host-JVM evidence, not ART.
"""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[1]


def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def run(source_root, out, kotlinc, stanza_runner):
    source_root, out = Path(source_root).resolve(), Path(out).resolve()
    out.mkdir(parents=True, exist_ok=False)
    spec = importlib.util.spec_from_file_location("class_metadata_receipt_fixture", stanza_runner)
    stanza = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(stanza)
    fixture = ROOT / "qa/java/TestClassMetadataAndroidCompat.java"
    kotlin_home = Path(kotlinc).resolve().parents[1]
    stdlib = kotlin_home / "lib/kotlin-stdlib.jar"
    if not stdlib.is_file():
        raise RuntimeError("Complete Kotlin distribution required")
    production = [source_root / p for p in [*stanza.SHARED_EARLY, *stanza.SHARED_LATE]]
    production += [source_root / stanza.AREA / name for name in stanza.PRODUCTION]
    core = source_root / "app/src/main/java/com/waenhancer/xposed/core"
    production += [core / name for name in ("BaseFeature.kt", "HookRegistrar.java", "ViewHookDispatcher.java")]
    before = {str(p.relative_to(source_root)): sha256(p) for p in production}
    receipt_out = out / "receipt"
    receipt_exit = stanza.run_contracts(source_root, receipt_out, kotlinc)
    receipt = json.loads((receipt_out / "result.json").read_text(encoding="utf-8"))
    if (receipt_exit != 0 or receipt.get("result") != "PASS" or
            receipt.get("scenarios") != 101 or receipt.get("checks") != 702):
        raise RuntimeError("Original receipt contracts failed; class-cache gate blocked")
    classes = out / "contract-classes"
    classes.mkdir()
    compiled = subprocess.run(["javac", "--release", "17", "-encoding", "UTF-8", "-d", str(classes),
                               str(fixture)], capture_output=True, timeout=60)
    (out / "compile.log").write_bytes(compiled.stdout + compiled.stderr)
    if compiled.returncode:
        raise RuntimeError("Class metadata fixture did not compile; see compile.log")
    rows = []
    for mode, expected_cases, expected_checks in (("availability", 6, 6), ("contracts", 13, 62)):
        command = ["java", "-ea", "-cp", str(classes), "TestClassMetadataAndroidCompat", mode,
                   str(receipt_out / "classes"), str(stdlib), str(receipt_out / "dexkit-2.2.0.jar")]
        completed = subprocess.run(command, capture_output=True, timeout=90)
        output = (completed.stdout + completed.stderr).decode("utf-8", errors="replace")
        (out / (mode + ".log")).write_text(output, encoding="utf-8")
        summaries = re.findall(r"Class metadata: (\d+) cases, (\d+) checks, (\d+) failed cases", output)
        summary = tuple(map(int, summaries[0])) if len(summaries) == 1 else None
        passed = completed.returncode == 0 and summary == (expected_cases, expected_checks, 0)
        rows.append({"mode": mode, "exit_code": completed.returncode, "summary": summary,
                     "expected_cases": expected_cases, "expected_checks": expected_checks, "passed": passed})
        print(output, end="")
        if not passed:
            break
    after = {str(p.relative_to(source_root)): sha256(p) for p in production}
    passed = before == after and len(rows) == 2 and all(row["passed"] for row in rows)
    report = {
        "status": "PASS" if passed else "FAIL", "runs": rows, "production_source_sha256": before,
        "production_inputs_unchanged": before == after,
        "fixture_sha256": sha256(fixture), "receipt_runner_sha256": sha256(Path(stanza_runner)),
        "receipt_fixture_sha256": receipt["fixture_sha256"],
        "kotlin_stdlib_sha256": sha256(stdlib), "dexkit_jar_sha256": receipt["dexkit_jar_sha256"],
        "original_receipt_scenarios": receipt["scenarios"], "original_receipt_checks": receipt["checks"],
        "complete_production_classes": True, "android_art_executed": False,
        "limits": ("Only java.lang.ClassValue is denied by the availability class-loader control. "
                   "Android/Xposed services remain the receipt fixture boundaries. Weak-reference "
                   "expiration is explicit and deterministic; no timed GC/unloading claim. "
                   "Publication races use blocked return-type linkage and controlled first publication."),
    }
    (out / "result.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    return 0 if passed else 1


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-root", type=Path, default=ROOT)
    parser.add_argument("--out", required=True, type=Path)
    parser.add_argument("--kotlinc", default=os.environ.get("KOTLINC") or shutil.which("kotlinc"))
    parser.add_argument("--stanza-runner", type=Path,
                        default=Path(__file__).with_name("test_receipt_stanza_runtime.py"))
    args = parser.parse_args()
    if not args.kotlinc or not shutil.which("java") or not shutil.which("javac"):
        parser.error("JDK and complete Kotlin compiler distribution required")
    return run(args.source_root, args.out, args.kotlinc, args.stanza_runner.resolve())


if __name__ == "__main__":
    raise SystemExit(main())
