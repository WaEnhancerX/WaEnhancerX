#!/usr/bin/env python3
"""Contract tests for scripts/release/assemble-notes.py."""

import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "scripts/release/assemble-notes.py"
failures = []


def check(condition, message):
    if not condition:
        failures.append(message)


def run(directory, *extra):
    return subprocess.run(
        [sys.executable, str(SCRIPT), "--dir", str(directory), *extra],
        capture_output=True,
        text=True,
    )


with tempfile.TemporaryDirectory() as tmp:
    d = Path(tmp)
    (d / "10.txt").write_text("[Fixes]\n- Fix ten\n\n[Added]\n- Add ten\n", encoding="utf-8")
    (d / "2.txt").write_text(
        "[Improvements]\n- Improve two\n  wrapped line\n\n[Fixes]\n- Fix two\n- Fix ten\n",
        encoding="utf-8",
    )
    (d / "_template.txt").write_text("[Added]\n- ignored\n", encoding="utf-8")
    (d / "README.md").write_text("ignored", encoding="utf-8")
    result = run(d)
    expected = (
        "[Added]\n- Add ten\n\n"
        "[Fixes]\n- Fix two\n- Fix ten\n\n"
        "[Improvements]\n- Improve two wrapped line\n"
    )
    check(result.returncode == 0, "valid fragments exit 0")
    check(result.stdout == expected, f"merged output differs: {result.stdout!r}")

    out_file = d / "notes.out"
    check(run(d, "--out", str(out_file)).returncode == 0 and out_file.read_text() == expected, "--out file")

with tempfile.TemporaryDirectory() as tmp:
    d = Path(tmp)
    for name, body in {
        "bullet-first.txt": "- orphan\n",
        "garbage.txt": "[Fixes]\nnot a bullet\n",
        "empty.txt": "[Fixes]\n",
    }.items():
        (d / name).write_text(body, encoding="utf-8")
        result = run(d)
        check(result.returncode == 2 and not result.stdout, f"{name} fails closed")
        (d / name).unlink()
    check(run(d).returncode == 2, "no fragments fails closed")

real = run(ROOT / "changelog.d")
check(real.returncode == 2, "repo changelog.d has only README/template, so no notes yet")

if failures:
    raise SystemExit("Changelog assembly violations:\n- " + "\n- ".join(failures))

print("PASS: changelog fragment assembly contract")
