#!/usr/bin/env python3
"""Positive and negative fixtures for inline/asset catalog source QA."""
from pathlib import Path
import copy
import json
import tempfile
import unittest
import xml.etree.ElementTree as ET
from catalog_source import catalog_features

ROOT = Path(__file__).resolve().parents[1]
SOURCE = '''private const val ASSET_NAME = "preference_catalog.json"
private val canonicalGroupOverrides = mapOf(
    "alpha" to "second"
)
.flatMap { it.items() }
.filter { item -> canonicalGroupOverrides[item.key]?.let { it == item.groupId } ?: true }
.distinctBy { it.key }
'''
GROUPS = [
    {'id': 'first', 'tabIndex': 1, 'tabId': 'privacy', 'keys': ['alpha', 'beta']},
    {'id': 'second', 'tabIndex': 4, 'subTabId': 'layout', 'tabId': 'styles',
     'keys': ['alpha'], 'proKeys': ['alpha']},
    {'id': 'navigation', 'tabIndex': -1, 'tabId': 'settings', 'keys': ['route']},
]
STRINGS = {**{f'feat_{k}_{s}': f'{k} {s}' for k in ('alpha','beta','route') for s in ('title','desc')},
           **{f'sec_{g}': g for g in ('first','second','navigation')},
           **{f'catalog_tab_{t}': t for t in ('privacy','styles','settings')}}

class CatalogSourceTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='waex-catalog-source-')
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.source = self.root/'app/src/main/java/com/waenhancer/config/FeatureCatalog.kt'
        self.asset = self.root/'app/src/main/assets/preference_catalog.json'
        self.resources = self.root/'app/src/main/res/values/strings.xml'
        for p in (self.source,self.asset,self.resources): p.parent.mkdir(parents=True,exist_ok=True)
        self.write()
    def write(self,groups=None,strings=None,source=SOURCE):
        self.source.write_text(source)
        self.asset.write_text(json.dumps(GROUPS if groups is None else groups))
        xml=ET.Element('resources')
        for key,value in (STRINGS if strings is None else strings).items():
            ET.SubElement(xml,'string',name=key).text=value
        ET.ElementTree(xml).write(self.resources,encoding='utf-8')
    def rejects(self):
        with self.assertRaises(ValueError): catalog_features(self.root)
    def test_asset_canonical_order_and_routing(self):
        rows=catalog_features(self.root)
        self.assertEqual(['beta','alpha','route'],[r['key'] for r in rows])
        alpha=rows[1]
        self.assertEqual(('second',4,'layout',True),(alpha['group_id'],alpha['tab_index'],alpha['sub_tab_id'],alpha['is_pro']))
        self.assertIsNone(alpha['catalog_line'])
        self.assertEqual('app/src/main/assets/preference_catalog.json',alpha['catalog_source'])
        self.assertEqual(-1,rows[2]['tab_index'])
    def test_first_placement_without_override(self):
        groups=copy.deepcopy(GROUPS);groups[1]['keys'].append('beta');self.write(groups)
        self.assertEqual('first',next(r for r in catalog_features(self.root) if r['key']=='beta')['group_id'])
    def test_legacy_inline(self):
        self.write(source='FeatureItem("one", "One", "desc")\nFeatureItem("two", "Two", "desc")')
        self.assertEqual([1,2],[r['catalog_line'] for r in catalog_features(self.root)])
    def test_duplicate_inline_rejected(self):
        self.write(source='FeatureItem("one", "One")\nFeatureItem("one", "Two")');self.rejects()
    def test_duplicate_group_rejected(self):
        self.write(GROUPS+[GROUPS[0]]);self.rejects()
    def test_duplicate_key_in_group_rejected(self):
        groups=copy.deepcopy(GROUPS);groups[0]['keys'].append('alpha');self.write(groups);self.rejects()
    def test_duplicate_json_property_rejected(self):
        self.asset.write_text('[{"id":"first","id":"second"}]');self.rejects()
    def test_empty_catalog_rejected(self):
        self.write([]);self.rejects()
    def test_blank_key_rejected(self):
        groups=copy.deepcopy(GROUPS);groups[0]['keys'].append(' ');self.write(groups);self.rejects()
    def test_missing_title_rejected(self):
        strings=dict(STRINGS);del strings['feat_alpha_title'];self.write(strings=strings);self.rejects()
    def test_empty_description_rejected(self):
        self.write(strings={**STRINGS,'feat_alpha_desc':' '});self.rejects()
    def test_duplicate_xml_string_rejected(self):
        data=self.resources.read_text().replace('</resources>','<string name="feat_alpha_title">Other</string></resources>')
        self.resources.write_text(data);self.rejects()
    def test_invalid_canonical_destination_rejected(self):
        self.write(source=SOURCE.replace('"alpha" to "second"','"alpha" to "missing"'));self.rejects()
    def test_undeclared_pro_key_rejected(self):
        groups=copy.deepcopy(GROUPS);groups[1]['proKeys'].append('missing');self.write(groups);self.rejects()
    def test_boolean_tab_index_rejected(self):
        groups=copy.deepcopy(GROUPS);groups[0]['tabIndex']=True;self.write(groups);self.rejects()
    def test_path_traversal_rejected(self):
        self.write(source=SOURCE.replace('"preference_catalog.json"','"../private.json"'));self.rejects()
    def test_unsupported_routing_rejected(self):
        self.write(source=SOURCE.replace('.distinctBy { it.key }','.distinctBy { it.groupId }'));self.rejects()
    def test_current_tree_uses_asset_and_92_canonical_features(self):
        rows=catalog_features(ROOT)
        self.assertEqual(92,len(rows));self.assertEqual(92,len({r['key'] for r in rows}))
        by_key={r['key']:r for r in rows}
        self.assertNotIn('call_privacy',by_key)
        self.assertNotIn('always_typing',by_key)
        self.assertEqual('home_layout_navigation',by_key['status_style']['group_id'])
        self.assertEqual('media_utility_downloader',by_key['download_view_once']['group_id'])
        self.assertEqual('media_quality_status',by_key['send_audio_as_voice_status']['group_id'])
        self.assertEqual('layout',by_key['disable_sensor_proximity']['sub_tab_id'])
        self.assertEqual(('home_layout_navigation', 4, 'layout', False),
                         (by_key['metaai']['group_id'], by_key['metaai']['tab_index'],
                          by_key['metaai']['sub_tab_id'], by_key['metaai']['is_pro']))
        self.assertTrue(all(r['label'].strip() for r in rows))

    def test_meta_ai_control_retains_existing_storage_and_hook_contract(self):
        source=ROOT/'app/src/main/java/com/waenhancer'
        styles=(source/'ui/screens/settings/StylesSettingsScreen.kt').read_text()
        keys=styles.split('val stylesKeys = remember {',1)[1].split('val settingsState',1)[0]
        self.assertIn('"metaai"',keys)
        self.assertIn('preferenceManager.putBooleans(changes)',styles)
        hook=(source/'xposed/features/homescreen/ChatListCustomizationsHook.java').read_text()
        self.assertIn('return isEnabled("metaai", false);',hook)
        schema=(source/'config/PreferenceSchema.java').read_text()
        self.assertIn('"metaai", Type.BOOLEAN, Sensitivity.PUBLIC_SETTING, Store.PUBLIC',schema)
        values=[]
        for folder in ('values-es','values-b+es+419'):
            tree=ET.parse(ROOT/f'app/src/main/res/{folder}/meta_ai_control.xml')
            values.append({n.attrib['name']:n.text for n in tree.getroot()})
        self.assertEqual(values[0],values[1])
        self.assertEqual({'feat_metaai_title','feat_metaai_desc'},set(values[0]))

if __name__=='__main__': unittest.main()
