#!/usr/bin/env python3
"""Contract test for scripts/release/publish_review.sh using a fake gh (no network)."""

import json
import os
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "scripts/release/publish_review.sh"
failures = []

FAKE_GH = """#!/usr/bin/env bash
echo "${GH_TOKEN}|$*" >> "$GH_LOG"
if [[ "$*" == "api user"* ]]; then
  [ "$GH_TOKEN" = "bad-token" ] && exit 1
  echo "waex-bot"; exit 0
fi
if [[ "$*" == *"--input -"* ]]; then printf '%s' "$(cat)" > "$GH_STATE/stdin"; fi
if [[ "$*" == *"/reviews"* && "$*" == *"--method POST"* ]]; then
  n="$(cat "$GH_STATE/reviews" 2>/dev/null || echo 0)"; echo $((n + 1)) > "$GH_STATE/reviews"
  [ "$n" -lt "${FAKE_REVIEW_FAILS:-0}" ] && exit 1
  exit 0
fi
if [[ "$*" == *"/reviews"* && "$*" == *"--paginate"* ]]; then cat "$GH_STATE/existing.json" 2>/dev/null || echo '[]'; exit 0; fi
[[ "$*" == *"/check-runs"* && -n "${FAKE_CHECK_FAILS:-}" ]] && exit 1
exit 0
"""


def check(condition, message):
    if not condition:
        failures.append(message)


def run(verdict, payload=True, check_file=True, review_fails=0, check_fails=False, reset=False, number="7", reviewer=None, existing=None):
    with tempfile.TemporaryDirectory() as tmp:
        tmp = Path(tmp)
        (tmp / "bin").mkdir()
        gh = tmp / "bin/gh"
        gh.write_text(FAKE_GH)
        gh.chmod(0o755)
        (tmp / "state").mkdir()
        if existing is not None:
            (tmp / "state/existing.json").write_text(json.dumps(existing))
        (tmp / "comment.md").write_text("comment body\n")
        (tmp / "payload.json").write_text('{"commit_id":"x","event":"COMMENT","body":"b","comments":[{"path":"a","line":1,"side":"RIGHT","body":"c"}]}' if payload else "")
        (tmp / "check.json").write_text('{"name":"nightly-ai-review"}' if check_file else "")
        env = {
            **os.environ, "PATH": f"{tmp / 'bin'}:{os.environ['PATH']}", "GH_LOG": str(tmp / "log"), "GH_STATE": str(tmp / "state"),
            "GH_TOKEN": "actions-token", "GITHUB_REPOSITORY": "o/r", "PR_NUMBER": number, "VERDICT": verdict,
            "COMMENT_FILE": str(tmp / "comment.md"), "PAYLOAD_FILE": str(tmp / "payload.json"), "CHECK_FILE": str(tmp / "check.json"),
            "FAKE_REVIEW_FAILS": str(review_fails), "FAKE_CHECK_FAILS": "1" if check_fails else "",
            "RESET_LABELS": "1" if reset else "0",
        }
        env.pop("REVIEW_TOKEN", None)
        if reviewer:
            env["REVIEW_TOKEN"] = reviewer
        result = subprocess.run(["bash", str(SCRIPT)], env=env, capture_output=True, text=True)
        calls = [tuple(line.split("|", 1)) for line in (tmp / "log").read_text().splitlines()] if (tmp / "log").exists() else []
        run.stdin = (tmp / "state/stdin").read_text() if (tmp / "state/stdin").exists() else ""
        return result, calls


def args(calls):
    return [a for _, a in calls]


def posts(calls):
    return [(t, a) for t, a in calls if "/reviews" in a and "--method POST" in a]


# ---- verdict handling (no reviewer account: everything is posted by GitHub Actions)
r, calls = run("approve")
check(r.returncode == 0, "approve succeeds")
check(any("--add-label ai-approved" in a for a in args(calls)) and not any("needs-maintainer" in a for a in args(calls)), "approve adds only ai-approved")
check(len(posts(calls)) == 1 and "repos/o/r/pulls/7/reviews" in posts(calls)[0][1] and "--input" in posts(calls)[0][1], "one review posted to the PR")
check(any("repos/o/r/check-runs" in a for a in args(calls)), "check run created")
check(not any(a.startswith("api user") for a in args(calls)), "no identity lookup without a reviewer token")
check(all(t == "actions-token" for t, _ in calls), "everything uses the workflow token without a reviewer token")

r, calls = run("reject")
check(any("--add-label ai-rejected" in a for a in args(calls)) and any("--add-label needs-maintainer" in a for a in args(calls)), "reject adds ai-rejected and needs-maintainer")
for verdict in ("uncertain", "error"):
    r, calls = run(verdict)
    check(any("--add-label needs-maintainer" in a for a in args(calls)) and not any("ai-approved" in a or "ai-rejected" in a for a in args(calls)), f"{verdict} only needs a maintainer")

# ---- fallbacks
r, calls = run("reject", review_fails=1)
check(r.returncode == 0 and len(posts(calls)) == 2, "a refused inline review is retried once")
check(run.stdin and '"comments"' not in run.stdin and '"body": "b"' in run.stdin, "the retry drops the inline comments but keeps the body")
check(not any(a.startswith("pr comment") for a in args(calls)), "no plain comment when the retry works")
r, calls = run("reject", review_fails=2)
check(r.returncode == 0 and any(a.startswith("pr comment 7") and "--body-file" in a for a in args(calls)), "last resort is a plain comment")
r, calls = run("error", payload=False, check_file=False)
check(r.returncode == 0 and any(a.startswith("pr comment 7") for a in args(calls)) and not posts(calls), "no payload means a plain comment")
check(not any("check-runs" in a for a in args(calls)), "no check payload means no check run")
r, calls = run("approve", check_fails=True)
check(r.returncode == 0 and "Could not create the nightly-ai-review check run" in r.stdout, "a failed check run only warns")

# ---- re-review resets labels first
r, calls = run("approve", reset=True)
resets = [a for a in args(calls) if "--remove-label" in a]
check(len(resets) == 3 and all(label in " ".join(resets) for label in ("ai-approved", "ai-rejected", "needs-maintainer")), "a re-review clears the earlier labels first")
check(args(calls).index(resets[-1]) < next(i for i, a in enumerate(args(calls)) if "--add-label" in a), "labels are cleared before the new one is added")

# ---- the review is posted as the reviewer account
r, calls = run("reject", reviewer="bot-token")
check(r.returncode == 0, "reject with a reviewer token succeeds")
check(posts(calls) and all(t == "bot-token" for t, _ in posts(calls)), "the pull-request review is posted with the reviewer token")
labels = [(t, a) for t, a in calls if "--add-label" in a]
check(labels and all(t == "actions-token" for t, _ in labels), "labels are still set with the workflow token")
checks = [(t, a) for t, a in calls if "/check-runs" in a]
check(checks and all(t == "actions-token" for t, _ in checks), "the check run is still created by GitHub Actions (only an App can write checks)")
check(any(t == "bot-token" and a.startswith("api user") for t, a in calls), "the reviewer identity is looked up once")

r, calls = run("error", payload=False, check_file=False, reviewer="bot-token")
check(any(t == "bot-token" and a.startswith("pr comment 7") for t, a in calls), "the fallback comment is posted as the reviewer too")
r, calls = run("reject", review_fails=2, reviewer="bot-token")
check(any(t == "bot-token" and a.startswith("pr comment 7") for t, a in calls), "the last-resort comment is posted as the reviewer")

r, calls = run("approve", reviewer="bad-token")
check(r.returncode == 0 and "reviewer token was rejected" in r.stdout, "a rejected reviewer token only warns")
check(len(posts(calls)) == 1 and posts(calls)[0][0] == "actions-token", "a rejected reviewer token falls back to GitHub Actions so the review is still posted")

# ---- stale "changes requested" reviews of the same reviewer are dismissed, nobody else's
existing = [
    {"id": 11, "state": "CHANGES_REQUESTED", "user": {"login": "waex-bot"}},
    {"id": 12, "state": "CHANGES_REQUESTED", "user": {"login": "TheKillerMax"}},
    {"id": 13, "state": "APPROVED", "user": {"login": "waex-bot"}},
    {"id": 14, "state": "COMMENTED", "user": {"login": "waex-bot"}},
]
r, calls = run("approve", reviewer="bot-token", existing=existing)
dismissed = [a for a in args(calls) if "/dismissals" in a]
check(len(dismissed) == 1 and "reviews/11/dismissals" in dismissed[0] and "--method PUT" in dismissed[0], "only the reviewer's own stale change request is dismissed")
check(all(t == "bot-token" for t, a in calls if "/dismissals" in a), "the dismissal is made as the reviewer")
check(args(calls).index(dismissed[0]) < args(calls).index(posts(calls)[0][1]), "the stale review is dismissed before the new one is posted")
existing_actions = [{"id": 21, "state": "CHANGES_REQUESTED", "user": {"login": "github-actions[bot]"}}, {"id": 22, "state": "CHANGES_REQUESTED", "user": {"login": "waex-bot"}}]
r, calls = run("approve", existing=existing_actions)
dismissed = [a for a in args(calls) if "/dismissals" in a]
check(len(dismissed) == 1 and "reviews/21/dismissals" in dismissed[0], "without a reviewer token the stale review of github-actions[bot] is the one dismissed")
r, calls = run("error", payload=False, check_file=False, reviewer="bot-token", existing=existing)
check(not any("/dismissals" in a for a in args(calls)), "nothing is dismissed when no new review is posted")

# ---- input validation
for bad in ("", "7; rm -rf /", "abc", "12345678"):
    r, calls = run("approve", number=bad)
    check(r.returncode != 0 and not calls, f"invalid PR number {bad!r} is rejected before any call")

if failures:
    raise SystemExit("Publish review violations:\n- " + "\n- ".join(failures))
print("PASS: publish review script contract")
