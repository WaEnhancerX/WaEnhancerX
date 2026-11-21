#!/usr/bin/env python3
"""Require explicit older factory/reflection APIs before D8/R8 processing.

This source policy inspects JVM bytecode, not final APK compatibility. D8/R8 has
automatic Method.getParameterCount and List.of backports, so native API floors
and a baseline rejection here do not prove an APK linkage failure. Existing
behavior suites cover the same selectors. The separate cache suite executes
the production manager against a PackageInfo double that lacks the API 28
getLongVersionCode method.
"""
from pathlib import Path
import argparse
import re
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = Path('app/src/main/java/com/waenhancer')
REFLECTION = re.compile(r'\.\s*(?:getParameterCount\s*\(|parameterCount\b)')
FACTORY = re.compile(r'\b(?:java\s*\.\s*util\s*\.\s*)?List\s*\.\s*of\s*\(')
POLICY_MEMBER_REFERENCES = re.compile(
    r'java/lang/reflect/(?:Method|Constructor|Executable)\.getParameterCount:'
    r'|java/util/List\.of:')


def executable_text(source):
    # Preserve newlines and offsets for useful errors; comments/string literals
    # are not API calls. The targeted spellings do not occur in interpolation.
    ignored = re.compile(r'/\*[\s\S]*?\*/|//[^\n]*|"""[\s\S]*?"""|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'')
    return ignored.sub(lambda match: ''.join('\n' if c == '\n' else ' ' for c in match.group()), source)


def source_violations(root):
    problems = []
    files = sorted(p for p in (root / PACKAGE).rglob('*') if p.suffix in ('.java', '.kt'))
    assert files, 'production source tree missing'
    for path in files:
        source = executable_text(path.read_text())
        for pattern, minimum in [(REFLECTION, 26), (FACTORY, 30)]:
            for match in pattern.finditer(source):
                line = source.count('\n', 0, match.start()) + 1
                problems.append(f'{path.relative_to(root)}:{line}: native API floor {minimum}; explicit older API required: {match.group().strip()}')
    return files, problems


def compiled_violations(root):
    with tempfile.TemporaryDirectory(prefix='waex-api24-symbols-') as temporary:
        stage = Path(temporary)
        # This shared guard remains in main after the view-once and local-delete
        # reverts. Compile it directly: this policy must not depend on fixtures
        # or selectors that belong to an optional/reverted feature.
        source = root / PACKAGE / 'xposed/core/devkit/HookReturnTypeGuard.java'
        classes = stage / 'classes'
        subprocess.run(['javac', '--release', '17', '-d', str(classes),
                        str(source)], check=True, timeout=60)
        print('COMPILE_OK: complete production HookReturnTypeGuard; no platform doubles', flush=True)
        # Inspect the actual production class and any nested classes. The
        # source scan below still covers every production Java/Kotlin file.
        names = []
        prefix = 'com/waenhancer/xposed/core/devkit/HookReturnTypeGuard'
        for path in sorted(classes.glob(prefix + '*.class')):
            names.append(path.relative_to(classes).as_posix()[:-6].replace('/', '.'))
        assert prefix.replace('/', '.') in names, 'production guard bytecode missing'
        result = subprocess.run(['javap', '-classpath', str(classes), '-c', '-p', *names],
                                check=True, capture_output=True, text=True, timeout=60)
        return names, sorted(set(POLICY_MEMBER_REFERENCES.findall(result.stdout)))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--source-root', type=Path, default=ROOT,
                        help='Compile/check an unchanged earlier source tree as a negative control')
    args = parser.parse_args()
    root = args.source_root.resolve()
    # Compile before rejecting the baseline. A syntax/dependency failure must not
    # be mistaken for evidence that this source policy caught a member reference.
    names, bytecode = compiled_violations(root)
    files, source = source_violations(root)
    if bytecode or source:
        print('\n'.join(['SOURCE_POLICY: ' + issue for issue in bytecode + source]), flush=True)
        raise AssertionError(f'API 24 source policy rejected {len(source)} source sites and {len(bytecode)} pre-D8/R8 JVM member references')
    print(f'PASS: targeted API 24 source policy; {len(files)} production files; {len(names)} pre-D8/R8 production classfiles')


if __name__ == '__main__':
    main()
