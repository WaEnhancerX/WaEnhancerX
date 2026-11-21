#!/usr/bin/env python3
"""Merge one nightly PR on a maintainer's request, but only an AI-approved one.

Started by the nightly-manual-merge workflow (from the admin bot or the Actions tab). The
request names the commit the maintainer looked at. Nothing is trusted from the request itself:
the PR must still be open on nightly with that exact head commit, the `nightly-ai-review` check
run created by GitHub Actions for that exact commit must be an approval, and merge_pr.py then
requires the QA check and a clean merge before the SHA-pinned merge. A new commit makes the
approval stale because the check run belongs to the old commit.

A maintainer can explicitly ask to skip the QA check (SKIP_QA=true), for example while the QA
baseline is red. That only drops the wait for the QA check: the approval of the exact commit, the
open non-draft PR on nightly, the clean merge and the SHA pin are all still required. It works only
when the repository variable WAEX_ALLOW_QA_OVERRIDE is true (ALLOW_QA_OVERRIDE here), the merge is
labelled qa-skipped, and the PR comment says that QA was skipped.

Outputs (stdout and $GITHUB_OUTPUT): result=merged|held, reason, message.

Environment:
    GITHUB_REPOSITORY, PR_NUMBER, EXPECTED_SHA, MERGE_TOKEN (GitHub App token), REQUESTED_BY
    REQUIRED_CHECK (QA check name), QA_WAIT_SECONDS (default 120)
    SKIP_QA (the literal true skips the QA check), ALLOW_QA_OVERRIDE (the literal true permits it)
    MERGE_COMMENT_FILE (default merge-comment.md), GITHUB_OUTPUT (optional)
"""

import json
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import merge_pr  # noqa: E402

CHECK_NAME = "nightly-ai-review"
MARKER_RE = re.compile(r"<!-- waex-ai-review (\{.*?\}) -->")
REQUESTER_RE = re.compile(r"^[A-Za-z0-9_@.\- ]{1,40}$")

REASONS = dict(merge_pr.REASONS)
REASONS.update(
    {
        "no_review": "There is no completed AI review for this exact commit, so it cannot be merged from here.",
        "not_approved": "The AI review of this commit is not an approval (rejected, uncertain or failed).",
        "qa_override_disabled": "Merging without the QA check is switched off. Set the repository variable WAEX_ALLOW_QA_OVERRIDE to true to allow it.",
    }
)


def latest_review_check(check_runs, sha):
    """Newest nightly-ai-review run for this commit that GitHub Actions created, else None."""
    runs = [
        run
        for run in check_runs
        if run.get("name") == CHECK_NAME
        and run.get("head_sha") == sha
        and (run.get("app") or {}).get("slug") == "github-actions"
    ]
    return max(runs, key=lambda run: run.get("id", 0)) if runs else None


def parse_marker(check):
    text = ((check or {}).get("output") or {}).get("summary") or ""
    match = MARKER_RE.search(text)
    if not match:
        return None
    try:
        data = json.loads(match.group(1))
    except ValueError:
        return None
    return data if isinstance(data, dict) else None


def verify_approved(api, repo, number, expected_sha):
    """Return (ok, reason, confidence). `api(method, path, payload=None)` returns parsed JSON."""
    try:
        pull = api("GET", f"/repos/{repo}/pulls/{number}")
        if pull["state"] != "open":
            return False, "not_open", None
        if pull["base"]["ref"] != "nightly":
            return False, "wrong_base", None
        if pull["draft"]:
            return False, "draft", None
        if pull["head"]["sha"] != expected_sha:
            return False, "head_changed", None
        runs = api("GET", f"/repos/{repo}/commits/{expected_sha}/check-runs?per_page=100")["check_runs"]
    except (merge_pr.ApiError, KeyError, TypeError):
        return False, "api_error", None
    check = latest_review_check(runs, expected_sha)
    if check is None or check.get("status") != "completed":
        return False, "no_review", None
    marker = parse_marker(check)
    if (
        check.get("conclusion") != "success"
        or not marker
        or marker.get("verdict") != "approve"
        or marker.get("sha") != expected_sha
    ):
        return False, "not_approved", None
    confidence = marker.get("confidence")
    return True, "ok", confidence if isinstance(confidence, int) and not isinstance(confidence, bool) else None


def sync_labels(api, repo, number, skip_qa=False):
    """Make the labels match the approval (the release workflow reads them after the merge)."""
    for stale in ("needs-maintainer", "ai-rejected"):
        try:
            api("DELETE", f"/repos/{repo}/issues/{number}/labels/{stale}")
        except merge_pr.ApiError as error:
            if error.status != 404:
                raise
    api("POST", f"/repos/{repo}/issues/{number}/labels", {"labels": ["ai-approved"] + (["qa-skipped"] if skip_qa else [])})


def request_manual_merge(api, repo, number, expected_sha, required_check, wait_seconds,
                         skip_qa=False, allow_qa_override=False, **timing):
    """Return (result, reason, confidence)."""
    if not merge_pr.SHA_RE.match(expected_sha or ""):
        return "held", "bad_input", None
    if skip_qa and not allow_qa_override:
        return "held", "qa_override_disabled", None
    ok, reason, confidence = verify_approved(api, repo, number, expected_sha)
    if not ok:
        return "held", reason, None
    try:
        sync_labels(api, repo, number, skip_qa)
    except (merge_pr.ApiError, KeyError, TypeError):
        return "held", "api_error", None
    result, reason = merge_pr.merge_reviewed(api, repo, number, expected_sha, required_check, wait_seconds, skip_qa=skip_qa, **timing)
    return result, reason, confidence


def audit_comment(requested_by, sha, confidence, skip_qa=False):
    who = requested_by if REQUESTER_RE.match(requested_by or "") else "a maintainer"
    score = f" (confidence {confidence}%)" if confidence is not None else ""
    qa = (
        "**The QA check was skipped at the maintainer's explicit request.** The merge was pinned to that commit."
        if skip_qa
        else "The QA check passed and the merge was pinned to that commit."
    )
    return (
        f"Merged at the request of {who.replace('@', '@' + chr(0x200b))} after the AI approval{score} "
        f"of commit `{sha[:12]}`. {qa}\n"
    )


def main():
    env = os.environ
    try:
        repo, token = env["GITHUB_REPOSITORY"], env["MERGE_TOKEN"]
        number, sha = int(env["PR_NUMBER"]), env["EXPECTED_SHA"].strip().lower()
        wait = int(env.get("QA_WAIT_SECONDS", "120"))
    except (KeyError, ValueError):
        result, reason, confidence, number, sha = "held", "bad_input", None, 0, ""
        skip_qa = False
    else:
        # Only the exact word true counts, for the request and for the permission.
        skip_qa = env.get("SKIP_QA", "").strip().lower() == "true"
        allow = env.get("ALLOW_QA_OVERRIDE", "").strip().lower() == "true"
        result, reason, confidence = request_manual_merge(
            lambda method, path, payload=None: merge_pr.github(method, path, token, payload),
            repo, number, sha, env.get("REQUIRED_CHECK") or merge_pr.DEFAULT_CHECK, wait,
            skip_qa=skip_qa, allow_qa_override=allow,
        )
    message = REASONS[reason]
    print(f"result={result}\nreason={reason}\nmessage={message}")
    if env.get("GITHUB_OUTPUT"):
        with open(env["GITHUB_OUTPUT"], "a", encoding="utf-8") as handle:
            handle.write(f"result={result}\nreason={reason}\nmessage={message}\n")
    comment = audit_comment(env.get("REQUESTED_BY", ""), sha, confidence, skip_qa) if result == "merged" else ""
    with open(env.get("MERGE_COMMENT_FILE", "merge-comment.md"), "w", encoding="utf-8") as handle:
        handle.write(comment)
    return 0 if result == "merged" else 1


if __name__ == "__main__":
    sys.exit(main())
