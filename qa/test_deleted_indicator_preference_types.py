#!/usr/bin/env python3
"""Guard type-safe Anti-Revoke indicator preferences in the current Privacy UI."""
from pathlib import Path
import re
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / 'app/src/main/java/com/waenhancer'
ui = (SRC / 'ui/screens/privacy/GlobalPrivacySettingsScreen.kt').read_text()
schema = (SRC / 'config/PreferenceSchema.java').read_text()
hook = (SRC / 'xposed/features/privacy/AntiRevokeHook.java').read_text()
entries = dict(re.findall(r'add\(entries,\s*"([^"]+)",\s*Type\.([A-Z_]+),', schema))
expected = {
    'antirevokestatus': 'STRING',
    'anti_revoke_indicator': 'INT',
    'deleted_message_color': 'INT',
}
for key, kind in expected.items():
    assert entries[key] == kind, (key, entries.get(key))
assert 'preferenceManager.putString("antirevokestatus", if (value) "1" else "0")' in ui
assert 'preferenceManager.putInt("anti_revoke_indicator", valKey.toInt())' in ui
assert 'preferenceManager.putInt("deleted_message_color", colorInt)' in ui
assert 'preferenceManager.getAll()["deleted_message_color"]' in ui
assert 'DeletedIndicatorPreferencePolicy.toColorHex(' in ui
assert 'DeletedIndicatorPreferencePolicy.toArgbOrNull(' in ui
for unsafe in ['putString("anti_revoke_indicator",', 'putString("deleted_message_color",',
               'putBoolean("antirevokestatus",']:
    assert unsafe not in ui, unsafe
# Existing hooks read canonical INT values or legacy String values without loss.
assert 'prefs.getInt("deleted_message_color",' in hook
assert 'prefs.getInt("anti_revoke_indicator", 2)' in hook
# The shared live reader preserves the STRING setting and current target scope;
# the compiled bridge suite below checks the actual hook and registry decisions.
assert 'return getPrivacy().flag("antirevokestatus");' in hook
with tempfile.TemporaryDirectory(prefix='waex_deleted_indicator_') as output:
    subprocess.run(['javac', '--release', '17', '-d', output,
                    str(SRC/'ui/screens/privacy/DeletedIndicatorPreferencePolicy.java'),
                    str(ROOT/'qa/java/TestDeletedIndicatorPreferencePolicy.java')], check=True)
    subprocess.run(['java', '-cp', output,
                    'com.waenhancer.ui.screens.privacy.TestDeletedIndicatorPreferencePolicy'], check=True)
print('PASS: Anti-Revoke indicator type-safe UI and compatible hook wiring')
subprocess.run([sys.executable, str(ROOT / 'qa/test_deleted_indicator_bridge.py')], check=True)
