#!/usr/bin/env python3
"""Write the pending (not yet released) nightly changelog for the release-index branch.

Every pull request merged into nightly brings a fragment in changelog.d/. This merges them section by
section, exactly as the nightly release will, and writes nightly-changelog.json so the notes of the next
Nightly are visible before it exists. The release clears the fragments it used, so after a release the
file is empty again. A malformed fragment is skipped (it is reported to stderr), and the output is
deterministic so the index branch only changes when the fragments do.

Usage: build_pending_changelog.py FRAGMENTS_DIR OUTPUT_DIR
"""

import importlib.util
import json
import sys
from pathlib import Path

SCHEMA = 1
MAX_NOTES = 6000


def load_assembler():
    path = Path(__file__).resolve().parent / "assemble-notes.py"
    spec = importlib.util.spec_from_file_location("assemble_notes", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def build(fragments_dir):
    notes, used, skipped = load_assembler().assemble_detailed(fragments_dir, lenient=True)
    for name, reason in skipped:
        print(f"warning: skipped {name}: {reason}", file=sys.stderr)
    return {"schema": SCHEMA, "channel": "nightly", "fragments": sorted(used), "notes": notes[:MAX_NOTES]}


def main(argv=None):
    args = sys.argv[1:] if argv is None else argv
    if len(args) != 2:
        print(__doc__, file=sys.stderr)
        return 2
    data = build(args[0])
    out = Path(args[1])
    out.mkdir(parents=True, exist_ok=True)
    text = json.dumps(data, indent=2, ensure_ascii=False) + "\n"
    target = out / "nightly-changelog.json"
    if not target.exists() or target.read_text(encoding="utf-8") != text:
        target.write_text(text, encoding="utf-8")
    return 0


if __name__ == "__main__":
    sys.exit(main())
