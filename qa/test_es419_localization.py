#!/usr/bin/env python3
"""Public display/resource contract. Static checks do not certify every runtime screen."""
from pathlib import Path
import re
from collections import Counter
from xml.etree import ElementTree as ET
import tempfile
from localization_support import entries, android_text, stage_string_resources

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT/'app/src/main/java'
base=entries(ROOT,'values');es=entries(ROOT,'values-es');latam=entries(ROOT,'values-b+es+419')
assert base.keys()==es.keys()==latam.keys(), 'Missing/extra Spanish resources'
assert es==latam, 'Spanish fallback and explicit es-419 diverged'
PATTERN=re.compile(r'%(?:(\d+)\$)?[-#+ 0,(]*\d*(?:\.\d+)?([sdif])')
checks=0
for key,(source,formatted) in base.items():
    target=latam[key][0]
    assert target.strip(),key
    assert not re.search('[\u202a-\u202e\u2066-\u2069\ufffd]',target),f'Unexpected bidi/replacement character: {key}'
    if formatted:
        def slots(text):
            seq=0;result=[]
            for index,typ in PATTERN.findall(text.replace('%%','')):
                if not index:seq+=1;index=str(seq)
                result.append((int(index),typ))
            return Counter(result)
        assert slots(source)==slots(target),(key,slots(source),slots(target))
    assert source.count('\n') == target.count('\n'), f'Changed paragraph breaks: {key}'
    checks+=1
for folder in ['values','values-es','values-b+es+419']:
    plural=ET.parse(ROOT/'app/src/main/res'/folder/'ui_strings.xml').getroot().find("plurals[@name='waex_recordings_deleted']")
    assert plural is not None and {e.attrib['quantity'] for e in plural}=={'one','other'}
    assert all('%1$d' in ''.join(e.itertext()) for e in plural)
# Every new static R reference is defined. Keep original English only as explicit fallback.
for path in SRC.rglob('*'):
    if path.suffix not in ['.kt','.java']:continue
    text=path.read_text(encoding="utf-8")
    for key in re.findall(r'R\.string\.(waex_ui_[a-z0-9_]+)',text):assert key in base,(path,key)
locale=(SRC/'com/waenhancer/utils/LocaleHelper.kt').read_text(encoding="utf-8")
assert 'LanguageOption("es-419"' in locale
assert 'DisplayLocalePolicy.canonicalCode' in locale
picker=(SRC/'com/waenhancer/ui/screens/settings/ModuleSettingsScreen.kt').read_text(encoding="utf-8")
assert 'text = lang.englishName' not in picker
assert 'getLanguageExplanation(lang.code)' in picker
assert 'listOf("System", "Light", "Dark")' in picker, 'Do not translate stored theme values'
assert 'putString(PREF_APP_LANGUAGE, langCode)' in locale
bridge=(SRC/'com/waenhancer/xposed/bridge/HookProvider.java').read_text(encoding="utf-8")
assert 'new HashMap<String, Object>(prefs.getAll())' in bridge, 'Retain existing language snapshot path'
assert 'UiText.initialize(appContext, bridgeClient)' in (SRC/'com/waenhancer/xposed/MainHook.java').read_text(encoding="utf-8")
assert 'UiText.initialize(this, PreferenceStores.publicStore(this))' in (SRC/'com/waenhancer/app/WaexApp.kt').read_text(encoding="utf-8")
helper=(SRC/'com/waenhancer/utils/UiText.java').read_text(encoding="utf-8")
assert 'Locale.setDefault' not in helper
assert 'getResourcePackageName' in helper and 'createPackageContext(MODULE_PACKAGE, 0)' in helper
assert 'getIdentifier(' not in helper, 'No repeated dynamic resource-name lookups'
assert 'arguments.length == 0' in helper
assert not re.search(r'preferences\.(?:edit|put|remove)',helper), 'Display helper must be read-only'
keep = ET.parse(ROOT/'app/src/main/res/raw/waex_localized_ui_keep.xml').getroot()
kept=keep.attrib['{http://schemas.android.com/tools}keep'].split(',')
assert '@string/waex_ui_*' in kept and '@plurals/waex_recordings_deleted' in kept
# Key enums, JIDs, SQL, cryptographic labels and selectors are intentionally not language strings.
media=(SRC/'com/waenhancer/xposed/features/media/MediaPreviewHook.kt').read_text(encoding="utf-8")
for k in ['WhatsApp Image Keys','WhatsApp Video Keys','WhatsApp Audio Keys','WhatsApp Document Keys']:
    assert f'"{k}".toByteArray(Charsets.UTF_8)' in media
schema=(SRC/'com/waenhancer/config/PreferenceSchema.java').read_text(encoding="utf-8")
assert 'UiText' not in schema
catalog=(ROOT/'app/src/main/assets/preference_catalog.json').read_text(encoding="utf-8")
assert 'disable_sensor_proximity' in catalog
dashboard=(SRC/'com/waenhancer/ui/screens/dashboard/MainDashboardScreen.kt').read_text(encoding="utf-8")
assert 'text = InternalLabels.hmaReason(item.reason)' in dashboard
assert 'text = item.reason,' not in dashboard, 'HMA reason must be localized at its display boundary'
# Negative controls: a missing slot, malformed token and bidi marker must be recognized.
for wrong in ['Sin variable', '%2$s', '%1$d']:
    assert slots('Count: %1$s') != slots(wrong)
assert re.search('[\u202a-\u202e\u2066-\u2069\ufffd]','a\u202eb')
print(f'PASS: {checks} string keys in default/es/es-419, positional formats, paragraphs, plurals, routing and stable keys; 4 negative controls')

# Dedicated and mixed values XML files must reach both the AAPT2 and framework
# fixtures. Styles/colors remain outside these isolated string tables.
with tempfile.TemporaryDirectory() as folder:
    fixture=Path(folder)/'fixture';staged=Path(folder)/'staged';identities={}
    for qualifier in ('values','values-es','values-b+es+419'):
        source=fixture/'app/src/main/res'/qualifier;source.mkdir(parents=True)
        (source/'extra.xml').write_text('<resources><string name="extra">Extra</string></resources>', encoding="utf-8")
        (source/'mixed.xml').write_text('<resources><color name="color">#000000</color>'
            '<string name="mixed">A &amp; B</string><plurals name="items"><item quantity="one">%1$d item</item>'
            '<item quantity="other">%1$d items</item></plurals></resources>', encoding="utf-8")
    stage_string_resources(fixture,staged,identities)
    assert len(identities)==6
    for qualifier in ('values','values-es','values-b+es+419'):
        assert (staged/qualifier/'extra.xml').read_bytes()==(fixture/'app/src/main/res'/qualifier/'extra.xml').read_bytes()
        mixed=ET.parse(staged/qualifier/'mixed.xml').getroot()
        assert [item.tag for item in mixed]==['string','plurals']
        assert mixed.find('string').text=='A & B'
print('PASS: dedicated/mixed resource files staged with original string/plural content and source hashes')
