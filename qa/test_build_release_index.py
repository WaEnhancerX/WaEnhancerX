#!/usr/bin/env python3
"""Contract tests for scripts/release/build_release_index.py using offline fixtures."""
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts/release"))
import build_release_index as b  # noqa: E402

failures = []


def check(cond, msg):
    if not cond:
        failures.append(msg)


REPO = "https://github.com/Xposed-Modules-Repo/com.waenhancer/releases/download"


def release(tag, version, digest="a" * 64, draft=False, url=None):
    return {
        "tag_name": tag, "draft": draft, "published_at": "2026-01-01T00:00:00Z",
        "html_url": f"https://github.com/Xposed-Modules-Repo/com.waenhancer/releases/tag/{tag}",
        "body": "[Fixes]\n- x",
        "assets": [{
            "name": f"WaEnhancerX-v{version}_release.apk",
            "browser_download_url": url or f"{REPO}/{tag}/WaEnhancerX-v{version}_release.apk",
            "size": 10, "digest": f"sha256:{digest}" if digest else None,
        }],
    }


check(b.classify("2.0.9") == ("stable", 20009999), "stable code")
check(b.classify("2.0.9-beta-3") == ("beta", 20009503), "beta code")
check(b.classify("2.0.9-nightly.12") == ("nightly", 20009012), "nightly code")
check(b.classify("2.0.9-nightly.500") is None, "nightly slot range")
check(b.classify("2.0.9-beta-499") is None, "beta slot range")
check(b.classify("2.0.9-rc-1") is None, "unknown suffix")
check(b.version_name_from_tag("20800-2.0.8") == "2.0.8", "numeric tag prefix")
check(b.version_name_from_tag("20009012-2.0.9-nightly.12") == "2.0.9-nightly.12", "nightly tag")

rels = [
    release("20800-2.0.8", "2.0.8"),
    release("17109-1.7.1-beta-9", "1.7.1-beta-9"),
    release("1.7.1-beta-9", "1.7.1-beta-9"),
    release("20900-2.0.9", "2.0.9", digest=None),                  # no digest -> dropped
    release("20901-2.0.10", "2.0.10", draft=True),                 # draft -> dropped
    release("21000-2.1.0", "2.1.0", url="https://evil.example/a.apk"),  # bad host -> dropped
]
stable = b.build_channel("stable", rels)
beta = b.build_channel("beta", rels)
check([e["version_name"] for e in stable] == ["2.0.8"], f"stable entries {stable}")
check(len(beta) == 1 and beta[0]["tag"] == "17109-1.7.1-beta-9", "duplicate beta prefers numeric tag")
check(b.build_channel("nightly", rels) == [], "no nightlies")
check(stable[0]["version_code"] == 20008999 and stable[0]["assets"][0]["sha256"] == "a" * 64, "entry fields")

many = [release(f"{i}-1.0.{i % 100}-beta-{i}", f"1.0.{i % 100}-beta-{i}") for i in range(1, 100)]
check(len(b.build_channel("beta", many)) <= b.MAX_ENTRIES, "entry cap")

with tempfile.TemporaryDirectory() as d:
    p = Path(d) / "stable.json"
    check(b.write_if_changed(p, "stable", stable), "first write")
    first = p.read_text()
    check(not b.write_if_changed(p, "stable", stable), "unchanged content is not rewritten")
    check(p.read_text() == first, "updated_at not churned")
    check(b.write_if_changed(p, "stable", []), "changed content rewritten")

if failures:
    print("FAIL:\n- " + "\n- ".join(failures))
    sys.exit(1)
print("PASS: release index builder contract")
