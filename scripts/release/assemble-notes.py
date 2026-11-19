#!/usr/bin/env python3
"""Merge changelog.d/*.txt fragments into release notes.

Each fragment uses the changelog.txt layout: `[Section]` headers followed by
`- bullet` lines (continuation lines are indented). Sections are merged in the
order Added, Fixes, Improvements, then any other section in first-seen order.
Fragments are read in natural filename order (2.txt before 10.txt); files whose
name starts with `_` are ignored. A malformed fragment fails the run (exit 2)
so a broken note never reaches a release.

Usage:
    assemble-notes.py [--dir changelog.d] [--out notes.txt]
"""

import argparse
import re
import sys
from pathlib import Path

SECTION_RE = re.compile(r"^\[([^\[\]]+)\]\s*$")
PREFERRED = ["Added", "Fixes", "Improvements"]


class FragmentError(ValueError):
    pass


def natural_key(path):
    return [int(part) if part.isdigit() else part for part in re.split(r"(\d+)", path.name)]


def parse_fragment(text, label):
    sections = {}
    current = None
    for number, raw in enumerate(text.splitlines(), start=1):
        line = raw.rstrip()
        if not line.strip():
            continue
        header = SECTION_RE.match(line)
        if header:
            current = header.group(1).strip()
            sections.setdefault(current, [])
        elif line.startswith("- "):
            if current is None:
                raise FragmentError(f"{label}:{number}: bullet before any [Section]")
            body = line[2:].strip()
            if not body:
                raise FragmentError(f"{label}:{number}: empty bullet")
            sections[current].append(body)
        elif raw[:1] in (" ", "\t") and current is not None and sections[current]:
            sections[current][-1] += " " + line.strip()
        else:
            raise FragmentError(f"{label}:{number}: expected [Section] or '- bullet'")
    if not any(sections.values()):
        raise FragmentError(f"{label}: no bullets found")
    return sections


def assemble(fragments_dir):
    root = Path(fragments_dir)
    files = sorted(
        (p for p in root.glob("*.txt") if p.is_file() and not p.name.startswith("_")),
        key=natural_key,
    )
    merged = {}
    order = []
    for path in files:
        parsed = parse_fragment(path.read_text(encoding="utf-8"), path.name)
        for name, bullets in parsed.items():
            if name not in merged:
                merged[name] = []
                order.append(name)
            for bullet in bullets:
                if bullet not in merged[name]:
                    merged[name].append(bullet)
    names = [n for n in PREFERRED if n in merged] + [n for n in order if n not in PREFERRED]
    blocks = []
    for name in names:
        if merged[name]:
            blocks.append("\n".join([f"[{name}]"] + [f"- {b}" for b in merged[name]]))
    return "\n\n".join(blocks) + ("\n" if blocks else "")


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--dir", default="changelog.d")
    parser.add_argument("--out", help="write notes to this file instead of stdout")
    args = parser.parse_args(argv)
    try:
        notes = assemble(args.dir)
    except (FragmentError, OSError, UnicodeDecodeError) as error:
        print(f"error: {error}", file=sys.stderr)
        return 2
    if not notes:
        print("error: no changelog fragments found", file=sys.stderr)
        return 2
    if args.out:
        Path(args.out).write_text(notes, encoding="utf-8")
    else:
        sys.stdout.write(notes)
    return 0


if __name__ == "__main__":
    sys.exit(main())
