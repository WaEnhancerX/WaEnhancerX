#!/usr/bin/env python3
"""Contract tests for scripts/release/manual_merge.py (no network)."""

import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts/release"))

import manual_merge  # noqa: E402
import merge_pr  # noqa: E402

SHA = "a" * 40
OTHER = "b" * 40
QA = "QA"
failures = []


def check(condition, message):
    if not condition:
        failures.append(message)


def marker(verdict="approve", confidence=91, sha=SHA):
    return f'text\n<!-- waex-ai-review {json.dumps({"v": 1, "verdict": verdict, "confidence": confidence, "sha": sha}, separators=(",", ":"))} -->'


def review_run(run_id=10, conclusion="success", status="completed", verdict="approve", sha=SHA, app="github-actions", name=manual_merge.CHECK_NAME, head=SHA, confidence=91):
    return {"id": run_id, "name": name, "head_sha": head, "status": status, "conclusion": conclusion,
            "app": {"slug": app}, "output": {"summary": marker(verdict, confidence, sha)}}


def qa_run(conclusion="success"):
    return {"id": 99, "name": QA, "status": "completed", "conclusion": conclusion}


class FakeApi:
    def __init__(self, runs=None, pull=None, label_error=None, merge_error=None):
        self.pull = {"state": "open", "draft": False, "base": {"ref": "nightly"}, "head": {"sha": SHA},
                     "mergeable": True, "title": "Fix thing"}
        self.pull.update(pull or {})
        self.runs = runs if runs is not None else [review_run(), qa_run()]
        self.label_error = label_error
        self.merge_error = merge_error
        self.calls = []

    def __call__(self, method, path, payload=None):
        self.calls.append((method, path))
        if method == "GET" and "/check-runs" in path:
            return {"check_runs": self.runs}
        if method == "GET":
            return self.pull
        if method == "DELETE":
            if self.label_error is not None:
                raise merge_pr.ApiError(self.label_error)
            return {}
        if method == "POST":
            return {}
        if method == "PUT":
            if self.merge_error is not None:
                raise merge_pr.ApiError(self.merge_error)
            self.merged = payload
            return {"merged": True}
        raise AssertionError((method, path))


def run(api, sha=SHA):
    return manual_merge.request_manual_merge(api, "o/r", 7, sha, QA, 0, sleep=lambda s: None, clock=lambda: 0)


# ---- the happy path
api = FakeApi()
check(run(api) == ("merged", "merged", 91), "approved, QA green and mergeable merges")
check(api.merged["sha"] == SHA, "merge is pinned to the approved commit")
check(("DELETE", "/repos/o/r/issues/7/labels/needs-maintainer") in api.calls and ("POST", "/repos/o/r/issues/7/labels") in api.calls, "labels are synced for the release workflow")

# ---- only an approval for this exact commit counts
check(run(FakeApi(runs=[qa_run()]))[:2] == ("held", "no_review"), "no review check run")
check(run(FakeApi(runs=[review_run(status="in_progress", conclusion=None), qa_run()]))[:2] == ("held", "no_review"), "unfinished review")
check(run(FakeApi(runs=[review_run(conclusion="failure", verdict="reject"), qa_run()]))[:2] == ("held", "not_approved"), "rejected review")
check(run(FakeApi(runs=[review_run(conclusion="neutral", verdict="uncertain"), qa_run()]))[:2] == ("held", "not_approved"), "uncertain review")
check(run(FakeApi(runs=[review_run(app="evil-app"), qa_run()]))[:2] == ("held", "no_review"), "a check run from another app is ignored")
check(run(FakeApi(runs=[review_run(head=OTHER), qa_run()]))[:2] == ("held", "no_review"), "a review of another commit is ignored")
check(run(FakeApi(runs=[review_run(sha=OTHER), qa_run()]))[:2] == ("held", "not_approved"), "marker for another commit is rejected")
check(run(FakeApi(runs=[review_run(verdict="reject"), qa_run()]))[:2] == ("held", "not_approved"), "success conclusion with a reject marker is rejected")
nomark = review_run(); nomark["output"]["summary"] = "approved, trust me"
check(run(FakeApi(runs=[nomark, qa_run()]))[:2] == ("held", "not_approved"), "approval without the marker is rejected")
check(run(FakeApi(runs=[review_run(10, "success"), review_run(11, "failure", verdict="reject"), qa_run()]))[:2] == ("held", "not_approved"), "the newest review wins over an older approval")
check(run(FakeApi(runs=[review_run(10, "failure", verdict="reject"), review_run(11, "success"), qa_run()]))[0] == "merged", "a newer approval wins over an older rejection")

# ---- the PR must still be the one that was approved
check(run(FakeApi(pull={"head": {"sha": OTHER}}))[:2] == ("held", "head_changed"), "a new commit makes the approval stale")
check(run(FakeApi(pull={"state": "closed"}))[:2] == ("held", "not_open"), "closed PR")
check(run(FakeApi(pull={"base": {"ref": "main"}}))[:2] == ("held", "wrong_base"), "wrong base")
check(run(FakeApi(pull={"draft": True}))[:2] == ("held", "draft"), "draft")
for bad in ("", "abc", "G" * 40, None):
    check(run(FakeApi(), sha=bad)[:2] == ("held", "bad_input"), f"bad sha {bad!r}")

# ---- QA and mergeability still apply
check(run(FakeApi(runs=[review_run(), qa_run("failure")]))[:2] == ("held", "qa_failed"), "QA failure blocks the merge")
check(run(FakeApi(runs=[review_run()]))[:2] == ("held", "qa_timeout"), "QA that never appears blocks the merge")
check(run(FakeApi(pull={"mergeable": False}))[:2] == ("held", "conflict"), "conflicting PR is not merged")
check(run(FakeApi(merge_error=409))[:2] == ("held", "head_changed"), "GitHub 409 means the head moved")
check(run(FakeApi(merge_error=405))[:2] == ("held", "merge_rejected"), "GitHub 405 means the merge was rejected")
check(run(FakeApi(label_error=404))[0] == "merged", "a missing stale label is fine")
check(run(FakeApi(label_error=500))[:2] == ("held", "api_error"), "a label API failure holds the merge")


def boom(method, path, payload=None):
    raise merge_pr.ApiError(500)


check(manual_merge.request_manual_merge(boom, "o/r", 7, SHA, QA, 0)[:2] == ("held", "api_error"), "API error is held")
unmerged = FakeApi(runs=[review_run(conclusion="failure", verdict="reject")])
run(unmerged)
check(not hasattr(unmerged, "merged") and not any(c[0] == "POST" for c in unmerged.calls), "a held request changes nothing")

# ---- audit comment and message table
comment = manual_merge.audit_comment("mubashar_dev", SHA, 91)
check("mubashar_dev" in comment and "aaaaaaaaaaaa" in comment and "91%" in comment, "audit comment names who, the commit and the confidence")
check("@octocat" not in manual_merge.audit_comment("@octocat", SHA, None) and "a maintainer" in manual_merge.audit_comment("evil\nname", SHA, None), "audit comment is sanitised")
check(all(key in manual_merge.REASONS for key in ("no_review", "not_approved", "merged", "qa_failed", "head_changed")), "reason table is complete")

if failures:
    raise SystemExit("Manual merge violations:\n- " + "\n- ".join(failures))
print("PASS: nightly manual merge contract")
