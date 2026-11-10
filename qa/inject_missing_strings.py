#!/usr/bin/env python3
"""Fill missing Android string resources without overwriting existing translations.

The script uses Google Translate's public web endpoint.  It protects Android
format arguments, escape sequences, URLs, and product names before translating,
then verifies that every locale has every default string/plural and that format
arguments still match.  Non-language arrays intentionally remain in values/.
"""

from __future__ import annotations

import argparse
from collections import Counter
import html
import json
from pathlib import Path
import re
import shutil
import time
from urllib.parse import urlencode
from urllib.request import Request, urlopen
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / "app/src/main/res"
ENDPOINT = "https://translate.googleapis.com/translate_a/single"
PROVIDER = "google"
LOCALES = {
    "values-ar": "ar",
    "values-b+es+419": "es",
    "values-de": "de",
    "values-es": "es",
    "values-fr": "fr",
    "values-hi": "hi",
    "values-in": "id",
    "values-pt": "pt",
    "values-ru": "ru",
    "values-sl": "sl",
    "values-tr": "tr",
    "values-ur": "ur",
    "values-zh-rCN": "zh-CN",
}
RESOURCE_TAGS = {"string", "plurals"}
FORMAT = re.compile(r"%(?:\d+\\?\$)?[-#+ 0,(]*\d*(?:\.\d+)?[sdif]")
PROTECTED = re.compile(
    r"%(?:\d+\\?\$)?[-#+ 0,(]*\d*(?:\.\d+)?[sdif]|%%"
    r"|https?://\S+|\\[ntr'\"$]|@[a-zA-Z]+/[a-zA-Z0-9_]+"
    r"|\b(?:WAEX|WaEnhancerX|WhatsApp|LSPosed|Xposed|Meta AI|GitHub|Telegram|Tasker|"
    r"Android|Gemini Nano|AssemblyAI|Groq|RSA|EC|APK|API|DPI|FAB|JID|CSS|Wi-Fi)\b"
)
SPLIT = "__WAEXSPLIT%04d__"


def android_text(value: str) -> str:
    if len(value) >= 2 and value[0] == value[-1] == '"':
        value = value[1:-1]
    return (value.replace(r"\n", "\n").replace(r"\t", "\t")
            .replace(r'\"', '"').replace(r"\'", "'").replace(r"\$", "$"))


def resources(folder: Path) -> dict[tuple[str, str], tuple[Path, ET.Element]]:
    found = {}
    for source in sorted(folder.glob("*.xml")):
        for node in ET.parse(source).getroot():
            if node.tag not in RESOURCE_TAGS or "name" not in node.attrib:
                continue
            key = node.tag, node.attrib["name"]
            if key in found:
                raise ValueError(f"duplicate {folder.name}/{key[1]}")
            found[key] = source, node
    return found


def remove_generated_duplicates(folder: Path) -> int:
    """Repair an interrupted rerun that appended a generated block twice."""
    removed = 0
    for source in sorted(folder.glob("*.xml")):
        tree = ET.parse(source)
        root = tree.getroot()
        seen = set()
        file_removed = 0
        for node in list(root):
            if node.tag not in RESOURCE_TAGS or "name" not in node.attrib:
                continue
            key = node.tag, node.attrib["name"]
            if key in seen:
                root.remove(node)
                removed += 1
                file_removed += 1
            else:
                seen.add(key)
        if file_removed:
            ET.indent(tree, space="    ")
            tree.write(source, encoding="utf-8", xml_declaration=True)
    return removed


def node_text(node: ET.Element) -> str:
    return android_text("".join(node.itertext()))


def protect(value: str) -> tuple[str, list[str]]:
    saved = []

    def replace(match: re.Match[str]) -> str:
        saved.append(match.group(0))
        return f"__WAEXTOKEN{len(saved) - 1:03d}__"

    return PROTECTED.sub(replace, value), saved


def restore(value: str, saved: list[str]) -> str:
    # Translators occasionally add spaces around an opaque token.
    value = re.sub(r"__\s*WAEXTOKEN\s*(\d{3})\s*__", r"__WAEXTOKEN\1__", value)
    for index, original in enumerate(saved):
        token = f"__WAEXTOKEN{index:03d}__"
        if value.count(token) != 1:
            raise ValueError(f"translation damaged protected token {token}: {value!r}")
        value = value.replace(token, original)
    return value


def request_translation(text: str, language: str) -> str:
    if PROVIDER == "microsoft":
        try:
            import translators as translation_clients
        except ImportError as error:
            raise RuntimeError("Microsoft mode requires the temporary 'translators' package") from error
        microsoft_language = {"zh-CN": "zh-Hans"}.get(language, language)
        return translation_clients.translate_text(
            text, translator="bing", from_language="en", to_language=microsoft_language,
            timeout=60, sleep_seconds=0.5, http_client="httpx",
        )
    payload = urlencode({"client": "gtx", "sl": "en", "tl": language,
                         "dt": "t", "q": text}).encode()
    request = Request(ENDPOINT, data=payload, headers={"User-Agent": "WAEX-localization/1.0"})
    last_error = None
    for attempt in range(9):
        try:
            with urlopen(request, timeout=60) as response:
                data = json.load(response)
            return "".join(part[0] for part in data[0])
        except Exception as error:  # Network throttling is transient.
            last_error = error
            time.sleep(min(120, 3 * (2 ** attempt)))
    raise RuntimeError(f"translation request failed: {last_error}")


def translate_many(values: list[str], language: str) -> list[str]:
    translated = []
    offset = 0
    while offset < len(values):
        batch, protected, size = [], [], 0
        while offset + len(batch) < len(values):
            value, saved = protect(values[offset + len(batch)])
            added = len(value) + 28
            limit = 900 if PROVIDER == "microsoft" else 3500
            if batch and size + added > limit:
                break
            batch.append(value)
            protected.append(saved)
            size += added
        joined_parts = []
        for index, value in enumerate(batch):
            if index:
                joined_parts.append(SPLIT % index)
            joined_parts.append(value)
        joined = "\n".join(joined_parts)
        result = request_translation(joined, language)
        parts = re.split(r"\n?__\s*WAEXSPLIT\s*\d+\s*__\n?", result)
        if len(parts) != len(batch):
            raise ValueError(f"batch boundary mismatch: expected {len(batch)}, got {len(parts)}: {result!r}")
        translated.extend(restore(part.strip(), saved) for part, saved in zip(parts, protected))
        offset += len(batch)
        time.sleep(1.5)
    return translated


def xml_value(value: str) -> str:
    # Quoted Android strings allow apostrophes; embedded quotes/backslashes must
    # remain escaped after XML decoding.
    value = value.replace("\\", "\\\\").replace('"', r'\"')
    return '"' + html.escape(value, quote=False) + '"'


def render_node(node: ET.Element, translated: list[str]) -> str:
    name = node.attrib["name"]
    if node.tag == "string":
        formatted = ' formatted="false"' if node.attrib.get("formatted") == "false" else ""
        return f'    <string name="{name}"{formatted}>{xml_value(translated[0])}</string>'
    lines = [f'    <plurals name="{name}">']
    for item, value in zip(node.findall("item"), translated):
        lines.append(f'        <item quantity="{item.attrib["quantity"]}">{xml_value(value)}</item>')
    lines.append("    </plurals>")
    return "\n".join(lines)


def insert(path: Path, entries: list[str]) -> None:
    if path.exists():
        content = path.read_text(encoding="utf-8")
        closing = content.rfind("</resources>")
        if closing < 0:
            raise ValueError(f"missing </resources> in {path}")
        prefix = content[:closing].rstrip()
    else:
        path.parent.mkdir(parents=True, exist_ok=True)
        prefix = '<?xml version="1.0" encoding="utf-8"?>\n<resources>'
    path.write_text(prefix + "\n" + "\n".join(entries) + "\n</resources>\n", encoding="utf-8")


def fill_locale(folder_name: str, language: str) -> int:
    base = resources(RES / "values")
    target = resources(RES / folder_name)
    missing = [key for key in base if key not in target]
    by_file: dict[str, list[tuple[tuple[str, str], ET.Element]]] = {}
    for key in missing:
        source, node = base[key]
        by_file.setdefault(source.name, []).append((key, node))

    count = 0
    for filename, nodes in by_file.items():
        # arrays.xml contains no language strings; resources() excludes its arrays.
        flat = []
        lengths = []
        for _, node in nodes:
            values = [node_text(node)] if node.tag == "string" else [node_text(x) for x in node.findall("item")]
            flat.extend(values)
            lengths.append(len(values))
        translated = translate_many(flat, language)
        rendered, position = [], 0
        for (_, node), length in zip(nodes, lengths):
            rendered.append(render_node(node, translated[position:position + length]))
            position += length
        insert(RES / folder_name / filename, rendered)
        count += len(nodes)
        print(f"  {folder_name}/{filename}: added {len(nodes)} resources")
    return count


def slots(text: str) -> Counter[str]:
    return Counter(token.replace(r"\$", "$") for token in FORMAT.findall(text) if token != "%%")


def validate(folder_names=None) -> None:
    base = resources(RES / "values")
    for folder_name in folder_names or LOCALES:
        target = resources(RES / folder_name)
        missing = set(base) - set(target)
        if missing:
            raise ValueError(f"{folder_name} still missing: {sorted(missing)}")
        for key, (_, node) in base.items():
            source_values = [node_text(node)] if node.tag == "string" else [node_text(x) for x in node.findall("item")]
            translated_node = target[key][1]
            target_values = ([node_text(translated_node)] if node.tag == "string"
                             else [node_text(x) for x in translated_node.findall("item")])
            if len(source_values) != len(target_values):
                raise ValueError(f"plural count mismatch: {folder_name}/{key[1]}")
            for source, translated in zip(source_values, target_values):
                check_slots = node.attrib.get("formatted") != "false"
                if not translated.strip() or (check_slots and slots(source) != slots(translated)):
                    raise ValueError(f"invalid translation: {folder_name}/{key[1]}")


def sync_spanish() -> None:
    """Keep the repository's canonical es/es-419 exact-value contract."""
    source = RES / "values-es"
    target = RES / "values-b+es+419"
    for path in source.glob("*.xml"):
        if any(node.tag in RESOURCE_TAGS for node in ET.parse(path).getroot()):
            shutil.copyfile(path, target / path.name)


def main() -> None:
    global PROVIDER
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--validate", action="store_true", help="only validate existing resources")
    parser.add_argument("--provider", choices=("google", "microsoft"), default="google")
    parser.add_argument("--locale", action="append", choices=tuple(LOCALES),
                        help="process only this values-* folder (repeatable)")
    parser.add_argument("--sync-spanish", action="store_true",
                        help="copy canonical values-es language resources to es-419")
    args = parser.parse_args()
    PROVIDER = args.provider
    selected = {name: LOCALES[name] for name in args.locale} if args.locale else LOCALES
    total = 0
    if args.sync_spanish:
        sync_spanish()
    for folder_name in selected:
        removed = remove_generated_duplicates(RES / folder_name)
        if removed:
            print(f"  {folder_name}: removed {removed} duplicate resources")
    if not args.validate:
        for folder_name, language in selected.items():
            total += fill_locale(folder_name, language)
    validate(selected)
    print(f"PASS: all {len(resources(RES / 'values'))} string/plural resources are present in "
          f"{len(selected)} locales; added {total}")


if __name__ == "__main__":
    main()
