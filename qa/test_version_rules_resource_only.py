#!/usr/bin/env python3
"""Keep arrays.xml as the only built-in supported-version source."""

from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
VALIDATOR = ROOT / "app/src/main/java/com/waenhancer/utils/UniversalVersionValidator.kt"
ARRAYS = ROOT / "app/src/main/res/values/arrays.xml"

validator = VALIDATOR.read_text(encoding="utf-8")
arrays = ARRAYS.read_text(encoding="utf-8")

violations = []
if "STATIC_BUILTIN_RULES" in validator:
    violations.append("static built-in rule collection remains")
if re.search(r'"\d+\.\d+\.\d+\.(?:xx|\*)"', validator, re.IGNORECASE):
    violations.append("hardcoded supported version remains in validator")
if 'getStringArray(id)' not in validator:
    violations.append("validator does not read the resource array")
if 'name="universal_supported_versions"' not in arrays:
    violations.append("supported-version resource array is missing")
if "if (context == null) return null" not in validator:
    violations.append("context-free validation can invent built-in rules")

if violations:
    raise SystemExit("Version-rule source violations:\n- " + "\n- ".join(violations))

print("PASS: arrays.xml is the only built-in supported-version source")
