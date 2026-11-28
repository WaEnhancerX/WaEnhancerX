#!/usr/bin/env python3
"""Test manual-retry mutation selection without substituting receipt behavior."""
import ast
import importlib.util
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


RUNNER = Path(__file__).with_name('mutate_manual_receipt_retry.py')
SPEC = importlib.util.spec_from_file_location('manual_retry_mutations', RUNNER)
RUNNER_MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(RUNNER_MODULE)
EXPECTED_NAMES = [
    'lost-delayed-retry',
    'lost-synchronous-retry',
    'duplicate-veto-recovery',
    'missing-enqueue-ack',
    'lost-delivery-only-retry',
]


class MutationSelectionTest(unittest.TestCase):
    def setUp(self):
        tree = ast.parse(RUNNER.read_text(encoding='utf-8'))
        matches = [node.value for node in ast.walk(tree)
                   if isinstance(node, ast.Assign)
                   and any(isinstance(target, ast.Name) and target.id == 'mutations'
                           for target in node.targets)]
        self.assertEqual(1, len(matches))
        self.mutations = ast.literal_eval(matches[0])

    def test_complete_matrix_keeps_all_five_reviewed_cases(self):
        self.assertEqual(EXPECTED_NAMES, [case[0] for case in self.mutations])
        self.assertEqual(self.mutations,
                         RUNNER_MODULE.select_mutations(self.mutations, EXPECTED_NAMES[0]))

    def test_every_named_suffix_keeps_order_and_original_inputs(self):
        original = self.mutations.copy()
        for index, name in enumerate(EXPECTED_NAMES):
            with self.subTest(start_at=name):
                selected = RUNNER_MODULE.select_mutations(self.mutations, name)
                self.assertEqual(EXPECTED_NAMES[index:], [case[0] for case in selected])
                self.assertEqual(original[index:], selected)
        self.assertEqual(original, self.mutations)

    def test_unknown_and_empty_names_are_rejected(self):
        for value in ('', 'unknown', 'lost-delayed-retry-extra'):
            with self.subTest(start_at=value):
                with self.assertRaisesRegex(ValueError, 'Unknown mutation start:'):
                    RUNNER_MODULE.select_mutations(self.mutations, value)

    def test_unknown_cli_name_has_no_output_side_effect(self):
        with tempfile.TemporaryDirectory(prefix='waex-mutation-selection-') as directory:
            output = Path(directory) / 'must-not-exist'
            result = subprocess.run(
                [sys.executable, str(RUNNER), '--out', str(output),
                 '--kotlinc', 'not-invoked', '--start-at', 'unknown'],
                capture_output=True, text=True, timeout=10, check=False)
            self.assertEqual(2, result.returncode)
            self.assertIn('Unknown mutation start: unknown', result.stderr)
            self.assertFalse(output.exists())

    def test_cli_documents_partial_run_boundary(self):
        result = subprocess.run([sys.executable, str(RUNNER), '--help'],
                                capture_output=True, text=True, timeout=10, check=False)
        self.assertEqual(0, result.returncode)
        self.assertIn('--start-at', result.stdout)
        self.assertIn('preceding cases are not counted', ' '.join(result.stdout.split()))


if __name__ == '__main__':
    unittest.main()
