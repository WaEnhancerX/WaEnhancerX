#!/usr/bin/env python3
"""Reject independent profile-photo regressions with compiled behavioral mutants.

Each mutation must compile the current production source and reach a specified
failing behavior assertion. Compilation errors and incomplete fixtures never PASS.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
HOOK = Path("app/src/main/java/com/waenhancer/xposed/features/media/ProfilePhotoDownloadHook.java")
ERRORS = Path("app/src/main/java/com/waenhancer/xposed/utils/HookErrors.java")
TEST = ROOT / "qa/test_profile_photo_save.py"


def replace(source: str, before: str, after: str, expected: int = 1) -> str:
    actual = source.count(before)
    if actual != expected:
        raise AssertionError(f"Mutation target count {actual} != {expected}: {before[:76]}")
    return source.replace(before, after)


def mutants(original: str):
    ack = ("                if (resolver.update(output, ready, null, null) != 1) {\n"
           "                    throw new IllegalStateException(\"MediaStore did not publish profile photo\");\n"
           "                }")
    no_ack = replace(original, ack, "                resolver.update(output, ready, null, null);", 2)
    yield ("publication_acknowledgements", no_ack,
           ("FAIL storage failure updateZero", "FAIL stream must reject updateZero"))

    leak = replace(original, "            rendered.recycle();\n            throw failure;",
                   "            // Mutation: forget owned rendered Bitmap.\n            throw failure;")
    yield ("rendered_bitmap_ownership", leak,
           ("FAIL drawable failure restores host bounds",
            "FAIL bounds restoration error releases rendered copy"))

    latch = replace(original,
                    "                saveInFlight.set(false);\n"
                    "                logFailure(\"submit profile photo save\", rejected);",
                    "                // Mutation: retain locked admission gate.\n"
                    "                logFailure(\"submit profile photo save\", rejected);")
    yield ("executor_rejection_admission", latch,
           ("FAIL executor rejection restores admission",))

    status = replace(original,
                     "return photoAvailable ? SaveResult.SAVE_FAILED : SaveResult.PHOTO_UNAVAILABLE;",
                     "return SaveResult.PHOTO_UNAVAILABLE;")
    yield ("failed_save_reported_unavailable", status,
           ("FAIL storage failure nullInsert",))


def exercise(source_root: Path) -> tuple[int, str]:
    command = [sys.executable, str(TEST), "--source-root", str(source_root)]
    result = subprocess.run(command, cwd=ROOT, text=True, capture_output=True, timeout=95)
    return result.returncode, result.stdout + result.stderr


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", type=Path, help="New directory for test evidence")
    args = parser.parse_args()
    original = (ROOT / HOOK).read_text(encoding="utf-8")
    errors = (ROOT / ERRORS).read_bytes()
    findings = []
    with tempfile.TemporaryDirectory(prefix="waex_profile_mutants_") as temp:
        for name, edited, expected in mutants(original):
            stage = Path(temp) / name
            source_file = stage / HOOK
            source_file.parent.mkdir(parents=True)
            source_file.write_text(edited, encoding="utf-8")
            real_errors = stage / ERRORS
            real_errors.parent.mkdir(parents=True)
            real_errors.write_bytes(errors)
            code, output = exercise(stage)
            if code == 0 or "COMPILE_OK complete profile hook" not in output:
                raise AssertionError(f"{name} did not reach compiled behavior:\n{output[-3000:]}")
            if not re.search(r"RESULT cases=30 failed=[1-9][0-9]* checks=\d+", output):
                raise AssertionError(f"{name} did not finish all 30 cases:\n{output[-3000:]}")
            for target in expected:
                if target not in output:
                    raise AssertionError(f"{name} missing expected failure {target}:\n{output[-3000:]}")
            findings.append({"mutation": name, "exit_code": code, "target_failures": list(expected),
                             "source_sha256": hashlib.sha256(source_file.read_bytes()).hexdigest()})
            print("PASS negative_control", name, "; ".join(expected), flush=True)
            if args.out:
                args.out.mkdir(parents=True, exist_ok=True)
                (args.out / (name + ".log")).write_text(output, encoding="utf-8")
    if len(findings) != 4:
        raise AssertionError("Unexpected mutation suite count")
    if args.out:
        (args.out / "result.json").write_text(json.dumps({
            "production_sha256": hashlib.sha256((ROOT / HOOK).read_bytes()).hexdigest(),
            "HookErrors_sha256": hashlib.sha256(errors).hexdigest(),
            "mutations": findings, "limits": "Strict JVM fixtures, no Android/WhatsApp injection"
        }, indent=2) + "\n", encoding="utf-8")
    print("PASS 4/4 compiled behavior mutations rejected")


if __name__ == "__main__":
    main()
