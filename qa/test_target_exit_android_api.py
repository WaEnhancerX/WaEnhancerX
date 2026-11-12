#!/usr/bin/env python3
"""Compile the actual WhatsApp exit-history adapter against an Android SDK API jar.

Only the provider authority is stubbed because PreferenceBridgeClient itself
has broader Xposed/AndroidX dependencies unrelated to this feature. The stub's
single constant is checked against the real implementation before compilation.
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
CLIENT = SOURCE / "xposed/bridge/client/PreferenceBridgeClient.java"
STUB = ROOT / "qa/fixtures/android_target_exit_stub/com/waenhancer/xposed/bridge/client/PreferenceBridgeClient.java"
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
    required = 'public static final String AUTHORITY = "com.waenhancer.hookprovider";'
    if required not in CLIENT.read_text(encoding="utf-8"):
        print("FAIL: test authority stub no longer matches production", file=sys.stderr)
        return 1
    with tempfile.TemporaryDirectory(prefix="waex-target-exit-api-") as temp:
        classes = Path(temp) / "classes"
        classes.mkdir()
        command = [javac, "--release", "17", "-cp", str(args.android_jar),
                   "-d", str(classes), str(STUB), str(TARGETS),
                   str(CONTRACT), str(ADAPTER)]
        result = subprocess.run(command, text=True, capture_output=True, timeout=90)
        if result.returncode:
            print(result.stdout + result.stderr, file=sys.stderr)
            return 1
    print(f"PASS actual TargetExitReporter Android API compilation jar={args.android_jar}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
