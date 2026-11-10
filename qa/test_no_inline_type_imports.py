#!/usr/bin/env python3
"""Require Java/Kotlin types to be declared through imports, not inline names."""

from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
SOURCE_ROOTS = [ROOT]
SOURCE_SUFFIXES = {".java", ".kt", ".kts"}
EXCLUDED_PARTS = {".git", ".gradle", "build", "references"}

qualified_type = re.compile(
    r"\b(?:android|androidx|java|javax|kotlin|kotlinx|org|com|de)"
    r"\.(?:[a-z_][A-Za-z0-9_$]*\.)+[A-Z][A-Za-z0-9_$]*\b"
)


def code_without_comments_or_literals(text: str) -> str:
    """Blank comments and literals while retaining newlines and source positions."""
    output = list(text)
    index = 0
    state = "code"
    quote = ""
    while index < len(text):
        if state == "code":
            if text.startswith("//", index):
                output[index:index + 2] = "  "
                index += 2
                state = "line_comment"
                continue
            if text.startswith("/*", index):
                output[index:index + 2] = "  "
                index += 2
                state = "block_comment"
                continue
            if text.startswith('"""', index):
                output[index:index + 3] = "   "
                index += 3
                state = "triple_string"
                continue
            if text[index] in {'"', "'"}:
                quote = text[index]
                output[index] = " "
                index += 1
                state = "literal"
                continue
            index += 1
            continue

        if state == "line_comment":
            if text[index] == "\n":
                state = "code"
            else:
                output[index] = " "
            index += 1
            continue

        if state == "block_comment":
            if text.startswith("*/", index):
                output[index:index + 2] = "  "
                index += 2
                state = "code"
            else:
                if text[index] != "\n":
                    output[index] = " "
                index += 1
            continue

        if state == "triple_string":
            if text.startswith('"""', index):
                output[index:index + 3] = "   "
                index += 3
                state = "code"
            else:
                if text[index] != "\n":
                    output[index] = " "
                index += 1
            continue

        if text[index] == "\\" and index + 1 < len(text):
            output[index] = " "
            if text[index + 1] != "\n":
                output[index + 1] = " "
            index += 2
        elif text[index] == quote:
            output[index] = " "
            index += 1
            state = "code"
        else:
            if text[index] != "\n":
                output[index] = " "
            index += 1

    return "".join(output)


violations: list[str] = []
files_checked = 0
for source_root in SOURCE_ROOTS:
    if not source_root.exists():
        continue
    for source in source_root.rglob("*"):
        if source.suffix not in SOURCE_SUFFIXES or EXCLUDED_PARTS.intersection(source.parts):
            continue
        files_checked += 1
        code = code_without_comments_or_literals(source.read_text(encoding="utf-8"))
        for line_number, line in enumerate(code.splitlines(), start=1):
            if line.lstrip().startswith(("import ", "package ")):
                continue
            for match in qualified_type.finditer(line):
                violations.append(
                    f"{source.relative_to(ROOT)}:{line_number}: {match.group(0)}"
                )

if violations:
    raise SystemExit(
        "Inline type references must use proper imports:\n- " + "\n- ".join(violations)
    )

print(f"PASS: {files_checked} production source files use proper type imports")
