#!/usr/bin/env python3
"""Compile the production Android file policy against strict host doubles.

The test does not compile WAEX's private licensing module or claim WhatsApp E2E
validation. It checks that synthetic invalid downloads cannot replace valid data.
"""

from pathlib import Path
import argparse
import re
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "app/src/main/java/com/waenhancer/core/emojis/EmojiCloudStorage.java"
TEST = ROOT / "qa/fixtures/EmojiCloudStorageChecks.java"
STUB = ROOT / "qa/fixtures/android/system"


def run(source: str, label: str, expect_fail: bool) -> None:
    with tempfile.TemporaryDirectory(prefix="waex-emoji-integrity-") as work:
        workspace = Path(work)
        target = workspace / "EmojiCloudStorage.java"
        target.write_text(source, encoding="utf-8")
        command = [
            "javac", "--release", "17", "-d", str(workspace),
            str(target), str(TEST), str(STUB / "ErrnoException.java"),
            str(STUB / "Os.java"),
        ]
        compile_result = subprocess.run(command, capture_output=True, text=True)
        if compile_result.returncode:
            raise AssertionError(f"{label}: compilation failed: {compile_result.stderr}")

        result = subprocess.run(
            ["java", "-cp", str(workspace),
             "com.waenhancer.core.emojis.EmojiCloudStorageChecks"],
            capture_output=True, text=True,
        )
        if expect_fail:
            if result.returncode == 0:
                raise AssertionError(f"{label}: negative control was not detected")
            if "AssertionError" not in result.stderr:
                raise AssertionError(f"{label}: failed for an unrelated reason: {result.stderr}")
            print(f"PASS negative control: {label} was rejected")
        else:
            if result.returncode:
                raise AssertionError(f"{label}: {result.stderr or result.stdout}")
            print(result.stdout.strip())


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, default=SOURCE)
    parser.add_argument("--skip-negative-controls", action="store_true")
    args = parser.parse_args()
    source = args.source.read_text(encoding="utf-8")
    run(source, "production", expect_fail=False)
    if args.skip_negative_controls:
        return

    unsafe_name, count = re.subn(
        r"(public static boolean isSafeFontName\(String filename\) \{).*?(\n    \})",
        r"\1\n        return true;\2", source, count=1, flags=re.DOTALL,
    )
    if count != 1:
        raise AssertionError("filename negative control could not be constructed")
    run(unsafe_name, "unsafe filenames", expect_fail=True)

    absent_size_check = source.replace(
        "|| (listedSize > 0L && bytes != listedSize)", "|| false", 1,
    )
    if absent_size_check == source:
        raise AssertionError("size negative control could not be constructed")
    run(absent_size_check, "ignored declared content size", expect_fail=True)

    broken_promotion = source.replace(
        "Os.rename(stage.getAbsolutePath(), target.getAbsolutePath());",
        "Os.rename(stage.getAbsolutePath(), target.getAbsolutePath()); target.delete();", 1,
    )
    if broken_promotion == source:
        raise AssertionError("replace negative control could not be constructed")
    run(broken_promotion, "lost replacement", expect_fail=True)


if __name__ == "__main__":
    main()
