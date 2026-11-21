#!/usr/bin/env python3
"""Build the per-channel release index files (schema 1) read by the WAEX app.

Channels are decided by version shape: `-nightly.N` is nightly, `-beta-N` is beta, no suffix is
stable. Stable and beta come from the module repository, nightly from the project repository.
Only releases whose APK assets carry a GitHub SHA-256 digest are listed, because the app verifies
every download against it.

Usage: build_release_index.py OUTPUT_DIR   (reads GITHUB_TOKEN when set)
"""
import json
import os
import re
import sys
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

SCHEMA = 1
MAX_ENTRIES = 30
MAX_NOTES = 6000
MAX_FILE_BYTES = 900_000
CHANNEL_REPOS = {
    "stable": "Xposed-Modules-Repo/com.waenhancer",
    "beta": "Xposed-Modules-Repo/com.waenhancer",
    "nightly": "WaEnhancerX/WaEnhancerX",
}
ALLOWED_REPOS = ("WaEnhancerX/WaEnhancerX", "Xposed-Modules-Repo/com.waenhancer")
VERSION = re.compile(r"^(\d+)\.(\d+)\.(\d+)(?:-(beta-(\d+)|nightly\.(\d+)))?$")
DIGEST = re.compile(r"^sha256:([0-9a-f]{64})$")


def version_name_from_tag(tag):
    tag = tag.strip().removeprefix("debug-")
    head, sep, rest = tag.partition("-")
    if sep and head.isdigit():
        tag = rest
    return tag.removeprefix("v").removeprefix("V").split("+")[0].strip()


def classify(version):
    """Return (channel, versionCode) or None when the version or its slot is invalid."""
    m = VERSION.match(version)
    if not m:
        return None
    major, minor, patch = int(m[1]), int(m[2]), int(m[3])
    if major > 209 or minor > 99 or patch > 99:
        return None
    base = (major * 10000 + minor * 100 + patch) * 1000
    if m[6] is not None:
        n = int(m[6])
        return ("nightly", base + n) if 1 <= n <= 499 else None
    if m[5] is not None:
        n = int(m[5])
        return ("beta", base + 500 + n) if 1 <= n <= 498 else None
    return ("stable", base + 999)


def asset_matches(name, version):
    if not name.lower().endswith(".apk"):
        return False
    m = re.search(r"(?i)(?:^|[-_])v?(\d+\.\d+(?:\.\d+)?(?:-[a-z0-9.-]+)?)(?:\+[0-9a-f]{7,40})?(?=[_-]|\.apk$)", name)
    return bool(m) and m[1].lower() == version.lower()


def entry_for(release, channel):
    """Convert one GitHub release to an index entry for `channel`, or None."""
    if release.get("draft"):
        return None
    tag = (release.get("tag_name") or "").strip()
    version = version_name_from_tag(tag)
    info = classify(version)
    if not info or info[0] != channel:
        return None
    assets = []
    for a in release.get("assets") or []:
        url, name = a.get("browser_download_url") or "", a.get("name") or ""
        digest = DIGEST.match(a.get("digest") or "")
        ok_url = any(url.startswith(f"https://github.com/{r}/releases/download/") for r in ALLOWED_REPOS)
        if digest and ok_url and asset_matches(name, version) and (a.get("size") or 0) > 0:
            assets.append({"name": name, "url": url, "size": a["size"], "sha256": digest[1]})
    if not assets:
        return None
    notes = (release.get("body") or "").strip()[:MAX_NOTES]
    return {
        "tag": tag,
        "version_name": version,
        "version_code": info[1],
        "published_at": release.get("published_at") or "",
        "html_url": release.get("html_url") or "",
        "assets": assets,
        "notes": notes,
    }


def build_channel(channel, releases):
    by_version = {}
    for release in releases:
        entry = entry_for(release, channel)
        if not entry:
            continue
        current = by_version.get(entry["version_name"])
        # Duplicate tags for one version: prefer the numeric-prefixed tag, then the earliest one.
        rank = (entry["tag"].split("-")[0].isdigit(), )
        if current is None or rank > (current["tag"].split("-")[0].isdigit(), ):
            by_version[entry["version_name"]] = entry
    entries = sorted(by_version.values(), key=lambda e: e["version_code"], reverse=True)[:MAX_ENTRIES]
    while entries and len(json.dumps(entries)) > MAX_FILE_BYTES:
        entries.pop()
    return entries


def render(channel, entries, updated_at):
    return json.dumps(
        {"schema": SCHEMA, "channel": channel, "updated_at": updated_at, "releases": entries},
        indent=2, ensure_ascii=False,
    ) + "\n"


def write_if_changed(path, channel, entries):
    """Write the file only when its releases changed, so updated_at is not churned."""
    if path.exists():
        try:
            old = json.loads(path.read_text(encoding="utf-8"))
            if old.get("schema") == SCHEMA and old.get("releases") == entries:
                return False
        except ValueError:
            pass
    now = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    path.write_text(render(channel, entries, now), encoding="utf-8")
    return True


def fetch_releases(repo, token=None, pages=5):
    out = []
    for page in range(1, pages + 1):
        req = urllib.request.Request(
            f"https://api.github.com/repos/{repo}/releases?per_page=100&page={page}",
            headers={"Accept": "application/vnd.github+json", "User-Agent": "waex-release-index",
                     **({"Authorization": f"Bearer {token}"} if token else {})},
        )
        with urllib.request.urlopen(req, timeout=30) as response:
            batch = json.load(response)
        out.extend(batch)
        if len(batch) < 100:
            break
    return out


def main(argv):
    if len(argv) != 2:
        print(__doc__)
        return 2
    out_dir = Path(argv[1])
    token = os.environ.get("GITHUB_TOKEN")
    cache = {}
    changed = []
    for channel, repo in CHANNEL_REPOS.items():
        if repo not in cache:
            cache[repo] = fetch_releases(repo, token)
        entries = build_channel(channel, cache[repo])
        if write_if_changed(out_dir / f"{channel}.json", channel, entries):
            changed.append(channel)
        print(f"{channel}: {len(entries)} releases")
    print("changed:", ", ".join(changed) or "none")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
