#!/usr/bin/env python3
"""Read-only SHA-256 and 16 KiB ELF preflight for hosted ARM64 transcription libraries.

Input is an existing local directory with manifest.csv and every listed .so.
No network I/O or package modifications. Exit 0: aligned and intact;
1: ELF alignment findings; 2: malformed, missing or mismatched inputs.
A pass is not a code-signing, Android linker, or on-device function test.
"""
from __future__ import annotations

import argparse
import csv
import hashlib
import io
import json
from pathlib import Path
import re
import sys

from audit_native_alignment import (
    AuditError, MAX_NATIVE_BYTES, MAX_TOTAL_NATIVE_BYTES, PAGE_SIZE,
    inspect_elf, require,
)

MAX_MANIFEST_BYTES = 256 * 1024
MAX_LIBRARIES = 100
COLUMNS = {"file", "size_bytes", "md5", "sha256"}
LIBRARY_NAME = re.compile(r"lib[A-Za-z0-9_.+-]*\.so\Z")
HEX_SHA256 = re.compile(r"[0-9a-fA-F]{64}\Z")
HEX_MD5 = re.compile(r"[0-9a-fA-F]{32}\Z")


def audit(directory: Path) -> dict:
    require(directory.is_dir(), "Native library directory does not exist")
    manifest = directory / "manifest.csv"
    require(manifest.is_file() and not manifest.is_symlink(), "Missing regular manifest.csv")
    require(0 < manifest.stat().st_size <= MAX_MANIFEST_BYTES, "Manifest size is out of bounds")
    with manifest.open("rb") as stream:
        content = stream.read(MAX_MANIFEST_BYTES + 1)
    require(0 < len(content) <= MAX_MANIFEST_BYTES, "Manifest changed while reading")
    reader = csv.DictReader(io.StringIO(content.decode("utf-8-sig")))
    require(reader.fieldnames is not None and len(reader.fieldnames) == len(COLUMNS)
            and set(reader.fieldnames) == COLUMNS,
            "Manifest must contain file,size_bytes,md5,sha256 exactly")
    rows = list(reader)
    require(0 < len(rows) <= MAX_LIBRARIES, "Manifest library count is out of bounds")

    names = set()
    specifications = []
    total_size = 0
    for row in rows:
        require(None not in row and all(value is not None for value in row.values()),
                "Malformed manifest row")
        name = row["file"]
        require(bool(LIBRARY_NAME.fullmatch(name)), "Unsafe or invalid library filename")
        require(name.casefold() not in names, "Duplicate library filename")
        names.add(name.casefold())
        size_text = row["size_bytes"]
        require(bool(re.fullmatch(r"[0-9]+", size_text)), "Invalid library size")
        size = int(size_text)
        require(0 < size <= MAX_NATIVE_BYTES, "Library size is out of bounds")
        total_size += size
        require(total_size <= MAX_TOTAL_NATIVE_BYTES, "Total native size exceeds bound")
        require(bool(HEX_SHA256.fullmatch(row["sha256"])), "Invalid SHA-256 in manifest")
        require(bool(HEX_MD5.fullmatch(row["md5"])), "Invalid MD5 in manifest")
        specifications.append((name, size, row["sha256"].lower(), row["md5"].lower()))

    present = {p.name.casefold() for p in directory.glob("*.so")}
    require(present == names, "Manifest and local native-library filenames differ")

    libraries = []
    for name, size, sha, md5 in specifications:
        path = directory / name
        require(path.is_file() and not path.is_symlink(), name + ": missing regular file")
        require(path.stat().st_size == size, name + ": size does not match manifest")
        with path.open("rb") as stream:
            data = stream.read(size + 1)
        require(len(data) == size, name + ": size changed while reading")
        require(hashlib.sha256(data).hexdigest() == sha,
                name + ": SHA-256 does not match manifest")
        require(hashlib.md5(data).hexdigest() == md5,
                name + ": MD5 does not match manifest")
        try:
            result = inspect_elf(data)
        except AuditError as error:
            raise AuditError(name + ": " + str(error)) from error
        require((result["elf_bits"], result["machine"]) == (64, 183),
                name + ": expected an AArch64 ELF shared library")
        libraries.append({"file": name, "bytes": size, "sha256": sha,
                          "load_alignments": [s["alignment"] for s in result["load_segments"]],
                          "issues": result["issues"]})

    findings = [{"file": lib["file"], "issues": lib["issues"]}
                for lib in libraries if lib["issues"]]
    return {"schema_version": 1, "manifest_sha256": hashlib.sha256(content).hexdigest(),
            "page_size": PAGE_SIZE, "library_count": len(libraries),
            "libraries": libraries, "findings": findings, "passed": not findings,
            "scope": "Downloaded ARM64 native files: manifest checksums and ELF PT_LOAD only; "
                     "not manifest authenticity, Android loading, or transcription correctness."}


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path)
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args(argv)
    try:
        report = audit(args.directory)
    except (OSError, UnicodeError, AuditError, csv.Error) as error:
        print(json.dumps({"passed": False, "input_error": str(error)}))
        return 2
    if args.json:
        print(json.dumps(report, indent=2))
    else:
        print(f"{report['library_count']} libraries checked; "
              f"{len(report['findings'])} ELF alignment findings")
        for item in report["findings"]:
            print(f"  {item['file']}: {', '.join(item['issues'])}")
    return 0 if report["passed"] else 1


if __name__ == "__main__":
    sys.exit(main())
