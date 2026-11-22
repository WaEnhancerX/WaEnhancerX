#!/usr/bin/env python3
"""Ensure Styles proximity switch writes the actual sensor hook preference."""
from pathlib import Path
from catalog_source import catalog_features, string_resources
project=Path(__file__).resolve().parents[1]
root=project/'app/src/main/java/com/waenhancer'
screen=(root/'ui/screens/settings/StylesSettingsScreen.kt').read_text()
catalog=(root/'config/FeatureCatalog.kt').read_text()
aliases=(root/'config/PreferenceKeyAliases.java').read_text()
policy=(root/'xposed/features/media/ProximityEventPolicy.java').read_text()
hook=(root/'xposed/features/media/ProximitySensorHook.java').read_text()
schema=(root/'config/PreferenceSchema.java').read_text()
assert '"disable_sensor_proximity", Type.BOOLEAN' in schema
assert 'private static final String PREF_KEY = "disable_sensor_proximity"' in hook
assert 'isEnabled(PREF_KEY, false)' in hook
assert '"disable_sensor_proximity"' in screen
rows = catalog_features(project)
proximity = next(row for row in rows if row['key'] == 'disable_sensor_proximity')
assert proximity['tab_index'] == 4 and proximity['sub_tab_id'] == 'layout'
assert 'FeatureCatalog.groupsFor(4, "layout")' in screen
assert 'group.items()' in screen
assert 'LocalizationHelper.getTitle(context, item)' in screen
assert 'LocalizationHelper.getDesc(context, item)' in screen
assert '"miscellaneous_enhancements"' not in screen
assert string_resources(project)['feat_disable_sensor_proximity_title'].strip()
assert 'aliases.put("miscellaneous_enhancements", "disable_sensor_proximity")' in aliases
assert 'fun canonicalKey(key: String): String = PreferenceKeyAliases.canonical(key)' in catalog
assert 'keyToItem[PreferenceKeyAliases.canonical(it)]' in catalog
assert 'sensorType != PROXIMITY_TYPE' in policy
assert 'sensor.getType()' in hook
print('PASS: localized UI/catalog/schema/host sensor preference invariants')
