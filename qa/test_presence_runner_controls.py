#!/usr/bin/env python3
"""Unit controls for negative classification, AAR inputs and evidence isolation.

Synthetic JUnit transcripts and archive payloads test only runner contracts,
never Android or WhatsApp behavior. Real compiled mutants and cold dependency
execution remain required by the framework runner and its companion.
"""
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import zipfile

from android_presence_row_test import behavioral_failures, execute, fresh_output, prepare_runtime_dependencies


NAMES = ('cachedDot', 'unknownText')
LOG = '''JUnit version 4.13.2
There were 2 failures:
1) cachedDot[28](PresenceRowRenderingTest)
java.lang.AssertionError: visible dot expected
    at org.junit.Assert.fail(Assert.java:89)
    at PresenceRowRenderingTest.cachedDot(PresenceRowRenderingTest.kt:21)
2) unknownText[34](PresenceRowRenderingTest)
org.junit.ComparisonFailure: expected:<[]> but was:<[Online]>
    at org.junit.Assert.assertEquals(Assert.java:117)
    at PresenceRowRenderingTest.unknownText(PresenceRowRenderingTest.kt:30)
FAILURES!!!
Tests run: 84,  Failures: 2
'''


class BehavioralFailureControls(unittest.TestCase):
    def reject(self, log=LOG, result=1):
        with self.assertRaises(RuntimeError):
            behavioral_failures(log, result, 84, NAMES)

    def test_accepts_assertion_and_comparison_failure(self):
        self.assertEqual(list(NAMES), behavioral_failures(LOG, 1, 84, NAMES))

    def test_accepts_junit_unqualified_sdk_name_and_crlf(self):
        log = LOG.replace('unknownText[34](', 'unknownText(').replace('\n', '\r\n')
        self.assertEqual(list(NAMES), behavioral_failures(log, 1, 84, NAMES))

    def test_rejects_success_exit_even_with_failure_text(self):
        self.reject(result=0)

    def test_rejects_incomplete_execution(self):
        self.reject(LOG.replace('Tests run: 84', 'Tests run: 83'))

    def test_rejects_no_failed_assertions(self):
        self.reject('FAILURES!!!\nTests run: 84, Failures: 0\n')

    def test_rejects_missing_or_repeated_summary(self):
        for log in ('Compilation failed: unresolved reference', LOG + 'Tests run: 84, Failures: 2\n'):
            with self.subTest(log=log):
                self.reject(log)

    def test_rejects_unidentified_failure(self):
        self.reject(LOG.replace('Failures: 2', 'Failures: 3'))

    def test_rejects_undeclared_scenario(self):
        self.reject(LOG.replace('cachedDot', 'undeclaredScenario'))

    def test_rejects_exception_instead_of_assertion(self):
        self.reject(LOG.replace('java.lang.AssertionError:', 'java.lang.NullPointerException:'))

    def test_rejects_assertion_from_setup(self):
        self.reject(LOG.replace('org.junit.Assert.fail(Assert.java:89)',
                                'PresenceRowRenderingTest.reset(PresenceRowRenderingTest.kt:8)'))

    def test_rejects_assertion_without_its_test_stack(self):
        self.reject(LOG.replace('PresenceRowRenderingTest.cachedDot(', 'OtherTest.cachedDot('))

    def test_rejects_framework_load_or_initialization_failures(self):
        for marker in ('initializationError', 'ClassNotFoundException', 'UnsupportedClassVersionError'):
            with self.subTest(marker=marker):
                self.reject(LOG + marker + '\n')


class RuntimeDependencyControls(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='waex_presence_deps_')
        self.addCleanup(self.temp.cleanup)
        # Windows TEMP can use an 8.3 alias; the helper returns resolved paths.
        self.root = Path(self.temp.name).resolve()
        self.deps = self.root/'deps'; self.deps.mkdir()
        (self.deps/'plain.jar').write_bytes(b'public dependency placeholder; not loaded by Java')
        self.aar = self.deps/'monitor.aar'
        with zipfile.ZipFile(self.aar, 'w') as archive:
            archive.writestr('classes.jar', b'monitor classes fixture')
            archive.writestr('libs/nested.jar', b'nested classes fixture')

    def snapshot(self):
        return {p.name: p.read_bytes() for p in self.deps.iterdir()}

    def prepare(self, name):
        out = fresh_output(self.root/name)
        return out, prepare_runtime_dependencies(self.deps, out)

    def test_fresh_inputs_supply_each_entrypoint_independently(self):
        before = self.snapshot()
        for name in ('positive', 'companion'):
            with self.subTest(entrypoint=name):
                out, jars = self.prepare(name)
                self.assertEqual({self.deps/'plain.jar', out/'aar-jars/monitor-classes.jar',
                                  out/'aar-jars/monitor-libs_nested.jar'}, set(jars))
                self.assertEqual(b'monitor classes fixture', jars[1].read_bytes())
                self.assertEqual(b'nested classes fixture', jars[2].read_bytes())
                manifest = json.loads((out/'runtime-classpath.json').read_text())
                self.assertEqual([str(p) for p in jars], [entry['path'] for entry in manifest])
                self.assertEqual(hashlib.sha256(b'monitor classes fixture').hexdigest(), manifest[1]['sha256'])
                self.assertEqual('classes.jar', manifest[1]['member'])
                self.assertEqual(hashlib.sha256(self.aar.read_bytes()).hexdigest(), manifest[1]['archive_sha256'])
        self.assertEqual(before, self.snapshot())

    def test_verified_warm_extraction_is_replaced_by_own_run_path(self):
        old = self.deps/'monitor-classes.jar'
        old.write_bytes(b'monitor classes fixture')
        before = self.snapshot()
        out, jars = self.prepare('warm')
        self.assertNotIn(old, jars)
        self.assertIn(out/'aar-jars/monitor-classes.jar', jars)
        self.assertEqual(before, self.snapshot())

    def test_mismatched_warm_extraction_is_rejected_without_rewriting_it(self):
        old = self.deps/'monitor-classes.jar'
        old.write_bytes(b'unrelated bytes')
        before = self.snapshot()
        with self.assertRaisesRegex(RuntimeError, 'differs from AAR'):
            self.prepare('mismatch')
        self.assertEqual(before, self.snapshot())

    def test_ambiguous_flattened_member_names_are_rejected(self):
        with zipfile.ZipFile(self.aar, 'w') as archive:
            archive.writestr('libs/a_b.jar', b'first')
            archive.writestr('libs/a/b.jar', b'second')
        with self.assertRaisesRegex(RuntimeError, 'Ambiguous extracted AAR output name'):
            self.prepare('ambiguous')

    def test_existing_prepared_output_is_preserved(self):
        out, _ = self.prepare('existing')
        before = {p.relative_to(out): p.read_bytes() for p in out.rglob('*') if p.is_file()}
        with self.assertRaisesRegex(RuntimeError, 'Refusing to overwrite existing evidence'):
            prepare_runtime_dependencies(self.deps, out)
        self.assertEqual(before, {p.relative_to(out): p.read_bytes() for p in out.rglob('*') if p.is_file()})

    def test_both_cli_entrypoints_reject_existing_output_before_tools(self):
        out = fresh_output(self.root/'previous')
        receipt = out/'receipt.txt'; receipt.write_bytes(b'irreplaceable previous evidence')
        qa = Path(__file__).parent
        commands = ([sys.executable, str(qa/'android_presence_row_test.py'), '--out', str(out)],
                    [sys.executable, str(qa/'mutate_presence_rows.py'), str(out), str(self.deps)])
        for command in commands:
            with self.subTest(entrypoint=command[1]):
                result = subprocess.run(command, capture_output=True, text=True, timeout=30)
                self.assertNotEqual(0, result.returncode)
                self.assertIn('Refusing to overwrite existing evidence', result.stdout + result.stderr)
                self.assertEqual([receipt], list(out.iterdir()))
                self.assertEqual(b'irreplaceable previous evidence', receipt.read_bytes())

    def test_execute_rejects_existing_variant_before_reading_sources(self):
        out = fresh_output(self.root/'variant')
        receipt = out/'receipt.txt'; receipt.write_bytes(b'previous variant evidence')
        with self.assertRaisesRegex(RuntimeError, 'Refusing to overwrite existing evidence'):
            execute(self.root/'nonexistent', out, self.deps, False, self.root/'host.apk', ())
        self.assertEqual([receipt], list(out.iterdir()))
        self.assertEqual(b'previous variant evidence', receipt.read_bytes())


if __name__ == '__main__':
    unittest.main(verbosity=2)
