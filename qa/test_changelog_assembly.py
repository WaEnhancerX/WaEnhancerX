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

# Lenient mode (used by the nightly release): bad fragments are skipped and reported, nothing is an error,
# and the used files are listed so exactly those can be cleared after the release.
with tempfile.TemporaryDirectory() as tmp:
    d = Path(tmp)
    (d / "good.txt").write_text("[Fixes]\n- Fix one\n", encoding="utf-8")
    (d / "alsogood.txt").write_text("[Fixes]\n- Fix two\n\n[Added]\n- Add one\n", encoding="utf-8")
    (d / "bad.txt").write_text("garbage without a section\n", encoding="utf-8")
    (d / "_template.txt").write_text("[Added]\n- ignored\n", encoding="utf-8")
    used = d / "used.txt"
    lenient = run(d, "--lenient", "--files-out", str(used))
    check(lenient.returncode == 0, "lenient exits 0 with a bad fragment")
    check(lenient.stdout == "[Added]\n- Add one\n\n[Fixes]\n- Fix two\n- Fix one\n", f"lenient merges the good fragments section by section: {lenient.stdout!r}")
    check("skipped bad.txt" in lenient.stderr, "lenient reports the skipped fragment")
    check(used.read_text().split() == ["alsogood.txt", "good.txt"], f"files-out lists only the fragments that were used: {used.read_text()!r}")
    check(run(d).returncode == 2, "strict mode still fails on a bad fragment")

with tempfile.TemporaryDirectory() as tmp:
    d = Path(tmp)
    (d / "_template.txt").write_text("[Added]\n- ignored\n", encoding="utf-8")
    used = d / "used.txt"
    empty = run(d, "--lenient", "--files-out", str(used))
    check(empty.returncode == 0 and empty.stdout == "" and used.read_text() == "", "lenient with no fragments is empty and fine")
    check(run(d).returncode == 2, "strict mode with no fragments still fails")
    check(run(d / "missing", "--lenient").returncode == 0, "lenient with a missing directory is fine")

# The real repository may have no pending fragments after a release. Only that
# specific empty state permits strict assembly to exit 2; malformed notes must
# fail source QA rather than be silently skipped by the lenient release job.
real_dir = ROOT / "changelog.d"
real = run(real_dir)
has_fragments = any(p.is_file() and not p.name.startswith("_") for p in real_dir.glob("*.txt"))
if has_fragments:
    check(real.returncode == 0 and bool(real.stdout.strip()) and not real.stderr,
          f"repo changelog.d contains invalid fragments: {real.stderr.strip()!r}")
else:
    check(real.returncode == 2 and not real.stdout
          and real.stderr.strip() == "error: no changelog fragments found",
          "repo changelog.d without pending fragments must report only the empty state")

if failures:
    raise SystemExit("Changelog assembly violations:\n- " + "\n- ".join(failures))

print("PASS: changelog fragment assembly contract")
