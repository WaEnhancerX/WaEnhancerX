#!/usr/bin/env python3
"""Static guard: a target app's settings must never be read from, or written for, another target.

Regression for WA Business settings being applied to WhatsApp. Source-level only; it does not prove
runtime behaviour on a device.
"""
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1] / "app/src/main/java/com/waenhancer"


def read(rel):
    return (ROOT / rel).read_text()


def main():
    failures = []

    base = read("xposed/core/BaseFeature.kt")
    if re.search(r'"com\.whatsapp(\.w4b)?:', base):
        failures.append("BaseFeature reads a hard-coded target's scoped key; it would leak across targets")

    provider = read("xposed/bridge/HookProvider.java")
    get_pref = provider.split('"get_preference".equals(method)', 1)[1].split("record_preserved_message", 1)[0]
    if "targetPackageForCaller" not in get_pref:
        failures.append("get_preference does not resolve the caller's per-target override")

    repo = read("theme/ThemeRepository.kt")
    if "PreferenceStores" in repo:
        failures.append("ThemeRepository must write through WaexPreferenceManager so values stay target-scoped")

    manager = read("core/preferences/WaexPreferenceManager.java")
    get_all = manager.split("public Map<String, ?> getAll()", 1)[1].split("public int exportSettings", 1)[0]
    if "targetPackage" not in get_all:
        failures.append("WaexPreferenceManager.getAll() ignores the selected target's overrides")

    if failures:
        print("\n".join("FAIL: " + f for f in failures))
        return 1
    print("PASS: target-scoped preference isolation guards")
    return 0


if __name__ == "__main__":
    sys.exit(main())
