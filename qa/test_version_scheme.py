#!/usr/bin/env python3
"""Contract tests for scripts/release/version.py (nightly/beta/stable versionCode)."""

import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "scripts/release/version.py"
sys.path.insert(0, str(SCRIPT.parent))

import version  # noqa: E402

SHA = "0123456789abcdef0123456789abcdef01234567"
failures = []


def check(condition, message):
    if not condition:
        failures.append(message)


def expect_error(*args, **kwargs):
    try:
        version.compute(*args, **kwargs)
    except version.VersionError:
        return True
    return False


# Documented examples.
check(version.compute("stable", "2.0.9") == (20009999, "2.0.9"), "stable 2.0.9")
check(version.compute("beta", "2.0.9", 1) == (20009501, "2.0.9-beta-1"), "beta-1 2.0.9")
check(
    version.compute("nightly", "2.0.9", 1, SHA) == (20009001, "2.0.9-nightly.1+0123456"),
    "nightly.1 2.0.9",
)

# Ordering inside one base version: nightly < beta < stable.
nightly_max = version.compute("nightly", "2.0.9", 499, SHA)[0]
beta_min = version.compute("beta", "2.0.9", 1)[0]
beta_max = version.compute("beta", "2.0.9", 498)[0]
stable = version.compute("stable", "2.0.9")[0]
check(nightly_max < beta_min, "last nightly must stay below first beta")
check(beta_max < stable, "last beta must stay below stable")

# Next base's pre-releases beat the previous stable; legacy codes stay lower.
check(version.compute("nightly", "2.0.10", 1, SHA)[0] > stable, "next patch nightly > previous stable")
check(version.compute("nightly", "2.1.0", 1, SHA)[0] > version.compute("stable", "2.0.99")[0], "minor bump ordering")
for legacy in (17113, 20800):
    check(version.compute("nightly", "2.0.9", 1, SHA)[0] > legacy, f"above legacy code {legacy}")

# Rejected input.
check(expect_error("nightly", "2.0.9", 0, SHA), "nightly counter 0")
check(expect_error("nightly", "2.0.9", 500, SHA), "nightly counter 500")
check(expect_error("nightly", "2.0.9", 1, None), "nightly needs sha")
check(expect_error("nightly", "2.0.9", 1, "zzzzzzz"), "nightly rejects non-hex sha")
check(expect_error("beta", "2.0.9", 499), "beta counter 499")
check(expect_error("beta", "2.0.9", None), "beta needs counter")
check(expect_error("stable", "2.0.9", 1), "stable rejects counter")
check(expect_error("stable", "2.100.0"), "minor above 99")
check(expect_error("stable", "2.0.100"), "patch above 99")
check(expect_error("stable", "2.0"), "malformed base")
check(expect_error("stable", "300.0.0"), "versionCode over Android limit")
check(expect_error("preview", "2.0.9", 1), "unknown channel")

# CLI contract used by workflows.
cli = subprocess.run(
    [sys.executable, str(SCRIPT), "--channel", "beta", "--base", "2.0.9", "--counter", "3"],
    capture_output=True,
    text=True,
)
check(cli.returncode == 0, "CLI exits 0")
check(cli.stdout.splitlines() == ["version_code=20009503", "version_name=2.0.9-beta-3"], "CLI output lines")
bad = subprocess.run(
    [sys.executable, str(SCRIPT), "--channel", "beta", "--base", "2.0.9"],
    capture_output=True,
    text=True,
)
check(bad.returncode == 2 and not bad.stdout, "CLI fails closed with no stdout")

if failures:
    raise SystemExit("Version scheme violations:\n- " + "\n- ".join(failures))

print("PASS: nightly/beta/stable versionCode scheme contract")
