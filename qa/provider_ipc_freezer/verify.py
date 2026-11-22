#!/usr/bin/env python3
"""Compile the production IPC helper against strict Android contract stubs.

Mutants must fail the unchanged independent oracle. This is a JVM contract
check, not an Android build, installation, or functional WhatsApp acceptance.
"""

from __future__ import annotations

import json
import re
import shutil
import subprocess
import sys
from pathlib import Path
from tempfile import TemporaryDirectory

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
SOURCE = ROOT / "app/src/main/java/com/waenhancer/xposed/bridge/client/HookProviderIpc.java"
STUBS = sorted((HERE / "stubs").rglob("*.java"))
HARNESS = HERE / "Harness.java"
EXPECTED_CALLS = {
    "utils/WhatsAppPackageDetector.kt": 2,
    "xposed/MainHook.java": 1,
    "xposed/bridge/client/PreferenceBridgeClient.java": 2,
    "xposed/core/FeatureRegistry.java": 1,
    "xposed/features/conversation/PreserveDeleteForMeHook.kt": 1,
    "xposed/features/media/CallRecordingHook.java": 3,
    "xposed/features/media/VoiceTranscriptionBridge.java": 3,
    "xposed/utils/WhatsAppProcessRestarter.java": 1,
}


def compiler() -> list[str]:
    if shutil.which("javac"):
        return ["javac"]
    if shutil.which("java"):
        # Some minimal JDK images expose jdk.compiler but omit the javac launcher.
        return ["java", "com.sun.tools.javac.Main"]
    raise RuntimeError("Java 17+ compiler is unavailable")


def execute(source: str) -> tuple[bool, str]:
    with TemporaryDirectory(prefix="waex-ipc-") as temp:
        dest = Path(temp)
        candidate = dest / "HookProviderIpc.java"
        candidate.write_text(source, encoding="utf-8")
        classes = dest / "classes"
        classes.mkdir()
        # The minimal JDK in CI may omit lib/ct.sym, so --release is unavailable;
        # source/target 17 suffice for this stub-only API contract compilation.
        command = [*compiler(), "-source", "17", "-target", "17", "-d", str(classes),
                   *map(str, STUBS), str(HARNESS), str(candidate)]
        compiled = subprocess.run(command, capture_output=True, text=True, timeout=40)
        if compiled.returncode:
            return False, "COMPILE: " + compiled.stderr.strip()[:500]
        ran = subprocess.run(["java", "-cp", str(classes), "Harness"],
                             capture_output=True, text=True, timeout=15)
        return ran.returncode == 0, (
            ran.stdout.strip() if ran.returncode == 0 else
            "TEST: " + ran.stderr.strip()[:500]
        )


def mutate(source: str, old: str, new: str) -> str:
    if source.count(old) != 1:
        raise RuntimeError(f"Expected exactly one mutation site: {old[:75]}")
    return source.replace(old, new)


def check_wiring() -> tuple[bool, str]:
    base = ROOT / "app/src/main/java/com/waenhancer"
    errors: list[str] = []
    count = 0
    for relative, expected in EXPECTED_CALLS.items():
        path = base / relative
        actual = path.read_text(encoding="utf-8").count("HookProviderIpc.call(")
        count += actual
        if actual != expected:
            errors.append(f"{relative}: {actual}, expected {expected}")
    for path in base.rglob("*"):
        if path.suffix not in (".java", ".kt"):
            continue
        if path.name == "HookProviderIpc.java":
            continue
        source = path.read_text(encoding="utf-8")
        # A direct ContentResolver.call() leaves a stable dependency in the host.
        if re.search(r"(?:getContentResolver\(\)|contentResolver)\.call\s*\(", source):
            errors.append(f"unmigrated stable provider call: {path.relative_to(base)}")
    return not errors, f"{count} client calls, " + ("; ".join(errors) or "no direct stable calls")


def main() -> int:
    production = SOURCE.read_text(encoding="utf-8")
    good, observation = execute(production)
    wired, wiring = check_wiring()
    if not good or not wired:
        print(json.dumps({"reference": "FAIL", "details": observation,
                          "wiring": wiring, "wiring_pass": wired}, indent=2))
        return 1

    try_open = (
        "try (ContentProviderClient provider =\n"
        "                     context.getContentResolver().acquireUnstableContentProviderClient(URI)) {"
    )
    mutations = {
        "stable_reference": mutate(production, "acquireUnstableContentProviderClient(URI)",
                                   "acquireContentProviderClient(URI)"),
        "leaked_client": mutate(production, try_open, try_open.replace(
            "try (ContentProviderClient provider =", "ContentProviderClient provider ="
        ).replace("(URI)) {", "(URI);\n        try {")),
        "swallow_security_exception": mutate(production, "catch (RemoteException ignored)",
                                             "catch (Exception ignored)"),
        "dropped_reply": mutate(production, "return provider.call(method, arg, extras);",
                                "provider.call(method, arg, extras);\n            return null;"),
        "dropped_extras": mutate(production, "provider.call(method, arg, extras)",
                                "provider.call(method, arg, null)"),
    }
    observations: dict[str, str] = {}
    for name, candidate in mutations.items():
        mutant_passes, detail = execute(candidate)
        if mutant_passes:
            observations[name] = "SURVIVED (unexpected)"
        elif detail.startswith("TEST:"):
            observations[name] = "KILLED: " + detail[:230]
        else:
            observations[name] = "INVALID_MUTANT: " + detail[:230]

    killed = sum(value.startswith("KILLED:") for value in observations.values())
    summary = {"reference": "PASS", "details": observation,
               "wiring": wiring, "wiring_pass": wired,
               "mutants_killed": killed, "mutants_total": len(mutations),
               "mutations": observations,
               "limit": "JVM API-contract stubs; no Android APK build or freezer E2E"}
    print(json.dumps(summary, indent=2))
    return 0 if killed == len(mutations) else 1


if __name__ == "__main__":
    sys.exit(main())
