#!/usr/bin/env python3
"""Compute the Android versionCode/versionName for a WAEX release channel.

versionCode = (X * 10000 + Y * 100 + Z) * 1000 + slot

    nightly  slot = n            (n in 1..499)
    beta     slot = 500 + n      (n in 1..498)
    stable   slot = 999

For one base version X.Y.Z this orders nightly < beta < stable, and every
pre-release of a later base version is greater than the stable of an earlier one.

Usage:
    version.py --channel nightly --base 2.0.9 --counter 12 --sha 0123456789abcdef
    version.py --channel beta    --base 2.0.9 --counter 3
    version.py --channel stable  --base 2.0.9

Prints `version_code=...` and `version_name=...` lines, which can be appended to
$GITHUB_OUTPUT or exported as -PVERSION_CODE / -PVERSION_NAME for Gradle.
"""

import argparse
import re
import sys

MAX_VERSION_CODE = 2_100_000_000
NIGHTLY_MAX = 499
BETA_MAX = 498
BETA_OFFSET = 500
STABLE_SLOT = 999
BASE_RE = re.compile(r"^(\d+)\.(\d+)\.(\d+)$")
SHA_RE = re.compile(r"^[0-9a-f]{7,40}$")


class VersionError(ValueError):
    pass


def parse_base(base):
    match = BASE_RE.match(base)
    if not match:
        raise VersionError(f"base version must look like X.Y.Z, got {base!r}")
    major, minor, patch = (int(part) for part in match.groups())
    if minor > 99 or patch > 99:
        raise VersionError("minor and patch must be in 0..99")
    return major, minor, patch


def compute(channel, base, counter=None, sha=None):
    major, minor, patch = parse_base(base)

    if channel == "stable":
        if counter is not None:
            raise VersionError("stable does not take a counter")
        slot = STABLE_SLOT
        name = base
    elif channel == "nightly":
        if counter is None or not 1 <= counter <= NIGHTLY_MAX:
            raise VersionError(f"nightly counter must be in 1..{NIGHTLY_MAX}")
        if not sha or not SHA_RE.match(sha.lower()):
            raise VersionError("nightly needs a hex commit sha (7..40 chars)")
        slot = counter
        name = f"{base}-nightly.{counter}+{sha.lower()[:7]}"
    elif channel == "beta":
        if counter is None or not 1 <= counter <= BETA_MAX:
            raise VersionError(f"beta counter must be in 1..{BETA_MAX}")
        slot = BETA_OFFSET + counter
        name = f"{base}-beta-{counter}"
    else:
        raise VersionError(f"unknown channel {channel!r}")

    code = (major * 10000 + minor * 100 + patch) * 1000 + slot
    if code > MAX_VERSION_CODE:
        raise VersionError(f"versionCode {code} exceeds Android limit {MAX_VERSION_CODE}")
    return code, name


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--channel", required=True, choices=["nightly", "beta", "stable"])
    parser.add_argument("--base", required=True, help="base version X.Y.Z")
    parser.add_argument("--counter", type=int, help="nightly/beta build counter")
    parser.add_argument("--sha", help="commit sha (nightly only)")
    args = parser.parse_args(argv)
    try:
        code, name = compute(args.channel, args.base, args.counter, args.sha)
    except VersionError as error:
        print(f"error: {error}", file=sys.stderr)
        return 2
    print(f"version_code={code}")
    print(f"version_name={name}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
