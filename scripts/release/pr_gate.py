#!/usr/bin/env python3
"""Deterministic gate for pull requests that target `nightly`.

Runs from the base branch only (pull_request_target); it never checks out or
executes pull-request code. It decides one of:

    proceed     -> the PR may continue to automated QA and AI review
    maintainer  -> the PR waits for a human maintainer; no AI review happens

Every uncertainty (allowlist API failure, malformed response, incomplete file
list, oversized diff, protected path) resolves to `maintainer` (fail closed).
Reasons come from a fixed table so no pull-request text is echoed into comments.

Environment:
    GITHUB_REPOSITORY, GITHUB_TOKEN, GITHUB_OUTPUT (optional)
    PR_NUMBER, PR_AUTHOR_ID, PR_AUTHOR_TYPE, PR_BASE_REF, PR_DRAFT,
    PR_CHANGED_FILES, PR_ADDITIONS, PR_DELETIONS
    CONTRIBUTORS_API_URL
    CONTRIBUTORS_API_HMAC_KEY (optional; verifies X-WAEX-Timestamp/-Signature)
    PROTECTED_PATHS_FILE (default .github/nightly-protected-paths.json)
"""

import hashlib
import hmac
import json
import os
import re
import sys
import time
import urllib.error
import urllib.request

API_TIMEOUT_SECONDS = 10
API_ATTEMPTS = 3
SIGNATURE_MAX_AGE_SECONDS = 300
FILES_PAGE_SIZE = 100

RULES_DOC = "docs/NIGHTLY_CONTRIBUTOR_RULES.md"
RESUBMIT = (
    " If you cannot wait for a maintainer, close this PR, fix the problem and open a new PR against "
    f"`nightly` that follows every rule in `{RULES_DOC}`; later commits on this PR are not reviewed."
)

REASONS = {
    "ok": "Automated checks passed.",
    "wrong_base": "Rule R2: the PR must target the nightly branch." + RESUBMIT,
    "draft": "Rule R2: the PR was opened as a draft, and drafts are not reviewed." + RESUBMIT,
    "bot_author": "Rule R1: bot accounts are not eligible for automated review.",
    "bad_author": "Rule R1: the author could not be identified.",
    "allowlist_unavailable": "The contributor allowlist could not be verified, so a maintainer will review this PR.",
    "not_allowlisted": "Rule R1: the author is not on the contributor allowlist, so a maintainer will review this PR when they can.",
    "too_many_files": "Rule R4: the PR changes more than the automatic limit of files. Split it into smaller PRs." + RESUBMIT,
    "too_many_lines": "Rule R4: the PR changes more than the automatic limit of lines. Split it into smaller PRs." + RESUBMIT,
    "files_unavailable": "The changed-file list could not be fetched completely, so a maintainer will review this PR.",
    "no_text_diff": "Rule R7: a changed file has no reviewable text diff (binary or too large). Send it as a separate PR for maintainer review.",
    "protected_path": "Rule R5: the PR touches files that always need maintainer review. Move those changes into a separate PR.",
    "config_error": "The gate configuration could not be loaded, so a maintainer will review this PR.",
}


class GateError(Exception):
    pass


def glob_to_regex(pattern):
    out = []
    i = 0
    while i < len(pattern):
        char = pattern[i]
        if pattern.startswith("**/", i):
            out.append("(?:.*/)?")
            i += 3
            continue
        if pattern.startswith("**", i):
            out.append(".*")
            i += 2
            continue
        if char == "*":
            out.append("[^/]*")
        elif char == "?":
            out.append("[^/]")
        else:
            out.append(re.escape(char))
        i += 1
    return re.compile("^" + "".join(out) + "$", re.IGNORECASE)


def protected_matches(paths, config):
    regexes = [(g, glob_to_regex(g)) for g in config["protected_globs"]]
    extensions = tuple(e.lower() for e in config["protected_extensions"])
    hits = []
    for path in paths:
        for glob, regex in regexes:
            if regex.match(path):
                hits.append(glob)
                break
        else:
            if path.lower().endswith(extensions):
                hits.append("extension")
    return sorted(set(hits))


def verify_signature(key, timestamp, signature, body, now=None):
    try:
        age = abs((now if now is not None else time.time()) - int(timestamp))
    except (TypeError, ValueError):
        return False
    if age > SIGNATURE_MAX_AGE_SECONDS or not signature:
        return False
    message = timestamp.encode() + b"." + body
    expected = hmac.new(key.encode(), message, hashlib.sha256).hexdigest()
    return hmac.compare_digest(expected, signature.strip().lower())


def parse_allowlist(body):
    try:
        payload = json.loads(body)
        users = payload["github_users"]
    except (ValueError, KeyError, TypeError):
        raise GateError("malformed allowlist")
    if not isinstance(users, list) or not users:
        raise GateError("empty or invalid allowlist")
    for user in users:
        if isinstance(user, bool) or not isinstance(user, int) or user <= 0:
            raise GateError("allowlist entries must be positive integers")
    return set(users)


USER_AGENT = "WAEX-Nightly-Gate/1.0"


def http_get(url, headers, timeout=API_TIMEOUT_SECONDS):
    # Some CDNs reject the default Python-urllib agent, which would make the gate fail closed.
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT, **headers})
    with urllib.request.urlopen(request, timeout=timeout) as response:
        if response.status != 200:
            raise GateError(f"HTTP {response.status}")
        return response.read(), dict(response.headers)


def fetch_allowlist(url, hmac_key=None, getter=http_get, sleep=time.sleep):
    last = None
    for attempt in range(API_ATTEMPTS):
        try:
            body, headers = getter(url, {"Cache-Control": "no-cache", "Accept": "application/json"})
            if hmac_key:
                lowered = {k.lower(): v for k, v in headers.items()}
                if not verify_signature(
                    hmac_key,
                    lowered.get("x-waex-timestamp", ""),
                    lowered.get("x-waex-signature", ""),
                    body,
                ):
                    raise GateError("signature check failed")
            return parse_allowlist(body)
        except (GateError, OSError, urllib.error.URLError) as error:
            last = error
            if attempt + 1 < API_ATTEMPTS:
                sleep(1 + attempt)
    raise GateError(str(last))


def fetch_changed_files(repo, number, token, getter=http_get):
    files = []
    page = 1
    while True:
        url = f"https://api.github.com/repos/{repo}/pulls/{number}/files?per_page={FILES_PAGE_SIZE}&page={page}"
        body, _ = getter(
            url,
            {
                "Authorization": f"Bearer {token}",
                "Accept": "application/vnd.github+json",
                "X-GitHub-Api-Version": "2022-11-28",
            },
        )
        batch = json.loads(body)
        if not isinstance(batch, list):
            raise GateError("unexpected files response")
        files.extend(batch)
        if len(batch) < FILES_PAGE_SIZE:
            return files
        page += 1
        if page > 30:
            raise GateError("too many pages")


def decide(ctx, config, load_allowlist, load_files):
    """Return (decision, reason_key, matched_protected_globs)."""

    def stop(reason, matched=()):
        return "maintainer", reason, list(matched)

    if ctx["base_ref"] != "nightly":
        return stop("wrong_base")
    if ctx["draft"]:
        return stop("draft")
    if ctx["author_type"] != "User":
        return stop("bot_author")
    if not isinstance(ctx["author_id"], int) or ctx["author_id"] <= 0:
        return stop("bad_author")

    try:
        allowed = load_allowlist()
    except GateError:
        return stop("allowlist_unavailable")
    if ctx["author_id"] not in allowed:
        return stop("not_allowlisted")

    limits = config["limits"]
    if ctx["changed_files"] > limits["max_changed_files"]:
        return stop("too_many_files")
    if ctx["additions"] + ctx["deletions"] > limits["max_changed_lines"]:
        return stop("too_many_lines")

    try:
        files = load_files()
    except (GateError, ValueError, OSError, urllib.error.URLError):
        return stop("files_unavailable")
    if len(files) != ctx["changed_files"] or not files:
        return stop("files_unavailable")

    paths = []
    for entry in files:
        name = entry.get("filename")
        if not isinstance(name, str) or not name:
            return stop("files_unavailable")
        paths.append(name)
        if entry.get("previous_filename"):
            paths.append(entry["previous_filename"])
        if entry.get("status") not in ("removed", "renamed") and not entry.get("patch"):
            return stop("no_text_diff")

    matched = protected_matches(paths, config)
    if matched:
        return stop("protected_path", matched)
    return "proceed", "ok", []


def context_from_env(env):
    def number(name):
        try:
            return int(env.get(name, ""))
        except ValueError:
            return -1

    return {
        "base_ref": env.get("PR_BASE_REF", ""),
        "draft": env.get("PR_DRAFT", "true").lower() != "false",
        "author_id": number("PR_AUTHOR_ID"),
        "author_type": env.get("PR_AUTHOR_TYPE", ""),
        "changed_files": number("PR_CHANGED_FILES"),
        "additions": number("PR_ADDITIONS"),
        "deletions": number("PR_DELETIONS"),
    }


def main():
    env = os.environ
    try:
        with open(env.get("PROTECTED_PATHS_FILE", ".github/nightly-protected-paths.json"), encoding="utf-8") as handle:
            config = json.load(handle)
        for key in ("protected_globs", "protected_extensions", "limits"):
            config[key]
        ctx = context_from_env(env)
        url = env["CONTRIBUTORS_API_URL"]
        repo = env["GITHUB_REPOSITORY"]
        token = env["GITHUB_TOKEN"]
        number = int(env["PR_NUMBER"])
    except (OSError, ValueError, KeyError):
        decision, reason, matched = "maintainer", "config_error", []
    else:
        if min(ctx["changed_files"], ctx["additions"], ctx["deletions"]) < 0:
            decision, reason, matched = "maintainer", "files_unavailable", []
        else:
            decision, reason, matched = decide(
                ctx,
                config,
                lambda: fetch_allowlist(url, env.get("CONTRIBUTORS_API_HMAC_KEY") or None),
                lambda: fetch_changed_files(repo, number, token),
            )

    message = REASONS[reason]
    if matched:
        message += " Matched rules: " + ", ".join(f"`{m}`" for m in matched) + "."
    print(f"decision={decision}\nreason={reason}\nmessage={message}")
    output = env.get("GITHUB_OUTPUT")
    if output:
        with open(output, "a", encoding="utf-8") as handle:
            handle.write(f"decision={decision}\nreason={reason}\nmessage={message}\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
