#!/usr/bin/env python3
"""Compile the production flag policy and run its Java fixture with JDK 17+.

Uses JAVA_HOME when set, otherwise java/javac from PATH. This is a JVM policy
test: it does not execute Android, Xposed, Binder, actual WakeLock accounting,
timeouts, sensor delivery, WhatsApp, or battery/performance measurements.
"""
from pathlib import Path
import hashlib
import json
import os
import shutil
import subprocess
import sys
import tempfile
import time


ROOT = Path(__file__).resolve().parents[1]
POLICY = ROOT / "app/src/main/java/com/waenhancer/xposed/features/media/ProximityWakeLockPolicy.java"
FIXTURE = ROOT / "qa/TestProximityWakeLockPolicy.java"
MAIN_CLASS = "com.waenhancer.xposed.features.media.TestProximityWakeLockPolicy"


def jdk_tools():
    java_home = os.environ.get("JAVA_HOME")
    if java_home:
        directory = Path(java_home).expanduser() / "bin"
        suffix = ".exe" if os.name == "nt" else ""
        selected = {name: directory / (name + suffix) for name in ("java", "javac")}
        origin = "JAVA_HOME"
    else:
        selected = {name: shutil.which(name) for name in ("java", "javac")}
        origin = "PATH"
    for name, path in selected.items():
        if path is None or not Path(path).is_file():
            raise OSError(f"{origin} must provide both java and javac; missing {name}. Use a complete JDK 17+.")
    tools = {name: str(Path(path).resolve()) for name, path in selected.items()}
    print("JDK " + json.dumps({"selection": origin, **tools}), flush=True)
    return tools


def run(command, timeout):
    print("RUN " + json.dumps(list(map(str, command))), flush=True)
    subprocess.run(list(map(str, command)), cwd=ROOT, check=True, timeout=timeout)


def input_hashes():
    return {str(path.relative_to(ROOT)): hashlib.sha256(path.read_bytes()).hexdigest()
            for path in (POLICY, FIXTURE)}


def main():
    started = time.monotonic()
    stage = "toolchain/input validation"
    try:
        tools = jdk_tools()
        before = input_hashes()
        print("INPUT_SHA256 " + json.dumps(before, sort_keys=True), flush=True)
        run([tools["java"], "-version"], timeout=15)
        run([tools["javac"], "-version"], timeout=15)
        with tempfile.TemporaryDirectory(prefix="waex_proximity_wakelock_") as temp:
            stage = "compilation"
            run([tools["javac"], "--release", "17", "-encoding", "UTF-8", "-d", temp,
                 POLICY, FIXTURE], timeout=60)
            stage = "Java policy fixture"
            run([tools["java"], "-cp", temp, MAIN_CLASS], timeout=30)
        if input_hashes() != before:
            print("FAIL: source inputs changed during execution; result is not reusable.", file=sys.stderr)
            return 1
    except (OSError, subprocess.TimeoutExpired) as failure:
        print(f"BLOCKED: {stage}: {failure}", file=sys.stderr)
        return 2
    except subprocess.CalledProcessError as failure:
        print(f"FAIL: {stage} exited {failure.returncode}.", file=sys.stderr)
        return 1
    print(f"PASS: production proximity flag policy + Java fixture ({time.monotonic() - started:.3f}s). "
          "Android/Xposed/Binder, actual WakeLock lifecycle and device effects NOT_VERIFIED.", flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
