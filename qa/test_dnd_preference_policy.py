#!/usr/bin/env python3
"""JDK regression for actual DND decision policy; caller checks are structural."""
from pathlib import Path
import subprocess
import tempfile

root = Path(__file__).resolve().parents[1]
src = root / "app/src/main/java/com/waenhancer"
policy = src / "config/DndPreferencePolicy.java"
harness = root / "qa/java/TestDndPreferencePolicy.java"


def run(source, expect_pass):
    with tempfile.TemporaryDirectory() as folder:
        candidate = Path(folder) / "DndPreferencePolicy.java"
        candidate.write_text(source)
        subprocess.run(["javac", "--release", "17", "-d", folder, str(candidate), str(harness)], check=True)
        result = subprocess.run(["java", "-cp", folder, "com.waenhancer.config.TestDndPreferencePolicy"],
                                text=True, capture_output=True)
        if expect_pass:
            if result.returncode:
                raise AssertionError(result.stdout + result.stderr)
            print(result.stdout.strip())
        else:
            assert result.returncode != 0 and "canonical=false, legacy=true" in result.stderr, result.stderr
            print("PASS: compiled legacy-OR negative control fails canonical OFF assertion")


source = policy.read_text()
run(source, True)
expression = 'readBoolean.test(contains.test("dnd_mode") ? "dnd_mode" : "dndmode")'
assert expression in source
run(source.replace(expression, 'readBoolean.test("dnd_mode") || readBoolean.test("dndmode")'), False)

hook = (src / "xposed/features/privacy/DndModeHook.java").read_text()
header = (src / "xposed/features/homescreen/HomeScreenHeaderActionsHook.java").read_text()
sheet = (src / "xposed/features/homescreen/WaexQuickActionsSheet.java").read_text()
privacy = (src / "ui/screens/privacy/GlobalPrivacySettingsScreen.kt").read_text()
for name, text in [("hook", hook), ("header", header), ("sheet", sheet), ("privacy", privacy)]:
    assert "DndPreferencePolicy.isEnabled(" in text, f"{name} bypasses tested DND policy"
# Two public entry paths share one confirmed action: the quick-action sheet's
# onDndToggle callback and the classic/overflow menu item selected by ID.
# The third occurrence is the helper declaration, not another untested route.
assert header.count("promptDndModeSheet(") == 3, "unexpected DND confirmation route count"
assert '/* onDndToggle    */ () -> promptDndModeSheet(activity)' in header, "quick-action sheet must confirm DND"
assert 'if (id == MENU_ID_DND) {' in header, "menu dispatch must recognize DND"
assert 'promptDndModeSheet((Activity) activityObj);' in header, "menu dispatch must confirm DND"
assert header.count('target.add(Menu.NONE, MENU_ID_DND, Menu.NONE,') == 1, "DND menu must use shared builder"
assert 'item.setOnMenuItemClickListener(mi -> handleItemSelected(mi, activity));' in header, "menu must share checked dispatcher"
assert 'isEnabled(PREF_KEY, false) || isEnabled("dndmode", false)' not in hook
assert header.count('putBoolean("dnd_mode", !active)') == 1
assert 'putBoolean("dnd_mode"' not in sheet, "quick sheet must not mutate DND before confirmation"
assert 'putBoolean("ghostmode_active"' not in sheet, "quick sheet must not mutate Ghost Mode before confirmation"
assert 'putBoolean("freeze_last_seen_active"' not in sheet, "quick sheet must not mutate Freeze before confirmation"
assert '"Activate DND Mode?"' in header and '"Deactivate DND Mode?"' in header
assert '"Activate Freeze Last Seen?"' in header and '"Deactivate Freeze Last Seen?"' in header
assert '"Activate Ghost Mode?"' in header and '"Deactivate Ghost Mode?"' in header
print("PASS: DND callers defer mutation until the activate/deactivate restart confirmation")
