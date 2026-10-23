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
assert header.count("promptDndModeSheet(") == 4, "all DND menu paths must defer to confirmation"
assert 'isEnabled(PREF_KEY, false) || isEnabled("dndmode", false)' not in hook
assert header.count('saveAndRestart(activity, "dnd_mode", !active)') == 1
assert 'putBoolean("dnd_mode"' not in sheet, "quick sheet must not mutate DND before confirmation"
assert 'putBoolean("ghostmode_active"' not in sheet, "quick sheet must not mutate Ghost Mode before confirmation"
assert 'putBoolean("freeze_last_seen_active"' not in sheet, "quick sheet must not mutate Freeze before confirmation"
assert '"Activate DND Mode?"' in header and '"Deactivate DND Mode?"' in header
assert '"Activate Freeze Last Seen?"' in header and '"Deactivate Freeze Last Seen?"' in header
assert '"Activate Ghost Mode?"' in header and '"Deactivate Ghost Mode?"' in header
print("PASS: DND callers defer mutation until the activate/deactivate restart confirmation")
