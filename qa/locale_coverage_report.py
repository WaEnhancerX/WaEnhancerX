#!/usr/bin/env python3
"""Report source locale coverage without treating Android fallback as translation.

Inventory mode never claims locale acceptance; the existing strict AAPT2 and
runtime assertions remain authoritative. Strict mode fails on incomplete or
divergent Spanish tables. The JSON artifact lists *all* affected identifiers.
"""

import argparse
import hashlib
import json
from pathlib import Path

from localization_support import entries


ROOT = Path(__file__).resolve().parents[1]
QUALIFIERS = {"es": "values-es", "es-419": "values-b+es+419"}


def inventory(default, spanish, latam):
    """Return a deterministic, serializable inventory from parsed XML maps."""
    reference = set(default)
    locales = {"es": spanish, "es-419": latam}
    report = {
        "default_count": len(default),
        "locales": {},
        "spanish_value_mismatches": sorted(
            key for key in set(spanish) & set(latam)
            if spanish[key] != latam[key]
        ),
        "es_only": sorted(set(spanish) - set(latam)),
        "es_419_only": sorted(set(latam) - set(spanish)),
    }
    for name, table in locales.items():
        report["locales"][name] = {
            "present_count": len(table),
            "missing": sorted(reference - set(table)),
            "extra": sorted(set(table) - reference),
        }
    report["complete"] = all(
        not result["missing"] and not result["extra"]
        for result in report["locales"].values()
    ) and not report["spanish_value_mismatches"]
    return report


def source_hashes(root):
    source = Path(root) / "app/src/main/res"
    result = {}
    for qualifier in ("values", *QUALIFIERS.values()):
        result[qualifier] = {
            str(path.relative_to(root)): hashlib.sha256(path.read_bytes()).hexdigest()
            for path in sorted((source / qualifier).glob("*.xml"))
        }
    return result


def explain(report):
    lines = [
        "Spanish resource coverage: " + ("COMPLETE" if report["complete"] else "INCOMPLETE"),
        f'Default string count: {report["default_count"]}',
    ]
    for name, table in report["locales"].items():
        missing, extra = table["missing"], table["extra"]
        lines.append(
            f'{name}: {table["present_count"]} present; '
            f'{len(missing)} missing, {len(extra)} extra'
        )
        if missing:
            lines.append(f'  Missing {name}: {", ".join(missing[:12])}'
                         + (f' ... ({len(missing) - 12} more in JSON)' if len(missing) > 12 else ""))
        if extra:
            lines.append(f'  Extra {name}: {", ".join(extra[:12])}'
                         + (f' ... ({len(extra) - 12} more in JSON)' if len(extra) > 12 else ""))
    if report["spanish_value_mismatches"]:
        lines.append(f'{len(report["spanish_value_mismatches"])} common Spanish keys '
                     'have different text or formatting flags')
    if report["es_only"] or report["es_419_only"]:
        lines.append(f'Key-set divergence es-only={len(report["es_only"])} '
                     f'es-419-only={len(report["es_419_only"])}')
    return "\n".join(lines)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-root", type=Path, default=ROOT)
    parser.add_argument("--out", type=Path,
                        help="New evidence directory, with complete key lists and source hashes")
    parser.add_argument("--strict", action="store_true", help="Exit nonzero if any locale is incomplete")
    args = parser.parse_args()
    root = args.source_root.resolve()
    report = inventory(entries(root, "values"), entries(root, "values-es"),
                       entries(root, "values-b+es+419"))
    if args.out:
        out = args.out.resolve()
        out.mkdir(parents=True, exist_ok=False)
        (out / "locale-coverage.json").write_text(
            json.dumps({"scope": "source XML keys and values only; not Android rendering",
                        "coverage": report, "source_sha256": source_hashes(root)},
                       ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(explain(report))
    if args.strict and not report["complete"]:
        raise SystemExit(1)
    if not args.strict:
        print("INVENTORY ONLY: strict locale/AAPT2 checks still decide acceptance")


if __name__ == "__main__":
    main()
