#!/usr/bin/env python3
"""Guard canonical WAEX backup keys against destructive legacy alias rewriting.

Structural QA only. For full executable Kotlin round-trips, run an Android/JVM
stub harness (JDK and kotlinc needed).
"""
from pathlib import Path
import re

root = Path(__file__).resolve().parents[1] / "app/src/main/java/com/waenhancer"
codec = (root / "core/preferences/SettingsBackupCodec.kt").read_text(encoding="utf-8")
schema = (root / "config/PreferenceSchema.java").read_text(encoding="utf-8")
keys = dict(re.findall(r'add\(entries, "([^"]+)", Type\.([A-Z_]+),', schema))
start = codec.index('private val LEGACY_ALIASES:')
stop = codec.index('\n    fun encode(', start)
aliases = dict(re.findall(r'"([^"]+)"\s+to\s+"([^"]+)"', codec[start:stop]))
resolver = codec[codec.index('private fun resolveKey('):codec.index('private fun isSensitive(')]
# Every still-declared preference keeps its own meaning in ALL backup formats.
assert 'if (PreferenceSchema.entry(originalKey) != null) return originalKey' in resolver
assert 'if (!legacyJson) return null' in resolver
assert 'val mappedKey = LEGACY_ALIASES[originalKey] ?: return null' in resolver
assert 'return mappedKey.takeIf { PreferenceSchema.entry(it) != null }' in resolver
assert 'original == null || original.type == mapped.type' not in resolver
assert 'val key = resolveKey(rawKey, legacyJson = false)' in codec
assert 'val key = resolveKey(rawKey, legacyJson = legacy)' in codec
assert 'if (legacy && rawKey != key && source.has(key)) continue' in codec, (
    'Canonical JSON values could be overwritten by an older alias')
assert 'val legacy = !root.has("schemaVersion")' in codec
assert 'Record(key, entry.type, it)' in codec
# A two-store restore is not atomic; partial successes must be compensated.
assert 'privateEditor.commit() && publicEditor.commit()' not in codec
assert 'restoreTouchedKeys(publicStore, publicBefore, publicKeys)' in codec
assert 'restoreTouchedKeys(privateStore, privateBefore, privateKeys)' in codec
assert 'Backup restore failed and rollback could not restore all preferences' in codec
assert 'snapshotForRollback' in codec
assert 'input.use { it.readBytes() }' not in codec
assert 'private fun readBoundedBackup(input: InputStream)' in codec
assert 'MAX_BYTES + 1 - consumed' in codec
assert 'require(consumed <= MAX_BYTES)' in codec
assert 'original.containsKey(key)' in codec
colliding = [(a, b) for a, b in aliases.items() if a in keys and b in keys]
incompatible = [(a, b) for a, b in colliding if keys[a] != keys[b]]
assert len(colliding) >= 30, 'expected current keys shadowed by legacy aliases'
assert len(incompatible) >= 4, 'expected cross-type aliases to remain protected'
for name in ('anti_revoke', 'status_text_composer', 'voice_note_speed_modifier',
             'floating_bottom_bar_scroll_hide'):
    assert any(a == name for a, _ in incompatible), name

# Equal storage types are not proof of semantic equivalence: each pair below
# controls an independent feature in the actual hook consumers.
for original, incorrectly_redirected in {
    'typing_online_toasts': 'typing_privacy',
    'hide_seen_receipts': 'hidereceipt',
}.items():
    assert keys[original] == keys[incorrectly_redirected] == 'BOOLEAN'
    assert aliases.get(original) != incorrectly_redirected, (
        f'Unsafe legacy alias redirected {original} into unrelated privacy control')

hooks = root / 'xposed/features'
toast = (hooks / 'automation/PresenceToastsHook.java').read_text()
typing = (hooks / 'privacy/TypingPrivacyHook.java').read_text()
receipts = (hooks / 'privacy/HideReceiptsHook.java').read_text()
toggles_src = (root / 'xposed/core/privacy/PrivacyToggle.java').read_text()


def toggle_keys(name):
    """onKeys of one PrivacyToggle constant (the first keys(...) list after its name)."""
    block = toggles_src[toggles_src.index('    ' + name + '('):]
    return set(re.findall(r'"([^"]+)"', block[:block.index('keys(') + block[block.index('keys('):].index(')')]))


assert 'isEnabled("typing_online_toasts", false)' in toast
# Privacy switches are resolved only through PrivacyToggle; each legacy key stays on its own toggle.
assert 'typing_privacy' in toggle_keys('HIDE_TYPING')
assert 'typing_online_toasts' not in toggles_src
assert 'PrivacyToggle.HIDE_TYPING' in typing
assert 'hide_seen_receipts' in toggle_keys('HIDE_VOICE_PLAYED')
assert 'hidereceipt' in toggle_keys('HIDE_DELIVERY')
assert 'hide_seen_receipts' not in toggle_keys('HIDE_DELIVERY')
assert 'hidereceipt' not in toggle_keys('HIDE_VOICE_PLAYED')
assert 'PrivacyToggle.HIDE_VOICE_PLAYED' in receipts and 'PrivacyToggle.HIDE_DELIVERY' in receipts
print(f'PASS: canonical keys preserved even in legacy JSON; '
      f'{len(colliding)} current-key aliases, {len(incompatible)} cross-type collisions protected; '
      'two unrelated privacy preferences kept separate')
