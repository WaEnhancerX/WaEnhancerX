#!/usr/bin/env python3
"""Contract test for scripts/release/publish_review.sh using a fake gh (no network)."""

import os
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "scripts/release/publish_review.sh"
failures = []

FAKE_GH = """#!/usr/bin/env bash
echo "$*" >> "$GH_LOG"
stdin=""
if [[ "$*" == *"--input -"* ]]; then stdin="$(cat)"; printf '%s' "$stdin" > "$GH_STATE/stdin"; fi
if [[ "$*" == *"/reviews"* ]]; then
  n="$(cat "$GH_STATE/reviews" 2>/dev/null || echo 0)"; echo $((n + 1)) > "$GH_STATE/reviews"
  [ "$n" -lt "${FAKE_REVIEW_FAILS:-0}" ] && exit 1
fi
[[ "$*" == *"/check-runs"* && -n "${FAKE_CHECK_FAILS:-}" ]] && exit 1
exit 0
"""


def check(condition, message):
    if not condition:
        failures.append(message)


def run(verdict, payload=True, check_file=True, review_fails=0, check_fails=False, reset=False, number="7"):
    with tempfile.TemporaryDirectory() as tmp:
        tmp = Path(tmp)
        (tmp / "bin").mkdir()
        gh = tmp / "bin/gh"
        gh.write_text(FAKE_GH)
        gh.chmod(0o755)
        (tmp / "state").mkdir()
        (tmp / "comment.md").write_text("comment body\n")
        (tmp / "payload.json").write_text('{"commit_id":"x","event":"COMMENT","body":"b","comments":[{"path":"a","line":1,"side":"RIGHT","body":"c"}]}' if payload else "")
        (tmp / "check.json").write_text('{"name":"nightly-ai-review"}' if check_file else "")
        env = {
            **os.environ, "PATH": f"{tmp / 'bin'}:{os.environ['PATH']}", "GH_LOG": str(tmp / "log"), "GH_STATE": str(tmp / "state"),
            "GITHUB_REPOSITORY": "o/r", "PR_NUMBER": number, "VERDICT": verdict,
            "COMMENT_FILE": str(tmp / "comment.md"), "PAYLOAD_FILE": str(tmp / "payload.json"), "CHECK_FILE": str(tmp / "check.json"),
            "FAKE_REVIEW_FAILS": str(review_fails), "FAKE_CHECK_FAILS": "1" if check_fails else "",
            "RESET_LABELS": "1" if reset else "0",
        }
        result = subprocess.run(["bash", str(SCRIPT)], env=env, capture_output=True, text=True)
        log = (tmp / "log").read_text().splitlines() if (tmp / "log").exists() else []
        run.stdin = (tmp / "state/stdin").read_text() if (tmp / "state/stdin").exists() else ""
        return result, log


r, log = run("approve")
check(r.returncode == 0, "approve succeeds")
check(any("--add-label ai-approved" in line for line in log) and not any("needs-maintainer" in line for line in log), "approve adds only ai-approved")
check(sum("/reviews" in line for line in log) == 1 and any("repos/o/r/pulls/7/reviews" in line and "--input" in line for line in log), "one review posted to the PR")
check(any("repos/o/r/check-runs" in line for line in log), "check run created")

r, log = run("reject")
check(any("--add-label ai-rejected" in line for line in log) and any("--add-label needs-maintainer" in line for line in log), "reject adds ai-rejected and needs-maintainer")

for verdict in ("uncertain", "error"):
    r, log = run(verdict)
    check(any("--add-label needs-maintainer" in line for line in log) and not any("ai-approved" in line or "ai-rejected" in line for line in log), f"{verdict} only needs a maintainer")

r, log = run("reject", review_fails=1)
check(r.returncode == 0 and sum("/reviews" in line for line in log) == 2, "a refused inline review is retried once")
check(run.stdin and '"comments"' not in run.stdin and '"body": "b"' in run.stdin, "the retry drops the inline comments but keeps the body")
check(not any(line.startswith("pr comment") for line in log), "no plain comment when the retry works")

r, log = run("reject", review_fails=2)
check(r.returncode == 0 and any(line.startswith("pr comment 7") and "--body-file" in line for line in log), "last resort is a plain comment")

r, log = run("error", payload=False, check_file=False)
check(r.returncode == 0 and any(line.startswith("pr comment 7") for line in log) and not any("/reviews" in line for line in log), "no payload means a plain comment")
check(not any("check-runs" in line for line in log), "no check payload means no check run")

r, log = run("approve", check_fails=True)
check(r.returncode == 0 and "Could not create the nightly-ai-review check run" in r.stdout, "a failed check run only warns")

r, log = run("approve", reset=True)
resets = [line for line in log if "--remove-label" in line]
check(len(resets) == 3 and all(label in " ".join(resets) for label in ("ai-approved", "ai-rejected", "needs-maintainer")), "a re-review clears the earlier labels first")
check(log.index(resets[-1]) < next(i for i, line in enumerate(log) if "--add-label" in line), "labels are cleared before the new one is added")

for bad in ("", "7; rm -rf /", "abc", "12345678"):
    r, log = run("approve", number=bad)
    check(r.returncode != 0 and not log, f"invalid PR number {bad!r} is rejected before any call")

if failures:
    raise SystemExit("Publish review violations:\n- " + "\n- ".join(failures))
print("PASS: publish review script contract")
