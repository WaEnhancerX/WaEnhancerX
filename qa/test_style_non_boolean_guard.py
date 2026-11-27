#!/usr/bin/env python3
"""Check typed Styles editors, legacy aliases and fail-closed save feedback.

These are source-contract guards plus real JVM policy tests, not Compose or
Android device tests. Current editor destinations need not equal backup aliases.
"""
from pathlib import Path
import re
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / 'app/src/main/java/com/waenhancer'
# Historical backup contracts are separate from the current runtime consumers.
LEGACY = {
    'custom_theme': ('custom_css', 'STRING'),
    'custom_view_dpi': ('change_dpi', 'STRING'),
    'chat_filters': ('chatfilter', 'STRING'),
    'hide_ui_tabs': ('hidetabs', 'STRING_SET'),
    'quick_home_menu': ('show_home_menu', 'STRING'),
}
EDITORS = {
    'custom_theme': ('css_theme', 'String', 'TextValueEditorDialog', 'it.trim()'),
    'custom_view_dpi': ('change_dpi', 'String', 'TextValueEditorDialog', 'it.trim()'),
    'chat_filters': ('chatfilter', 'String', 'ChoiceValueEditorDialog', 'it'),
    'quick_home_menu': ('waex_menu_style', 'String', 'ChoiceValueEditorDialog', 'it'),
    'hide_ui_tabs': ('hidetabs', 'StringSet', 'MultiChoiceValueEditorDialog', 'it'),
}
CONSUMERS = {
    'css_theme': 'xposed/features/customization/CssThemeHook.kt',
    'change_dpi': 'xposed/features/customization/CustomDpiHook.java',
    'chatfilter': 'xposed/features/customization/ChatFilterDisplayHook.java',
    'waex_menu_style': 'xposed/features/homescreen/HomeScreenHeaderActionsHook.java',
    'hidetabs': 'xposed/features/customization/HideHomeTabsHook.java',
}


def check_wiring(styles):
    """Validate only the inspected editor contract; reject unsafe fixtures too."""
    assert 'if (StyleNonBooleanPreferencePolicy.requiresDedicatedEditor(key)) {' in styles
    assert 'if (StyleNonBooleanPreferencePolicy.requiresDedicatedEditor(key)) return@update' in styles
    assert re.search(r'if \(isLocked\)\s*\{\s*onActivatePro\(\)\s*\}\s*'
                     r'else if \(needsTypedEditor\)\s*\{\s*typedEditorKey = item.key', styles)
    assert re.search(r'if \(needsTypedEditor\)\s*\{\s*Icon\(', styles)
    assert 'StitchSwitch(' in styles and 'preferenceManager.putBooleans(changes)' in styles
    assert 'if (preferenceManager.putBooleans(changes))' in styles
    editor = styles.split('private fun TypedStyleEditorDialog(', 1)[1].split('\n@Composable', 1)[0]
    assert re.search(r'val completeSave: \(Boolean\) -> Unit = \{ saved ->\s*'
                     r'if \(saved\)\s*\{\s*onSaved\(\)\s*\}\s*else\s*\{', editor)
    assert editor.count('onSaved()') == 1, 'Only a successful commit may close the editor'
    assert '"Unable to save setting"' in editor
    assert 'onDismiss()' not in editor.split('when (key)', 1)[0]
    assert not re.search(r'preferenceManager\.put(?:String|StringSet|Boolean)\(', editor)
    for ui, (key, typ, dialog, value) in EDITORS.items():
        arm = editor.split('"' + ui + '" -> ' + dialog + '(', 1)[1]
        arm = re.split(r'\n        (?:"[^"]+"|else) ->', arm, maxsplit=1)[0]
        assert 'preferenceManager.get' + typ + '("' + key + '",' in arm
        call = f'completeSave(preferenceManager.putPublic{typ}("{key}", {value}))'
        assert 'onSave = { ' + call + ' }' in arm, ui
    # Do not erase optional receipt and current proximity integration when rebasing.
    if 'ManualSeenTickPreferencePolicy.isEnabled' in styles:
        # #35 may include the checked persistence follow-up independently of #39.
        assert ('preferenceManager.putPublicString("seentick", nextPlacement)' in styles
                or 'preferenceManager.putString("seentick",' in styles)
    assert '"disable_sensor_proximity"' in styles
    assert 'FeatureCatalog.groupsFor(4, "layout")' in styles
    assert 'LocalizationHelper.getTitle(context, item)' in styles
    assert 'LocalizationHelper.getDesc(context, item)' in styles


def main():
    styles = (SRC/'ui/screens/settings/StylesSettingsScreen.kt').read_text(encoding='utf-8')
    policy = (SRC/'ui/screens/settings/StyleNonBooleanPreferencePolicy.java').read_text(encoding='utf-8')
    schema = (SRC/'config/PreferenceSchema.java').read_text(encoding='utf-8')
    codec = (SRC/'core/preferences/SettingsBackupCodec.kt').read_text(encoding='utf-8')
    for ui, (key, typ) in LEGACY.items():
        assert 'case "' + ui + '"' in policy, ui
        assert re.search(r'add\(entries,\s*"'+key+r'",\s*Type\.'+typ+r'\b', schema), key
        assert re.search(r'"'+ui+r'"\s+to\s+"'+key+r'"', codec), ui
    for key, typ, _, _ in EDITORS.values():
        schema_type = 'STRING_SET' if typ == 'StringSet' else 'STRING'
        assert re.search(r'add\(entries,\s*"'+key+r'",\s*Type\.'+schema_type+r'\b', schema), key
        consumer = (SRC/CONSUMERS[key]).read_text(encoding='utf-8')
        if key == 'css_theme':
            # Current CSS hook reads the canonical constant through BaseFeature;
            # an explicitly empty canonical value must still beat the legacy key.
            assert 'const val CSS_KEY = "css_theme"' in consumer
            assert 'const val LEGACY_CSS_KEY = "custom_css"' in consumer
            assert '(if (hasPref(CSS_KEY)) getStringPref(CSS_KEY, "") else getStringPref(LEGACY_CSS_KEY, "")).orEmpty()' in consumer
        else:
            assert re.search(r'get'+typ+r'\(\s*"'+key+r'"', consumer), key
    check_wiring(styles)
    mutations = []
    for key, typ, _, value in EDITORS.values():
        checked = f'completeSave(preferenceManager.putPublic{typ}("{key}", {value}))'
        mutations.append(styles.replace(checked, f'preferenceManager.put{typ}("{key}", {value}); onSaved()', 1))
        mutations.append(styles.replace(checked, checked.replace('"'+key+'"', '"wrong_key"'), 1))
    mutations.extend([
        styles.replace('if (saved) {', 'if (true) {', 1),
        styles.replace('onSaved()\n        } else {', 'onSaved()\n        } else { onSaved()', 1),
        styles.replace('if (StyleNonBooleanPreferencePolicy.requiresDedicatedEditor(key)) return@update', '', 1),
        styles.replace('typedEditorKey = item.key', 'updatePreference(item.key, true)', 1),
    ])
    for index, fixture in enumerate(mutations):
        assert fixture != styles, f'Inert negative fixture {index}'
        try:
            check_wiring(fixture)
        except (AssertionError, IndexError):
            pass
        else:
            raise AssertionError(f'Unsafe source fixture {index} was accepted')
    with tempfile.TemporaryDirectory(prefix='waex_style_types_') as tmp:
        subprocess.run(['javac','--release','17','-d',tmp,
                        str(SRC/'ui/screens/settings/StyleNonBooleanPreferencePolicy.java'),
                        str(ROOT/'qa/TestStyleNonBooleanPreferencePolicy.java')],check=True)
        subprocess.run(['java','-cp',tmp,
                        'com.waenhancer.ui.screens.settings.TestStyleNonBooleanPreferencePolicy'],check=True)
    print(f'PASS: 5 typed editor/consumer contracts; {len(mutations)} unsafe fixtures rejected; legacy aliases retained')


if __name__ == '__main__':
    main()
