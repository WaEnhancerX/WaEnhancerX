#!/usr/bin/env python3
"""Protect the layered Meta AI removal contract."""

from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
HOOK = ROOT / "app/src/main/java/com/waenhancer/xposed/features/homescreen/ChatListCustomizationsHook.java"
REGISTRY = ROOT / "app/src/main/java/com/waenhancer/xposed/core/FeatureRegistry.java"
STRINGS = ROOT / "app/src/main/res/values/meta_ai_control.xml"
SETTINGS = ROOT / "app/src/main/java/com/waenhancer/ui/screens/settings/StylesSettingsScreen.kt"

hook = HOOK.read_text(encoding="utf-8")
registry = REGISTRY.read_text(encoding="utf-8")
strings = STRINGS.read_text(encoding="utf-8")
settings = SETTINGS.read_text(encoding="utf-8")

contracts = {
    "host preference follows complete suppression": 'HOST_PREFERENCE.equals(param.args[0])' in hook
    and "shouldDisableHostConfiguration()" in hook and "param.setResult(false)" in hook,
    "new views removed": '"onAttachedToWindow"' in hook and "removeView(view)" in hook,
    "existing hierarchy rescanned": '"onPostResume"' in hook and "removeAssistantViews(decor)" in hook,
    "layout space collapsed": "params.width = 0" in hook and "params.height = 0" in hook,
    "assistant navigation blocked": "isAssistantConversation" in hook and "activity.finish()" in hook,
    "known FAB resource handled": 'case "fab_second"' in hook,
    "boolean feature gates disabled": all(
        f"setBooleanOverride({property_id}, metaAiFullyDisabled, false)" in registry
        for property_id in (8025, 6251, 8026, 14886)
    ),
    "integer feature gate disabled":
        "setIntegerOverride(15535, metaAiFullyDisabled, 0)" in registry,
    "existing users default to all removal switches": all(
        f'prefs.getBoolean("{key}", true)' in registry + hook
        for key in ("metaai_remove_home_fab", "metaai_remove_entry_points", "metaai_block_chat")
    ),
    "FAB and other entry points are independently removable":
        "shouldRemoveHomeFab() && fab" in hook
        and "shouldRemoveEntryPoints() && !fab" in hook,
    "preference is named Remove Meta AI":
        '<string name="feat_metaai_title">Remove Meta AI</string>' in strings,
    "summary matches the complete behavior": all(
        phrase in strings for phrase in ("surfaces", "access points")
    ),
    "three sub-switches appear only while enabled":
        'item.key == "metaai" && settingsState[item.key] == true' in settings
        and settings.count("MetaAiRemovalSwitch(") >= 4,
    "all sub-switches persist through the public preference bridge": all(
        f'putBooleans(mapOf("{key}" to enabled))' in settings
        for key in ("metaai_remove_home_fab", "metaai_remove_entry_points", "metaai_block_chat")
    ),
}

missing = [name for name, present in contracts.items() if not present]
if missing:
    raise SystemExit("Meta AI removal contract violations:\n- " + "\n- ".join(missing))

print(f"PASS: {len(contracts)} layered Meta AI removal contracts are present")
