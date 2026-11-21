#!/usr/bin/env python3
"""Contract tests for scripts/release/build_pending_changelog.py."""

import json
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "scripts/release/build_pending_changelog.py"
failures = []


def check(condition, message):
    if not condition:
        failures.append(message)


def run(fragments, out):
    return subprocess.run([sys.executable, str(SCRIPT), str(fragments), str(out)], capture_output=True, text=True)


with tempfile.TemporaryDirectory() as tmp:
    tmp = Path(tmp)
    frag, out = tmp / "changelog.d", tmp / "index"
    frag.mkdir()
    (frag / "status-download.txt").write_text("[Fixes]\n- Fix one\n\n[Added]\n- Add one\n", encoding="utf-8")
    (frag / "backup.txt").write_text("[Fixes]\n- Fix two\n- Fix one\n", encoding="utf-8")
    (frag / "broken.txt").write_text("no section\n", encoding="utf-8")
    (frag / "_template.txt").write_text("[Added]\n- ignored\n", encoding="utf-8")
    (frag / "README.md").write_text("ignored", encoding="utf-8")
    result = run(frag, out)
    check(result.returncode == 0, f"builds with a broken fragment: {result.stderr}")
    check("skipped broken.txt" in result.stderr, "reports the skipped fragment")
    data = json.loads((out / "nightly-changelog.json").read_text(encoding="utf-8"))
    check(data["schema"] == 1 and data["channel"] == "nightly", "schema and channel")
    check(data["fragments"] == ["backup.txt", "status-download.txt"], f"fragments are the used ones, sorted: {data['fragments']}")
    check(data["notes"] == "[Added]\n- Add one\n\n[Fixes]\n- Fix two\n- Fix one\n", f"sections are merged section by section without duplicates: {data['notes']!r}")
    first = (out / "nightly-changelog.json").read_bytes()
    run(frag, out)
    check((out / "nightly-changelog.json").read_bytes() == first, "the output is deterministic")

    # Merging another PR adds to the same sections; a release clears everything.
    (frag / "new-feature.txt").write_text("[Added]\n- Add two\n[Improvements]\n- Better one\n", encoding="utf-8")
    run(frag, out)
    data = json.loads((out / "nightly-changelog.json").read_text(encoding="utf-8"))
    added = data["notes"].split("[Added]\n")[1].split("\n\n")[0].splitlines()
    check(sorted(added) == ["- Add one", "- Add two"], f"a new PR adds its entries to the same [Added] section: {data['notes']!r}")
    check(data["notes"].count("[Added]") == 1 and "[Improvements]\n- Better one" in data["notes"], "sections are not repeated and new sections appear")
    for f in frag.glob("*.txt"):
        if not f.name.startswith("_"):
            f.unlink()
    run(frag, out)
    data = json.loads((out / "nightly-changelog.json").read_text(encoding="utf-8"))
    check(data["fragments"] == [] and data["notes"] == "", "after a release the pending changelog is empty")

    missing = run(tmp / "nope", tmp / "index2")
    check(missing.returncode == 0 and json.loads((tmp / "index2/nightly-changelog.json").read_text())["notes"] == "", "a missing directory gives an empty changelog")

    (frag / "huge.txt").write_text("[Fixes]\n" + "".join(f"- Entry number {i} with some words\n" for i in range(2000)), encoding="utf-8")
    run(frag, out)
    check(len(json.loads((out / "nightly-changelog.json").read_text(encoding="utf-8"))["notes"]) <= 6000, "notes are bounded")

check(subprocess.run([sys.executable, str(SCRIPT)], capture_output=True, text=True).returncode == 2, "wrong arguments fail")

if failures:
    raise SystemExit("Pending changelog violations:\n- " + "\n- ".join(failures))
print("PASS: pending changelog contract")
