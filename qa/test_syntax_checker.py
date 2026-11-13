#!/usr/bin/env python3
"""End-to-end CLI regressions for the source-only Java/Kotlin syntax checker.

Requires JDK 17+; Kotlin compiler/CLI is required for cases exercising Kotlin
PSI. No Android SDK, Gradle, WAEX/WhatsApp APKs, or private submodules needed.
"""
from __future__ import annotations

import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

RUNNER = Path(__file__).with_name('check_syntax.py')
TIMEOUT = 120


class SyntaxCheckerTests(unittest.TestCase):
    def setUp(self):
        self.work = tempfile.TemporaryDirectory(prefix='waex_syntax_fixture_')
        self.addCleanup(self.work.cleanup)
        self.root = Path(self.work.name)

    def write(self, path: str, content: str) -> None:
        target = self.root / path
        target.parent.mkdir(parents=True, exist_ok=True)
        # Preserve fixture line endings exactly; Windows text mode would turn
        # intentional CRLF into CR-CR-LF and shift parser error locations.
        target.write_bytes(content.encode('utf-8'))

    def run_checker(self, *options: str, env_overrides: dict | None = None):
        env = os.environ.copy()
        if env_overrides:
            env.update(env_overrides)
        outcome = subprocess.run([sys.executable, str(RUNNER),
                                  '--root', str(self.root), '--json', *options],
                                 text=True, capture_output=True,
                                 timeout=TIMEOUT, env=env, check=False)
        try:
            payload = json.loads(outcome.stdout)
        except json.JSONDecodeError as error:
            self.fail(f'Invalid JSON: {error}; stderr={outcome.stderr[:500]}')
        return outcome.returncode, payload

    def test_valid_java_and_kotlin(self):
        self.write('src/Good.java', 'public class Good { int value = 10; }\n')
        self.write('src/Good.kt', 'fun sample(x: Int) = x + 1\n')
        code, obj = self.run_checker()
        self.assertEqual(code, 0, obj)
        self.assertEqual(obj['status'], 'passed')
        self.assertEqual((obj['java_files'], obj['kotlin_files']), (1, 1))
        self.assertEqual(obj['syntax_errors'], [])
        self.assertFalse(obj['kotlin_skipped'])

    def test_java_utf8_is_independent_of_legacy_windows_default_charset(self):
        self.write('src/International.java',
                   'class International { String text = "日本語 — 💁 🦍"; }\n')
        code, obj = self.run_checker(
            '--java-only',
            env_overrides={'JAVA_TOOL_OPTIONS': '-Dfile.encoding=windows-1252'})
        self.assertEqual(code, 0, obj)
        self.assertEqual(obj['status'], 'passed')
        self.assertEqual((obj['java_files'], obj['kotlin_files']), (1, 0))
        self.assertFalse(obj['syntax_errors'])

    def test_invalid_utf8_java_bytes_are_rejected(self):
        self.root.joinpath('Invalid.java').write_bytes(
            b'class Invalid { String text = "\xff"; }\n')
        code, obj = self.run_checker('--java-only')
        self.assertEqual(code, 1, obj)
        self.assertEqual(obj['status'], 'syntax_error')
        self.assertTrue(any(error['language'] == 'java'
                            for error in obj['syntax_errors']))

    def test_invalid_java_and_kotlin_are_reported(self):
        self.write('src/Broken.java', 'public class Broken { void broken( { }\n')
        self.write('src/Broken.kt', 'fun broken( {\n')
        code, obj = self.run_checker()
        self.assertEqual(code, 1, obj)
        self.assertEqual(obj['status'], 'syntax_error')
        self.assertIn('java', {e['language'] for e in obj['syntax_errors']})
        self.assertIn('kotlin', {e['language'] for e in obj['syntax_errors']})
        self.assertTrue(all(e['line'] >= 1 for e in obj['syntax_errors']))
        self.assertTrue(all(not e['file'].startswith(str(self.root)) for e in obj['syntax_errors']))

    def test_windows_crlf_and_legacy_cr_are_normalized_for_kotlin_psi(self):
        self.write('src/Good.java', 'class Good {}\r\n')
        self.write('src/Windows.kt', 'fun windows() = 1\r\nfun windowsAgain() = 2\r\n')
        self.write('src/Legacy.kt', 'fun old() = 1\rval oldAgain = 2\r')
        code, obj = self.run_checker()
        self.assertEqual(code, 0, obj)
        self.assertEqual(obj['status'], 'passed')
        self.assertEqual((obj['java_files'], obj['kotlin_files']), (1, 2))
        self.assertFalse(obj['syntax_errors'])

    def test_crlf_kotlin_syntax_errors_still_fail_at_original_line(self):
        self.write('src/Good.java', 'class Good {}\r\n')
        self.write('src/Broken.kt', 'val valid = 1\r\nfun broken( {\r\n')
        code, obj = self.run_checker()
        self.assertEqual(code, 1, obj)
        self.assertEqual(obj['status'], 'syntax_error')
        self.assertTrue(any(e['language'] == 'kotlin' and e['line'] == 2
                            for e in obj['syntax_errors']))

    def test_missing_kotlin_compiler_fails_closed(self):
        self.write('Good.java', 'class Good {}\n')
        self.write('Good.kt', 'val answer = 42\n')
        code, obj = self.run_checker(env_overrides={'KOTLIN_HOME': str(self.root / 'absent_kotlin')})
        self.assertEqual(code, 2, obj)
        self.assertEqual(obj['status'], 'infrastructure_error')
        self.assertTrue(any('Kotlin compiler' in e for e in obj['toolchain_errors']))
        self.assertFalse(obj['kotlin_skipped'])

    def test_java_only_is_explicitly_partial(self):
        self.write('Good.java', 'class Good {}\n')
        self.write('Bad.kt', 'fun broken( {\n')
        code, obj = self.run_checker('--java-only', env_overrides={'KOTLIN_HOME': str(self.root / 'absent')})
        self.assertEqual(code, 0, obj)
        self.assertTrue(obj['kotlin_skipped'])
        self.assertEqual(obj['status'], 'partial_java_only')
        self.assertEqual((obj['java_files'], obj['kotlin_files']), (1, 1))

    def test_java_only_without_java_cannot_appear_successful(self):
        self.write('Good.kt', 'val good = 1\n')
        code, obj = self.run_checker('--java-only')
        self.assertEqual(code, 2, obj)
        self.assertTrue(obj['toolchain_errors'])

    def test_generated_directories_are_excluded(self):
        self.write('src/Good.java', 'public class Good {}\n')
        for directory in ('build', '.gradle', '.git', 'node_modules', '__pycache__', 'out'):
            self.write(f'{directory}/Broken.java', 'public class Broken { (\n')
            self.write(f'{directory}/Broken.kt', 'fun broken( {\n')
        code, obj = self.run_checker()
        self.assertEqual(code, 0, obj)
        self.assertEqual(obj['java_files'], 1)
        self.assertEqual(obj['kotlin_files'], 0)

    def test_empty_source_tree_rejected(self):
        self.write('README.md', 'Nothing to parse\n')
        self.write('build/Generated.kt', 'fun generated( {\n')
        code, obj = self.run_checker()
        self.assertEqual(code, 2, obj)
        self.assertIn('No .java or .kt', obj['toolchain_errors'][0])

    def test_skips_gradle_scripts_and_checks_no_type_resolution(self):
        self.write('build.gradle.kts', 'fun wrong( {\n')
        self.write('src/Demo.java', 'import not.installed.Type; class Demo { Type value; }\n')
        self.write('src/Demo.kt', 'fun example(thing: NonexistentType): Any = thing\n')
        code, obj = self.run_checker()
        self.assertEqual(code, 0, obj)
        self.assertEqual((obj['java_files'], obj['kotlin_files']), (1, 1))

    def test_java_syntax_error_not_silently_ignored_in_java_only_mode(self):
        self.write('Broken.java', 'public class Broken { not syntax ? }\n')
        self.write('Good.kt', 'val answer = 42\n')
        code, obj = self.run_checker('--java-only')
        self.assertEqual(code, 1, obj)
        self.assertEqual(obj['status'], 'syntax_error')
        self.assertTrue(obj['kotlin_skipped'])
        self.assertTrue(any(e['language'] == 'java' for e in obj['syntax_errors']))

    def test_missing_root_rejected(self):
        self.root = self.root / 'does_not_exist'
        code, obj = self.run_checker()
        self.assertEqual(code, 2, obj)
        self.assertTrue(any('root does not exist' in x.lower() for x in obj['toolchain_errors']))


if __name__ == '__main__':
    unittest.main()
