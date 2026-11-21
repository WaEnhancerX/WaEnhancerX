#!/usr/bin/env python3
"""Prevent informational/debug logging from returning to production sources."""

from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
SOURCE_ROOTS = [ROOT / "app/src/main", ROOT / "licensing/src/main", ROOT / "samples"]
SOURCE_SUFFIXES = {".java", ".kt", ".kts", ".js", ".ts", ".tsx", ".jsx"}

forbidden = {
    "verbose Android log": re.compile(r"\b(?:android\.util\.)?Log\.v\s*\("),
    "debug Android log": re.compile(r"\b(?:android\.util\.)?Log\.d\s*\("),
    "informational Android log": re.compile(r"\b(?:android\.util\.)?Log\.i\s*\("),
    "standard output": re.compile(r"\bSystem\.out\.print(?:ln)?\s*\("),
    "console informational log": re.compile(r"\bconsole\.(?:log|info|debug)\s*\("),
    "hooking-mode diagnostic": re.compile(r"\[HookingMode]"),
    "lazy lifecycle diagnostic": re.compile(r"\[LazyHooks]"),
    "successful cache diagnostic": re.compile(r"\[DexCache].*\b(?:resolved|invalidated)\b", re.I),
}

violations: list[str] = []
files_checked = 0
for source_root in SOURCE_ROOTS:
    if not source_root.exists():
        continue
    for source in source_root.rglob("*"):
        if source.suffix not in SOURCE_SUFFIXES or "build" in source.parts:
            continue
        files_checked += 1
        text = source.read_text(encoding="utf-8")
        for label, pattern in forbidden.items():
            for match in pattern.finditer(text):
                line = text.count("\n", 0, match.start()) + 1
                violations.append(f"{source.relative_to(ROOT)}:{line}: {label}")

if violations:
    raise SystemExit("Production logging policy violations:\n- " + "\n- ".join(violations))

print(f"PASS: {files_checked} production source files contain no informational/debug logs")
