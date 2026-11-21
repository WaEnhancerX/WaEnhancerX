#!/usr/bin/env python3
"""Static fail-closed regression for exception messages in selected sensitive paths.

This does not scan unrelated app logging; source-only, not Android instrumentation.
"""
from pathlib import Path
import re

root = Path(__file__).resolve().parents[1] / 'app/src/main/java/com/waenhancer'
paths = {
    'privacy': 'xposed/features/privacy/CustomPrivacyHook.kt',
    'media': 'xposed/features/media/MediaPreviewHook.kt',
    'view_once': 'xposed/features/media/DownloadViewOnceHook.java',
    'proximity': 'xposed/features/media/ProximitySensorHook.java',
    'ipc': 'xposed/bridge/client/PreferenceBridgeClient.java',
}
src = {key:(root/path).read_text(encoding='utf-8') for key,path in paths.items()}


def hook_logs(text):
    start_marker = 'XposedBridge.log('
    offset = 0
    while True:
        at = text.find(start_marker, offset)
        if at == -1: break
        start = i = at + len(start_marker)
        depth, quote, escaped = 1, None, False
        while i < len(text):
            c = text[i]
            if quote is not None:
                if escaped: escaped=False
                elif c == '\\': escaped=True
                elif c == quote: quote=None
            elif c in ('"', "'"): quote=c
            elif c == '(': depth += 1
            elif c == ')':
                depth -= 1
                if depth == 0:
                    yield text[start:i]
                    break
            i += 1
        else:
            raise AssertionError('Malformed log expression')
        offset = i + 1


UNSAFE = re.compile(r'\b(?:t|e|ex|dbError|error)\s*\.\s*getMessage\s*\('
                    r'|\$\{\s*(?:t|e|ex|dbError|error)\s*\.\s*message\s*\}'
                    r'|\$message\b')


def check_log_parser(parser=hook_logs):
    """A delimiter inside quoted text must never hide a later unsafe payload."""
    cases = [
        ('ordinary safe log', 'TAG + "Failed: " + t.getClass().getSimpleName()', False),
        ('closing parenthesis before unsafe payload', 'TAG + "synthetic close ) " + error.getMessage()', True),
        ('opening parenthesis before unsafe payload', 'TAG + "synthetic open ( " + error.getMessage()', True),
        ('multiple literal parentheses', 'TAG + "literal (( )))" + t.getClass().getSimpleName()', False),
        ('escaped double quote', r'TAG + "escaped \" ) still quoted" + t.getMessage()', True),
        ('escaped backslash before closing quote', r'TAG + "backslash \\" + t.getMessage()', True),
        ('escaped quote after escaped backslash', r'TAG + "slash and quote \\\" ) still quoted" + t.getMessage()', True),
        ('closing character literal', "TAG + ')' + error.getMessage()", True),
        ('opening character literal', "TAG + '(' + t.getClass().getSimpleName()", False),
        ('escaped character quote', r"TAG + '\'' + error.getMessage()", True),
        ('nested calls with quoted delimiter', 'String.valueOf(format(")", error.getMessage()))', True),
        ('multiline expression', '\n    TAG + "multiline )" +\n    t.getMessage()\n', True),
        ('Kotlin interpolation payload', '"$TAG failed ) ${t.message}"', True),
        ('Kotlin category interpolation', '"$TAG failed ( ${t.javaClass.simpleName}"', False),
        ('quoted nested marker', 'TAG + "literal XposedBridge.log(" + error.getMessage()', True),
    ]
    checks = 0
    for label, expression, unsafe in cases:
        calls = list(parser('XposedBridge.log(' + expression + ');'))
        assert calls == [expression], f'Log parser changed/truncated expression: {label}'
        assert bool(UNSAFE.search(calls[0])) == unsafe, f'Log payload detector changed: {label}'
        checks += 1

    expressions = [cases[1][1], cases[0][1], cases[6][1]]
    adjacent = '\n'.join('XposedBridge.log(' + expression + ');' for expression in expressions)
    assert list(parser(adjacent)) == expressions, 'Log parser skipped or merged adjacent calls'
    checks += 1
    for incomplete in (
        'XposedBridge.log("quoted closing delimiters )))"',
        'XposedBridge.log("unterminated );',
        "XposedBridge.log(TAG + ');",
        r'XposedBridge.log("escaped end \");',
        'XposedBridge.log(TAG + error.getMessage();',
    ):
        try:
            list(parser(incomplete))
        except AssertionError:
            pass
        else:
            raise AssertionError('Malformed log expression accepted: ' + incomplete)
        checks += 1
    return checks


parser_checks = check_log_parser()
for key,source in src.items():
    calls = list(hook_logs(source))
    assert len(calls) >= (2 if key == 'proximity' else 3), f'Logging removed unexpectedly in {key}'
    for i,expr in enumerate(calls):
        assert not UNSAFE.search(expr), f'{key} may leak exception details at call {i+1}'

for probe in ('"Leak ${t.message}"', '"Leak ${dbError.message}"',
              '"Leak $message"', 'TAG + "Leak " + t.getMessage()'):
    assert UNSAFE.search(probe), 'Detector failed closed on an unsafe fixture'
for probe in ('"Failed ${t.javaClass.simpleName}"',
              'TAG + "Failed " + t.getClass().getSimpleName()'):
    assert not UNSAFE.search(probe), 'False-positive for class-only diagnostic'

assert src['privacy'].count('${t.javaClass.simpleName}') == 4
assert src['media'].count('${t.javaClass.simpleName}') >= 4
assert src['media'].count('${dbError.javaClass.simpleName}') == 2
assert 'Error: ${e.javaClass.simpleName}' in src['media']
assert src['ipc'].count('t.getClass().getSimpleName()') >= 5
assert 'error.getMessage()' not in src['ipc'], 'Persistent runtime health leaks exception text'
assert 'String detail = error.getClass().getSimpleName();' in src['ipc']
print('PASS: exception text redacted from selected custom-privacy, media, view-once, proximity and IPC logs/status')
print(f'PASS: {parser_checks} log parser/payload controls; quotes, escapes, literal parentheses and malformed calls')
