#!/usr/bin/env python3
"""Require full translatable Slovenian coverage and locale-specific cardinal forms.

The four Slovenian CLDR cardinal categories are one, two, few and other.
Resource coverage is not native-language approval or device rendering:
https://www.unicode.org/cldr/charts/48/verify/numbers/sl.html
"""
from collections import Counter
from pathlib import Path
import re
import tempfile
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / "app/src/main/res"
PLACEHOLDER = re.compile(r"%(?:\d+\\?\$)?[a-zA-Z]")
CARDINAL = {"one", "two", "few", "other"}


def arguments(text):
    return Counter(value.replace("\\$", "$") for value in PLACEHOLDER.findall(text))


def content(node):
    return "".join(node.itertext())


def read_resources(folder):
    resources = {}
    for source in sorted(folder.glob("*.xml")):
        for node in ET.parse(source).getroot():
            if node.tag not in ("string", "plurals"):
                continue
            name = node.attrib["name"]
            key = (node.tag, name)
            if key in resources:
                raise ValueError(f"Duplicate resource {folder.name}/{name}")
            resources[key] = node
    if not resources:
        raise ValueError(f"Empty resource table {folder}")
    return resources


def forms(node):
    result = {}
    for item in node:
        quantity = item.get("quantity")
        if item.tag != "item" or quantity not in {"zero", "one", "two", "few", "many", "other"}:
            raise ValueError(f"Invalid plural item in {node.attrib['name']}")
        if quantity in result:
            raise ValueError(f"Duplicate plural quantity {quantity}")
        result[quantity] = content(item)
    return result


def validate(default, slovenian):
    required = {key for key, node in default.items()
                if node.get("translatable", "true") != "false"}
    missing = required - slovenian.keys()
    unknown = slovenian.keys() - default.keys()
    if missing or unknown:
        raise ValueError(f"Missing Slovenian resources: {sorted(missing)}; "
                         f"Unknown Slovenian resources: {sorted(unknown)}")
    for key, target in slovenian.items():
        source = default[key]
        if source.get("formatted", "true") != target.get("formatted", "true"):
            raise ValueError(f"Formatting flag mismatch: {key}")
        if key[0] == "string":
            if not content(target).strip():
                raise ValueError(f"Empty Slovenian value: {key}")
            if arguments(content(source)) != arguments(content(target)):
                raise ValueError(f"Format placeholder mismatch: {key}")
        else:
            reference = forms(source)
            translated = forms(target)
            if "other" not in reference:
                raise ValueError(f"Default plural missing other: {key}")
            if set(translated) != CARDINAL:
                raise ValueError(f"Incomplete Slovenian cardinal categories: {key}")
            for quantity, value in translated.items():
                expected = reference.get(quantity, reference["other"])
                if not value.strip() or arguments(expected) != arguments(value):
                    raise ValueError(f"Plural placeholder or value mismatch: {key}/{quantity}")
    return len(required)


class SlovenianLocaleTests(unittest.TestCase):
    def test_locale_is_selectable(self):
        helper = (ROOT / "app/src/main/java/com/waenhancer/utils/LocaleHelper.kt").read_text(
            encoding="utf-8")
        self.assertIn('LanguageOption("sl", "Slovenian", "Slovenščina")', helper)

    def test_complete_translatable_resource_contract(self):
        default = read_resources(RES / "values")
        slovenian = read_resources(RES / "values-sl")
        required = validate(default, slovenian)
        self.assertGreater(required, 0)
        print(f"Validated {required} translatable string/plural resources; "
              "includes all current UI strings and four Slovenian cardinal forms")

    def test_negative_resource_controls(self):
        default_xml = """<resources>
          <string name="brand" translatable="false">WAEX</string>
          <string name="title" formatted="false">Title</string>
          <string name="count">%1$d</string>
          <plurals name="records"><item quantity="one">%1$d recording</item>
          <item quantity="other">%1$d recordings</item></plurals>
        </resources>"""
        good = """<resources>
          <string name="title" formatted="false">Naslov</string>
          <string name="count">%1$d</string>
          <plurals name="records"><item quantity="one">%1$d posnetek</item>
          <item quantity="two">%1$d posnetka</item>
          <item quantity="few">%1$d posnetki</item>
          <item quantity="other">%1$d posnetkov</item></plurals>
        </resources>"""
        controls = {
            "missing": (good.replace('<string name="count">%1$d</string>', ""),
                        "Missing Slovenian resources"),
            "unknown": (good.replace('</resources>', '<string name="obsolete">X</string></resources>'),
                        "Unknown Slovenian resources"),
            "duplicate": (good.replace('</resources>', '<string name="title">X</string></resources>'),
                          "Duplicate resource"),
            "empty": (good.replace("Naslov", "  "), "Empty Slovenian value"),
            "format_flag": (good.replace(' formatted="false"', ''), "Formatting flag"),
            "placeholder": (good.replace('<string name="count">%1$d', '<string name="count">%1$s'),
                            "Format placeholder"),
            "plural_missing": (good.replace('<item quantity="two">%1$d posnetka</item>', ''),
                               "Incomplete Slovenian cardinal"),
            "plural_duplicate": (good.replace('quantity="few"', 'quantity="two"'),
                                 "Duplicate plural quantity"),
            "plural_placeholder": (good.replace('%1$d posnetki', '%1$s posnetki'),
                                   "Plural placeholder"),
            "plural_empty": (good.replace('%1$d posnetkov', ''), "Plural placeholder"),
        }
        with tempfile.TemporaryDirectory(prefix="waex-sl-contract-") as tmp:
            root = Path(tmp)
            values = root / "values"
            sl = root / "values-sl"
            values.mkdir()
            sl.mkdir()
            (values / "ui_strings.xml").write_text(default_xml, encoding="utf-8")
            translated = sl / "ui_strings.xml"
            translated.write_text(good, encoding="utf-8")
            reference = read_resources(values)
            self.assertEqual(validate(reference, read_resources(sl)), 3)
            for name, (mutant, error) in controls.items():
                with self.subTest(name=name):
                    self.assertNotEqual(mutant, good)
                    translated.write_text(mutant, encoding="utf-8")
                    with self.assertRaisesRegex(ValueError, error):
                        validate(reference, read_resources(sl))


if __name__ == "__main__":
    unittest.main()
