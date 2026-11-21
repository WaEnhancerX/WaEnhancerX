#!/usr/bin/env python3
"""Chinese resource contract tests; source checks, not an APK/device acceptance test."""
from collections import Counter
from pathlib import Path
import re
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / 'app/src/main/res'
LOCALES = ('zh-rCN', 'zh-rHK', 'zh-rTW')
FORMAT = re.compile(r'%(?:(\d+)\$)?[-#+ 0,(<]*\d*(?:\.\d+)?(?:[tT][A-Za-z]|[bBhHsScCdoxXeEfgGaA%n])')
TEMPLATE = re.compile(r'\$\{[^}]+\}')


def text(value):
    value = value or ''
    if len(value) > 1 and value[0] == value[-1] == '"':
        value = value[1:-1]
    return (value.replace(r'\n', '\n').replace(r'\t', '\t')
            .replace(r'\"', '"').replace(r"\'", "'").replace(r'\$', '$'))


def catalog(folder):
    found = {}
    for path in sorted((RES / folder).glob('*.xml')):
        for node in ET.parse(path).getroot():
            if node.tag not in ('string', 'plurals'):
                continue
            name = node.attrib['name']
            if name in found:
                raise AssertionError(f'Duplicate resource: {folder}/{name}')
            found[name] = (node, path)
    return found


def placeholders(value):
    return Counter(m.group(0) for m in FORMAT.finditer(text(value)))


def edges(value):
    value = text(value)
    return re.match(r'^\s*', value).group(0), re.search(r'\s*$', value).group(0)


class ChineseLocaleTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.base = catalog('values')
        cls.required = {k: v for k, v in cls.base.items() if v[0].get('translatable') != 'false'}
        cls.targets = {locale: catalog('values-' + locale) for locale in LOCALES}

    def test_complete_unique_resource_coverage(self):
        self.assertGreater(len(self.required), 1800)
        for locale, entries in self.targets.items():
            self.assertEqual(set(entries), set(self.required), locale)

    def test_source_attributes_and_types(self):
        for locale, entries in self.targets.items():
            for name, (node, _) in entries.items():
                source = self.required[name][0]
                self.assertEqual(source.tag, node.tag, (locale, name))
                self.assertEqual(source.get('formatted', 'true'), node.get('formatted', 'true'), (locale, name))

    def test_format_arguments(self):
        for locale, entries in self.targets.items():
            for name, (node, _) in entries.items():
                source = self.required[name][0]
                if node.tag == 'string':
                    self.assertEqual(placeholders(source.text), placeholders(node.text), (locale, name))
                else:
                    source_forms = {i.get('quantity'): i for i in source}
                    self.assertEqual(set(source_forms), {i.get('quantity') for i in node}, (locale, name))
                    for item in node:
                        self.assertEqual(placeholders(source_forms[item.get('quantity')].text), placeholders(item.text), (locale, name))

    def test_template_arguments(self):
        for locale, entries in self.targets.items():
            for name, (node, _) in entries.items():
                if node.tag == 'string':
                    self.assertEqual(Counter(TEMPLATE.findall(text(self.required[name][0].text))),
                                     Counter(TEMPLATE.findall(text(node.text))), (locale, name))

    def test_significant_boundary_whitespace(self):
        for locale, entries in self.targets.items():
            for name, (node, _) in entries.items():
                if node.tag == 'string':
                    self.assertEqual(edges(self.required[name][0].text), edges(node.text), (locale, name))

    def test_nonempty_text(self):
        for locale, entries in self.targets.items():
            for name, (node, _) in entries.items():
                if node.tag == 'string':
                    self.assertTrue(text(node.text).strip(), (locale, name))

    def test_ui_strings_keep_android_quoting(self):
        for locale, entries in self.targets.items():
            for name, (node, path) in entries.items():
                if path.name != 'ui_strings.xml':
                    continue
                items = [node] if node.tag == 'string' else list(node)
                for item in items:
                    self.assertTrue((item.text or '').startswith('"') and (item.text or '').endswith('"'), (locale, name))

    def test_technical_names_are_not_machine_translated(self):
        bad = ('钩子', '挂钩', '鉤子', '掛鉤', '根权限', '根權限', '根管理器', '专业版', '專業版')
        for locale, entries in self.targets.items():
            for name, (node, _) in entries.items():
                value = text(node.text)
                for word in bad:
                    self.assertNotIn(word, value, (locale, name))
                if re.search(r'\bhooks?\b', text(self.required[name][0].text), re.I):
                    self.assertIn('Hook', value, (locale, name))

    def test_tasker_parameter_keys(self):
        key = 'waex_ui_extra_number_1234567890_extra_message'
        for locale, entries in self.targets.items():
            value = text(entries[key][0].text)
            self.assertIn('number:1234567890\nExtra: message:', value, locale)

    def test_emoji_download_progress(self):
        for locale, entries in self.targets.items():
            self.assertIn('%1$d%%', text(entries['custom_emojis_downloading'][0].text), locale)

    def test_locale_registration_and_packaging(self):
        helper = (ROOT / 'app/src/main/java/com/waenhancer/utils/LocaleHelper.kt').read_text()
        build = (ROOT / 'app/build.gradle.kts').read_text()
        for locale in LOCALES:
            self.assertIn(f'LanguageOption("{locale.replace("-r", "-")}"', helper)
            self.assertIn(f'"{locale}"', build)

    def test_regional_network_terminology(self):
        key = 'custom_emojis_fetch_error'
        self.assertIn('网络', text(self.targets['zh-rCN'][key][0].text))
        self.assertIn('網絡', text(self.targets['zh-rHK'][key][0].text))
        self.assertIn('網路', text(self.targets['zh-rTW'][key][0].text))

    def test_emoji_confirmations_use_delete_terminology(self):
        keys = ('emoji_style_ui_remove_title', 'emoji_style_ui_clear_title', 'emoji_style_ui_clear_message')
        for locale, entries in self.targets.items():
            verb = '删除' if locale == 'zh-rCN' else '刪除'
            for key in keys:
                self.assertTrue(text(entries[key][0].text).startswith(verb), (locale, key))

    def test_resource_aliases_remain_references(self):
        aliases = {k: n.text for k, (n, _) in self.required.items() if (n.text or '').startswith('@string/')}
        self.assertEqual(len(aliases), 2)
        for locale, entries in self.targets.items():
            for name, reference in aliases.items():
                self.assertEqual(reference, entries[name][0].text, (locale, name, 'quoted aliases render as literal text'))
                self.assertIn(reference.removeprefix('@string/'), entries)

    def test_emoji_screen_references_are_defined(self):
        source = (ROOT / 'app/src/main/java/com/waenhancer/ui/screens/conversation/CustomEmojisScreen.kt').read_text()
        references = set(re.findall(r'R\.string\.([a-zA-Z0-9_]+)', source))
        self.assertFalse(references - set(self.base), references - set(self.base))
        self.assertIn('stringResource(R.string.custom_emojis_delete)', source)
        self.assertNotIn('R.string.waex_ui_remove_download', source)

    def test_negative_placeholder_controls(self):
        source = '%1$s %2$d%%'
        for bad in ('%1$d %2$d%%', '%1$s %2$d', '%1$s %3$d%%', '%1$s %2$d%% %2$d'):
            self.assertNotEqual(placeholders(source), placeholders(bad))

    def test_negative_boundary_controls(self):
        self.assertNotEqual(edges('" more\\n"'), edges('"更多"'))
        self.assertNotEqual(edges('"\\n\\nCopy details"'), edges('"复制详情"'))


if __name__ == '__main__':
    unittest.main(verbosity=2)
