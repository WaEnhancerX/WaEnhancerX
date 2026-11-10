#!/usr/bin/env python3
"""Enforce centralized hook ownership for every modular feature."""

from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
FEATURES = ROOT / "app/src/main/java/com/waenhancer/xposed/features"
CORE = ROOT / "app/src/main/java/com/waenhancer/xposed/core"

registration = re.compile(
    r"\b(?:XposedBridge\.(?:hookMethod|hookAllMethods|hookAllConstructors)"
    r"|XposedHelpers\.findAndHook(?:Method|Constructor))\s*\("
)
qualified_bypass = re.compile(
    r"de\.robv\.android\.xposed\.(?:XposedBridge\.(?:hookMethod|hookAllMethods|hookAllConstructors)"
    r"|XposedHelpers\.findAndHook(?:Method|Constructor))\s*\("
)

violations: list[str] = []
registrars = 0

for source in sorted(FEATURES.rglob("*")):
    if source.suffix not in {".java", ".kt"}:
        continue
    text = source.read_text(encoding="utf-8")
    calls = registration.findall(text)
    if not calls:
        continue
    registrars += len(calls)
    relative = source.relative_to(ROOT)
    if qualified_bypass.search(text):
        violations.append(f"{relative}: directly calls the framework registration API")

    # Hook-owning production classes must inherit the automatic ownership bridge. Helper and
    # policy files contain no registrations and are intentionally outside this requirement.
    class_name = source.stem
    java_base = re.search(rf"\bclass\s+{re.escape(class_name)}\b[^{{]*\bextends\s+BaseFeature\b", text)
    kotlin_base = re.search(rf"\bclass\s+{re.escape(class_name)}\b[\s\S]*?\)\s*:\s*BaseFeature\s*\(", text)
    if java_base is None and kotlin_base is None:
        violations.append(f"{relative}: registers hooks but does not inherit BaseFeature")

base = (CORE / "BaseFeature.kt").read_text(encoding="utf-8")
registrar = (CORE / "HookRegistrar.java").read_text(encoding="utf-8")
registry = (CORE / "FeatureRegistry.java").read_text(encoding="utf-8")

required_contracts = {
    "BaseFeature exposes central bridge": "HookRegistrar.BRIDGE" in base,
    "BaseFeature exposes central helpers": "HookRegistrar.HELPERS" in base,
    "BaseFeature owns unhook handles": "installedHooks" in base and "hook.unhook()" in base,
    "Registrar retains single hooks": "instanceof XC_MethodHook.Unhook" in registrar,
    "Registrar retains hook sets": "instanceof Set" in registrar,
    "Registry starts ownership context": "entry.feature.beginHookTracking()" in registry,
    "Registry always ends ownership context": "entry.feature.endHookTracking()" in registry,
    "Registry disposes lazy hooks centrally": "feature.unhookAll()" in registry,
    "Registry automatically registers lazy disposal": "lazyHooks.registerDisposer(" in registry,
}
for label, present in required_contracts.items():
    if not present:
        violations.append(label)

if violations:
    raise SystemExit("Hook lifecycle architecture violations:\n- " + "\n- ".join(violations))

print(f"PASS: {registrars} feature hook registrations use centralized lifecycle ownership")
print(f"PASS: {len(required_contracts)} central install/dispose contracts are present")
