#!/usr/bin/env python3
"""Compile and run a bounded, uninstalled locale-table probe on explicit Android.

Requires existing JDK17+, SDK36, build-tools, adb and an already compiled table
from qa/verify_es419_resources.py. No installation, network, root request, app
restart, account access, global settings or private dependency is performed.
"""
from __future__ import annotations

import argparse
import base64
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import shlex
import subprocess
import sys
import time
import uuid


def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--resources-apk", type=Path, required=True)
    parser.add_argument("--java-home", type=Path, required=True)
    parser.add_argument("--sdk-root", type=Path, required=True)
    parser.add_argument("--build-tools", default="36.0.0")
    parser.add_argument("--serial", required=True)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    out = args.out.resolve()
    out.mkdir(parents=True, exist_ok=False)
    root, apk, java_home, sdk = (value.resolve() for value in (args.root, args.resources_apk, args.java_home, args.sdk_root))
    if not re.fullmatch(r"[A-Za-z0-9_.:-]+", args.serial):
        raise ValueError("Invalid explicit ADB serial")
    if not re.fullmatch(r"[A-Za-z0-9_.-]+", args.build_tools):
        raise ValueError("Invalid SDK build-tools version")
    windows = os.name == "nt"
    suffix = ".exe" if windows else ""
    javac = java_home / "bin" / ("javac" + suffix)
    adb = sdk / "platform-tools" / ("adb" + suffix)
    build_tools = sdk / "build-tools" / args.build_tools
    d8 = build_tools / ("d8.bat" if windows else "d8")
    android = sdk / "platforms/android-36/android.jar"
    source = Path(__file__).resolve().with_name("WaexLocaleTableProbe.java")
    oracle_file = Path(__file__).resolve().parent.parent / "check_resources.py"
    table_evidence = apk.parent / "result.json"
    commands = []
    results = {}
    identity = {}
    device_dir = "/data/local/tmp/waex-l10n-probe-" + uuid.uuid4().hex
    owned_device_dir = False
    cleanup_ok = True
    overall = "BLOCKED"
    error = None
    env = os.environ.copy()
    env["JAVA_HOME"] = str(java_home)
    env["PATH"] = str(java_home / "bin") + os.pathsep + env.get("PATH", "")

    def run(argv, name, timeout=60, require_zero=True):
        start = time.monotonic()
        try:
            proc = subprocess.run([str(v) for v in argv], stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                  encoding="utf-8", errors="replace", timeout=timeout, env=env)
            text, exit_code = proc.stdout, proc.returncode
        except subprocess.TimeoutExpired as exc:
            raw = exc.stdout or b""
            text = raw.decode("utf-8", "replace") if isinstance(raw, bytes) else raw
            text += "\nHOST_TIMEOUT\n"
            exit_code = 124
        (out / name).write_text(text, encoding="utf-8")
        commands.append({"command": [str(v) for v in argv], "log": name, "exit": exit_code,
                         "duration_seconds": round(time.monotonic() - start, 3)})
        if require_zero and exit_code != 0:
            raise RuntimeError(name + " failed with exit " + str(exit_code))
        return exit_code, text

    try:
        for required in (javac, adb, d8, android, source, oracle_file, apk, table_evidence):
            if not required.is_file():
                raise RuntimeError("Required existing input missing: " + str(required))
        specification = importlib.util.spec_from_file_location("waex_independent_locale_oracle", oracle_file)
        oracle = importlib.util.module_from_spec(specification)
        specification.loader.exec_module(oracle)
        oracle.self_check()
        tables = {}
        source_hashes = {}
        for qualifier in oracle.QUALIFIERS:
            tables[qualifier], hashes = oracle.read_resources(root, qualifier)
            source_hashes.update(hashes)
        if not (tables["values"].keys() == tables["values-es"].keys() == tables["values-b+es+419"].keys()):
            raise RuntimeError("Source resource coverage is incomplete")
        evidence = json.loads(table_evidence.read_text(encoding="utf-8"))
        if evidence.get("identities") != source_hashes:
            raise RuntimeError("AAPT2 evidence identities differ from the candidate source")
        rows = []
        for key in sorted(tables["values"]):
            english = tables["values"][key]
            spanish = tables["values-b+es+419"][key]
            fallback = tables["values-es"][key]
            if (spanish["text"], spanish["attributes"], spanish["reference"]) != (fallback["text"], fallback["attributes"], fallback["reference"]):
                raise RuntimeError("Spanish fallback differs: " + repr(key))
            formatted = english["attributes"].get("formatted") != "false"
            contract = oracle.comparable_contract(english["text"], formatted)
            if english["attributes"] != spanish["attributes"] or english["reference"] != spanish["reference"] or contract != oracle.comparable_contract(spanish["text"], formatted):
                raise RuntimeError("Resource/format contract differs: " + repr(key))
            types = contract[2]
            codes = "".join(types.get(i, "s") for i in range(1, max(types, default=0) + 1))
            values = [base64.b64encode(entry["text"].encode("utf-8")).decode("ascii") for entry in (english, spanish)]
            rows.append("\t".join([*key, *values, codes]))
        inputs = out / "resource-expectations.tsv"
        inputs.write_bytes(("\n".join(rows) + "\n").encode("utf-8"))
        identity = {"resource_sources": source_hashes, "resource_apk_sha256": digest(apk),
                    "aapt2_evidence_sha256": digest(table_evidence), "expectations_sha256": digest(inputs),
                    "probe_java_sha256": digest(source), "oracle_sha256": digest(oracle_file),
                    "runner_sha256": digest(__file__), "android_jar_sha256": digest(android)}
        (out / "input-identities.json").write_text(json.dumps(identity, indent=2) + "\n", encoding="utf-8")
        run([javac, "-version"], "javac-version.log")
        run([adb, "version"], "adb-version.log")
        run([d8, "--version"], "d8-version.log")
        _, state = run([adb, "-s", args.serial, "get-state"], "device-state.log")
        if state.strip() != "device":
            raise RuntimeError("Explicit Android target is not ready")
        _, sdk_level = run([adb, "-s", args.serial, "shell", "getprop ro.build.version.sdk"], "device-sdk.log")
        if int(sdk_level.strip()) < 26:
            raise RuntimeError("This probe requires Android API26+; no older compatibility is claimed")
        run([adb, "-s", args.serial, "shell", "getprop ro.build.fingerprint"], "device-fingerprint.log")
        run([adb, "-s", args.serial, "shell", "getprop ro.product.cpu.abilist"], "device-abi.log")
        run([adb, "-s", args.serial, "shell", "id"], "device-shell-identity.log")
        _, timeout_path = run([adb, "-s", args.serial, "shell", "command -v timeout"], "device-timeout.log")
        if not timeout_path.strip().startswith("/system/"):
            raise RuntimeError("A real Android timeout command is required for a bounded probe")
        classes = out / "classes"
        classes.mkdir()
        run([javac, "--release", "17", "-encoding", "UTF-8", "-cp", android, "-d", classes, source], "compile-java.log", timeout=90)
        dex = out / "dex"
        dex.mkdir()
        class_files = sorted(classes.glob("*.class"))
        if not class_files:
            raise RuntimeError("No compiled Java probe classes")
        run([d8, "--lib", android, "--min-api", "26", "--output", dex, *class_files], "compile-dex.log", timeout=90)
        dex_file = dex / "classes.dex"
        if not dex_file.is_file():
            raise RuntimeError("D8 did not produce classes.dex")
        identity["probe_dex_sha256"] = digest(dex_file)
        run([adb, "-s", args.serial, "shell", "mkdir " + shlex.quote(device_dir)], "device-create.log")
        owned_device_dir = True
        for local, remote in ((dex_file, "classes.dex"), (apk, "resources.apk"), (inputs, "expectations.tsv")):
            run([adb, "-s", args.serial, "push", local, device_dir + "/" + remote], "push-" + remote + ".log")
            _, remote_digest = run([adb, "-s", args.serial, "shell", "sha256sum " + shlex.quote(device_dir + "/" + remote)], "hash-" + remote + ".log")
            if remote_digest.split()[0].lower() != digest(local):
                raise RuntimeError("Remote input SHA256 mismatch: " + remote)
        modes = {"candidate": None, "negative-expected": "NEGATIVE_EXPECTED_VALUE",
                 "negative-owner": "NEGATIVE_RESOURCE_OWNER", "negative-plural": "NEGATIVE_PLURAL_SELECTION",
                 "negative-format": "NEGATIVE_FORMAT_EXPANSION"}
        for mode, expected_marker in modes.items():
            command = "CLASSPATH=" + shlex.quote(device_dir + "/classes.dex") + " timeout 40s app_process /system/bin WaexLocaleTableProbe "
            command += " ".join(shlex.quote(v) for v in (device_dir + "/resources.apk", device_dir + "/expectations.tsv", mode))
            exit_code, text = run([adb, "-s", args.serial, "shell", command], mode + ".log", timeout=55, require_zero=False)
            summaries = re.findall(r"^WAEX_PROBE_RESULT=(.+)$", text, re.M)
            if len(summaries) != 1:
                raise RuntimeError(mode + ": no unique structured Android result")
            parsed = json.loads(summaries[0])
            if mode == "candidate":
                results[mode] = {"status": parsed.get("status", "BLOCKED"), "exit": exit_code, "observation": parsed}
                if exit_code != 0 or parsed.get("status") != "PASS":
                    overall = "FAIL" if parsed.get("failure_class", "").endswith("CheckFailure") else "BLOCKED"
                    raise RuntimeError("Candidate Android resource probe did not pass")
                results[mode] = {"status": "PASS", "exit": exit_code, "observation": parsed}
            else:
                if exit_code != 3 or parsed.get("status") != "FAIL" or parsed.get("marker") != expected_marker or not parsed.get("failure_class", "").endswith("CheckFailure"):
                    raise RuntimeError(mode + ": expected Android assertion was not observed")
                results[mode] = {"status": "FAIL", "expected_failure_detected": True, "exit": exit_code, "observation": parsed}
        overall = "PASS"
    except Exception as exc:
        error = type(exc).__name__ + ": " + str(exc)
    finally:
        if owned_device_dir:
            if not re.fullmatch(r"/data/local/tmp/waex-l10n-probe-[0-9a-f]{32}", device_dir):
                cleanup_ok = False
            else:
                paths = [device_dir + "/" + name for name in ("classes.dex", "resources.apk", "expectations.tsv")]
                exit_code, _ = run([adb, "-s", args.serial, "shell", "rm -f -- " + " ".join(shlex.quote(v) for v in paths)], "cleanup-files.log", require_zero=False)
                cleanup_ok = exit_code == 0
                exit_code, _ = run([adb, "-s", args.serial, "shell", "rmdir -- " + shlex.quote(device_dir)], "cleanup-directory.log", require_zero=False)
                cleanup_ok = cleanup_ok and exit_code == 0
            if not cleanup_ok:
                overall = "FAIL"
                error = (error + "; " if error else "") + "Own temporary Android probe files could not be fully removed"
        report = {"status": overall, "error": error, "serial": args.serial,
                  "device_directory": device_dir if owned_device_dir else None,
                  "device_files_removed": cleanup_ok if owned_device_dir else None,
                  "results": results, "identities": identity, "commands": commands,
                  "scope": "Real Android Resources/locale fallback/formatting/plurals from an isolated uninstalled AAPT2 table. No WAEX APK build, installed app UI, UiText/bridge behavior, hook, private module or performance acceptance."}
        (out / "result.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(json.dumps({k: report[k] for k in ("status", "error", "device_files_removed", "results")}, ensure_ascii=False))
    return 0 if overall == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
