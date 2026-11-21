#!/usr/bin/env python3
"""Reject unsafe SQLite revoke predicate mutations after a compiled behavioral run."""
from pathlib import Path
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "app/src/main/java/com/waenhancer/xposed/features/privacy/AntiRevokeHook.java"
RUNNER = ROOT / "qa/test_revoke_sqlite_contract.py"


def run(source: Path) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        [sys.executable, str(RUNNER), "--source", str(source),
         "--scenario", "message_predicate"],
        capture_output=True, text=True, check=False,
    )


def main() -> None:
    original = SOURCE.read_text(encoding="utf-8")
    marker = "private static final Pattern ROW_ID_WHERE = "
    lines = [line for line in original.splitlines(keepends=True) if marker in line]
    if len(lines) != 1 or not lines[0].strip().endswith(");"):
        raise AssertionError("exactly one supported production ROW_ID_WHERE field is required")
    line = lines[0]
    baseline = run(SOURCE)
    if baseline.returncode != 0 or "checks passed" not in baseline.stdout:
        raise AssertionError(
            "reference fixture did not execute successfully:\n"
            + baseline.stdout + baseline.stderr
        )

    with tempfile.TemporaryDirectory(prefix="waex-revoke-predicate-mutants-") as directory:
        temp = Path(directory) / "AntiRevokeHook.java"
        newline = "\r\n" if line.endswith("\r\n") else "\n"

        # This compiles the actual predicate but would suppress benign deletes too.
        temp.write_text(
            original.replace(line, '    private static final Pattern ROW_ID_WHERE = Pattern.compile(".*");' + newline),
            encoding="utf-8",
        )
        unsafe = run(temp)
        output = unsafe.stdout + unsafe.stderr
        if unsafe.returncode == 0 or "java.lang.AssertionError" not in output:
            raise AssertionError(
                "permissive-predicate mutant did not fail in Java assertions:\n" + output
            )
        if "cannot find symbol" in output or "error: " in output:
            raise AssertionError("mutation was rejected by compilation, not behavior:\n" + output)

        # Missing production field must be diagnosed, not replaced with fixture constants.
        temp.write_text(original.replace(line, ""), encoding="utf-8")
        missing = run(temp)
        output = missing.stdout + missing.stderr
        if missing.returncode == 0 or "expected exactly one production pattern ROW_ID_WHERE" not in output:
            raise AssertionError("removed-field mutant escaped the source identity check:\n" + output)

    print("PASS: reference compiled; unsafe predicate failed Java assertions; absent pattern rejected")


if __name__ == "__main__":
    main()
