#!/usr/bin/env python3
"""Compile the real recording-file allocator and test concurrent non-overwriting output.

The fixture uses a real temporary filesystem and a deterministic same-second
collision. Static wiring checks are separate from the allocator's behavior.
"""
import argparse
import hashlib
import json
import subprocess
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SOURCE = Path("app/src/main/java/com/waenhancer/calls/RecordingOutputFiles.java")
FIXTURE = Path("qa/fixtures/RecordingOutputFilesProbe.java")
HOOK = Path("app/src/main/java/com/waenhancer/xposed/features/media/CallRecordingHook.java")
PROVIDER = Path("app/src/main/java/com/waenhancer/xposed/bridge/HookProvider.java")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=ROOT)
    parser.add_argument("--out", type=Path)
    args = parser.parse_args()
    root = args.root.resolve()
    out = args.out or Path(tempfile.mkdtemp(prefix="waex-recording-files-"))
    out.mkdir(parents=True, exist_ok=True)
    source = (root / SOURCE).read_text(encoding="utf-8")
    hook = (root / HOOK).read_text(encoding="utf-8")
    provider = (root / PROVIDER).read_text(encoding="utf-8")
    checks = {
        "hook_requests_unique_name": "RecordingOutputFiles.newName(format.extension)" in hook,
        "provider_reserves_atomically": "RecordingOutputFiles.reserve(directory, safeName)" in provider,
        "provider_never_truncates": "ParcelFileDescriptor.MODE_TRUNCATE" not in provider,
        "provider_closes_on_failure": "descriptor.close()" in provider,
        "provider_rejects_collisions": "if (output == null) return null;" in provider,
    }
    if not all(checks.values()):
        raise AssertionError("Production wiring failed: " + json.dumps(checks, sort_keys=True))

    def execute(label, java_source):
        place = out / label
        place.mkdir(exist_ok=True)
        src = place / SOURCE.name
        src.write_text(java_source, encoding="utf-8")
        classes = place / "classes"
        classes.mkdir(exist_ok=True)
        compile_run = subprocess.run(
            ["javac", "--release", "17", "-d", str(classes), str(src), str(root / FIXTURE)],
            capture_output=True, text=True, timeout=40,
        )
        (place / "compile.log").write_text(compile_run.stdout + compile_run.stderr)
        if compile_run.returncode:
            raise AssertionError(f"{label}: Java compiler failure: {compile_run.stderr[:800]}")
        sandbox = place / "recordings"
        sandbox.mkdir()
        result = subprocess.run(
            ["java", "-cp", str(classes), "com.waenhancer.calls.RecordingOutputFilesProbe", str(sandbox)],
            capture_output=True, text=True, timeout=50,
        )
        (place / "run.log").write_text(result.stdout + result.stderr)
        return result

    positive = execute("candidate", source)
    if positive.returncode or "PASS " not in positive.stdout:
        raise AssertionError("Candidate behavior failed: " + positive.stderr[:1000])

    # Independent negative: the old acceptance of an already-existing name
    # must be detected even when all other allocator logic remains identical.
    must_replace = "return output.createNewFile() ? output : null;"
    if source.count(must_replace) != 1:
        raise AssertionError("Could not construct exclusive-creation mutation")
    mutated = source.replace(must_replace, "output.createNewFile(); return output;", 1)
    negative = execute("mutation_allow_existing", mutated)
    if negative.returncode == 0:
        raise AssertionError("Negative control unexpectedly accepted overwriting an existing name")

    report = {
        "status": "PASS",
        "assertion_categories": list(checks),
        "wiring": checks,
        "source_sha256": hashlib.sha256((root / SOURCE).read_bytes()).hexdigest(),
        "hook_sha256": hashlib.sha256((root / HOOK).read_bytes()).hexdigest(),
        "provider_sha256": hashlib.sha256((root / PROVIDER).read_bytes()).hexdigest(),
        "candidate_exit_code": positive.returncode,
        "negative_control_exit_code": negative.returncode,
        "limitations": "Pure Java plus real host filesystem; no Android provider, recording or device effect",
    }
    (out / "result.json").write_text(json.dumps(report, indent=2) + "\n")
    print(positive.stdout.strip())
    print("PASS: mutation allowing an existing filename fails the collision fixture")
    print("EVIDENCE:", out)


if __name__ == "__main__":
    main()
