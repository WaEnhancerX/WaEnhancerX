#!/usr/bin/env python3
"""Contract tests for scripts/release/pr_gate.py (nightly PR gate decisions)."""

import hashlib
import hmac
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts/release"))

import pr_gate  # noqa: E402

CONFIG = json.loads((ROOT / ".github/nightly-protected-paths.json").read_text(encoding="utf-8"))
failures = []


def check(condition, message):
    if not condition:
        failures.append(message)


def ctx(**overrides):
    base = {
        "base_ref": "nightly", "draft": False, "author_id": 70108066, "author_type": "User",
        "changed_files": 1, "additions": 10, "deletions": 2,
    }
    base.update(overrides)
    return base


def src_file(name="app/src/main/java/com/waenhancer/Foo.kt", **extra):
    entry = {"filename": name, "status": "modified", "patch": "@@ -1 +1 @@"}
    entry.update(extra)
    return entry


def run(context=None, allowed=frozenset({70108066}), files=None, allow_error=False, files_error=False):
    def load_allowlist():
        if allow_error:
            raise pr_gate.GateError("down")
        return set(allowed)

    def load_files():
        if files_error:
            raise pr_gate.GateError("down")
        return files if files is not None else [src_file()]

    return pr_gate.decide(context or ctx(), CONFIG, load_allowlist, load_files)


def reason(result):
    return result[1]


check(run()[:2] == ("proceed", "ok"), "plain source change by allowlisted user proceeds")
check(reason(run(ctx(base_ref="main"))) == "wrong_base", "wrong base")
check(reason(run(ctx(draft=True))) == "draft", "draft")
check(reason(run(ctx(author_type="Bot"))) == "bot_author", "bot author")
check(reason(run(ctx(author_id=-1))) == "bad_author", "unidentified author")
check(reason(run(ctx(author_id=1))) == "not_allowlisted", "author not on list")
check(reason(run(allow_error=True)) == "allowlist_unavailable", "API down fails closed")
check(reason(run(files_error=True)) == "files_unavailable", "file list error fails closed")
check(reason(run(ctx(changed_files=41))) == "too_many_files", "too many files")
check(reason(run(ctx(additions=1500, deletions=1))) == "too_many_lines", "too many lines")
check(reason(run(ctx(changed_files=2))) == "files_unavailable", "incomplete file list fails closed")
check(reason(run(files=[src_file(patch=None)])) == "no_text_diff", "missing patch")

for path in (
    ".github/workflows/x.yml", "app/build.gradle.kts", "gradle/libs.versions.toml", "licensing/a.kt",
    "app/src/main/AndroidManifest.xml", "app/src/main/cpp/a.cpp", "app/proguard-rules.pro",
    "scripts/release/version.py", "qa/test_pr_gate.py", "qa/test_nightly_release_contract.py", "libs/evil.jar", "tools/run.sh", "KEY.JKS", "gradle.properties",
):
    result = run(files=[src_file(path)])
    check(reason(result) == "protected_path", f"{path} must be protected")

check(reason(run(files=[src_file(name="app/src/main/java/ok.kt", status="renamed", previous_filename=".github/workflows/old.yml")])) == "protected_path",
      "rename out of a protected path is protected")
check(run(files=[src_file("app/src/main/res/values/strings.xml")])[1] == "ok", "resource change allowed")
check(run(files=[src_file("qa/test_media_hooks.py")])[1] == "ok", "ordinary qa test allowed")

# Every message cites a rule or states that a maintainer reviews, and the held ones explain resubmission.
for key in ("wrong_base", "draft", "too_many_files", "too_many_lines"):
    check("close this PR" in pr_gate.REASONS[key] and "R" in pr_gate.REASONS[key], f"{key} message explains resubmission")
check(all(("Rule R" in m or "maintainer" in m or key == "ok") for key, m in pr_gate.REASONS.items()), "messages cite a rule or a maintainer")

# Allowlist parsing.
check(pr_gate.parse_allowlist(b'{"github_users":[1,2]}') == {1, 2}, "valid allowlist")
for bad in (b"", b"{}", b'{"github_users":[]}', b'{"github_users":["1"]}', b'{"github_users":[true]}',
            b'{"github_users":[0]}', b'{"github_users":"x"}', b"[1]"):
    try:
        pr_gate.parse_allowlist(bad)
        check(False, f"{bad!r} must be rejected")
    except pr_gate.GateError:
        pass

# Retry then fail closed; HMAC verification.
calls = []


def flaky(url, headers):
    calls.append(url)
    raise OSError("down")


try:
    pr_gate.fetch_allowlist("https://x", getter=flaky, sleep=lambda s: None)
    check(False, "persistent failure must raise")
except pr_gate.GateError:
    check(len(calls) == pr_gate.API_ATTEMPTS, "retries before failing")

body = b'{"github_users":[5]}'
key, ts = "k", "1000"
sig = hmac.new(key.encode(), ts.encode() + b"." + body, hashlib.sha256).hexdigest()
good = lambda url, headers: (body, {"X-WAEX-Timestamp": ts, "X-WAEX-Signature": sig})
check(pr_gate.verify_signature(key, ts, sig, body, now=1100), "valid signature")
check(not pr_gate.verify_signature(key, ts, sig, body, now=2000), "stale signature")
check(not pr_gate.verify_signature("other", ts, sig, body, now=1100), "wrong key")
check(not pr_gate.verify_signature(key, ts, "", body, now=1100), "missing signature")
tampered = lambda url, headers: (b'{"github_users":[5,6]}', {"X-WAEX-Timestamp": ts, "X-WAEX-Signature": sig})
orig = pr_gate.time.time
pr_gate.time.time = lambda: 1100
try:
    check(pr_gate.fetch_allowlist("https://x", key, getter=good, sleep=lambda s: None) == {5}, "signed response accepted")
    try:
        pr_gate.fetch_allowlist("https://x", key, getter=tampered, sleep=lambda s: None)
        check(False, "tampered body must be rejected")
    except pr_gate.GateError:
        pass
finally:
    pr_gate.time.time = orig

if failures:
    raise SystemExit("PR gate violations:\n- " + "\n- ".join(failures))
print("PASS: nightly PR gate decision contract")
