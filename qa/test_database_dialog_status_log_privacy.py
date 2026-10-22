#!/usr/bin/env python3
"""Fail-closed source regression for 18 diagnostics; no Android runtime implied."""
from pathlib import Path
import re

ROOT=Path(__file__).resolve().parents[1]/'app/src/main/java/com/waenhancer/xposed'
FILES={
 'core/db/MessageStore.kt':4,
 'core/components/NativeWhatsAppDialog.kt':8,
 'features/media/StatusDownloadHook.java':6,
}
unsafe=re.compile(r'\b(?:t|e|ex)\.getMessage\(\)|\$\{(?:t|e|ex)\.message\}|\+\s*(?:t|e|ex)(?![\w.])')
for name,count in FILES.items():
 src=(ROOT/name).read_text(encoding='utf-8')
 logs=[l.strip() for l in src.splitlines() if 'XposedBridge.log(' in l]
 assert len(logs)==count,(name,len(logs),count)
 for i,log in enumerate(logs):
  assert not unsafe.search(log),(name,i+1,log)
  assert ('getClass().getSimpleName()' in log or '.javaClass.simpleName}' in log),(name,i+1)
for fixture,expected in [
 ('TAG + "Error " + t.getMessage()',True),
 ('TAG + "Error " + t',True),
 ('TAG + "Error " + t.getClass().getSimpleName()',False),
 ('"Error ${e.message}"',True),
 ('"Error ${e.javaClass.simpleName}"',False),
]:
 assert bool(unsafe.search(fixture))==expected,fixture

store=(ROOT/'core/db/MessageStore.kt').read_text()
dialog=(ROOT/'core/components/NativeWhatsAppDialog.kt').read_text()
status=(ROOT/'features/media/StatusDownloadHook.java').read_text()
for operation in ('getIdFromKey','getMediaFromId','findAccountDataDir'):
 assert operation in store
for operation in ('setTitle(', 'setMessage(', 'setPositiveButton('):
 assert operation in dialog
assert 'saveMediaFile' in status
# The status helper now propagates failed closes/publication to one localized UI path.
# Keep all diagnostic counts and private-payload negative controls above intact.
start=status.index('    private void saveMediaFile(')
end=status.index('    private void copyToClipboard(', start)
save=status[start:end]
assert 'StatusMediaExporter.export(ctx, srcFile);' in save
assert 'waex_ui_failed_to_download_status_19426d76' in save
assert 'failure.getClass().getSimpleName()' in save
assert 'getMessage()' not in save
print('PASS: 18 DB, native dialog, and status-save diagnostics redact private exception texts')
