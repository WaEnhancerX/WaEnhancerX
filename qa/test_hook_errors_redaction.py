#!/usr/bin/env python3
"""Compile the real HookErrors with explicit local annotation/logger doubles."""
from pathlib import Path
import argparse
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
PRODUCTION = ROOT / "app/src/main/java/com/waenhancer/xposed/utils/HookErrors.java"
TEST = ROOT / "qa/java/HookErrorsChecks.java"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, default=PRODUCTION)
    args = parser.parse_args()
    with tempfile.TemporaryDirectory(prefix="waex-hook-errors-") as folder:
        output = Path(folder)
        stubs = {
            "androidx/annotation/NonNull.java":
                "package androidx.annotation; public @interface NonNull {}",
            "androidx/annotation/VisibleForTesting.java":
                "package androidx.annotation; public @interface VisibleForTesting {}",
            "de/robv/android/xposed/XposedBridge.java":
                "package de.robv.android.xposed; public class XposedBridge { public static void log(String s) {} }",
        }
        files = []
        for relative, code in stubs.items():
            path = output / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(code, encoding="utf-8")
            files.append(path)
        subprocess.run(["javac", "--release", "8", "-d", str(output), *map(str, files),
                        str(args.source), str(TEST)], check=True, timeout=30)
        subprocess.run(["java", "-cp", str(output),
                        "com.waenhancer.xposed.utils.HookErrorsChecks"], check=True, timeout=30)


if __name__ == "__main__":
    main()
