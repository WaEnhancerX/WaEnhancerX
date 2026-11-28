#!/usr/bin/env python3
"""Synthetic acceptance and negative controls for hosted native-library preflight."""
import csv
import hashlib
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

from audit_native_alignment import AuditError, PAGE_SIZE
from audit_on_demand_native import audit
from test_native_alignment import elf


class OnDemandLibraryTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name)

    def create_manifest(self, content=None, *, filename="libwaexwhisper.so",
                        size_override=None, sha_override=None, extra=None):
        content = elf() if content is None else content
        (self.directory / filename).write_bytes(content)
        row = {"file": filename,
               "size_bytes": str(len(content) if size_override is None else size_override),
               "md5": hashlib.md5(content).hexdigest(),
               "sha256": sha_override or hashlib.sha256(content).hexdigest()}
        rows = [row] + (extra or [])
        with (self.directory / "manifest.csv").open("w", newline="", encoding="utf-8") as out:
            writer = csv.DictWriter(out, fieldnames=list(row))
            writer.writeheader()
            writer.writerows(rows)

    def test_intact_aligned_arm64_shared_object_passes(self):
        self.create_manifest()
        result = audit(self.directory)
        self.assertTrue(result["passed"])
        self.assertEqual(1, result["library_count"])
        self.assertEqual([], result["findings"])
        self.assertEqual([PAGE_SIZE], result["libraries"][0]["load_alignments"])

    def test_cplusplus_runtime_library_filename_is_accepted(self):
        self.create_manifest(filename="libc++_shared.so")
        self.assertTrue(audit(self.directory)["passed"])

    def test_4k_elf_is_a_finding_and_cli_exits_one(self):
        self.create_manifest(elf(alignment=4096))
        result = audit(self.directory)
        self.assertFalse(result["passed"])
        self.assertEqual(["ELF_LOAD_ALIGNMENT_BELOW_16K"],
                         result["findings"][0]["issues"])
        script = Path(__file__).with_name("audit_on_demand_native.py")
        proc = subprocess.run([sys.executable, str(script), str(self.directory)],
                              capture_output=True, text=True, timeout=15)
        self.assertEqual(1, proc.returncode, proc.stderr)

    def test_incorrect_hash_and_size_raise_input_error(self):
        self.create_manifest(sha_override="0" * 64)
        with self.assertRaisesRegex(AuditError, "SHA-256"):
            audit(self.directory)
        self.create_manifest(size_override=1)
        with self.assertRaisesRegex(AuditError, "size"):
            audit(self.directory)

    def test_manifest_rejects_missing_and_extra_libraries(self):
        self.create_manifest()
        (self.directory / "libwaexwhisper.so").unlink()
        with self.assertRaisesRegex(AuditError, "differ"):
            audit(self.directory)
        self.create_manifest()
        (self.directory / "libextra.so").write_bytes(elf())
        with self.assertRaisesRegex(AuditError, "differ"):
            audit(self.directory)

    def test_manifest_rejects_path_traversal_duplicates_and_wrong_abi(self):
        for filename in ("../libescape.so", "lib/evil.so"):
            with self.subTest(filename=filename):
                self.create_manifest()
                text = (self.directory / "manifest.csv").read_text()
                (self.directory / "manifest.csv").write_text(
                    text.replace("libwaexwhisper.so", filename))
                with self.assertRaises(AuditError):
                    audit(self.directory)
        self.create_manifest(extra=[{"file": "libwaexwhisper.so", "size_bytes": "1",
                                     "md5": "0" * 32, "sha256": "0" * 64}])
        with self.assertRaisesRegex(AuditError, "Duplicate"):
            audit(self.directory)
        self.create_manifest(elf(bits=32))
        with self.assertRaisesRegex(AuditError, "AArch64"):
            audit(self.directory)

    def test_duplicate_manifest_column_is_rejected(self):
        self.create_manifest()
        p = self.directory / "manifest.csv"
        p.write_text(p.read_text().replace("file,size_bytes,md5,sha256",
                                           "file,size_bytes,md5,sha256,file"))
        with self.assertRaisesRegex(AuditError, "Manifest must contain"):
            audit(self.directory)

    def test_truncated_elf_is_not_accepted(self):
        self.create_manifest(b"not an ELF")
        with self.assertRaisesRegex(AuditError, "ELF"):
            audit(self.directory)


if __name__ == "__main__":
    unittest.main()
