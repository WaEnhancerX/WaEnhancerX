#!/usr/bin/env python3
"""Check canonical suite selection and the actual CI discovery shell, without builds."""
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
EXPECTED = {'update': 12, 'filter': 3, 'evidence': 15}


def literal_assignment(name):
    tree = ast.parse((ROOT / 'qa/verify_stability_framework.py').read_text())
    matches = [
        node.value for node in ast.walk(tree)
        if isinstance(node, ast.Assign)
        and any(isinstance(target, ast.Name) and target.id == name for target in node.targets)
    ]
    if len(matches) != 1:
        raise AssertionError('Expected one literal assignment for ' + name)
    return ast.literal_eval(matches[0])


def selection_script():
    workflow = (ROOT / '.github/workflows/source-qa.yml').read_text()
    job = workflow.split('\n  stability-framework:\n', 1)[1]
    step = job.split('      - name: Select only implementations actually present and require their fixtures\n', 1)[1]
    body = step.split('        run: |\n', 1)[1].split('\n      - ', 1)[0]
    return textwrap.dedent(body)


def execute_selection(root):
    with tempfile.TemporaryDirectory(prefix='waex-mode-output-') as tmp:
        output = Path(tmp) / 'github-output'
        result = subprocess.run(
            ['bash', '-c', selection_script()], cwd=root,
            env=dict(os.environ, GITHUB_OUTPUT=str(output)),
            capture_output=True, text=True, timeout=10
        )
        return result, output.read_text() if output.exists() else ''


class StabilitySelectionTests(unittest.TestCase):
    def test_all_selects_each_canonical_suite_once(self):
        modes = runner.select_modes('all')
        self.assertEqual(['update', 'filter', 'evidence'], modes)
        self.assertEqual(len(modes), len(set(modes)))
        self.assertNotIn('clock', modes)

    def test_clock_is_only_an_alias(self):
        self.assertEqual(['evidence'], runner.select_modes('clock'))
        self.assertEqual(runner.select_modes('evidence'), runner.select_modes('clock'))
        for mode in ('update', 'filter', 'evidence'):
            self.assertEqual([mode], runner.select_modes(mode))
        with self.assertRaises(ValueError):
            runner.select_modes('obsolete')

    def test_independent_method_inventory_and_runner_expectations(self):
        self.assertEqual(EXPECTED, literal_assignment('expected'))
        for mode, count in EXPECTED.items():
            fixture = ROOT / ('qa/fixtures/Stability' + mode.title() + 'Test.kt')
            methods = re.findall(r'@Test\s+fun\s+(\w+)', fixture.read_text())
            self.assertEqual(count, len(methods), mode)
            self.assertEqual(len(methods), len(set(methods)), mode)
        self.assertEqual(30, sum(EXPECTED.values()))
        self.assertFalse((ROOT / 'qa/fixtures/StabilityClockTest.kt').exists())

    def test_every_staged_source_exists_without_retired_clock(self):
        sources = literal_assignment('sources')
        self.assertEqual(set(EXPECTED), set(sources))
        self.assertEqual(['utils/InjectionEvidence.kt'], sources['evidence'])
        for mode, paths in sources.items():
            self.assertEqual(len(paths), len(set(paths)), mode)
            for path in paths:
                self.assertNotIn('RuntimeObservationClock', path)
                self.assertTrue((ROOT / 'app/src/main/java/com/waenhancer' / path).is_file(), path)
        self.assertIn('update/UpdateChannel.kt', sources['update'])
        self.assertIn('update/ReleaseIndex.kt', sources['update'])
        self.assertIn('ui/designsystem/WaexColors.kt', sources['update'])
        self.assertIn('xposed/features/customization/LayoutPassThrottle.kt', sources['filter'])
        self.assertIn('xposed/utils/HostResourceIds.java', sources['filter'])

    def test_current_workflow_selects_evidence_with_no_retired_source(self):
        self.assertFalse((ROOT / 'app/src/main/java/com/waenhancer/ui/components/RuntimeObservationClock.kt').exists())
        result, output = execute_selection(ROOT)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual('modes= update evidence filter\n', output)
        self.assertNotIn('RuntimeObservationClock', selection_script())

    def test_workflow_fails_if_current_evidence_fixture_is_missing(self):
        with tempfile.TemporaryDirectory(prefix='waex-mode-missing-') as tmp:
            root = Path(tmp)
            source = root / 'app/src/main/java/com/waenhancer/utils/InjectionEvidence.kt'
            source.parent.mkdir(parents=True)
            source.touch()
            result, output = execute_selection(root)
        self.assertNotEqual(0, result.returncode)
        self.assertEqual('', output)

    def test_retired_clock_alone_does_not_select_a_suite(self):
        with tempfile.TemporaryDirectory(prefix='waex-mode-retired-') as tmp:
            root = Path(tmp)
            source = root / 'app/src/main/java/com/waenhancer/ui/components/RuntimeObservationClock.kt'
            source.parent.mkdir(parents=True)
            source.touch()
            result, output = execute_selection(root)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual('modes=\n', output)


if __name__ == '__main__':
    unittest.main()
