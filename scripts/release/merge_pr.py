#!/usr/bin/env python3
"""Merge a reviewed nightly PR, but only the exact commit that was reviewed.

Runs from the base branch only. It is reached after the gate and AI review both
approved. It then requires the public QA check for that exact head commit and
merges through the GitHub API with `sha=<reviewed commit>`, which GitHub rejects
if the PR head moved. Anything unexpected leaves the PR open for a maintainer.

Outputs (stdout and $GITHUB_OUTPUT): result=merged|held, reason, message.

Environment:
    GITHUB_REPOSITORY, PR_NUMBER, REVIEWED_SHA
    MERGE_TOKEN        GitHub App installation token (merge and bypass rights)
    REQUIRED_CHECK     check-run name that must succeed
                       (default "JDK 21 / Python 3.11 / Kotlin syntax")
    QA_WAIT_SECONDS    how long to wait for that check (default 1500)
"""

import json
import os
import re
import sys
import time
import urllib.error
import urllib.request

API = "https://api.github.com"
USER_AGENT = "WAEX-Nightly-Merge/1.0"
DEFAULT_CHECK = "JDK 21 / Python 3.11 / Kotlin syntax"
POLL_SECONDS = 30
MERGEABLE_RETRIES = 5
SHA_RE = re.compile(r"^[0-9a-f]{40}$")

REASONS = {
    "merged": "Merged the reviewed commit.",
    "bad_input": "The merge request was malformed.",
    "not_open": "The PR is no longer open.",
    "wrong_base": "The PR does not target the nightly branch.",
    "draft": "The PR is a draft.",
    "head_changed": "Rule R2: the PR head changed after review, so it was not merged. Open a new PR with the final commit.",
    "qa_failed": "Rule R13: the required QA check did not pass on the reviewed commit. Fix the failure and open a new PR.",
    "qa_timeout": "Rule R13: the required QA check did not finish in time, so a maintainer will take it from here.",
    "conflict": "The PR cannot be merged cleanly.",
    "merge_rejected": "GitHub rejected the merge.",
    "api_error": "GitHub could not be reached reliably.",
}


class ApiError(Exception):
    def __init__(self, status, body=None):
        super().__init__(f"HTTP {status}")
        self.status = status
        self.body = body


def github(method, path, token, payload=None, timeout=30):
    request = urllib.request.Request(
        API + path,
        data=json.dumps(payload).encode() if payload is not None else None,
        method=method,
        headers={
            "Authorization": f"Bearer {token}",
            "Accept": "application/vnd.github+json",
            "X-GitHub-Api-Version": "2022-11-28",
            "User-Agent": USER_AGENT,
            "Content-Type": "application/json",
        },
    )
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return json.loads(response.read() or b"{}")
    except urllib.error.HTTPError as error:
        raise ApiError(error.code)
    except (OSError, ValueError):
        raise ApiError(0)


def commit_title(title, number):
    clean = re.sub(r"[\x00-\x1f\x7f]", " ", title or "").strip()[:120]
    return f"{clean} (#{number})"


def qa_state(check_runs, name):
    """Return success, failed or pending for the newest run with this name."""
    runs = sorted((r for r in check_runs if r.get("name") == name), key=lambda r: r.get("id", 0))
    if not runs:
        return "pending"
    latest = runs[-1]
    if latest.get("status") != "completed":
        return "pending"
    return "success" if latest.get("conclusion") == "success" else "failed"


def merge_reviewed(api, repo, number, reviewed_sha, required_check, wait_seconds,
                   sleep=time.sleep, clock=time.monotonic):
    """Return (result, reason). `api(method, path, payload=None)` returns parsed JSON."""
    if not SHA_RE.match(reviewed_sha or ""):
        return "held", "bad_input"
    try:
        pull = api("GET", f"/repos/{repo}/pulls/{number}")
        if pull["state"] != "open":
            return "held", "not_open"
        if pull["base"]["ref"] != "nightly":
            return "held", "wrong_base"
        if pull["draft"]:
            return "held", "draft"
        if pull["head"]["sha"] != reviewed_sha:
            return "held", "head_changed"

        deadline = clock() + wait_seconds
        while True:
            runs = api("GET", f"/repos/{repo}/commits/{reviewed_sha}/check-runs?per_page=100")["check_runs"]
            state = qa_state(runs, required_check)
            if state == "success":
                break
            if state == "failed":
                return "held", "qa_failed"
            if clock() >= deadline:
                return "held", "qa_timeout"
            sleep(POLL_SECONDS)

        for attempt in range(MERGEABLE_RETRIES):
            pull = api("GET", f"/repos/{repo}/pulls/{number}")
            if pull["head"]["sha"] != reviewed_sha:
                return "held", "head_changed"
            if pull.get("mergeable") is True:
                break
            if pull.get("mergeable") is False:
                return "held", "conflict"
            sleep(2)
        else:
            return "held", "conflict"

        try:
            api("PUT", f"/repos/{repo}/pulls/{number}/merge", {
                "sha": reviewed_sha,
                "merge_method": "squash",
                "commit_title": commit_title(pull.get("title"), number),
                "commit_message": "",
            })
        except ApiError as error:
            return "held", "head_changed" if error.status == 409 else "merge_rejected"
    except (ApiError, KeyError, TypeError):
        return "held", "api_error"
    return "merged", "merged"


def main():
    env = os.environ
    try:
        repo, token = env["GITHUB_REPOSITORY"], env["MERGE_TOKEN"]
        number, sha = int(env["PR_NUMBER"]), env["REVIEWED_SHA"]
        wait = int(env.get("QA_WAIT_SECONDS", "1500"))
    except (KeyError, ValueError):
        result, reason = "held", "bad_input"
    else:
        result, reason = merge_reviewed(
            lambda method, path, payload=None: github(method, path, token, payload),
            repo, number, sha, env.get("REQUIRED_CHECK") or DEFAULT_CHECK, wait,
        )
    message = REASONS[reason]
    print(f"result={result}\nreason={reason}\nmessage={message}")
    if env.get("GITHUB_OUTPUT"):
        with open(env["GITHUB_OUTPUT"], "a", encoding="utf-8") as handle:
            handle.write(f"result={result}\nreason={reason}\nmessage={message}\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
