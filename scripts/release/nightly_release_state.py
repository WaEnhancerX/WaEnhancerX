#!/usr/bin/env python3
"""Decide whether nightly has anything new to release.

The nightly release is started by hand or every twelve hours and builds the head of the nightly
branch. It should only produce a release when something was merged since the last one. The last
release leaves a bump commit made by GitHub Actions whose subject names the commit that was built:

    chore(release): nightly 2.1.0-nightly.3+64dbdf6 (20100003) [skip ci]

Everything after that commit on the branch is new, except further bump commits. Only a bump commit
authored by github-actions[bot] counts: a merged pull request whose title imitates a bump commit is
a normal commit by its author and must neither hide new work nor move the base.

Usage: nightly_release_state.py [--repo DIR] [--ref nightly] [--force]
Prints should_release=, reason=, new_commits=, built_sha=, head_sha= lines (for $GITHUB_OUTPUT).
Exit code 2 on a git failure, so that the caller does not release by accident.
"""

import argparse
import re
import subprocess
import sys

BUMP_RE = re.compile(r"^chore\(release\): nightly \S+\+([0-9a-f]{7,40}) \(\d+\)")
BOT_AUTHOR = "github-actions[bot]"
HISTORY = 400
SEP = "\x1f"


class StateError(Exception):
    pass


def git(repo, *args):
    result = subprocess.run(["git", "-C", repo, *args], capture_output=True, text=True)
    if result.returncode != 0:
        raise StateError(f"git {' '.join(args)} failed: {result.stderr.strip()[:200]}")
    return result.stdout


def is_bump(author, subject):
    return author == BOT_AUTHOR and subject.startswith("chore(release):")


def decide(repo, ref="HEAD", force=False):
    """Return a dict: should_release, reason, new_commits, built_sha, head_sha."""
    head = git(repo, "rev-parse", ref).strip()
    built = None
    for line in git(repo, "log", f"--format=%an{SEP}%s", "-n", str(HISTORY), ref).splitlines():
        author, _, subject = line.partition(SEP)
        match = BUMP_RE.match(subject)
        if match and author == BOT_AUTHOR:
            built = match.group(1)
            break

    def result(should, reason, new=0, base=""):
        return {"should_release": should, "reason": reason, "new_commits": new, "built_sha": base, "head_sha": head}

    if force:
        return result(True, "forced", 0, built or "")
    if built is None:
        return result(True, "no_previous_release")
    try:
        base = git(repo, "rev-parse", "--verify", f"{built}^{{commit}}").strip()
    except StateError:
        return result(True, "unknown_base", 0, built)
    new = 0
    for line in git(repo, "log", f"--format=%an{SEP}%s", f"{base}..{head}").splitlines():
        author, _, subject = line.partition(SEP)
        if not is_bump(author, subject):
            new += 1
    if new:
        return result(True, "new_commits", new, base)
    return result(False, "nothing_new", 0, base)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--repo", default=".")
    parser.add_argument("--ref", default="HEAD")
    parser.add_argument("--force", action="store_true", help="release even when nothing is new")
    args = parser.parse_args(argv)
    try:
        state = decide(args.repo, args.ref, args.force)
    except (StateError, OSError) as error:
        print(f"error: {error}", file=sys.stderr)
        return 2
    print(f"should_release={'true' if state['should_release'] else 'false'}")
    for key in ("reason", "new_commits", "built_sha", "head_sha"):
        print(f"{key}={state[key]}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
