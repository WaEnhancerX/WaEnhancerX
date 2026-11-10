#!/usr/bin/env python3
"""JDK-only alias key/persistence wiring test; no Android runtime claims."""
from pathlib import Path
import subprocess
import tempfile
import re

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / 'app/src/main/java/com/waenhancer'
styles = (SRC / 'ui/screens/settings/StylesSettingsScreen.kt').read_text()
policy = (SRC / 'ui/screens/settings/StyleBooleanAliasPolicy.java').read_text()
schema = (SRC / 'config/PreferenceSchema.java').read_text()
for key in ('segundos', 'novaconfig', 'igstatus', 'verify_blocked_contact'):
    assert re.search(r'add\(entries,\s*"' + key + r'",\s*Type\.BOOLEAN', schema), key
    assert 'return "' + key + '";' in policy
assert 'StyleBooleanAliasPolicy.canonicalKey(key)' in styles
assert 'preferenceManager.getBoolean(canonicalKey, legacyDefault)' in styles
assert 'preferenceManager.putBooleans(changes)' in styles
assert 'val changes = mapOf(canonicalKey to value)' in styles
# Upstream moved the audio-status route out of Styles; its media settings remain separate.
styles_keys = styles.split("val stylesKeys = remember {", 1)[1].split("val settingsState", 1)[0]
assert '"send_audio_as_voice_status"' not in styles_keys
assert 'settingsState[key] = preferenceManager.getBoolean(canonicalKey, value)' in styles
assert 'Unable to save setting' in styles
assert 'preferenceManager.putBoolean(StyleBooleanAliasPolicy.canonicalKey(key), value)' not in styles
assert 'preferenceManager.putBoolean(key, value)' not in styles
assert 'preferenceManager.getBoolean(key, defaultValue)' in styles
with tempfile.TemporaryDirectory(prefix='waex_style_aliases_') as tmp:
    subprocess.run(['javac', '--release', '17', '-d', tmp,
                    str(SRC / 'ui/screens/settings/StyleBooleanAliasPolicy.java'),
                    str(ROOT / 'qa/TestStyleBooleanAliasPolicy.java')], check=True)
    subprocess.run(['java', '-cp', tmp,
                    'com.waenhancer.ui.screens.settings.TestStyleBooleanAliasPolicy'], check=True)
print('PASS: canonical Boolean UI aliases, compatibility reads and typed schema retained')
