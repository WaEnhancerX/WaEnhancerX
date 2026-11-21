#!/usr/bin/env python3
"""Work out the next release version for a channel and update gradle.properties.

The channel comes from the branch (main -> stable, beta -> beta, nightly -> nightly). The
current VERSION_NAME in gradle.properties plus the repository's release tags decide the base
version and counter; the version code is computed by version.py's formula.

Base version: the current base, unless that base already has a stable release tag, in which
case it moves to the next patch (the patch rolls over at 9: 2.0.9 -> 2.1.0, 2.9.9 -> 3.0.0).
Counter (beta, nightly): one above the highest counter already tagged or recorded for that
channel and base.

Usage: next_version.py --channel beta --sha <commit> [--properties gradle.properties]
                       [--tags-file FILE | tags are read from `git tag`] [--write]
Prints version_code=, version_name= and base_version= lines.
"""
import argparse
import re
import subprocess
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import version  # noqa: E402

PATCH_ROLLOVER = 9
NAME_RE = re.compile(r"^(\d+)\.(\d+)\.(\d+)(?:-(?:beta-(\d+)|nightly\.(\d+)))?(?:\+[0-9a-fA-F]+)?$")
TAG_RE = re.compile(r"^(?:\d+-)?v?(\d+)\.(\d+)\.(\d+)(?:-(?:beta-(\d+)|nightly\.(\d+)))?$")
CHANNEL_BY_BRANCH = {"main": "stable", "beta": "beta", "nightly": "nightly"}


def parse_name(name, pattern=NAME_RE):
    m = pattern.match(name.strip())
    if not m:
        raise version.VersionError(f"unsupported version {name!r}")
    base = tuple(int(m[i]) for i in (1, 2, 3))
    beta = int(m[4]) if m[4] else None
    nightly = int(m[5]) if m[5] else None
    return base, beta, nightly


def bump(base):
    major, minor, patch = base
    if patch >= PATCH_ROLLOVER:
        patch, minor = 0, minor + 1
        if minor > PATCH_ROLLOVER:
            minor, major = 0, major + 1
    else:
        patch += 1
    return (major, minor, patch)


def scan_tags(tags):
    stable, beta, nightly = set(), {}, {}
    for tag in tags:
        m = TAG_RE.match(tag.strip())
        if not m:
            continue
        base = tuple(int(m[i]) for i in (1, 2, 3))
        if m[4]:
            beta[base] = max(beta.get(base, 0), int(m[4]))
        elif m[5]:
            nightly[base] = max(nightly.get(base, 0), int(m[5]))
        else:
            stable.add(base)
    return stable, beta, nightly


def next_version(channel, current_name, tags, sha=None):
    """Return (version_code, version_name, base_version)."""
    base, cur_beta, cur_nightly = parse_name(current_name)
    stable, beta, nightly = scan_tags(tags)
    while base in stable:
        base = bump(base)
    base_text = ".".join(map(str, base))
    if channel == "stable":
        counter = None
    else:
        seen = {"beta": beta, "nightly": nightly}[channel].get(base, 0)
        own = cur_beta if channel == "beta" else cur_nightly
        # The recorded counter only counts for the base it was recorded against.
        recorded = own or 0
        if parse_name(current_name)[0] != base:
            recorded = 0
        counter = max(seen, recorded) + 1
    code, name = version.compute(channel, base_text, counter, sha)
    return code, name, base_text


def write_properties(path, code, name):
    text = Path(path).read_text(encoding="utf-8")
    text, n1 = re.subn(r"(?m)^VERSION_CODE=.*$", f"VERSION_CODE={code}", text)
    text, n2 = re.subn(r"(?m)^VERSION_NAME=.*$", f"VERSION_NAME={name}", text)
    if n1 != 1 or n2 != 1:
        raise version.VersionError("gradle.properties must contain exactly one VERSION_CODE and VERSION_NAME")
    Path(path).write_text(text, encoding="utf-8")


def read_name(path):
    m = re.search(r"(?m)^VERSION_NAME=(.+?)\s*$", Path(path).read_text(encoding="utf-8"))
    if not m:
        raise version.VersionError("VERSION_NAME missing from gradle.properties")
    return m[1]


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    p.add_argument("--channel", required=True, choices=["stable", "beta", "nightly"])
    p.add_argument("--properties", default="gradle.properties")
    p.add_argument("--tags-file")
    p.add_argument("--sha")
    p.add_argument("--write", action="store_true", help="update gradle.properties in place")
    args = p.parse_args(argv)
    try:
        if args.tags_file:
            tags = Path(args.tags_file).read_text(encoding="utf-8").split()
        else:
            tags = subprocess.run(["git", "tag"], capture_output=True, text=True, check=True).stdout.split()
        code, name, base = next_version(args.channel, read_name(args.properties), tags, args.sha)
        if args.write:
            write_properties(args.properties, code, name)
    except (version.VersionError, subprocess.CalledProcessError, OSError) as error:
        print(f"error: {error}", file=sys.stderr)
        return 2
    print(f"version_code={code}")
    print(f"version_name={name}")
    print(f"base_version={base}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
