#!/usr/bin/env python3
"""Run the full TargetAppManager with real coroutines and explicit platform doubles.

No Android runtime, root command, IPC or external broadcast is executed. The
iterator barrier forces two overlapping requests to finish iterating before
either claims its targets. Every case runs in a fresh JVM. Missing dependencies,
compilation errors and behavioral failures fail the suite; none count as a pass.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
FIXTURES = Path(__file__).resolve().parent / "fixtures/target_hook_sync"
PRODUCTION = Path("app/src/main/java/com/waenhancer/utils/TargetAppManager.kt")
CASES = (
    "single_ack", "forced_ack", "forced_timeout", "partial_overlap",
    "disjoint_progress", "timeout_retry", "cancel_retry", "duplicate_input",
    "empty_request", "default_installed_only", "broadcast_failure",
)


def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def kotlin_distribution(explicit):
    configured = explicit or os.environ.get("WAEX_QA_KOTLIN_HOME")
    if configured:
        home = Path(configured).expanduser().resolve()
    else:
        executable = shutil.which("kotlinc")
        if not executable:
            raise RuntimeError("kotlinc unavailable; provide --kotlin-home")
        home = Path(executable).resolve().parent.parent
    if not (home / "lib/kotlin-compiler.jar").is_file():
        raise RuntimeError("Expected a Kotlin CLI distribution with lib/kotlin-compiler.jar: " + str(home))
    return home


def jar_metadata(path):
    result = {"name": path.name, "sha256": sha256(path)}
    with zipfile.ZipFile(path) as archive:
        version_file = "META-INF/kotlinx_coroutines_core.version"
        if version_file in archive.namelist():
            result["coroutines_version"] = archive.read(version_file).decode("ascii").strip()
    return result


def run_checks(source_root=ROOT, out=None, kotlin_home=None, java=None,
               coroutines_jar=None, stdlib_jar=None):
    if out is None:
        with tempfile.TemporaryDirectory(prefix="waex-hook-sync-") as temporary:
            return run_checks(source_root, Path(temporary) / "run", kotlin_home, java,
                              coroutines_jar, stdlib_jar)
    source_root = Path(source_root).resolve()
    out = Path(out).resolve()
    out.mkdir(parents=True, exist_ok=False)
    source = source_root / PRODUCTION
    java = str(java or shutil.which("java") or "")
    if not java:
        raise RuntimeError("java unavailable")
    home = kotlin_distribution(kotlin_home)
    coroutines = Path(coroutines_jar or os.environ.get("WAEX_QA_COROUTINES_JAR") or
                      home / "lib/kotlinx-coroutines-core-jvm.jar").resolve()
    stdlib = Path(stdlib_jar or os.environ.get("WAEX_QA_KOTLIN_STDLIB_JAR") or
                  home / "lib/kotlin-stdlib.jar").resolve()
    for path in (source, coroutines, stdlib):
        if not path.is_file():
            raise RuntimeError("Required file unavailable: " + str(path))
    source_hash = sha256(source)
    fixture_hashes = {p.name: sha256(p) for p in sorted(FIXTURES.glob("*.kt"))}
    if len(fixture_hashes) != 7:
        raise RuntimeError("Expected all seven explicit fixture sources")
    record = {
        "status": "STARTED", "production_sha256": source_hash,
        "fixture_sha256": fixture_hashes, "cases": [],
        "dependencies": [jar_metadata(coroutines), jar_metadata(stdlib)],
        "full_production_source": True, "real_coroutines": True,
        "platform_preferences_and_root_are_doubles": True,
        "device_acceptance": False,
    }

    def command(arguments, directory, label, timeout):
        result = subprocess.run(list(map(str, arguments)), cwd=directory,
                                stdin=subprocess.DEVNULL, capture_output=True, timeout=timeout)
        (directory / (label + ".stdout")).write_bytes(result.stdout)
        (directory / (label + ".stderr")).write_bytes(result.stderr)
        return result

    try:
        src = out / "source"
        shutil.copytree(FIXTURES, src)
        shutil.copyfile(source, src / "TargetAppManager.kt")
        if sha256(src / "TargetAppManager.kt") != source_hash:
            raise RuntimeError("Production source copy changed")
        classpath = os.pathsep.join(map(str, (coroutines, stdlib)))
        jar = out / "checks.jar"
        # Calling the Java compiler main class avoids .bat/shell argument
        # reinterpretation on Windows; every path is a separate argv element.
        compiler = [java, "-Dkotlin.home=" + str(home), "-cp", home / "lib/*",
                    "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-jvm-target", "17",
                    "-no-stdlib", "-cp", classpath, "-d", jar, *sorted(src.glob("*.kt"))]
        compiled = command(compiler, out, "compile", 90)
        record["compile_exit"] = compiled.returncode
        if compiled.returncode:
            print(compiled.stderr.decode("utf-8", "replace"))
            raise RuntimeError("Kotlin compilation failed")
        record["compiled_jar_sha256"] = sha256(jar)
        runtime = os.pathsep.join(map(str, (jar, coroutines, stdlib)))
        for case in CASES:
            case_out = out / case
            case_out.mkdir()
            ran = command([java, "-ea", "-cp", runtime,
                           "com.waenhancer.utils.SyncChecksKt", case], case_out, "run", 20)
            stdout = ran.stdout.decode("utf-8", "replace")
            lines = stdout.splitlines()
            receipts = [line.split("|") for line in lines if line.startswith("RESULT|")]
            passed = (ran.returncode == 0 and len(receipts) == 1 and
                      len(receipts[0]) == 4 and receipts[0][1:3] == ["PASS", case])
            entry = {"case": case, "process_exit": ran.returncode, "passed": passed,
                     "receipt": receipts, "observations": [line for line in lines if line.startswith("OBS|")],
                     "runtime": [line for line in lines if line.startswith("RUNTIME|")],
                     "errors": [line for line in lines if line.startswith("ERROR|")],
                     "stdout_sha256": sha256(case_out / "run.stdout"),
                     "stderr_sha256": sha256(case_out / "run.stderr")}
            record["cases"].append(entry)
            print("HOOK_SYNC " + json.dumps({"case": case, "passed": passed, "receipt": receipts}), flush=True)
            if not passed:
                print(stdout)
                print(ran.stderr.decode("utf-8", "replace"))
                raise RuntimeError("Hook-sync behavior failed: " + case)
        if sha256(source) != source_hash or fixture_hashes != {
                p.name: sha256(p) for p in sorted(FIXTURES.glob("*.kt"))}:
            raise RuntimeError("An input changed during execution")
        record["status"] = "PASS"
        return record
    except Exception as error:
        record["status"] = "FAIL"
        record["failure"] = str(error)
        raise
    finally:
        (out / "RESULT.json").write_text(json.dumps(record, indent=2) + "\n", encoding="utf-8")
        print("HOOK_SYNC_RESULT " + json.dumps({"status": record["status"],
              "cases": len(record["cases"]), "production_sha256": source_hash}), flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-root", type=Path, default=ROOT)
    parser.add_argument("--out", type=Path)
    parser.add_argument("--kotlin-home", type=Path)
    parser.add_argument("--java", type=Path)
    parser.add_argument("--coroutines-jar", type=Path)
    parser.add_argument("--stdlib-jar", type=Path)
    run_checks(**vars(parser.parse_args()))


if __name__ == "__main__":
    main()
