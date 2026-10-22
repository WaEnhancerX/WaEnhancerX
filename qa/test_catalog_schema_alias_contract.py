#!/usr/bin/env python3
"""Read-only feature catalog contract audit (not a hook or Android runtime test).

Classifies catalog keys into typed public preferences, legacy UI aliases, and
navigation-only entries. Do not infer that an alias is a working UI switch.
"""
from pathlib import Path
import re
from catalog_source import catalog_features

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / 'app/src/main/java/com/waenhancer'

NAVIGATION_ONLY = {'supported_versions', 'license_verification', 'pro_features_unlock'}
LEGACY_UI = {
    'custom_theme': ('custom_css', 'STRING'),
    'custom_time_format': ('segundos', 'BOOLEAN'),
    'custom_toolbar': ('novaconfig', 'BOOLEAN'),
    'custom_view_dpi': ('change_dpi', 'STRING'),
    'seen_tick_customization': ('seentick', 'STRING'),
    'chat_filters': ('chatfilter', 'STRING'),
    'hide_ui_tabs': ('hidetabs', 'STRING_SET'),
    'instagram_status_layout': ('igstatus', 'BOOLEAN'),
    'quick_home_menu': ('show_home_menu', 'STRING'),
    'contact_blocked_verify': ('verify_blocked_contact', 'BOOLEAN'),
    'miscellaneous_enhancements': ('disable_sensor_proximity', 'BOOLEAN'),
}
CATALOG_PATTERN = re.compile(r'FeatureItem\("([^"]+)"\s*,')
SCHEMA_PATTERN = re.compile(r'add\(entries,\s*"([^"]+)"\s*,\s*Type\.([A-Z_]+)')
ALIAS_PATTERN = re.compile(r'"([^"]+)"\s+to\s+"([^"]+)"')


def parse_catalog(text):
    return CATALOG_PATTERN.findall(text)


def parse_schema(text):
    pairs = SCHEMA_PATTERN.findall(text)
    assert len(pairs) == len(dict(pairs)), 'Duplicate PreferenceSchema keys'
    return dict(pairs)


def parse_aliases(text):
    start = text.index('private val LEGACY_ALIASES:')
    end = text.index('\n    )', start)
    pairs = ALIAS_PATTERN.findall(text[start:end])
    assert len(pairs) == len(dict(pairs)), 'Duplicate backup alias keys'
    return dict(pairs)


def validate(catalog, schema, aliases):
    assert len(catalog) == len(set(catalog)), 'FeatureCatalog has duplicate keys'
    missing = set(catalog) - set(schema)
    allowed = NAVIGATION_ONLY | (set(LEGACY_UI) & set(catalog))
    assert missing == allowed, (f'Unclassified catalog keys: {sorted(missing-allowed)}; '
                                f'expected non-schema entries now typed: {sorted(allowed-missing)}')
    for key, (canonical, expected_type) in LEGACY_UI.items():
        assert aliases.get(key) == canonical, f'Backup alias broken: {key} -> {canonical}'
        assert schema.get(canonical) == expected_type, f'Type mismatch: {canonical} expected {expected_type}'
    assert not NAVIGATION_ONLY.intersection(schema), 'Navigation or license routes became preferences'
    return len(catalog), len(missing)


def main():
    cat = [row['key'] for row in catalog_features(ROOT)]
    schema = parse_schema((SRC / 'config/PreferenceSchema.java').read_text(encoding='utf-8'))
    aliases = parse_aliases((SRC / 'core/preferences/SettingsBackupCodec.kt').read_text(encoding='utf-8'))
    count, missing = validate(cat, schema, aliases)
    assert count == 92, 'Catalog size changed; review and update feature acceptance matrix'
    assert schema['status_style'] == 'STRING', 'Status style must remain a typed choice'
    assert missing in (13, 14), f'Unexpected intentionally non-schema keys: {missing}'
    # Negative fixtures: an unknown catalog entry, wrong alias, or promotion
    # of license/navigation keys to writable preferences must be rejected.
    for invalid_catalog, invalid_schema, invalid_aliases in [
        (cat + ['unknown_new_feature'], schema, aliases),
        (cat, schema, {**aliases, 'hide_ui_tabs': 'hideread'}),
        (cat, {**schema, 'license_verification': 'BOOLEAN'}, aliases),
    ]:
        try:
            validate(invalid_catalog, invalid_schema, invalid_aliases)
        except AssertionError:
            pass
        else:
            raise AssertionError('Negative fixture was incorrectly accepted')

    active = set(cat) & set(LEGACY_UI)
    risky = sorted(key for key, (_, kind) in LEGACY_UI.items()
                   if kind != 'BOOLEAN' and key in active)
    style = (SRC / 'ui/screens/settings/StylesSettingsScreen.kt').read_text(encoding='utf-8')
    if 'ManualSeenTickPreferencePolicy.valueAfterExplicitToggle' in style:
        risky = [key for key in risky if key != 'seen_tick_customization']

    print(f'PASS: {count} catalog entries, {len(schema)} typed preferences, '
          f'{len(active)} legacy aliases, {len(NAVIGATION_ONLY)} navigation-only keys')
    print('MANUAL UI REVIEW REQUIRED (non-Boolean legacy keys): ' + ', '.join(risky))
    print('This test verifies catalog/schema/backup metadata, not correct Style UI persistence.')


if __name__ == '__main__':
    main()
