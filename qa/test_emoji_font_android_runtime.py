#!/usr/bin/env python3
"""Compile the real font policy with Android SDK and run it on an isolated device directory.

Requires authorized ADB, a JDK, Android SDK jar and D8; does not touch WhatsApp
data or install an APK. Reports the actual DEX SHA-256 and target API version.
"""

from pathlib import Path
import argparse
import hashlib
import subprocess
import tempfile
import uuid

ROOT = Path(__file__).resolve().parents[1]
POLICY = ROOT / "app/src/main/java/com/waenhancer/core/emojis/EmojiCloudStorage.java"
PROBE = ROOT / "qa/fixtures/EmojiCloudStorageAndroidProbe.java"


def run(arguments: list[str], timeout: int = 45) -> str:
    result = subprocess.run(arguments, capture_output=True, text=True, timeout=timeout)
    if result.returncode:
        raise RuntimeError(f"exit={result.returncode} for {arguments[0]}: {result.stderr or result.stdout}")
    return result.stdout.strip()


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", type=Path, required=True)
    parser.add_argument("--java", type=Path, required=True)
    parser.add_argument("--javac", type=Path, required=True)
    parser.add_argument("--android-jar", type=Path, required=True)
    parser.add_argument("--d8-jar", type=Path, required=True)
    parser.add_argument("--serial", required=True)
    args = parser.parse_args()

    for path in (args.adb, args.java, args.javac, args.android_jar, args.d8_jar, POLICY, PROBE):
        if not path.is_file():
            raise FileNotFoundError(str(path))

    adb = [str(args.adb), "-s", args.serial]
    state = run([*adb, "get-state"])
    if state != "device":
        raise RuntimeError("Expected authorized Android device, got " + state)

    with tempfile.TemporaryDirectory(prefix="waex-emoji-android-") as temp:
        base = Path(temp)
        classes = base / "classes"
        classes.mkdir()
        dexdir = base / "dex"
        dexdir.mkdir()
        run([str(args.javac), "--release", "8", "-cp", str(args.android_jar),
             "-d", str(classes), str(POLICY), str(PROBE)])
        compiled = [
            classes / "com/waenhancer/core/emojis/EmojiCloudStorage.class",
            classes / "com/waenhancer/core/emojis/EmojiCloudStorageAndroidProbe.class",
        ]
        run([str(args.java), "-cp", str(args.d8_jar), "com.android.tools.r8.D8",
             "--min-api", "24", "--lib", str(args.android_jar),
             "--output", str(dexdir), *[str(p) for p in compiled]])
        dex = dexdir / "classes.dex"
        if not dex.is_file():
            raise RuntimeError("D8 returned success without a classes.dex")
        digest = hashlib.sha256(dex.read_bytes()).hexdigest()

        remote = "/data/local/tmp/waex-emoji-integrity-" + uuid.uuid4().hex
        # A generated, unguessable path, never an existing WAEX/WhatsApp directory.
        probe = subprocess.run([*adb, "shell", "test", "-e", remote], capture_output=True)
        if probe.returncode == 0:
            raise RuntimeError("Test directory unexpectedly already exists")
        run([*adb, "shell", "mkdir", remote])
        try:
            run([*adb, "push", str(dex), remote + "/classes.dex"])
            output = run([*adb, "shell",
                          f"CLASSPATH={remote}/classes.dex app_process /system/bin "
                          f"com.waenhancer.core.emojis.EmojiCloudStorageAndroidProbe {remote}"], timeout=60)
            if "PASS " not in output or "Android runtime checks" not in output:
                raise AssertionError("Native probe did not report success: " + output)
            print(output)
            print("DEX SHA256 " + digest)
        finally:
            run([*adb, "shell", "rm", "-r", remote])


if __name__ == "__main__":
    main()
