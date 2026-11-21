#!/usr/bin/env python3
"""Contract tests for scripts/release/nightly_release_state.py (real git, temporary repositories)."""

import os
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "scripts/release/nightly_release_state.py"
sys.path.insert(0, str(SCRIPT.parent))

import nightly_release_state as state  # noqa: E402

failures = []
BOT = "github-actions[bot]"


def check(condition, message):
    if not condition:
        failures.append(message)


def git(repo, *args, author="Dev", env_extra=None):
    env = {**os.environ, "GIT_AUTHOR_NAME": author, "GIT_AUTHOR_EMAIL": "a@example.com",
           "GIT_COMMITTER_NAME": author, "GIT_COMMITTER_EMAIL": "a@example.com", **(env_extra or {})}
    return subprocess.run(["git", "-C", str(repo), *args], capture_output=True, text=True, env=env, check=True).stdout.strip()


class Repo:
    def __init__(self, path):
        self.path = path
        git(path, "init", "-q", "-b", "nightly")
        self.n = 0

    def commit(self, subject, author="Dev"):
        self.n += 1
        (self.path / f"f{self.n}.txt").write_text(str(self.n))
        git(self.path, "add", "-A")
        git(self.path, "commit", "-q", "-m", subject, author=author)
        return git(self.path, "rev-parse", "HEAD")

    def release(self, number, built_sha, author=BOT):
        return self.commit(f"chore(release): nightly 2.1.0-nightly.{number}+{built_sha[:7]} (2010000{number}) [skip ci]", author=author)


def decide(repo, **kw):
    return state.decide(str(repo.path), **kw)


with tempfile.TemporaryDirectory() as tmp:
    repo = Repo(Path(tmp))
    first = repo.commit("feat: first change (#1)")
    s = decide(repo)
    check(s["should_release"] and s["reason"] == "no_previous_release", f"no release yet: {s}")

    repo.release(1, first)
    s = decide(repo)
    check(not s["should_release"] and s["reason"] == "nothing_new" and s["new_commits"] == 0, f"nothing new after a release: {s}")
    check(s["built_sha"] == first and s["head_sha"] == git(repo.path, "rev-parse", "HEAD"), "reports the built and the head commit")

    second = repo.commit("fix: second change (#2)")
    s = decide(repo)
    check(s["should_release"] and s["reason"] == "new_commits" and s["new_commits"] == 1, f"one new commit: {s}")

    third = repo.commit("fix: third change (#3)")
    check(decide(repo)["new_commits"] == 2, "two new commits")

    repo.release(2, third)
    check(not decide(repo)["should_release"], "released again, nothing new")
    repo.release(3, third)
    check(not decide(repo)["should_release"], "several bump commits in a row are not new work")

    # Work merged while a release was building lands before its bump commit but after the built commit.
    late = repo.commit("fix: merged while the build ran (#4)")
    repo.release(4, third)  # the build started from `third`, the bump commit was pushed after #4 landed
    s = decide(repo)
    check(s["should_release"] and s["new_commits"] == 1 and s["built_sha"] == third, f"a commit that landed during a build is still new: {s}")

    check(decide(repo, force=True)["reason"] == "forced" and decide(repo, force=True)["should_release"], "force releases anyway")
    s = decide(repo, ref="HEAD~1")
    check(s["head_sha"] == git(repo.path, "rev-parse", "HEAD~1"), "an explicit ref is honoured")

with tempfile.TemporaryDirectory() as tmp:
    repo = Repo(Path(tmp))
    a = repo.commit("feat: change (#1)")
    repo.release(1, a, author="Some Contributor")  # a human commit that imitates a bump commit
    s = decide(repo)
    check(s["should_release"] and s["reason"] == "no_previous_release", f"a human bump-like commit is not a release: {s}")

with tempfile.TemporaryDirectory() as tmp:
    repo = Repo(Path(tmp))
    a = repo.commit("feat: change (#1)")
    repo.release(1, a)
    repo.commit("chore(release): nightly 9.9.9-nightly.9+" + a[:7] + " (1) [skip ci]", author="Some Contributor")  # a merged PR with a bump-like title
    s = decide(repo)
    check(s["should_release"] and s["new_commits"] == 1, f"a merged PR titled like a bump commit still counts as new work: {s}")

with tempfile.TemporaryDirectory() as tmp:
    repo = Repo(Path(tmp))
    repo.commit("feat: change (#1)")
    repo.commit("chore(release): nightly 2.1.0-nightly.1+deadbee (20100001) [skip ci]", author=BOT)
    s = decide(repo)
    check(s["should_release"] and s["reason"] == "unknown_base", f"an unknown built commit releases rather than hiding work: {s}")

# The command line prints key=value lines and fails closed outside a repository.
with tempfile.TemporaryDirectory() as tmp:
    repo = Repo(Path(tmp))
    a = repo.commit("feat: change (#1)")
    repo.release(1, a)
    out = subprocess.run([sys.executable, str(SCRIPT), "--repo", str(repo.path)], capture_output=True, text=True)
    lines = dict(line.split("=", 1) for line in out.stdout.splitlines())
    check(out.returncode == 0 and lines["should_release"] == "false" and lines["reason"] == "nothing_new" and lines["new_commits"] == "0", f"cli output: {out.stdout!r}")
    forced = subprocess.run([sys.executable, str(SCRIPT), "--repo", str(repo.path), "--force"], capture_output=True, text=True)
    check("should_release=true" in forced.stdout and "reason=forced" in forced.stdout, "cli --force")
with tempfile.TemporaryDirectory() as tmp:
    broken = subprocess.run([sys.executable, str(SCRIPT), "--repo", tmp], capture_output=True, text=True)
    check(broken.returncode == 2 and not broken.stdout, "outside a repository it fails closed with no output")

if failures:
    raise SystemExit("Nightly release state violations:\n- " + "\n- ".join(failures))
print("PASS: nightly release state contract")
