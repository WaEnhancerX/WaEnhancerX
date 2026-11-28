#!/usr/bin/env python3
"""Offline controls for the status-style runner's fixed DexKit download only."""
from datetime import datetime, timezone
from email.message import Message
from email.utils import format_datetime
import hashlib
import io
import json
from pathlib import Path
import ssl
import subprocess
import tempfile
import time
import unittest
from unittest.mock import patch
import urllib.error
import zipfile

import test_status_style_hook_contract as runner

REAL_MONOTONIC = time.monotonic


def archive(entries):
    output = io.BytesIO()
    with zipfile.ZipFile(output, 'w') as bundle:
        for name, data in entries:
            bundle.writestr(name, data)
    return output.getvalue()


def http_error(code, retry_after=None):
    headers = Message()
    if retry_after is not None:
        headers['Retry-After'] = retry_after
    return urllib.error.HTTPError(runner.DEXKIT_URL, code, 'synthetic HTTP response',
                                  headers, io.BytesIO(b'offline response'))


class DownloadTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='waex-style-download-')
        self.addCleanup(self.temporary.cleanup)
        self.out = Path(self.temporary.name)
        self.jar = b'synthetic offline classes; never executed'
        self.aar = archive([('classes.jar', self.jar)])
        self.actions = []
        self.calls = []
        self.read_limits = []
        self.responses = []
        self.sleeps = []
        self.now = 0.0
        self.timeouts = []
        self.process_actions = []
        self.pin_patch = patch.multiple(runner,
            DEXKIT_AAR_SHA256=hashlib.sha256(self.aar).hexdigest(),
            DEXKIT_SHA256=hashlib.sha256(self.jar).hexdigest())
        self.pin_patch.start()
        self.addCleanup(self.pin_patch.stop)
        self.clock_patches = [
            patch.object(runner.time, 'monotonic', lambda: self.now),
            patch.object(runner.time, 'time', lambda: 1_700_000_000.0 + self.now),
            patch.object(runner.time, 'sleep', self.sleep)]
        for item in self.clock_patches:
            item.start()
            self.addCleanup(item.stop)
        self.network = patch.object(runner.urllib.request, 'urlopen', self.open)
        self.network.start()
        self.addCleanup(self.network.stop)
        self.process = patch.object(runner.subprocess, 'run', self.child)
        self.process.start()
        self.addCleanup(self.process.stop)

    def sleep(self, delay):
        self.sleeps.append(delay)
        self.now += delay

    def open(self, url, timeout):
        self.assertEqual(url, runner.DEXKIT_URL)
        self.assertEqual(timeout, runner.DEXKIT_ATTEMPT_SECONDS)
        self.calls.append(url)
        self.assertTrue(self.actions, 'Unplanned network call is forbidden')
        action = self.actions.pop(0)
        if isinstance(action, Exception):
            raise action
        owner = self

        class Response(io.BytesIO):
            def read(self, limit):
                owner.read_limits.append(limit)
                return super().read(limit)
        response = Response(action)
        self.responses.append(response)
        return response

    def child(self, argv, **kwargs):
        self.assertEqual(argv[2], '--dexkit-download-attempt')
        self.assertEqual(Path(argv[1]), Path(runner.__file__).resolve())
        self.assertEqual(len(argv), 4)
        self.timeouts.append(kwargs['timeout'])
        if self.process_actions:
            action = self.process_actions.pop(0)
            if action == 'timeout':
                self.now += kwargs['timeout']
                raise subprocess.TimeoutExpired(argv, kwargs['timeout'])
            if isinstance(action, Exception):
                raise action
        code = runner._dexkit_attempt(Path(argv[3]))
        return subprocess.CompletedProcess(argv, code)

    def receipt(self):
        return json.loads((self.out / 'dexkit-download.json').read_text())

    def assert_no_published_jar(self):
        self.assertFalse((self.out / 'dexkit-2.2.0.jar').exists())

    def test_success_uses_fixed_url_bounds_and_pins(self):
        self.actions = [self.aar]
        self.assertEqual(runner.dexkit(self.out).read_bytes(), self.jar)
        self.assertEqual(len(self.calls), 1)
        self.assertEqual(self.read_limits, [4 * 1024 * 1024 + 1])
        self.assertTrue(all(response.closed for response in self.responses))
        self.assertEqual(self.sleeps, [])
        self.assertEqual(self.receipt()['status'], 'verified')

    def test_429_then_success_preserves_both_attempts(self):
        failure = http_error(429, '3')
        self.actions = [failure, self.aar]
        runner.dexkit(self.out)
        self.assertTrue(failure.fp.closed)
        self.assertEqual(self.sleeps, [3.0])
        attempts = self.receipt()['attempts']
        self.assertEqual([a['status'] for a in attempts], ['http_error', 'downloaded'])
        self.assertEqual(attempts[0]['http_status'], 429)
        self.assertEqual(attempts[0]['retry_after'], '3')
        for number in (1, 2):
            for suffix in ('.json', '.log'):
                self.assertTrue((self.out / ('dexkit-download-attempt-' + str(number) + suffix)).is_file())

    def test_transient_server_failure_uses_bounded_backoff(self):
        self.actions = [http_error(503), http_error(502), self.aar]
        runner.dexkit(self.out)
        self.assertEqual(self.sleeps, [1.0, 2.0])
        self.assertEqual(len(self.calls), 3)

    def test_retry_after_http_date_is_respected(self):
        future = format_datetime(datetime.fromtimestamp(1_700_000_008, timezone.utc), usegmt=True)
        self.actions = [http_error(429, future), self.aar]
        runner.dexkit(self.out)
        self.assertEqual(self.sleeps, [8.0])

    def test_retry_after_obsolete_http_dates_use_utc(self):
        for number, value in enumerate(('Tue Nov 14 22:13:28 2023',
                                       'Tuesday, 14-Nov-23 22:13:28 GMT')):
            with self.subTest(header=value):
                out = self.out / str(number)
                out.mkdir()
                self.now = 0.0
                before = len(self.sleeps)
                self.actions = [http_error(429, value), self.aar]
                runner.dexkit(out)
                self.assertEqual(self.sleeps[before:], [8.0])

    def test_rfc850_future_year_uses_http_pivot_and_exceeds_budget(self):
        self.actions = [http_error(429, 'Friday, 14-Nov-70 22:13:28 GMT'), self.aar]
        with self.assertRaises(TimeoutError):
            runner.dexkit(self.out)
        self.assertEqual(len(self.calls), 1)
        self.assertEqual(self.sleeps, [])
        self.assert_no_published_jar()

    def test_rfc850_fifty_year_boundary_includes_month_day_and_time(self):
        cases = (('Tuesday, 14-Nov-73 22:13:19 GMT', True),
                 ('Wednesday, 14-Nov-73 22:13:28 GMT', False))
        for number, (value, future) in enumerate(cases):
            with self.subTest(header=value):
                out = self.out / str(number)
                out.mkdir()
                self.now = 0.0
                before = len(self.sleeps)
                self.actions = [http_error(429, value), self.aar]
                if future:
                    with self.assertRaises(TimeoutError):
                        runner.dexkit(out)
                    self.assertEqual(self.sleeps[before:], [])
                else:
                    runner.dexkit(out)
                    self.assertEqual(self.sleeps[before:], [1.0])

    def test_malformed_retry_after_uses_local_backoff(self):
        self.actions = [http_error(429, 'not a date'), self.aar]
        runner.dexkit(self.out)
        self.assertEqual(self.sleeps, [1.0])
        self.assertEqual(self.receipt()['attempts'][0]['retry_after'], 'not a date')

    def test_retry_after_beyond_budget_stops_without_early_retry(self):
        self.actions = [http_error(429, '120'), self.aar]
        with self.assertRaises(TimeoutError):
            runner.dexkit(self.out)
        self.assertEqual(len(self.calls), 1)
        self.assertEqual(self.sleeps, [])
        self.assertEqual(self.receipt()['status'], 'failed')
        self.assert_no_published_jar()

    def test_large_decimal_retry_after_cannot_fall_back_to_an_early_retry(self):
        self.actions = [http_error(429, '9' * 5000), self.aar]
        with self.assertRaises(TimeoutError):
            runner.dexkit(self.out)
        self.assertEqual(len(self.calls), 1)
        self.assertEqual(self.sleeps, [])
        self.assertEqual(self.receipt()['attempts'][0]['retry_after'], '9' * 5000)
        self.assert_no_published_jar()

    def test_decimal_retry_after_with_leading_zeroes_preserves_its_value(self):
        self.actions = [http_error(429, '0' * 5000 + '3'), self.aar]
        runner.dexkit(self.out)
        self.assertEqual(self.sleeps, [3.0])

    def test_persistent_429_stops_at_three_attempts(self):
        self.actions = [http_error(429) for _ in range(4)]
        with self.assertRaises(RuntimeError):
            runner.dexkit(self.out)
        self.assertEqual(len(self.calls), 3)
        self.assertEqual(self.sleeps, [1.0, 2.0])
        self.assertEqual(len(self.receipt()['attempts']), 3)
        self.assert_no_published_jar()

    def test_permanent_http_is_not_retried(self):
        for code in (400, 401, 403, 404, 410):
            with self.subTest(status=code):
                out = self.out / str(code)
                out.mkdir()
                before = len(self.calls)
                self.actions = [http_error(code), self.aar]
                with self.assertRaises(RuntimeError):
                    runner.dexkit(out)
                self.assertEqual(len(self.calls), before + 1)
                result = json.loads((out / 'dexkit-download.json').read_text())
                self.assertFalse(result['attempts'][0]['retryable'])
        self.assertEqual(self.sleeps, [])

    def test_transport_timeout_and_reset_can_recover(self):
        self.actions = [urllib.error.URLError(TimeoutError('offline timeout')),
                        ConnectionResetError('offline reset'), self.aar]
        runner.dexkit(self.out)
        self.assertEqual(len(self.calls), 3)

    def test_certificate_and_unclassified_url_errors_are_definitive(self):
        failures = [
            urllib.error.URLError(ssl.SSLCertVerificationError(1, 'offline certificate')),
            urllib.error.URLError('unclassified failure'),
            FileNotFoundError('unrelated local failure')]
        for number, failure in enumerate(failures):
            with self.subTest(error=type(failure).__name__):
                out = self.out / str(number)
                out.mkdir()
                before = len(self.calls)
                self.actions = [failure, self.aar]
                with self.assertRaises(RuntimeError):
                    runner.dexkit(out)
                self.assertEqual(len(self.calls), before + 1)
        self.assertEqual(self.sleeps, [])

    def test_child_deadlines_include_backoff_in_total_budget(self):
        self.process_actions = ['timeout', 'timeout', 'timeout', 'timeout']
        with self.assertRaises(RuntimeError):
            runner.dexkit(self.out)
        self.assertEqual(self.timeouts, [30, 30, 27])
        self.assertEqual(self.now, 90)
        self.assertEqual(self.sleeps, [1.0, 2.0])
        self.assertEqual([a['status'] for a in self.receipt()['attempts']], ['timeout'] * 3)
        self.assert_no_published_jar()

    def test_spawn_failure_is_not_retried(self):
        self.process_actions = [OSError('offline spawn failure')]
        with self.assertRaises(OSError):
            runner.dexkit(self.out)
        self.assertEqual(len(self.timeouts), 1)
        self.assertEqual(self.sleeps, [])
        self.assertEqual(self.receipt()['error_type'], 'OSError')

    def test_aar_hash_failure_is_not_retried(self):
        self.actions = [b'unexpected artifact', self.aar]
        with self.assertRaisesRegex(RuntimeError, 'AAR size or identity'):
            runner.dexkit(self.out)
        self.assertEqual(len(self.calls), 1)
        self.assertEqual(self.sleeps, [])
        self.assertEqual(self.receipt()['status'], 'failed')
        self.assertTrue((self.out / 'dexkit-download-attempt-1.payload').is_file())
        self.assertFalse((self.out / 'dexkit-2.2.0.aar').exists())
        self.assert_no_published_jar()

    def test_oversized_response_is_not_retried(self):
        self.actions = [b'x' * (4 * 1024 * 1024 + 2), self.aar]
        with self.assertRaisesRegex(RuntimeError, 'AAR size or identity'):
            runner.dexkit(self.out)
        self.assertEqual(len(self.calls), 1)
        self.assertEqual((self.out / 'dexkit-download-attempt-1.payload').stat().st_size,
                         4 * 1024 * 1024 + 1)
        self.assert_no_published_jar()

    def test_classes_hash_failure_is_not_retried_or_published(self):
        self.actions = [self.aar, self.aar]
        with patch.object(runner, 'DEXKIT_SHA256', '0' * 64):
            with self.assertRaisesRegex(RuntimeError, 'Pinned real DexKit'):
                runner.dexkit(self.out)
        self.assertEqual(len(self.calls), 1)
        self.assert_no_published_jar()

    def test_invalid_zip_and_missing_classes_are_definitive(self):
        for number, payload in enumerate((b'not a ZIP', archive([('other', b'data')]))):
            with self.subTest(case=number):
                out = self.out / str(number)
                out.mkdir()
                self.actions = [payload, self.aar]
                before = len(self.calls)
                with patch.object(runner, 'DEXKIT_AAR_SHA256', hashlib.sha256(payload).hexdigest()):
                    with self.assertRaises((RuntimeError, zipfile.BadZipFile)):
                        runner.dexkit(out)
                self.assertEqual(len(self.calls), before + 1)
                self.assertFalse((out / 'dexkit-2.2.0.jar').exists())

    def test_existing_evidence_is_never_overwritten(self):
        receipt = self.out / 'dexkit-download.json'
        receipt.write_text('earlier evidence\n')
        with self.assertRaisesRegex(RuntimeError, 'already exists'):
            runner.dexkit(self.out)
        self.assertEqual(receipt.read_text(), 'earlier evidence\n')
        self.assertEqual(self.timeouts, [])

    def test_orphan_attempt_evidence_is_never_overwritten(self):
        previous = self.out / 'dexkit-download-attempt-1.log'
        previous.write_text('earlier attempt\n')
        with self.assertRaisesRegex(RuntimeError, 'already exists'):
            runner.dexkit(self.out)
        self.assertEqual(previous.read_text(), 'earlier attempt\n')
        self.assertEqual(self.timeouts, [])

    def test_actual_owned_child_is_stopped_on_timeout(self):
        # This child only writes its own marker then waits. No network is used.
        self.process.stop()
        for item in self.clock_patches:
            item.stop()
        child = self.out / 'blocked-child.py'
        child.write_text(
            "from pathlib import Path\nimport sys,time\n"
            "Path(sys.argv[-1]+'.started').write_text('owned child started')\n"
            "time.sleep(60)\n")
        before = REAL_MONOTONIC()
        with patch.object(runner, '__file__', str(child)), \
             patch.object(runner, 'DEXKIT_ATTEMPTS', 1), \
             patch.object(runner, 'DEXKIT_ATTEMPT_SECONDS', 1):
            with self.assertRaises(RuntimeError):
                runner.dexkit(self.out)
        elapsed = REAL_MONOTONIC() - before
        self.assertTrue((self.out / 'dexkit-download-attempt-1.started').is_file())
        self.assertLess(elapsed, 5, 'Owned sleeping child was not stopped promptly')
        self.assertEqual(self.receipt()['attempts'][0]['status'], 'timeout')
        self.assert_no_published_jar()


if __name__ == '__main__':
    unittest.main()
