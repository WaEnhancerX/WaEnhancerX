#!/usr/bin/env python3
"""Contract tests for scripts/release/next_version.py."""
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts/release"))
import next_version as n  # noqa: E402
import version  # noqa: E402

SHA = "0123456789abcdef0123456789abcdef01234567"
failures = []


def check(cond, msg):
    if not cond:
        failures.append(msg)


def nv(channel, current, tags, sha=SHA):
    return n.next_version(channel, current, tags, sha)[:2]


TAGS = ["20800-2.0.8", "2.0.8", "20700-2.0.7", "v2.0.0-beta-1"]
# Unreleased base is released/built as itself.
check(nv("stable", "2.0.9", TAGS) == (20009999, "2.0.9"), "stable unreleased base")
# Released base moves to the next patch.
check(nv("stable", "2.0.8", TAGS) == (20009999, "2.0.9"), "stable bumps released base")
check(nv("beta", "2.0.8", TAGS) == (20009501, "2.0.9-beta-1"), "beta after stable")
check(nv("beta", "2.0.9-beta-1", TAGS) == (20009502, "2.0.9-beta-2"), "beta increments")
check(nv("beta", "2.0.9-beta-1", TAGS + ["20009503-2.0.9-beta-3"]) == (20009504, "2.0.9-beta-4"), "beta follows highest tag")
check(nv("nightly", "2.0.9-beta-2", TAGS) == (20009001, "2.0.9-nightly.1+0123456"), "nightly starts at 1")
check(nv("nightly", "2.0.9-nightly.4+abcdef0", TAGS) == (20009005, "2.0.9-nightly.5+0123456"), "nightly increments")
check(nv("stable", "2.0.9-beta-3", TAGS) == (20009999, "2.0.9"), "beta promoted to stable")
check(nv("stable", "2.0.9-beta-3", TAGS + ["2.0.9"]) == (20100999, "2.1.0"), "released base rolls patch over 9")
check(nv("beta", "2.9.9", ["2.9.9"]) == (30000501, "3.0.0-beta-1"), "minor rolls into major")
# Counter belongs to its base: a stale recorded counter does not leak into a new base.
check(nv("beta", "2.0.8-beta-7", TAGS) == (20009501, "2.0.9-beta-1"), "counter resets with base")
# Nightly counter overflow is rejected.
try:
    nv("nightly", "2.0.9-nightly.499", TAGS)
    check(False, "nightly overflow accepted")
except version.VersionError:
    pass
# Ordering across channels for one base.
check(nv("nightly", "2.0.9", TAGS)[0] < nv("beta", "2.0.9", TAGS)[0] < nv("stable", "2.0.9", TAGS)[0], "channel order")

with tempfile.TemporaryDirectory() as d:
    p = Path(d) / "gradle.properties"
    p.write_text("A=1\nVERSION_CODE=20800\nVERSION_NAME=2.0.8\nDEV_GH=x\n")
    tags = Path(d) / "tags.txt"
    tags.write_text("\n".join(TAGS))
    rc = n.main(["--channel", "beta", "--properties", str(p), "--tags-file", str(tags), "--write"])
    check(rc == 0, "cli exit")
    text = p.read_text()
    check("VERSION_CODE=20009501" in text and "VERSION_NAME=2.0.9-beta-1" in text and "A=1" in text and "DEV_GH=x" in text, f"properties rewritten: {text!r}")

if failures:
    print("FAIL:\n- " + "\n- ".join(failures))
    sys.exit(1)
print("PASS: next version contract")
