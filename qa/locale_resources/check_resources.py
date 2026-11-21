#!/usr/bin/env python3
"""Independent review of WAEX translations; no product modules imported.

Checks XML contracts and all Java format templates, including formatted=false
resources which UiText formats at runtime. This is not AAPT2 or Android runtime.
Actual JVM Formatter acceptance is a separate generated-input check.
"""
from __future__ import annotations

import argparse
import base64
from collections import Counter
import hashlib
import json
from pathlib import Path
import re
from xml.etree import ElementTree as ET

QUALIFIERS = ("values", "values-es", "values-b+es+419")
TOKEN = re.compile(r"%(?:(?P<index>[1-9][0-9]*)\$)?(?P<flags>[-#+ 0,(<]*)(?P<width>[1-9][0-9]*)?(?:\.(?P<precision>[0-9]+))?(?P<conversion>[bBhHsScCdoxXeEfgGaA%n]|[tT][HIklMSLNpzZsQBbhAaCYyjmdeRTrDFc])")
BIDI = re.compile(r"[\u202a-\u202e\u2066-\u2069\ufffd]")
NAMED_MACROS = re.compile(r"%(?:name|number|message)\b")
STRING_REFERENCE = re.compile(r"@string/([a-zA-Z_][a-zA-Z0-9_]*)")


def string_reference(raw: str):
    # Inspect the original XML value before Android escape decoding. In
    # particular, \@string/name is literal text and must never become an alias.
    match = STRING_REFERENCE.fullmatch(raw.strip())
    return match.group(1) if match else None


def resolve_resource_aliases(resources):
    resolved = {}
    active = []

    def visit(key):
        if key in resolved:
            return resolved[key]
        if key not in resources:
            raise ValueError("Missing string alias target: " + repr(key))
        if key in active:
            raise ValueError("Cyclic string alias: " + repr(active + [key]))
        active.append(key)
        record = dict(resources[key])
        reference = record.get("reference")
        if reference is not None:
            record["text"] = visit(("string", reference, ""))["text"]
        active.pop()
        resolved[key] = record
        return record

    for key in resources:
        visit(key)
    return resolved


def display_text(raw: str) -> str:
    # Unstyled strings follow AAPT2 ResourceParser trimming and
    # ResourceUtils::StringBuilder::AppendText quote/whitespace state. Read the
    # reference before this transformation: escaped @ remains literal text.
    # https://android.googlesource.com/platform/frameworks/base/+/49cb41f9d7d2/tools/aapt2/ResourceUtils.cpp
    spaces = " \t\n\r\v\f"
    raw = raw.strip(spaces)
    result = []
    index = 0
    quoted = False
    previous_space = False
    while index < len(raw):
        character = raw[index]
        if not quoted and character in spaces:
            if not previous_space:
                result.append(" ")
                previous_space = True
            index += 1
            continue
        previous_space = False
        if character == "\\":
            index += 1
            if index >= len(raw):
                break  # AAPT2 ignores a trailing escape with no next codepoint.
            escaped = raw[index]
            if escaped == "u":
                digits = raw[index + 1:index + 5]
                if len(digits) != 4 or not re.fullmatch(r"[0-9A-Fa-f]{4}", digits):
                    raise ValueError("Invalid Unicode escape")
                result.append(chr(int(digits, 16)))
                index += 5
            else:
                # Escaped whitespace is inserted literally. Other escapes,
                # including \$, \@ and \", emit the escaped codepoint.
                result.append({"n": "\n", "t": "\t"}.get(escaped, escaped))
                index += 1
        elif character == '"':
            quoted = not quoted
            index += 1
        elif character == "'" and not quoted:
            raise ValueError("Unescaped apostrophe outside Android quotes")
        else:
            result.append(character)
            index += 1
    return "".join(result)


def format_contract(text: str, strict: bool) -> tuple[Counter, int, dict[int, str]]:
    tokens = Counter()
    types = {}
    raw_percents = 0
    sequential = 0
    previous = None
    index = 0
    while index < len(text):
        if text[index] != "%":
            index += 1
            continue
        match = TOKEN.match(text, index)
        if match is None:
            if strict:
                raise ValueError("Malformed Java format token at offset " + str(index))
            raw_percents += 1
            index += 1
            continue
        explicit, flags, width, precision, conversion = (match.group(k) for k in ("index", "flags", "width", "precision", "conversion"))
        if len(set(flags)) != len(flags):
            raise ValueError("Duplicate format flag")
        if conversion in ("%", "n"):
            if explicit or (conversion == "n" and (flags or width or precision)):
                raise ValueError("Invalid no-argument format token")
            if conversion == "%" and (set(flags) - {"-"} or precision):
                raise ValueError("Invalid percent format token")
            argument = 0
        elif "<" in flags:
            if previous is None or explicit:
                raise ValueError("Invalid relative argument reuse")
            argument = previous
        elif explicit:
            argument = int(explicit)
            previous = argument
        else:
            sequential += 1
            argument = sequential
            previous = argument
        stable_flags = "".join(sorted(flags.replace("<", "")))
        tokens[(argument, conversion, stable_flags, width or "", precision or "")] += 1
        if argument:
            category = ("d" if conversion.lower() in ("d", "o", "x") else
                        "f" if conversion.lower() in ("e", "f", "g", "a") else
                        "t" if conversion[:1] in ("t", "T") else
                        "c" if conversion.lower() == "c" else "s")
            if category != "s" or argument not in types:
                if argument in types and types[argument] not in ("s", category):
                    raise ValueError("Conflicting argument categories")
                types[argument] = category
        index = match.end()
    return tokens, raw_percents, types


def comparable_contract(text: str, formatted: bool) -> tuple[Counter, int, dict[int, str], Counter]:
    macros = Counter(NAMED_MACROS.findall(text))
    masked = NAMED_MACROS.sub("", text)
    tokens, percents, types = format_contract(masked, strict=formatted)
    # formatted=false disables an Android compiler check; it does not prevent
    # UiText.get from using String.format with supplied arguments.
    if types and percents:
        raise ValueError("Runtime format template contains an unescaped percent")
    return tokens, percents, types, macros


def read_resources(root: Path, qualifier: str):
    resources = {}
    hashes = {}
    for path in sorted((root / "app/src/main/res" / qualifier).glob("*.xml")):
        data = path.read_bytes()
        node = ET.fromstring(data)
        if node.tag != "resources":
            raise ValueError("Wrong XML root: " + str(path))
        relevant = False
        for entry in node:
            if entry.tag not in ("string", "plurals"):
                continue
            relevant = True
            name = entry.attrib["name"]
            if entry.tag == "string":
                members = [("", entry)]
            else:
                members = [(item.attrib["quantity"], item) for item in entry]
                if {q for q, _ in members} != {"one", "other"}:
                    raise ValueError("Unexpected plural quantities: " + name)
            for quantity, item in members:
                key = (entry.tag, name, quantity)
                if key in resources:
                    raise ValueError("Duplicate XML resource: " + repr(key))
                if list(item):
                    raise ValueError("Unreviewed string markup: " + repr(key))
                raw = "".join(item.itertext())
                text = display_text(raw)
                attributes = {k: v for k, v in entry.attrib.items() if k != "name"}
                resources[key] = {"text": text, "reference": string_reference(raw),
                                  "attributes": attributes, "path": str(path.relative_to(root))}
        if relevant:
            hashes[str(path.relative_to(root))] = hashlib.sha256(data).hexdigest()
    return resolve_resource_aliases(resources), hashes


def self_check():
    count = 0
    good = "%1$s: %2$02d%%"
    reference = comparable_contract(good, True)
    for wrong in ("%1$s", "%2$s: %2$02d%%", "%1$s: %2$02s%%", "%1$s: %2$d%%", "%1$s: %2$02d", "%1$s: %2$02d%% %1$s"):
        if comparable_contract(wrong, True) == reference:
            raise AssertionError("Negative format control survived")
        count += 1
    for wrong in ("%1$q", "%1$i", "%1$s %", "%0$s", "%1$--2s"):
        try:
            comparable_contract(wrong, True)
        except ValueError:
            count += 1
        else:
            raise AssertionError("Malformed format control survived")
    if comparable_contract("%2$s", False) == comparable_contract("sin argumento", False):
        raise AssertionError("formatted=false runtime template omitted")
    count += 1
    if comparable_contract("%name %number %message", False) == comparable_contract("%name %message", False):
        raise AssertionError("Named macro omission accepted")
    count += 1
    if display_text(r'"a\n\"b\"\t%1\$s"') != 'a\n"b"\t%1$s':
        raise AssertionError("Escape control failed")
    positive = 1
    def fixture(values):
        return {("string", key, ""): {"text": display_text(raw), "reference": string_reference(raw)}
                for key, raw in values.items()}
    aliases = resolve_resource_aliases(fixture({"first": "@string/second", "second": "@string/leaf", "leaf": "Resolved"}))
    if aliases[("string", "first", "")]["text"] != "Resolved":
        raise AssertionError("String alias chain was not resolved")
    positive += 1
    for invalid in ({"first": "@string/missing"}, {"first": "@string/second", "second": "@string/first"}):
        try:
            resolve_resource_aliases(fixture(invalid))
        except ValueError:
            count += 1
        else:
            raise AssertionError("Invalid string alias control survived")
    for literal in (r"\@string/leaf", '"@string/leaf"'):
        resolved = resolve_resource_aliases(fixture({"literal": literal, "leaf": "Resolved"}))
        if resolved[("string", "literal", "")]["text"] != "@string/leaf" or resolved[("string", "literal", "")]["reference"] is not None:
            raise AssertionError("Literal string was incorrectly resolved as an alias")
        positive += 1
    for raw, expected in (
        ('Show edited history when clicking "Edited"', 'Show edited history when clicking Edited'),
        (r'Click \"Edited\"', 'Click "Edited"'),
        ('  first   second  ', 'first second'),
        ('"first  second"\t  third', 'first  second third'),
        ('"  leading and trailing  "', '  leading and trailing  '),
        (r'first\n  second\t   third', 'first\n second\t third'),
        ('"can\'t  collapse"', "can't  collapse"),
        (r'\u0041 \u00F1', 'A ñ'),
        (r'Price \$5', 'Price $5'),
    ):
        if display_text(raw) != expected:
            raise AssertionError("Android quote/whitespace control failed: " + repr(raw))
        positive += 1
    for invalid in ("an unescaped apostrophe isn't valid", r"\u123"):
        try:
            display_text(invalid)
        except ValueError:
            count += 1
        else:
            raise AssertionError("Invalid Android string control survived")
    return {"negative": count, "positive": positive}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    out = args.out.resolve()
    out.mkdir(parents=True, exist_ok=False)
    root = args.root.resolve()
    controls = self_check()
    values = {}
    identities = {}
    for qualifier in QUALIFIERS:
        values[qualifier], hashes = read_resources(root, qualifier)
        identities.update(hashes)
    base = values["values"]
    errors = []
    formatted_rows = []
    stats = {}
    for qualifier in QUALIFIERS[1:]:
        target = values[qualifier]
        missing = sorted(set(base) - set(target))
        extra = sorted(set(target) - set(base))
        stats[qualifier] = {"entries": len(target), "missing": len(missing), "extra": len(extra), "formatted_templates": 0, "formatted_false_templates": 0}
        if missing:
            errors.append({"kind": "missing", "qualifier": qualifier, "keys": missing})
        if extra:
            errors.append({"kind": "extra", "qualifier": qualifier, "keys": extra})
        for key in sorted(set(base) & set(target)):
            source, translation = base[key], target[key]
            try:
                text = translation["text"]
                if not text.strip() or BIDI.search(text):
                    raise ValueError("Empty or bidi/replacement text")
                if source["attributes"] != translation["attributes"]:
                    raise ValueError("Changed XML resource attributes")
                if source["reference"] != translation["reference"]:
                    raise ValueError("Changed XML string alias target")
                if Counter(c for c in source["text"] if c in "\n\t") != Counter(c for c in text if c in "\n\t"):
                    raise ValueError("Changed newline/tab contract")
                formatted = source["attributes"].get("formatted") != "false"
                expected = comparable_contract(source["text"], formatted)
                actual = comparable_contract(text, formatted)
                if actual != expected:
                    raise ValueError("Changed complete format/macro contract")
                if expected[2]:
                    stats[qualifier]["formatted_templates"] += 1
                    stats[qualifier]["formatted_false_templates"] += int(not formatted)
                    types = expected[2]
                    codes = "".join(types.get(i, "s") for i in range(1, max(types) + 1))
                    encoded = [base64.b64encode(s.encode()).decode() for s in (source["text"], text)]
                    formatted_rows.append("\t".join([qualifier, "/".join(key), codes, *encoded]))
            except ValueError as exc:
                errors.append({"kind": "contract", "qualifier": qualifier, "key": key, "reason": str(exc)})
    es, latam = values["values-es"], values["values-b+es+419"]
    for key in sorted(set(es) & set(latam)):
        if (es[key]["text"], es[key]["attributes"], es[key]["reference"]) != (latam[key]["text"], latam[key]["attributes"], latam[key]["reference"]):
            errors.append({"kind": "fallback_divergence", "key": key})
    (out / "formatter-input.tsv").write_text("\n".join(formatted_rows), encoding="utf-8")
    result = {"status": "FAIL" if errors else "PASS", "root": str(root), "reference_entries": len(base), "stats": stats, "oracle_negative_controls": controls["negative"], "oracle_positive_controls": controls["positive"], "errors": errors, "identities": identities, "scope": "Independent XML and format contract audit with local string-alias resolution; limited escape decoder; no Android compilation, resource selection or UI rendering claimed"}
    (out / "result.json").write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({k: v for k, v in result.items() if k not in ("identities", "errors")}, ensure_ascii=False))
    print("error_groups=" + str(len(errors)))
    for error in errors[:8]:
        compact = dict(error)
        if "keys" in compact:
            compact["keys"] = compact["keys"][:4]
        print(json.dumps(compact, ensure_ascii=False))
    raise SystemExit(int(bool(errors)))


if __name__ == "__main__":
    main()
