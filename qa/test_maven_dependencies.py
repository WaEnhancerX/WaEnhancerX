#!/usr/bin/env python3
"""Exercise dependency recovery with real controlled child processes, without network."""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import time
import unittest

ROOT = Path(__file__).resolve().parents[1]
EVIDENCE = None
HELPER = None
FAKE_MAVEN = r'''#!/usr/bin/env python3
import json, pathlib, subprocess, sys, time
root = pathlib.Path(__file__).resolve().parent
config = json.loads((root / 'config.json').read_text())
calls = root / 'calls.jsonl'
number = len(calls.read_text().splitlines()) if calls.exists() else 0
with calls.open('a') as stream:
    stream.write(json.dumps(sys.argv[1:]) + '\n')
if config.get('require_update') and '-U' not in sys.argv and '--update-snapshots' not in sys.argv:
    print('[ERROR] Could not find artifact; failure was cached in the local repository, resolution will not be reattempted')
    raise SystemExit(1)
step = config['steps'][min(number, len(config['steps']) - 1)]
print(step.get('stdout', ''), flush=True)
print(step.get('stderr', ''), file=sys.stderr, flush=True)
if step.get('child'):
    marker = root / 'orphan-marker'
    child = subprocess.Popen([sys.executable, '-c',
        'import pathlib,time;time.sleep(0.8);pathlib.Path(' + repr(str(marker)) + ').write_text("escaped")'])
    (root / 'child-pid').write_text(str(child.pid))
time.sleep(step.get('sleep', 0))
raise SystemExit(step.get('exit', 0))
'''


class MavenDependencyChecks(unittest.TestCase):
    def setUp(self):
        self.directory = EVIDENCE / self._testMethodName
        self.directory.mkdir()
        self.executable = self.directory / 'mvn-fixture'
        self.executable.write_text(FAKE_MAVEN)
        self.executable.chmod(0o755)
        self.pom = self.directory / 'pom.xml'
        self.pom.write_text('<project/>')
        self.command = [str(self.executable), '-B', '-f', str(self.pom), HELPER.DEPENDENCY_GOAL,
                        '-DoutputDirectory=' + str(self.directory / 'deps')]
        self.log = self.directory / 'dependencies.log'

    def configure(self, steps, **extra):
        (self.directory / 'config.json').write_text(json.dumps({'steps': steps, **extra}))

    def calls(self):
        path = self.directory / 'calls.jsonl'
        return [json.loads(line) for line in path.read_text().splitlines()] if path.exists() else []

    def metadata(self):
        return json.loads((self.directory / 'dependencies-retry.json').read_text())

    def run_failure(self, **kwargs):
        with self.assertRaises(HELPER.MavenDependencyError):
            HELPER.resolve_dependencies(self.command, self.log, **kwargs)

    def test_success_on_first_attempt_preserves_coordinate_and_arguments(self):
        self.configure([{'stdout': '[INFO] BUILD SUCCESS', 'exit': 0}])
        original = list(self.command)
        self.assertEqual(0, HELPER.resolve_dependencies(self.command, self.log))
        self.assertEqual(original, self.command)
        self.assertEqual([['-U', *original[1:]]], self.calls())
        self.assertEqual('success', self.metadata()['status'])
        self.assertEqual(1, len(self.metadata()['attempts']))

    def test_transport_failure_then_success_retries_same_coordinates(self):
        self.configure([{'stdout': '[ERROR] Could not transfer artifact x:jar:1: Connection reset', 'exit': 1},
                        {'stdout': '[INFO] BUILD SUCCESS', 'exit': 0}])
        self.assertEqual(0, HELPER.resolve_dependencies(self.command, self.log))
        self.assertEqual(2, len(self.calls()))
        self.assertEqual(self.calls()[0], self.calls()[1])
        self.assertTrue(self.metadata()['attempts'][0]['retryable'])
        self.assertIn('Connection reset', self.log.read_text())
        self.assertIn('BUILD SUCCESS', self.log.read_text())

    def test_exact_ci_plugin_resolution_error_recovers_on_third_attempt(self):
        failure = {'stdout': '[ERROR] Plugin org.apache.maven.plugins:maven-dependency-plugin:3.8.1 '
                   'or one of its dependencies could not be resolved:\n[ERROR] Could not find artifact '
                   'org.apache.maven.plugins:maven-dependency-plugin:jar:3.8.1 in central\n'
                   '[ERROR] [Help 1] https://cwiki.apache.org/confluence/display/MAVEN/PluginResolutionException', 'exit': 1}
        self.configure([failure, failure, {'stdout': 'BUILD SUCCESS', 'exit': 0}])
        self.assertEqual(0, HELPER.resolve_dependencies(self.command, self.log))
        self.assertEqual(3, len(self.calls()))
        self.assertTrue(all(call == self.calls()[0] for call in self.calls()))
        self.assertEqual(3, len(list(self.directory.glob('dependencies.attempt-*.log'))))

    def test_unresolved_artifact_stops_after_three_and_raises(self):
        self.configure([{'stderr': '[ERROR] Could not find artifact example:missing:jar:1', 'exit': 1}])
        self.run_failure()
        self.assertEqual(3, len(self.calls()))
        self.assertEqual('failed', self.metadata()['status'])
        self.assertEqual(3, len(self.metadata()['attempts']))
        self.assertEqual(3, self.log.read_text().count('[ERROR] Could not find artifact'))

    def test_baseline_without_recovery_fails_before_same_fixture_succeeds(self):
        self.configure([{'stdout': '[ERROR] Could not transfer artifact example:test:jar:1', 'exit': 1},
                        {'stdout': 'BUILD SUCCESS', 'exit': 0}])
        self.run_failure(max_attempts=1)
        self.assertEqual(1, len(self.calls()))
        baseline = self.metadata()
        (self.directory / 'baseline-retry.json').write_text(json.dumps(baseline, indent=2))
        (self.directory / 'baseline.log').write_bytes(self.log.read_bytes())
        (self.directory / 'calls.jsonl').unlink()
        self.assertEqual(0, HELPER.resolve_dependencies(self.command, self.log))
        self.assertEqual(2, len(self.calls()))

    def test_update_flag_refreshes_simulated_negative_cache(self):
        self.configure([{'stdout': 'BUILD SUCCESS', 'exit': 0}], require_update=True)
        baseline = subprocess.run(self.command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        self.assertEqual(1, baseline.returncode)
        (self.directory / 'baseline-no-update.log').write_bytes(baseline.stdout)
        self.assertEqual(0, HELPER.resolve_dependencies(self.command, self.log))
        self.assertNotIn('-U', self.calls()[0])
        self.assertIn('-U', self.calls()[1])

    def test_existing_short_update_flag_is_not_duplicated(self):
        self.configure([{'exit': 0}])
        self.command.insert(1, '-U')
        HELPER.resolve_dependencies(self.command, self.log)
        self.assertEqual(1, self.calls()[0].count('-U'))

    def test_existing_long_update_flag_is_not_duplicated(self):
        self.configure([{'exit': 0}])
        self.command.insert(1, '--update-snapshots')
        HELPER.resolve_dependencies(self.command, self.log)
        self.assertNotIn('-U', self.calls()[0])
        self.assertEqual(1, self.calls()[0].count('--update-snapshots'))

    def test_compilation_failure_is_not_retried_even_with_resolution_warning(self):
        self.configure([{'stdout': '[WARNING] Could not find artifact optional\n[ERROR] COMPILATION ERROR: missing symbol', 'exit': 1}])
        self.run_failure()
        self.assertEqual(1, len(self.calls()))
        self.assertFalse(self.metadata()['attempts'][0]['retryable'])

    def test_test_failure_is_not_retried(self):
        self.configure([{'stdout': '[ERROR] There are test failures.', 'exit': 1}])
        self.run_failure()
        self.assertEqual(1, len(self.calls()))

    def test_malformed_pom_is_not_retried(self):
        self.configure([{'stdout': '[ERROR] Non-parseable POM: XML mismatch\n[ERROR] ArtifactDescriptorException', 'exit': 1}])
        self.run_failure()
        self.assertEqual(1, len(self.calls()))

    def test_authentication_denial_is_not_retried(self):
        self.configure([{'stdout': '[ERROR] Could not transfer artifact example:jar:1; status code: 403, reason phrase: Forbidden', 'exit': 1}])
        self.run_failure()
        self.assertEqual(1, len(self.calls()))

    def test_unclassified_error_is_not_guessed_to_be_transport(self):
        self.configure([{'stdout': '[WARNING] The POM for something is missing\n[ERROR] Unsupported project configuration', 'exit': 1}])
        self.run_failure()
        self.assertEqual(1, len(self.calls()))

    def test_missing_executable_preserves_explicit_failure_evidence(self):
        self.command[0] = str(self.directory / 'does-not-exist')
        self.run_failure()
        self.assertEqual([], self.calls())
        self.assertIn('start_error', self.metadata()['attempts'][0])
        self.assertEqual('failed', self.metadata()['status'])
        self.assertTrue(self.log.read_text())

    @unittest.skipUnless(os.name == 'posix', 'process-group cleanup is a POSIX CI contract')
    def test_total_timeout_kills_launcher_and_child_and_retains_partial_log(self):
        self.configure([{'stdout': 'partial before blocked transport', 'sleep': 5, 'child': True}])
        start = time.monotonic()
        self.run_failure(timeout=0.35)
        self.assertLess(time.monotonic() - start, 1.5)
        self.assertEqual('timeout', self.metadata()['status'])
        self.assertTrue(self.metadata()['attempts'][0]['timed_out'])
        self.assertEqual(1, len(self.calls()))
        self.assertIn('partial before blocked transport', self.log.read_text())
        time.sleep(0.8)
        self.assertFalse((self.directory / 'orphan-marker').exists())

    def test_backoff_obeys_total_budget_and_does_not_start_late_attempt(self):
        self.configure([{'stdout': '[ERROR] Could not transfer artifact example:jar:1', 'exit': 1, 'sleep': 0.1}])
        start = time.monotonic()
        self.run_failure(timeout=0.3)
        self.assertLess(time.monotonic() - start, 1)
        self.assertEqual(1, len(self.calls()))
        self.assertEqual('timeout', self.metadata()['status'])

    def test_full_large_stdout_and_stderr_survive_retries(self):
        payload = 'prefix-' + 'x' * 100000 + '-suffix'
        self.configure([{'stdout': payload, 'stderr': '[ERROR] Could not transfer artifact example:jar:1', 'exit': 1},
                        {'stdout': 'recovered', 'exit': 0}])
        HELPER.resolve_dependencies(self.command, self.log)
        self.assertIn(payload, self.log.read_text())
        first = (self.directory / 'dependencies.attempt-1.log').read_text()
        self.assertIn(payload, first)
        self.assertIn('Could not transfer artifact', first)
        self.assertIn('recovered', self.log.read_text())

    def test_invalid_limits_goals_and_fail_never_never_launch(self):
        self.configure([{'exit': 0}])
        for value in (0, -1, 600.1, float('inf'), float('nan')):
            with self.subTest(timeout=value), self.assertRaises(ValueError):
                HELPER.resolve_dependencies(self.command, self.log, timeout=value)
        for value in (0, 4, -1):
            with self.subTest(attempts=value), self.assertRaises(ValueError):
                HELPER.resolve_dependencies(self.command, self.log, max_attempts=value)
        for command in ([str(self.executable), 'compile'],
                        [str(self.executable), HELPER.DEPENDENCY_GOAL.replace('3.8.1', '3.8.0')],
                        [*self.command, '-fn'], [*self.command, '--fail-never']):
            with self.subTest(command=command), self.assertRaises(ValueError):
                HELPER.resolve_dependencies(command, self.log)
        self.assertEqual([], self.calls())


def main():
    global EVIDENCE, HELPER
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--out', type=Path, required=True)
    parser.add_argument('--helper', type=Path, default=ROOT / 'qa/maven_dependencies.py')
    args = parser.parse_args()
    EVIDENCE = args.out.resolve()
    EVIDENCE.mkdir(parents=True, exist_ok=False)
    helper_path = args.helper.resolve()
    spec = importlib.util.spec_from_file_location('qa_maven_dependency_under_test', helper_path)
    HELPER = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(HELPER)
    suite = unittest.defaultTestLoader.loadTestsFromTestCase(MavenDependencyChecks)
    result = unittest.TextTestRunner(verbosity=2).run(suite)
    summary = {'tests': result.testsRun, 'failures': len(result.failures), 'errors': len(result.errors),
               'skipped': len(result.skipped), 'successful': result.wasSuccessful(),
               'helper_sha256': hashlib.sha256(helper_path.read_bytes()).hexdigest(),
               'test_sha256': hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
               'process_fixture_sha256': hashlib.sha256(FAKE_MAVEN.encode()).hexdigest(),
               'boundaries': 'Real controlled local processes; Maven output simulated; no network or application tests.'}
    (EVIDENCE / 'result.json').write_text(json.dumps(summary, indent=2) + '\n')
    print(json.dumps(summary))
    if not result.wasSuccessful() or result.testsRun != 18 or result.skipped:
        raise SystemExit(1)


if __name__ == '__main__':
    main()
