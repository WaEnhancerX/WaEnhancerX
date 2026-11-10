#!/usr/bin/env python3
"""Exercise the real pinned-source fetch/cache code with deterministic HTTP doubles.

No network or AOSP replacement is used by the codec suite. This test supplies a
tiny independent byte fixture and its SHA-256 solely to check acquisition errors,
retry bounds and cache integrity. The codec suite still verifies all six pinned
unmodified AOSP sources before compiling them.
"""
import base64
import hashlib
import io
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import urllib.error

import test_settings_backup_json as fetch

NAME = 'FetchFixture.java'
SOURCE = b'// Deterministic acquisition fixture, not org.json.\n'
DIGEST = hashlib.sha256(SOURCE).hexdigest()


class Response(io.BytesIO):
    def __init__(self, body=base64.b64encode(SOURCE),
                 url='https://android.googlesource.com/fixture'):
        super().__init__(body)
        self.url = url


def http_error(status):
    return urllib.error.HTTPError('https://android.googlesource.com/fixture',
                                  status, 'fixture failure', {}, io.BytesIO())


class SourceFetchChecks(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix='waex_source_fetch_')
        self.addCleanup(temporary.cleanup)
        self.directory = Path(temporary.name)
        self.target = self.directory / NAME
        pins = patch.object(fetch, 'SHA256', {NAME: DIGEST})
        pins.start()
        self.addCleanup(pins.stop)
        network = patch.object(fetch.urllib.request, 'urlopen')
        self.network = network.start()
        self.addCleanup(network.stop)
        sleep = patch('time.sleep')
        self.sleep = sleep.start()
        self.addCleanup(sleep.stop)

    def sources(self, allow_download=True):
        return fetch.json_sources(self.directory, allow_download)

    def assert_no_cached_bytes(self):
        self.assertEqual([], list(self.directory.iterdir()))

    def test_each_transient_status_retries_then_verifies_the_source(self):
        for status in (429, 502, 503, 504):
            with self.subTest(status=status):
                error, response = http_error(status), Response()
                self.network.side_effect = [error, response]
                self.assertEqual([self.target], self.sources())
                self.assertEqual(SOURCE, self.target.read_bytes())
                self.assertEqual(2, self.network.call_count)
                self.sleep.assert_called_once_with(1)
                self.assertTrue(error.fp.closed)
                self.assertTrue(response.closed)
                self.target.unlink()
                self.network.reset_mock()
                self.sleep.reset_mock()

    def test_third_attempt_can_succeed_without_a_fourth_request(self):
        self.network.side_effect = [http_error(503), http_error(502), Response()]
        self.assertEqual([self.target], self.sources())
        self.assertEqual(3, self.network.call_count)
        self.assertEqual([(1,), (2,)], [c.args for c in self.sleep.call_args_list])

    def test_persistent_transient_failure_is_bounded_and_leaves_no_cache(self):
        errors = [http_error(503) for _ in range(3)]
        self.network.side_effect = errors
        with self.assertRaises(urllib.error.HTTPError) as raised:
            self.sources()
        self.assertIs(errors[-1], raised.exception)
        self.assertEqual(3, self.network.call_count)
        self.assertEqual([(1,), (2,)], [c.args for c in self.sleep.call_args_list])
        self.assertTrue(all(e.fp.closed for e in errors))
        self.assert_no_cached_bytes()

    def test_other_http_failures_do_not_retry(self):
        for status in (400, 401, 403, 404, 500):
            with self.subTest(status=status):
                error = http_error(status)
                self.network.side_effect = error
                with self.assertRaises(urllib.error.HTTPError) as raised:
                    self.sources()
                self.assertIs(error, raised.exception)
                self.assertEqual(1, self.network.call_count)
                self.sleep.assert_not_called()
                self.assertTrue(error.fp.closed)
                self.assert_no_cached_bytes()
                self.network.reset_mock()

    def test_network_error_does_not_retry(self):
        self.network.side_effect = urllib.error.URLError('fixture network failure')
        with self.assertRaises(urllib.error.URLError):
            self.sources()
        self.assertEqual(1, self.network.call_count)
        self.sleep.assert_not_called()
        self.assert_no_cached_bytes()

    def test_bad_source_or_redirect_does_not_retry_or_write_cache(self):
        invalid = [
            (base64.b64encode(b'wrong digest'), 'https://android.googlesource.com/fixture'),
            (b'!!!invalid base64!!!', 'https://android.googlesource.com/fixture'),
            (b'A' * (512 * 1024 + 1), 'https://android.googlesource.com/fixture'),
            (base64.b64encode(SOURCE), 'https://example.invalid/fixture'),
            (base64.b64encode(SOURCE), 'https://android.googlesource.com.example.invalid/fixture'),
        ]
        for body, url in invalid:
            with self.subTest(url=url, length=len(body)):
                response = Response(body, url)
                self.network.return_value = response
                with self.assertRaises(ValueError):
                    self.sources()
                self.assertEqual(1, self.network.call_count)
                self.sleep.assert_not_called()
                self.assertTrue(response.closed)
                self.assert_no_cached_bytes()
                self.network.reset_mock()

    def test_offline_missing_source_does_not_request_network(self):
        with self.assertRaises(FileNotFoundError):
            self.sources(allow_download=False)
        self.network.assert_not_called()
        self.sleep.assert_not_called()
        self.assert_no_cached_bytes()

    def test_verified_cache_is_reused_without_network(self):
        self.target.write_bytes(SOURCE)
        self.assertEqual([self.target], self.sources())
        self.assertEqual([self.target], self.sources(allow_download=False))
        self.network.assert_not_called()
        self.sleep.assert_not_called()

    def test_corrupt_cache_is_reported_and_preserved_without_download(self):
        self.target.write_bytes(b'corrupt cache')
        with self.assertRaisesRegex(ValueError, 'digest mismatch'):
            self.sources()
        self.assertEqual(b'corrupt cache', self.target.read_bytes())
        self.network.assert_not_called()
        self.sleep.assert_not_called()

    def test_atomic_replace_failure_removes_temporary_file(self):
        self.network.return_value = Response()
        with patch.object(Path, 'replace', side_effect=OSError('fixture replace failure')):
            with self.assertRaises(OSError):
                self.sources()
        self.assert_no_cached_bytes()
        self.assertEqual(1, self.network.call_count)
        self.sleep.assert_not_called()


if __name__ == '__main__':
    unittest.main(verbosity=2)
