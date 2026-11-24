#!/usr/bin/env python3
"""JDK source-level regression for the manual read-receipt toolbar preference.

No Android build or LSPosed runtime behavior is implied by passing.
"""
from pathlib import Path
import subprocess
import json
import xml.etree.ElementTree as ET
import tempfile

root = Path(__file__).resolve().parents[1]
src = root / "app/src/main/java/com/waenhancer"
ui = (src / "ui/screens/settings/StylesSettingsScreen.kt").read_text()
cat = (src / "config/FeatureCatalog.kt").read_text()
schema = (src / "config/PreferenceSchema.java").read_text()
blue = (src / "xposed/features/privacy/BlueOnReplyHook.java").read_text()
receipts = (src / "xposed/features/privacy/HideReceiptsHook.java").read_text()
assert '"seentick", Type.STRING' in schema
assert '"seen_tick_customization"' in ui
assert 'preferenceManager.getString("seentick", "0")' in ui
assert 'preferenceManager.putPublicString("seentick", nextPlacement)' in ui
assert 'preferenceManager.putString("seentick",' not in ui
assert 'preferenceManager.putBoolean("seen_tick_customization"' not in ui
assert 'ManualSeenTickPreferencePolicy.valueAfterExplicitToggle(value, currentPlacement)' in ui
assert 'FeatureCatalog.groupsFor(4, "appearance")' in ui
assert 'preference_catalog.json' in cat
catalog = json.loads((root / 'app/src/main/assets/preference_catalog.json').read_text())
assert any('seen_tick_customization' in g['keys'] and g['tabIndex'] == 4 for g in catalog)
for resource in (root / 'app/src/main/res').glob('values*/strings.xml'):
    entries = {e.attrib.get('name'): e.text for e in ET.parse(resource).getroot() if e.tag == 'string'}
    assert entries.get('feat_seen_tick_customization_title')
    assert entries.get('feat_seen_tick_customization_desc')
    if resource.parent.name == 'values':
        assert entries['feat_seen_tick_customization_title'] == 'Manual Blue Tick Button'
        assert 'held read receipts' in entries['feat_seen_tick_customization_desc']
assert 'prefs.getAll().get("seentick")' in blue
# HideReceipts reads it through PrivacyPrefs.flag, which accepts Boolean, Number and String values.
assert 'getPrivacy().flag("seentick")' in receipts
privacy_prefs = (src / "xposed/core/privacy/PrivacyPrefs.java").read_text()
assert 'if (value instanceof Number) return ((Number) value).intValue() != 0;' in privacy_prefs
assert '!"0".equals(text) && !"false".equalsIgnoreCase(text)' in privacy_prefs
# The button sends receipts withheld by any privacy setting (Ghost Mode, Hide blue ticks,
# Hide delivered), including older ones from the persistent ledger, so it only needs its own
# switch. It must never send while a setting still withholds that chat's receipts.
assert 'return getPrivacy().flag("seentick");' in blue
assert 'getPrivacy().appliesTo(context, PrivacyToggle.HIDE_DELIVERY, key)) return SEND_BLOCKED;' in blue
assert 'ConversationRecipientPolicy.resolve(candidates)' in blue
assert 'extras.keySet()' not in blue
assert 'extractRemoteJid(' not in blue
assert 'Cannot verify chat recipient' in blue

with tempfile.TemporaryDirectory() as tmp:
    subprocess.run(['javac', '--release', '17', '-d', tmp,
                    str(src / "ui/screens/settings/ManualSeenTickPreferencePolicy.java"),
                    str(root / "qa/java/TestManualSeenTickPreferencePolicy.java")], check=True)
    subprocess.run(['java', '-cp', tmp,
                    'com.waenhancer.ui.screens.settings.TestManualSeenTickPreferencePolicy'], check=True)
print('PASS: manual read receipt preference, source wiring and hook contract')
