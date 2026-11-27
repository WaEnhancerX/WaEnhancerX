#!/usr/bin/env python3
"""Compile the real SQLite revoke callbacks with explicit platform/persistence doubles."""
import argparse
from pathlib import Path
import re
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
PRIVACY = ROOT / "app/src/main/java/com/waenhancer/xposed/features/privacy"


def method(source: str, name: str, optional: bool = False) -> str:
    match = re.search(r"    private (?:void|boolean|Set<String>) " + re.escape(name) + r"\(", source)
    if match is None:
        if optional:
            return ""
        raise AssertionError(f"missing production method {name}")
    opening = source.index("{", match.end())
    depth = 1
    cursor = opening + 1
    while depth:
        if source[cursor] == "{":
            depth += 1
        elif source[cursor] == "}":
            depth -= 1
        cursor += 1
    return source[match.start():cursor]


def pattern_constant(source: str, name: str) -> str:
    """Compile the exact one-line regex field used by the production predicate."""
    matches = re.findall(
        r"^    private static final Pattern " + re.escape(name)
        + r" = Pattern\.compile\([^\n;]+\);$",
        source, flags=re.MULTILINE,
    )
    if len(matches) != 1:
        raise AssertionError(f"expected exactly one production pattern {name}")
    return matches[0]


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, default=PRIVACY / "AntiRevokeHook.java")
    parser.add_argument("--scenario", choices=("all", "fallback_key", "ownership", "batch", "placeholder_scope", "message_predicate", "compatibility", "method_hook_active"), default="all")
    args = parser.parse_args()
    source = args.source.read_text(encoding="utf-8")
    methods = "\n\n".join([
        pattern_constant(source, "ROW_ID_WHERE"),
        method(source, "hookDatabaseRevocation"),
        method(source, "sanitizePreservedOwnRowId"),
        method(source, "readRemoteStatusKeys", optional=True),
        method(source, "isSingleRowIdDelete", optional=True),
    ])
    template = (ROOT / "qa/fixtures/RevokeSqliteBindingChecks.java").read_text(encoding="utf-8")
    assert template.count("/* PRODUCTION_METHODS */") == 1
    harness = template.replace("/* PRODUCTION_METHODS */", methods)
    with tempfile.TemporaryDirectory(prefix="waex-revoke-sqlite-") as directory:
        temp = Path(directory)
        binding = temp / "RevokeSqliteBindingChecks.java"
        binding.write_text(harness, encoding="utf-8")
        subprocess.run([
            "javac", "--release", "17", "-d", str(temp),
            str(PRIVACY / "PreservedOwnRowIdPolicy.java"), str(binding),
        ], check=True)
        subprocess.run([
            "java", "-cp", str(temp), "com.waenhancer.xposed.features.privacy.RevokeSqliteBindingChecks", args.scenario,
        ], check=True)


if __name__ == "__main__":
    main()
