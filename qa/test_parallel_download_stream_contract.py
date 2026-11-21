#!/usr/bin/env python3
"""Compile real parallel stream code, then exercise HTTP Range/EOF negative controls."""

import argparse
import json
import shutil
import subprocess
import tempfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SOURCES = [
    ROOT / "app/src/main/java/com/waenhancer/xposed/features/media/ParallelDownloadingInputStream.java",
    ROOT / "qa/java/ParallelDownloadingStreamContractChecks.java",
    ROOT / "qa/java/de/robv/android/xposed/XposedBridge.java",
]


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--out", type=Path, default=None)
    args = parser.parse_args()

    for path in SOURCES:
        if not path.is_file():
            raise SystemExit(f"Required production/fixture source missing: {path}")

    with tempfile.TemporaryDirectory(prefix="waex-parallel-range-") as temporary:
        classes = Path(temporary) / "classes"
        classes.mkdir()
        compiler = ["javac"] if shutil.which("javac") else ["java", "com.sun.tools.javac.Main"]
        compile_args = compiler + ["-encoding", "UTF-8", "-d", str(classes)] + [str(path) for path in SOURCES]
        compiled = subprocess.run(compile_args, text=True, capture_output=True, timeout=45)
        executed = None
        if compiled.returncode == 0:
            executed = subprocess.run(
                ["java", "-cp", str(classes), "com.waenhancer.xposed.features.media.ParallelDownloadingStreamContractChecks"],
                text=True, capture_output=True, timeout=45,
            )

        report = {
            "source": str(SOURCES[0].relative_to(ROOT)),
            "cases": 12,
            "compiler_command": compiler,
            "compile_returncode": compiled.returncode,
            "run_returncode": executed.returncode if executed else None,
            "status": "PASS" if executed and executed.returncode == 0 else "FAIL",
        }
        if args.out:
            args.out.mkdir(parents=True, exist_ok=True)
            (args.out / "result.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
            (args.out / "compile.log").write_text(compiled.stdout + compiled.stderr, encoding="utf-8")
            if executed:
                (args.out / "run.log").write_text(executed.stdout + executed.stderr, encoding="utf-8")

        print(json.dumps(report, sort_keys=True))
        if compiled.returncode:
            raise SystemExit("FAIL: standalone production Java compilation\n" + compiled.stderr)
        if not executed or executed.returncode:
            raise SystemExit("FAIL: parallel stream contract\n" + (executed.stdout + executed.stderr if executed else "not run"))


if __name__ == "__main__":
    main()
