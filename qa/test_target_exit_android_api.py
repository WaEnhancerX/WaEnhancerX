#!/usr/bin/env python3
"""Compile the actual WhatsApp exit-history adapter against an Android SDK API jar.

The actual unstable provider IPC helper is compiled with the adapter, so its
acquire/call/close API is checked against the same Android API jar.
This is an Android API compile contract, not a runtime/injection test.
"""

import argparse
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile


ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "app/src/main/java/com/waenhancer"
IPC = SOURCE / "xposed/bridge/client/HookProviderIpc.java"
ADAPTER = SOURCE / "xposed/utils/TargetExitReporter.java"
CONTRACT = SOURCE / "utils/TargetExitContract.java"
TARGETS = SOURCE / "utils/DeclaredWhatsAppTargets.java"


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--android-jar", required=True, type=Path)
    args = parser.parse_args()
    javac = shutil.which("javac")
    if javac is None or not args.android_jar.is_file():
        print("BLOCKED: JDK 17+ and an existing Android SDK 30+ android.jar required",
              file=sys.stderr)
        return 2
    with tempfile.TemporaryDirectory(prefix="waex-target-exit-api-") as temp:
        classes = Path(temp) / "classes"
        classes.mkdir()
        command = [javac, "--release", "17", "-cp", str(args.android_jar),
                   "-d", str(classes), str(IPC), str(TARGETS),
                   str(CONTRACT), str(ADAPTER)]
        result = subprocess.run(command, text=True, capture_output=True, timeout=90)
        if result.returncode:
            print(result.stdout + result.stderr, file=sys.stderr)
            return 1
    print(f"PASS actual TargetExitReporter Android API compilation jar={args.android_jar}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
