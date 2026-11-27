#!/usr/bin/env python3
"""Validate suite identities, source inventory and fail-closed fixture selection."""
import ast
import os
from pathlib import Path
import re
import subprocess
import tempfile
import textwrap
import unittest

import verify_stability_framework as runner

ROOT = Path(__file__).resolve().parents[1]
CLOCK = ROOT / 'app/src/main/java/com/waenhancer/ui/components/RuntimeObservationClock.kt'
EXPECTED = {'update': 12, 'filter': 3, 'evidence': 15, 'clock': 1}


def literal_assignment(name):
    tree = ast.parse((ROOT / 'qa/verify_stability_framework.py').read_text())
    values = [
        node.value for node in ast.walk(tree)
        if isinstance(node, ast.Assign)
        and any(isinstance(target, ast.Name) and target.id == name for target in node.targets)
    ]
    if len(values) != 1:
        raise AssertionError('Expected one literal assignment for ' + name)
    return ast.literal_eval(values[0])


def fake_root(root, with_clock=False):
    for mode in EXPECTED:
        fixture = root / ('qa/fixtures/Stability' + mode.title() + 'Test.kt')
        fixture.parent.mkdir(parents=True, exist_ok=True)
        fixture.touch()
    if with_clock:
        clock = root / 'app/src/main/java/com/waenhancer/ui/components/RuntimeObservationClock.kt'
        clock.parent.mkdir(parents=True)
        clock.touch()


class StabilitySelectionTests(unittest.TestCase):
    def test_current_all_runs_current_classifier_once(self):
        expected = ['update', 'filter', 'evidence'] + (['clock'] if CLOCK.is_file() else [])
        self.assertEqual(expected, runner.select_modes('all', ROOT))
        self.assertEqual(['evidence'], runner.select_modes('evidence', ROOT))

    def test_absent_clock_fails_instead_of_claiming_classifier_is_clock(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            fake_root(root)
            self.assertEqual(['update', 'filter', 'evidence'], runner.select_modes('all', root))
            with self.assertRaisesRegex(ValueError, 'no clock suite ran'):
                runner.select_modes('clock', root)

    def test_present_clock_keeps_distinct_identity(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            fake_root(root, with_clock=True)
            self.assertEqual(['update', 'filter', 'evidence', 'clock'], runner.select_modes('all', root))
            self.assertEqual(['clock'], runner.select_modes('clock', root))

    def test_missing_selected_fixture_is_fatal(self):
        for mode in EXPECTED:
            with self.subTest(mode=mode), tempfile.TemporaryDirectory() as tmp:
                root = Path(tmp)
                fake_root(root, with_clock=True)
                (root / ('qa/fixtures/Stability' + mode.title() + 'Test.kt')).unlink()
                with self.assertRaisesRegex(ValueError, 'Missing selected stability fixture'):
                    runner.select_modes(mode, root)
                with self.assertRaisesRegex(ValueError, 'Missing selected stability fixture'):
                    runner.select_modes('all', root)

    def test_standalone_mode_does_not_require_other_fixtures(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            fixture = root / 'qa/fixtures/StabilityEvidenceTest.kt'
            fixture.parent.mkdir(parents=True)
            fixture.touch()
            self.assertEqual(['evidence'], runner.select_modes('evidence', root))

    def test_unknown_mode_is_rejected(self):
        with self.assertRaisesRegex(ValueError, 'Unknown stability mode'):
            runner.select_modes('obsolete', ROOT)

    def test_preserved_method_inventory_and_runner_totals(self):
        self.assertEqual(EXPECTED, literal_assignment('expected'))
        for mode, count in EXPECTED.items():
            fixture = ROOT / ('qa/fixtures/Stability' + mode.title() + 'Test.kt')
            methods = re.findall(r'@Test\s+fun\s+(\w+)', fixture.read_text())
            self.assertEqual(count, len(methods), mode)
            self.assertEqual(len(methods), len(set(methods)), mode)
        total = 31 if CLOCK.is_file() else 30
        self.assertEqual(total, sum(EXPECTED[m] for m in runner.select_modes('all', ROOT)))

    def test_current_modes_stage_existing_production_sources(self):
        sources = literal_assignment('sources')
        self.assertEqual(set(EXPECTED), set(sources))
        self.assertEqual(['utils/InjectionEvidence.kt'], sources['evidence'])
        for mode in runner.select_modes('all', ROOT):
            self.assertEqual(len(sources[mode]), len(set(sources[mode])), mode)
            for path in sources[mode]:
                self.assertTrue((ROOT / 'app/src/main/java/com/waenhancer' / path).is_file(), path)
        for path in ('ui/designsystem/WaexColors.kt', 'xposed/utils/HookErrors.java'):
            self.assertIn(path, sources['update'])
        for path in ('xposed/features/customization/LayoutPassThrottle.kt',
                     'xposed/utils/HostResourceIds.java', 'xposed/utils/HookErrors.java'):
            self.assertIn(path, sources['filter'])


def workflow_job():
    workflow = (ROOT / '.github/workflows/source-qa.yml').read_text()
    return workflow.split('\n  stability-framework:\n', 1)[1].split('\n  preserved-vault-framework:\n', 1)[0]


def selection_script():
    step = workflow_job().split('      - name: Select only implementations actually present and require their fixtures\n', 1)[1]
    return textwrap.dedent(step.split('        run: |\n', 1)[1].split('\n      - ', 1)[0])


def execute_selection(root):
    with tempfile.TemporaryDirectory(prefix='waex-mode-output-') as tmp:
        output = Path(tmp) / 'github-output'
        result = subprocess.run(
            ['bash', '-c', selection_script()], cwd=root,
            env=dict(os.environ, GITHUB_OUTPUT=str(output)),
            capture_output=True, text=True, timeout=10
        )
        return result, output.read_text() if output.exists() else ''


def source_qa_step(name):
    workflow = (ROOT / '.github/workflows/source-qa.yml').read_text()
    marker = '      - name: ' + name + '\n'
    if workflow.count(marker) != 1:
        raise AssertionError('Expected one Source QA step: ' + name)
    step = workflow.split(marker, 1)[1]
    return textwrap.dedent(step.split('        run: |\n', 1)[1].split('\n      - ', 1)[0])


def execute_source_step(root, name, prefix='', **environment):
    return subprocess.run(
        ['bash', '-c', prefix + source_qa_step(name)], cwd=root,
        env=dict(os.environ, **environment), capture_output=True, text=True, timeout=10
    )


def empty_optional_sources(root):
    for name in ('customization/FilterItemsHook.kt', 'media/CallRecordingHook.java'):
        path = root / 'app/src/main/java/com/waenhancer/xposed/features' / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.touch()


class WorkflowSelectionTests(unittest.TestCase):
    def test_current_workflow_selects_evidence_once(self):
        self.assertFalse(CLOCK.exists())
        result, output = execute_selection(ROOT)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual('modes= update filter evidence\n', output)

    def test_workflow_rejects_missing_evidence_fixture(self):
        with tempfile.TemporaryDirectory(prefix='waex-mode-missing-') as tmp:
            root = Path(tmp)
            source = root / 'app/src/main/java/com/waenhancer/utils/InjectionEvidence.kt'
            source.parent.mkdir(parents=True)
            source.touch()
            result, output = execute_selection(root)
        self.assertNotEqual(0, result.returncode)
        self.assertEqual('', output)

    def test_optional_clock_requires_its_own_fixture(self):
        with tempfile.TemporaryDirectory(prefix='waex-mode-clock-') as tmp:
            root = Path(tmp)
            clock = root / 'app/src/main/java/com/waenhancer/ui/components/RuntimeObservationClock.kt'
            clock.parent.mkdir(parents=True)
            clock.touch()
            result, output = execute_selection(root)
            self.assertNotEqual(0, result.returncode)
            self.assertEqual('', output)
            fixture = root / 'qa/fixtures/StabilityClockTest.kt'
            fixture.parent.mkdir(parents=True)
            fixture.touch()
            (root / 'qa/verify_stability_framework.py').touch()
            result, output = execute_selection(root)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual('modes= clock\n', output)

    def test_ci_checks_selection_separately_and_keeps_combined_modes(self):
        job = workflow_job()
        self.assertIn('run: python3 qa/test_stability_framework_selection.py\n', job)
        self.assertNotIn('python3 qa/test_stability_framework_selection.py', selection_script())
        self.assertIn("if [[ \"$MODES\" == ' update filter evidence' || \"$MODES\" == ' update clock filter evidence' ]]; then", job)


class SourceQaWiringTests(unittest.TestCase):
    def test_optional_clock_requires_current_framework_files(self):
        with tempfile.TemporaryDirectory(prefix='waex-source-clock-') as tmp:
            root = Path(tmp)
            fake_root(root, with_clock=True)
            empty_optional_sources(root)
            (root / 'qa/verify_stability_framework.py').touch()
            self.assertFalse((root / 'qa/test_runtime_observation_clock.py').exists())
            result = execute_source_step(root, 'Require stability tests when their implementations are present')
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)

    def test_missing_optional_clock_fixture_or_runner_is_fatal(self):
        for missing in ('qa/fixtures/StabilityClockTest.kt', 'qa/verify_stability_framework.py'):
            with self.subTest(missing=missing), tempfile.TemporaryDirectory(prefix='waex-source-clock-missing-') as tmp:
                root = Path(tmp)
                fake_root(root, with_clock=True)
                empty_optional_sources(root)
                (root / 'qa/verify_stability_framework.py').touch()
                # The retired script must not satisfy the current contract.
                (root / 'qa/test_runtime_observation_clock.py').touch()
                (root / missing).unlink()
                result = execute_source_step(root, 'Require stability tests when their implementations are present')
                self.assertNotEqual(0, result.returncode, missing)

    def test_absent_clock_does_not_require_its_optional_framework_files(self):
        with tempfile.TemporaryDirectory(prefix='waex-source-clock-absent-') as tmp:
            root = Path(tmp)
            empty_optional_sources(root)
            result = execute_source_step(root, 'Require stability tests when their implementations are present')
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)

    def test_required_source_loop_executes_injection_contract_and_propagates_failure(self):
        name = 'Run required public-source JVM regression suites'
        step = source_qa_step(name)
        scripts = re.findall(r'^\s+(qa/[A-Za-z0-9_/]+\.py)\s*$', step, re.MULTILINE)
        target = 'qa/test_injection_evidence_contract.py'
        self.assertEqual(1, scripts.count(target))
        # This is a shell-dispatch recorder, not execution of the Python suites.
        recorder = '''python3() {
  printf '%s\\n' "$1" >> "$WAEX_DISPATCH_LOG"
  [[ -f "$1" ]] || return 2
  [[ "$1" != "$WAEX_FAIL_SUITE" ]] || return 23
}
'''
        with tempfile.TemporaryDirectory(prefix='waex-source-dispatch-') as tmp:
            root = Path(tmp)
            for script in scripts:
                path = root / script
                path.parent.mkdir(parents=True, exist_ok=True)
                path.touch()
            for scenario, failure, wanted in (('all', '', 0), ('failed', target, 23), ('missing', '', 2)):
                with self.subTest(scenario=scenario):
                    if scenario == 'missing':
                        (root / target).unlink()
                    log = root / (scenario + '.log')
                    result = execute_source_step(root, name, recorder,
                        WAEX_DISPATCH_LOG=str(log), WAEX_FAIL_SUITE=failure)
                    self.assertEqual(wanted, result.returncode, result.stdout + result.stderr)
                    calls = log.read_text().splitlines()
                    expected = scripts if scenario == 'all' else scripts[:scripts.index(target) + 1]
                    self.assertEqual(expected, calls)


if __name__ == '__main__':
    unittest.main()
