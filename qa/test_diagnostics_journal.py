#!/usr/bin/env python3
"""Compile and execute the actual privacy-bounded diagnostics journal on a stock JDK 17+.

Usage:
    python qa/test_diagnostics_journal.py
    python qa/test_diagnostics_journal.py --negative-control
"""

from pathlib import Path
import argparse
import shutil
import subprocess
import sys
import tempfile


ROOT = Path(__file__).resolve().parents[1]
JOURNAL = ROOT / "app/src/main/java/com/waenhancer/utils/DiagnosticsJournal.java"
CRASH_HANDLER = ROOT / "app/src/main/java/com/waenhancer/utils/DiagnosticsCrashHandler.java"
SNAPSHOT = ROOT / "app/src/main/java/com/waenhancer/utils/DiagnosticsSnapshot.java"
HEALTH_GATE = ROOT / "app/src/main/java/com/waenhancer/utils/DiagnosticsHealthGate.java"
TARGET_EXITS = ROOT / "app/src/main/java/com/waenhancer/utils/TargetExitContract.java"
TARGETS = ROOT / "app/src/main/java/com/waenhancer/utils/DeclaredWhatsAppTargets.java"
SCHEMA = ROOT / "app/src/main/java/com/waenhancer/config/PreferenceSchema.java"
HARNESS = ROOT / "qa/fixtures/DiagnosticsJournalSelfTest.java"


def run_once(javac: str, java: str, work: Path, mutate: str) -> subprocess.CompletedProcess[str]:
    source = work / "src/com/waenhancer/utils"
    source.mkdir(parents=True)
    content = JOURNAL.read_text(encoding="utf-8")
    if mutate == "hook":
        needle = ("!DeclaredWhatsAppTargets.contains(target)\n"
                  "                || !RUNTIME_STATUSES.contains(runtime)")
        if content.count(needle) != 1:
            raise AssertionError("Negative control injection point changed")
        content = content.replace(needle, "false\n"
                                  "                || !RUNTIME_STATUSES.contains(runtime)")
    (source / "DiagnosticsJournal.java").write_text(content, encoding="utf-8")
    target_source = TARGET_EXITS
    if mutate == "exit":
        target_content = TARGET_EXITS.read_text(encoding="utf-8")
        needle = "return DeclaredWhatsAppTargets.contains(target)"
        if target_content.count(needle) != 1:
            raise AssertionError("OS exit whitelist negative control injection point changed")
        target_source = source / "TargetExitContract.java"
        target_source.write_text(target_content.replace(needle, "return true"), encoding="utf-8")
    classes = work / "classes"
    classes.mkdir()
    build = subprocess.run(
        [javac, "--release", "17", "-d", str(classes),
         str(source / "DiagnosticsJournal.java"), str(CRASH_HANDLER), str(SNAPSHOT),
         str(HEALTH_GATE), str(target_source),
         str(TARGETS), str(SCHEMA), str(HARNESS)],
        text=True, capture_output=True, timeout=90
    )
    if build.returncode:
        raise AssertionError("javac failed:\n" + build.stdout + build.stderr)
    return subprocess.run(
        [java, "-ea", "-cp", str(classes), "DiagnosticsJournalSelfTest"],
        text=True, capture_output=True, timeout=120
    )


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--negative-control", action="store_true")
    arguments = parser.parse_args()
    javac = shutil.which("javac")
    java = shutil.which("java")
    if not javac or not java:
        print("BLOCKED: a JDK 17+ (javac and java) is required", file=sys.stderr)
        return 2
    with tempfile.TemporaryDirectory(prefix="waex-diagnostics-test-") as dirname:
        result = run_once(javac, java, Path(dirname) / "reference", "")
        print(result.stdout, end="")
        if result.returncode:
            print(result.stderr, file=sys.stderr)
            return 1
        if arguments.negative_control:
            negative = run_once(javac, java, Path(dirname) / "negative-hook", "hook")
            if negative.returncode == 0 or "untrusted event data rejected exactly" not in negative.stderr:
                print("FAIL: altered target validator escaped the negative control",
                      file=sys.stderr)
                return 1
            print("PASS negative control: forged target was detected")
            negative_exit = run_once(javac, java, Path(dirname) / "negative-exit", "exit")
            if (negative_exit.returncode == 0 or
                    "forged target exit metadata rejected" not in negative_exit.stderr):
                print("FAIL: forged WhatsApp exit bypass escaped the negative control",
                      file=sys.stderr)
                return 1
            print("PASS negative control: forged WhatsApp crash report was detected")
    return 0


if __name__ == "__main__":
    sys.exit(main())
