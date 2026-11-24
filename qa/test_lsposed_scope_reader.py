#!/usr/bin/env python3
"""Compile the production LSPosed reader with strict doubles and test its SQL contract.

No Android install, real LSPosed database copy, or WhatsApp account is required.
The compile/execution stage needs JDK 17+ and Kotlin 2.0.20+; use explicit
--javac/--java/--kotlinc paths on hosts where they are not on PATH.
"""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import sqlite3
import subprocess
import sys
import tempfile


READER = Path("app/src/main/java/com/waenhancer/utils/LsposedScopeReader.kt")
FIXTURES = Path("qa/fixtures/lsposed_scope")


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def run(command: list[str], *, env: dict[str, str]) -> dict:
    result = subprocess.run(command, capture_output=True, text=True, errors="replace", env=env)
    return {
        "command": [Path(command[0]).name, *command[1:]],
        "returncode": result.returncode,
        "stdout": result.stdout,
        "stderr": result.stderr,
    }


def test_sqlite_contract(reader: str) -> int:
    """Execute the exact Kotlin SQL literal against a real SQLite engine."""
    match = re.search(r'private const val SCOPE_QUERY = """(.*?)"""', reader, re.S)
    if not match:
        raise AssertionError("No static SQL query available for independent execution")
    sql = match.group(1)
    if sql.count("?") != 2:
        raise AssertionError("Scope SQL must bind module package and Android user ID")
    with sqlite3.connect(":memory:") as db:
        db.executescript(
            "CREATE TABLE modules(mid INTEGER, module_pkg_name TEXT, enabled INTEGER);"
            "CREATE TABLE scope(mid INTEGER, app_pkg_name TEXT, user_id INTEGER);"
        )
        db.executemany("INSERT INTO modules VALUES(?,?,?)", [
            (4, "com.waenhancer", 1), (5, "other.module", 1),
            (6, "com.waenhancer", 0),
        ])
        db.executemany("INSERT INTO scope VALUES(?,?,?)", [
            (4, "com.whatsapp", 0), (4, "com.whatsapp.w4b", 10),
            (4, "com.whatsapp", 11), (5, "com.whatsapp", 10),
            (6, "com.whatsapp", 10),
        ])
        cases = [
            ("com.waenhancer", 0, ["com.whatsapp"]),
            ("com.waenhancer", 10, ["com.whatsapp.w4b"]),
            ("com.waenhancer", 11, ["com.whatsapp"]),
            ("com.waenhancer", 9, []),
            ("other.module", 10, ["com.whatsapp"]),
        ]
        for module, user, expected in cases:
            actual = [row[0] for row in db.execute(sql, (module, str(user)))]
            if actual != expected:
                raise AssertionError(f"SQLite isolation mismatch for {module}/user{user}: {actual}")
    return len(cases)


def compile_and_run(label: str, reader: str, root: Path, tools: dict, env: dict, folder: Path) -> dict:
    classes = folder / "classes"
    classes.mkdir(parents=True, exist_ok=True)
    fixture = root / FIXTURES
    java_files = sorted(str(path) for path in fixture.rglob("*.java"))
    if len(java_files) != 7:
        raise AssertionError(f"Expected 7 strict Java stubs, got {len(java_files)}")
    source = folder / "LsposedScopeReader.kt"
    source.write_text(reader, encoding="utf-8")
    steps = []
    steps.append(run([tools["javac"], "-encoding", "UTF-8", "-d", str(classes), *java_files], env=env))
    if steps[-1]["returncode"] == 0:
        steps.append(run([
            tools["kotlinc"], str(source), str(fixture / "ScopeReaderChecks.kt"),
            "-classpath", str(classes), "-d", str(classes)
        ], env=env))
    if steps[-1]["returncode"] == 0:
        delimiter = ";" if os.name == "nt" else ":"
        stdlib = tools["stdlib"]
        classpath = delimiter.join([str(classes), str(stdlib)])
        steps.append(run([tools["java"], "-cp", classpath,
                          "com.waenhancer.utils.ScopeReaderChecks"], env=env))
    return {
        "label": label,
        "reader_sha256": sha256(reader.encode("utf-8")),
        "steps": steps,
        "compiled": len(steps) >= 2 and steps[1]["returncode"] == 0,
        "passed": len(steps) == 3 and steps[-1]["returncode"] == 0,
        "case_count": len(re.findall(r'checkCase\("', (fixture / "ScopeReaderChecks.kt").read_text(encoding="utf-8"))),
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--source-root", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--javac", default=shutil.which("javac"))
    parser.add_argument("--java", default=shutil.which("java"))
    parser.add_argument("--kotlinc", default=shutil.which("kotlinc"))
    parser.add_argument("--kotlin-stdlib", type=Path)
    parser.add_argument("--baseline-ref", help="Git ref whose reader must fail the same cases")
    parser.add_argument("--baseline-reader", type=Path, help="Unmodified baseline reader file")
    parser.add_argument("--mutation-control", action="store_true")
    parser.add_argument("--out", type=Path)
    args = parser.parse_args()
    root = args.source_root.resolve()
    source = (root / READER).read_text(encoding="utf-8")
    result: dict = {"source_path": str(READER), "source_sha256": sha256(source.encode()),
                    "sqlite_cases": test_sqlite_contract(source)}
    env = os.environ.copy()
    if not args.javac or not args.java or not args.kotlinc:
        print(json.dumps({**result, "status": "BLOCKED", "reason": "JDK/kotlinc not available"}, indent=2))
        return 2
    java_home = Path(args.javac).resolve().parent.parent
    env["JAVA_HOME"] = str(java_home)
    env["JAVA_TOOL_OPTIONS"] = "-Dfile.encoding=UTF-8"
    kotlin_home = Path(args.kotlinc).resolve().parent.parent
    stdlib = args.kotlin_stdlib or kotlin_home / "lib" / "kotlin-stdlib.jar"
    if not stdlib.is_file():
        raise FileNotFoundError(f"Kotlin stdlib missing: {stdlib}")
    paths = {"javac": args.javac, "java": args.java, "kotlinc": args.kotlinc, "stdlib": stdlib}

    with tempfile.TemporaryDirectory(prefix="waex-lsposed-scope-") as tmp:
        work = Path(tmp)
        result["candidate"] = compile_and_run("candidate", source, root, paths, env, work / "candidate")
        if args.baseline_ref or args.baseline_reader:
            baseline = (args.baseline_reader.read_text(encoding="utf-8") if args.baseline_reader else
                        subprocess.run(["git", "show", f"{args.baseline_ref}:{READER.as_posix()}"],
                                       cwd=root, capture_output=True, text=True, check=True).stdout)
            result["baseline"] = compile_and_run("baseline", baseline, root, paths, env, work / "baseline")
        if args.mutation_control:
            marker = "val userId = Process.myUid() / PER_USER_RANGE"
            if source.count(marker) != 1:
                raise AssertionError("Could not construct current-user mutation")
            mutant = source.replace(marker, "val userId = 0")
            result["mutant"] = compile_and_run("force-personal-user", mutant, root, paths, env, work / "mutant")

    candidate_ok = result["candidate"]["passed"]
    baseline_ok = "baseline" not in result or (result["baseline"]["compiled"] and not result["baseline"]["passed"])
    mutant_ok = "mutant" not in result or (result["mutant"]["compiled"] and not result["mutant"]["passed"])
    result["status"] = "PASS" if candidate_ok and baseline_ok and mutant_ok else "FAIL"
    if args.out:
        args.out.parent.mkdir(parents=True, exist_ok=True)
        args.out.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    for label in ("candidate", "baseline", "mutant"):
        if label in result:
            entry = result[label]
            print(f"{label}: compiled={entry['compiled']} passed={entry['passed']} cases={entry['case_count']}")
            for stage in entry["steps"]:
                print(f"  {stage['command'][0]} exit={stage['returncode']}")
                if stage["returncode"]:
                    print((stage["stderr"] or stage["stdout"])[-2800:])
    print(f"SQLite cases: {result['sqlite_cases']}; overall: {result['status']}")
    return 0 if result["status"] == "PASS" else 1


if __name__ == "__main__":
    sys.exit(main())
