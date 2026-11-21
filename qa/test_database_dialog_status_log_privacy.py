#!/usr/bin/env python3
"""Fail-closed source regression for 21 selected diagnostics; no Android runtime."""
from pathlib import Path
import re

if not __debug__:
    raise SystemExit("BLOCKED: privacy assertions are disabled under Python -O")

ROOT = Path(__file__).resolve().parents[1] / 'app/src/main/java/com/waenhancer/xposed'
FILES = {
    'core/db/MessageStore.kt': 4,
    'features/media/StatusDownloadHook.java': 17,
}
unsafe = re.compile(
    r'\b(?:t|e|ex|failure)\.getMessage\(\)'
    r'|\$\{(?:t|e|ex|failure)\.message\}'
    r'|\+\s*(?:t|e|ex|failure)(?![\w.])'
    r'|\+\s*(?:t|e|ex|failure)\.toString\(\)'
    r'|Log\.getStackTraceString\s*\('
    r'|\+\s*(?:keyId|jid|chatJid|messageId)\b'
)

for name, count in FILES.items():
    src = (ROOT / name).read_text(encoding='utf-8')
    # The independent JVM contract test parses complete Java expressions;
    # this source check preserves explicit callsite review and checks Kotlin.
    logs = [line.strip() for line in src.splitlines() if 'XposedBridge.log(' in line]
    assert len(logs) == count, (name, len(logs), count)
    for index, expr in enumerate(logs, start=1):
        assert not unsafe.search(expr), (name, index, expr)
        if name.endswith('StatusDownloadHook.java'):
            assert ('getClass().getSimpleName()' in expr or
                    'StatusPlaybackActivity not found — icon placement unavailable' in expr), (
                name, index, expr
            )
        else:
            assert '.javaClass.simpleName}' in expr, (name, index, expr)

for fixture, expected in [
    ('TAG + "Error " + t.getMessage()', True),
    ('TAG + "Error " + t', True),
    ('TAG + "Error " + t.getClass().getSimpleName()', False),
    ('TAG + "Error " + Log.getStackTraceString(t)', True),
    ('TAG + "Error " + t.toString()', True),
    ('TAG + "Error " + keyId', True),
    ('"Error ${e.message}"', True),
    ('"Error ${e.javaClass.simpleName}"', False),
]:
    assert bool(unsafe.search(fixture)) == expected, fixture

store = (ROOT / 'core/db/MessageStore.kt').read_text()
dialog = (ROOT / 'core/components/WaexBottomSheet.kt').read_text()
status = (ROOT / 'features/media/StatusDownloadHook.java').read_text()
for operation in ('getIdFromKey', 'getMediaFromId', 'findAccountDataDir'):
    assert operation in store
for operation in ('setTitle(', 'setMessage(', 'setPositiveButton('):
    assert operation in dialog
assert 'saveMediaFile' in status
assert 'Log.getStackTraceString(' not in status
start = status.index('    private void saveMediaFile(')
end = status.index('    private boolean exportStatus(', start)
save = status[start:end]
assert 'exportStatus(ctx, srcFile);' in save
export_start = end
export_end = status.index('    private boolean hasSavedStatus(', export_start)
export = status[export_start:export_end]
assert 'StatusMediaExporter.export(ctx, srcFile);' in export
assert 'R.string.waex_ui_failed_to_download_status' in export
assert 'R.string.waex_ui_status_saved_to_gallery_waex' in export
assert 'failure.getClass().getSimpleName()' in export
assert 'getMessage()' not in save
assert 'getMessage()' not in export
print('PASS: 21 DB and status diagnostics checked; private exception payloads rejected')
