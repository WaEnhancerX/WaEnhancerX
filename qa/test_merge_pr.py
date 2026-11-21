#!/usr/bin/env python3
"""Contract tests for scripts/release/merge_pr.py (no network)."""

import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts/release"))

import merge_pr  # noqa: E402

SHA = "a" * 40
CHECK = "QA"
failures = []


def check(condition, message):
    if not condition:
        failures.append(message)


class FakeApi:
    def __init__(self, pull=None, runs=None, merge_error=None, pulls=None):
        self.pull = {"state": "open", "draft": False, "base": {"ref": "nightly"},
                     "head": {"sha": SHA}, "mergeable": True, "title": "Fix\x00 thing"}
        self.pull.update(pull or {})
        self.runs = runs if runs is not None else [[{"id": 1, "name": CHECK, "status": "completed", "conclusion": "success"}]]
        self.merge_error = merge_error
        self.pulls = pulls
        self.merged = None
        self.polls = 0

    def __call__(self, method, path, payload=None):
        if method == "GET" and "/check-runs" in path:
            index = min(self.polls, len(self.runs) - 1)
            self.polls += 1
            return {"check_runs": self.runs[index]}
        if method == "GET":
            if self.pulls:
                return self.pulls.pop(0)
            return self.pull
        if method == "PUT":
            if self.merge_error is not None:
                raise merge_pr.ApiError(self.merge_error)
            self.merged = payload
            return {"merged": True}
        raise AssertionError(path)


def run(api, wait=100, ticks=None):
    t = {"now": 0}
    def clock():
        return t["now"]
    def sleep(seconds):
        t["now"] += max(seconds, 40)
    return merge_pr.merge_reviewed(api, "o/r", 7, SHA, CHECK, wait, sleep=sleep, clock=clock)


api = FakeApi()
check(run(api) == ("merged", "merged"), "happy path merges")
check(api.merged["sha"] == SHA and api.merged["merge_method"] == "squash", "merge pinned to reviewed sha")
check("\x00" not in api.merged["commit_title"] and api.merged["commit_title"].endswith("(#7)"), "commit title sanitized")

check(run(FakeApi({"state": "closed"})) == ("held", "not_open"), "closed PR")
check(run(FakeApi({"base": {"ref": "main"}})) == ("held", "wrong_base"), "wrong base")
check(run(FakeApi({"draft": True})) == ("held", "draft"), "draft")
check(run(FakeApi({"head": {"sha": "b" * 40}})) == ("held", "head_changed"), "head changed before QA")

# New commit pushed while waiting for QA: the second PR read differs.
moved = FakeApi(pulls=[
    {"state": "open", "draft": False, "base": {"ref": "nightly"}, "head": {"sha": SHA}, "title": "t"},
    {"state": "open", "draft": False, "base": {"ref": "nightly"}, "head": {"sha": "c" * 40}, "mergeable": True, "title": "t"},
])
check(run(moved) == ("held", "head_changed"), "head moved during QA")
check(moved.merged is None, "nothing merged when head moved")

failed = [[{"id": 1, "name": CHECK, "status": "completed", "conclusion": "failure"}]]
check(run(FakeApi(runs=failed)) == ("held", "qa_failed"), "QA failure")
other = [[{"id": 1, "name": "other", "status": "completed", "conclusion": "success"}]]
check(run(FakeApi(runs=other)) == ("held", "qa_timeout"), "required check never appears")
pending_then_ok = [
    [{"id": 1, "name": CHECK, "status": "in_progress", "conclusion": None}],
    [{"id": 1, "name": CHECK, "status": "completed", "conclusion": "success"}],
]
check(run(FakeApi(runs=pending_then_ok)) == ("merged", "merged"), "waits for pending QA")
rerun = [[{"id": 1, "name": CHECK, "status": "completed", "conclusion": "failure"},
          {"id": 2, "name": CHECK, "status": "completed", "conclusion": "success"}]]
check(run(FakeApi(runs=rerun)) == ("merged", "merged"), "newest run wins after a rerun")
rerun_bad = [[{"id": 1, "name": CHECK, "status": "completed", "conclusion": "success"},
              {"id": 2, "name": CHECK, "status": "completed", "conclusion": "cancelled"}]]
check(run(FakeApi(runs=rerun_bad)) == ("held", "qa_failed"), "newest cancelled run blocks")

check(run(FakeApi({"mergeable": False})) == ("held", "conflict"), "conflicting PR")
check(run(FakeApi({"mergeable": None})) == ("held", "conflict"), "mergeability never resolves")
check(run(FakeApi(merge_error=409)) == ("held", "head_changed"), "GitHub 409 means head moved")
check(run(FakeApi(merge_error=405)) == ("held", "merge_rejected"), "GitHub 405 means merge rejected")
check(run(FakeApi(merge_error=0)) == ("held", "merge_rejected"), "transport error on merge is held")

# skip_qa drops only the QA wait
nqa = FakeApi(runs=[[{"id": 9, "name": "other", "status": "completed", "conclusion": "success"}]])
check(merge_pr.merge_reviewed(nqa, "o/r", 7, SHA, CHECK, 0, skip_qa=True) == ("merged", "merged"), "skip_qa merges without the QA check")
check(nqa.polls == 0, "skip_qa does not read check runs")
check(merge_pr.merge_reviewed(FakeApi(runs=[[{"id": 9, "name": CHECK, "status": "completed", "conclusion": "failure"}]]), "o/r", 7, SHA, CHECK, 0, skip_qa=True) == ("merged", "merged"), "skip_qa merges over a failed QA check")
check(merge_pr.merge_reviewed(FakeApi({"head": {"sha": "b" * 40}}), "o/r", 7, SHA, CHECK, 0, skip_qa=True) == ("held", "head_changed"), "skip_qa still requires the reviewed commit")
check(merge_pr.merge_reviewed(FakeApi({"mergeable": False}), "o/r", 7, SHA, CHECK, 0, skip_qa=True) == ("held", "conflict"), "skip_qa still requires a clean merge")
check(merge_pr.merge_reviewed(FakeApi({"draft": True}), "o/r", 7, SHA, CHECK, 0, skip_qa=True) == ("held", "draft"), "skip_qa still refuses a draft")
check(merge_pr.merge_reviewed(FakeApi(runs=[[{"id": 9, "name": "other", "status": "completed", "conclusion": "success"}]]), "o/r", 7, SHA, CHECK, 0) == ("held", "qa_timeout"), "without skip_qa the QA check is still required")

for bad_sha in ("", "abc", "G" * 40, None):
    check(merge_pr.merge_reviewed(FakeApi(), "o/r", 7, bad_sha, CHECK, 10) == ("held", "bad_input"), f"bad sha {bad_sha!r}")

def boom(method, path, payload=None):
    raise merge_pr.ApiError(500)
check(merge_pr.merge_reviewed(boom, "o/r", 7, SHA, CHECK, 10) == ("held", "api_error"), "API error is held")

if failures:
    raise SystemExit("Merge violations:\n- " + "\n- ".join(failures))
print("PASS: nightly SHA-pinned merge contract")
