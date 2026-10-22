#!/usr/bin/env python3
"""Compile the actual HMA command builder and exercise backup/write/restore with local sh.

This focused JVM gate tests generated filesystem commands. A Java Base64 adapter
supplies the standard encoding used by the fixture; an unused policy link fails
if invoked. Full Android codecs, manager rules and the four production classes
are independently compiled/executed by android_hma_test.py on API 24/28/34.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import stat
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
SOURCE = 'app/src/main/java/com/waenhancer/utils/HmaConfigFile.java'
LOGICAL = '/data/misc/hide_my_applist_qa/config.json'
BASE64 = '''package android.util;
public final class Base64 {
 public static final int DEFAULT=0, NO_WRAP=2;
 public static byte[] decode(String value,int flags) { return java.util.Base64.getMimeDecoder().decode(value); }
 public static String encodeToString(byte[] value,int flags) { return java.util.Base64.getEncoder().encodeToString(value); }
}
'''
POLICY_LINK = '''package com.waenhancer.utils;
public final class HmaConfigPolicy {
 public static boolean isManagerPackage(String value) {
  throw new AssertionError("Manager rules belong to the complete Android framework gate");
 }
}
'''
HARNESS = '''package com.waenhancer.utils;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
public final class HmaCommandHarness {
 public static void main(String[] args) throws Exception {
  byte[] original=Base64.getDecoder().decode(args[1]);
  StringBuilder digest=new StringBuilder();
  for(byte b:MessageDigest.getInstance("SHA-256").digest(original))
   digest.append(String.format(java.util.Locale.ROOT,"%02x",b&255));
  HmaConfigFile.Snapshot snapshot=HmaConfigFile.parseSnapshot(args[3],
   digest+"\\n"+Base64.getEncoder().encodeToString(original));
  if(snapshot==null) throw new AssertionError("valid snapshot rejected");
  String replacement=new String(Base64.getDecoder().decode(args[2]),StandardCharsets.UTF_8);
  switch(args[0]) {
   case "backup": System.out.print(HmaConfigFile.backupCommand(snapshot)); break;
   case "write": System.out.print(HmaConfigFile.writeCommand(snapshot,replacement)); break;
   case "restore": System.out.print(HmaConfigFile.restoreCommand(snapshot,replacement)); break;
   default: throw new AssertionError("unsupported fixture operation");
  }
 }
}
'''


def run_contracts(source_root=ROOT, out=None):
    with tempfile.TemporaryDirectory(prefix='waex_hma_commands_') as temporary:
        output = Path(out).resolve() if out else Path(temporary)
        output.mkdir(parents=True, exist_ok=out is None)
        source_root = Path(source_root).resolve()
        stage, classes = output / 'sources', output / 'classes'
        classes.mkdir()
        sources = []
        for relative, content in {
            'android/util/Base64.java': BASE64,
            'com/waenhancer/utils/HmaConfigPolicy.java': POLICY_LINK,
            'com/waenhancer/utils/HmaCommandHarness.java': HARNESS,
            'com/waenhancer/utils/HmaConfigFile.java': (source_root / SOURCE).read_text(),
        }.items():
            target = stage / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(content)
            sources.append(str(target))
        compile_result = subprocess.run(['javac', '--release', '17', '-d', str(classes), *sources],
            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=60, check=False)
        (output / 'javac.log').write_bytes(compile_result.stdout)
        if compile_result.returncode:
            raise AssertionError('Complete HmaConfigFile compilation failed: ' + compile_result.stdout.decode(errors='replace'))
        # Only this SELinux endpoint is substituted; no real chcon is invoked.
        binary = output / 'bin'
        binary.mkdir()
        (binary / 'chcon').write_text('#!/bin/sh\nexit 0\n')
        (binary / 'chcon').chmod(0o755)

        class CommandChecks(unittest.TestCase):
            def setUp(self):
                self.case = output / self._testMethodName
                self.case.mkdir()
                self.file = self.case / 'config.json'
                self.backup = self.case / 'config.json.waex.bak'
                self.original = '{\n  "scope": {"com.whatsapp": {"hidden": true}}, "label": "Olá العربية 😀"\n}'.encode()
                self.replacement = b'{"scope":{"com.whatsapp":{"hidden":false}}}'
                self.file.write_bytes(self.original)
                self.file.chmod(0o640)
                self.serial = 0

            def command(self, operation, success=True):
                import base64
                generated = subprocess.run(['java', '-cp', str(classes), 'com.waenhancer.utils.HmaCommandHarness',
                    operation, base64.b64encode(self.original).decode(),
                    base64.b64encode(self.replacement).decode(), LOGICAL],
                    stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=15, check=True).stdout.decode()
                # Source commands quote the logical path. Preserve that quoting
                # when replacing it with the disposable physical path.
                escaped = str(self.file).replace("'", "'\"'\"'")
                translated = generated.replace(LOGICAL, escaped)
                self.assertNotIn('/data/', translated)
                self.serial += 1
                prefix = self.case / (str(self.serial) + '-' + operation)
                prefix.with_suffix('.sh').write_text(translated)
                result = subprocess.run(['/bin/sh', '-c', translated], stdout=subprocess.PIPE,
                    stderr=subprocess.PIPE, timeout=4, check=False,
                    env=dict(os.environ, PATH=str(binary) + os.pathsep + os.environ['PATH']))
                prefix.with_suffix('.stdout.log').write_bytes(result.stdout)
                prefix.with_suffix('.stderr.log').write_bytes(result.stderr)
                prefix.with_suffix('.json').write_text(json.dumps({'exit': result.returncode}))
                if success:
                    self.assertEqual(0, result.returncode, result.stderr.decode(errors='replace'))
                    expected = {'backup': b'WAEX_HMA_CONFIG_BACKED_UP', 'write': b'WAEX_HMA_CONFIG_WRITTEN',
                                'restore': b'WAEX_HMA_CONFIG_RESTORED'}[operation]
                    self.assertEqual(expected, result.stdout)
                else:
                    self.assertNotEqual(0, result.returncode)
                    self.assertEqual(b'', result.stdout)
                return result

            def tearDown(self):
                leaked = [p.name for p in self.case.iterdir() if '.waex.' in p.name and p != self.backup]
                self.assertEqual([], leaked, 'temporary transaction file leaked')

            def test_verified_backup_precedes_write_and_retains_bytes_mode(self):
                self.command('backup')
                self.assertEqual(self.original, self.file.read_bytes())
                self.assertEqual(self.original, self.backup.read_bytes())
                self.assertEqual(0o640, stat.S_IMODE(self.backup.stat().st_mode))
                self.command('write')
                self.assertEqual(self.replacement, self.file.read_bytes())
                self.assertEqual(self.original, self.backup.read_bytes())
                self.assertEqual(0o640, stat.S_IMODE(self.file.stat().st_mode))

            def test_write_without_backup_is_rejected(self):
                self.command('write', False)
                self.assertEqual(self.original, self.file.read_bytes())

            def test_restore_own_replacement_recovers_exact_bytes_and_mode(self):
                self.command('backup'); self.command('write'); self.command('restore')
                self.assertEqual(self.original, self.file.read_bytes())
                self.assertEqual(self.original, self.backup.read_bytes())
                self.assertEqual(0o640, stat.S_IMODE(self.file.stat().st_mode))

            def test_restore_preserves_concurrent_writer(self):
                self.command('backup'); self.command('write')
                self.file.write_bytes(b'another writer')
                self.command('restore', False)
                self.assertEqual(b'another writer', self.file.read_bytes())
                self.assertEqual(self.original, self.backup.read_bytes())

            def test_backup_symlink_is_not_followed_or_replaced(self):
                foreign = self.case / 'foreign'
                foreign.write_bytes(b'keep foreign')
                self.backup.symlink_to(foreign)
                self.command('backup', False)
                self.assertTrue(self.backup.is_symlink())
                self.assertEqual(b'keep foreign', foreign.read_bytes())
                self.assertEqual(self.original, self.file.read_bytes())

            def test_backup_directory_is_not_used_as_copy_target(self):
                self.backup.mkdir()
                self.command('backup', False)
                self.assertEqual([], list(self.backup.iterdir()))
                self.assertEqual(self.original, self.file.read_bytes())

            def test_stale_snapshot_cannot_authorize_backup(self):
                self.file.write_bytes(b'changed before backup')
                self.command('backup', False)
                self.assertEqual(b'changed before backup', self.file.read_bytes())
                self.assertFalse(self.backup.exists())

            def test_corrupt_backup_cannot_authorize_write(self):
                self.command('backup')
                self.backup.write_bytes(b'wrong backup')
                self.command('write', False)
                self.assertEqual(self.original, self.file.read_bytes())

            def test_corrupt_backup_cannot_be_restored(self):
                self.command('backup'); self.command('write')
                self.backup.write_bytes(b'wrong backup')
                self.command('restore', False)
                self.assertEqual(self.replacement, self.file.read_bytes())

            def test_regular_older_backup_is_replaced_atomically(self):
                self.backup.write_bytes(b'older backup')
                self.command('backup')
                self.assertEqual(self.original, self.backup.read_bytes())
                self.assertEqual(self.original, self.file.read_bytes())

            def test_restore_of_unchanged_original_is_acknowledged_without_rewrite(self):
                self.command('backup')
                inode = self.file.stat().st_ino
                self.command('restore')
                self.assertEqual(inode, self.file.stat().st_ino)
                self.assertEqual(self.original, self.file.read_bytes())

            def test_restore_refuses_symlinked_live_config(self):
                self.command('backup'); self.command('write')
                foreign = self.case / 'foreign'
                foreign.write_bytes(self.replacement)
                self.file.unlink(); self.file.symlink_to(foreign)
                self.command('restore', False)
                self.assertTrue(self.file.is_symlink())
                self.assertEqual(self.replacement, foreign.read_bytes())

        result = unittest.TextTestRunner(verbosity=2).run(unittest.defaultTestLoader.loadTestsFromTestCase(CommandChecks))
        summary = {'tests': result.testsRun, 'failures': len(result.failures), 'errors': len(result.errors),
            'skipped': len(result.skipped), 'executor_uid': os.geteuid(),
            'source_sha256': hashlib.sha256((source_root / SOURCE).read_bytes()).hexdigest(),
            'fixture_sha256': hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
            'boundaries': 'Complete command builder; explicit JDK Base64 adapter, unused fail-closed manager-policy link, and SELinux endpoint. Local sh over disposable files without invoking su or a root manager. Complete Android diagnostics/JSON/Base64 verified by the separate API 24/28/34 framework gate.'}
        (output / 'result.json').write_text(json.dumps(summary, indent=2) + '\n')
        print(json.dumps(summary), flush=True)
        if not result.wasSuccessful() or result.testsRun != 12 or result.skipped:
            raise AssertionError('Compiled HMA backup/write/restore contracts failed')
        return summary


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source-root', type=Path, default=ROOT)
    parser.add_argument('--out', type=Path)
    args = parser.parse_args()
    run_contracts(args.source_root, args.out)
