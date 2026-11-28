#!/usr/bin/env python3
"""Public preference schema regression plus the compiled Kotlin bubble-reader contract."""
from pathlib import Path
import subprocess
import sys
import tempfile
import re

root = Path(__file__).resolve().parents[1]
src = root / 'app/src/main/java/com/waenhancer'
schema = (src / 'config/PreferenceSchema.java').read_text()
definitions = re.findall(r'add\(entries, "([^"]+)", Type\.([A-Z_]+),', schema)
keys = dict(definitions)
assert len(definitions) == len(keys), 'Duplicate preference definitions'
assert 'waex_pro_active' not in keys, 'Never export derived license state'
for name,typ in {
    "anti_revoke_color_enabled": "BOOLEAN",
    "bubble_colors": "BOOLEAN",
    "bubble_incoming_color": "STRING",
    "bubble_outgoing_color": "STRING",
    "call_privacy_enabled": "BOOLEAN",
    "call_recording": "BOOLEAN",
    "channels_enhancements": "BOOLEAN",
    "dndmode": "BOOLEAN",
    "download_view_once": "BOOLEAN",
    "enable_media_preview": "BOOLEAN",
    "hide_recording": "BOOLEAN",
    "hide_typing": "BOOLEAN",
    "media_upload_quality": "BOOLEAN",
    "open_wae": "BOOLEAN",
    "separate_groups_tabs": "BOOLEAN",
    "statusdowload": "BOOLEAN",
    "video_preview_muted": "BOOLEAN",
}.items():
    assert keys.get(name) == typ, f'Missing/wrong typed key {name}'
with tempfile.TemporaryDirectory(prefix='waex_schema_coverage_') as out:
    subprocess.run(['javac','--release','17','-d',out,
                    str(src/'config/PreferenceSchema.java'),
                    str(root/'qa/java/TestFeaturePreferenceSchemaCoverage.java')],check=True)
    subprocess.run(['java','-cp',out,
                    'com.waenhancer.config.TestFeaturePreferenceSchemaCoverage'],check=True)
print('PASS: previously unknown hook/UI keys now included in exportable public schema')
subprocess.run([sys.executable, str(root / 'qa/test_bubble_color_precedence.py')], check=True)
