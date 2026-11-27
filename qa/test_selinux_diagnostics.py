#!/usr/bin/env python3
"""Compile the complete SELinux diagnostic reader against explicit JVM boundaries."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
READER = Path("app/src/main/java/com/waenhancer/utils/SystemDiagnostics.kt")
EXPECTED_CHECKS = 21

STUBS = {'Build.kt': 'package android.os\n'
             'object Build {\n'
             '    val MANUFACTURER = "fixture"\n'
             '    val MODEL = "host"\n'
             '    val SUPPORTED_ABIS = arrayOf("fixture")\n'
             '    object VERSION { const val SDK_INT = 30; const val RELEASE = "11" }\n'
             '}\n'
             'object SELinux {\n'
             '    var enforced = false\n'
             '    @JvmStatic fun isSELinuxEnforced() = enforced\n'
             '}\n',
 'Command.kt': 'package com.waenhancer.xposed.utils\n'
               'object AppRestartHelper {\n'
               '    var calls = 0\n'
               '    var output: String? = "Enforcing"\n'
               '    var fail = false\n'
               '    fun runRootCommand(command: String): String? {\n'
               '        require(command == "getenforce")\n'
               '        calls++\n'
               '        if (fail) throw IllegalStateException("fixture")\n'
               '        return output\n'
               '    }\n'
               '}\n',
 'Config.kt': 'package com.waenhancer.app\n'
              'object BuildConfig {\n'
              '    const val VERSION_NAME = "fixture"\n'
              '    const val VERSION_CODE = 1\n'
              '    const val GIT_SHA = "fixture"\n'
              '    const val MAPPING_ID = "fixture"\n'
              '}\n',
 'Context.kt': 'package android.content\n'
               'class Context {\n'
               '    val packageName = "fixture.manager"\n'
               '    val packageManager = android.content.pm.PackageManager(packageName)\n'
               '}\n',
 'PM.kt': 'package android.content.pm\n'
          'class ApplicationInfo\n'
          'class PackageInfo {\n'
          '    val versionName = "fixture"\n'
          '    val versionCode = 1\n'
          '    val longVersionCode = 1L\n'
          '    val applicationInfo: ApplicationInfo? = ApplicationInfo()\n'
          '}\n'
          'class PackageManager(val own: String) {\n'
          '    fun getPackageInfo(name: String, flags: Int): PackageInfo {\n'
          '        require(name == own)\n'
          '        return PackageInfo()\n'
          '    }\n'
          '    fun getApplicationLabel(info: ApplicationInfo): CharSequence = "fixture"\n'
          '}\n',
 'Prefs.kt': 'package com.waenhancer.config\n'
             'object PreferenceStores { fun publicStore(context: android.content.Context) = Store() }\n'
             'class Store {\n'
             '    fun getInt(key: String, fallback: Int) = fallback\n'
             '    fun getString(key: String, fallback: String?) = fallback\n'
             '    fun getStringSet(key: String, fallback: Set<String>?): Set<String>? = fallback\n'
             '}\n',
 'RootAccess.kt': 'package com.waenhancer.update\n'
                  'object UpdateDownloader {\n'
                  '    var allowed = true\n'
                  '    fun hasCachedRootAccess(): Boolean? = allowed\n'
                  '    fun hasRootAccess(force: Boolean): Boolean = allowed\n'
                  '}\n',
 'Targets.kt': 'package com.waenhancer.utils\n'
               'object TargetAppManager {\n'
               '    fun isModuleActive() = false\n'
               '    fun refresh(context: android.content.Context) = Unit\n'
               '}\n'}

DRIVER = 'package com.waenhancer.utils\nimport android.content.Context\nimport android.os.SELinux\nimport com.waenhancer.update.UpdateDownloader\nimport com.waenhancer.xposed.utils.AppRestartHelper\nimport java.io.File\n\nfun main() {\n    require(runCatching { File("/sys/fs/selinux/enforce").readText() }.isFailure) {\n        "Fixture requires an unreadable kernel path"\n    }\n    val failed = mutableListOf<String>()\n    var checks = 0\n    fun expect(name: String, condition: Boolean) {\n        checks++\n        if (!condition) failed.add(name)\n    }\n    val reader = SystemDiagnosticsReader\n    val context = Context()\n    val root = AppRestartHelper\n    SELinux.enforced = false\n    val fast = reader.getOrFastSnapshot(context, false)\n    expect("unreadable_fast_is_unknown", fast.selinux == "Unknown")\n    expect("no_fast_root_command", root.calls == 0)\n    val first = reader.read(context, false)\n    expect("full_read_replaces_provisional", first.selinux == "Enforcing")\n    expect("root_is_consulted_once", root.calls == 1)\n    val cached = reader.read(context, false)\n    expect("known_full_cache", cached.selinux == "Enforcing" && root.calls == 1)\n    root.output = " permissive\\n"\n    expect("force_reads_permissive", reader.read(context, false, true).selinux == "Permissive" && root.calls == 2)\n    UpdateDownloader.allowed = false\n    expect("no_root_unknown", reader.read(context, false, true).selinux == "Unknown")\n    expect("no_command_without_root", root.calls == 2)\n    UpdateDownloader.allowed = true\n    root.output = "Disabled"\n    expect("unknown_not_cached", reader.read(context, false).selinux == "Disabled" && root.calls == 3)\n    root.output = null\n    expect("missing_root_unknown", reader.read(context, false, true).selinux == "Unknown")\n    root.output = "garbage"\n    expect("invalid_root_unknown", reader.read(context, false).selinux == "Unknown")\n    root.fail = true\n    expect("throwing_root_unknown", reader.read(context, false, true).selinux == "Unknown")\n    root.fail = false\n    root.output = "1"\n    expect("recovered_root_enforcing", reader.read(context, false).selinux == "Enforcing")\n    expect("known_cache_after_recovery", reader.getOrFastSnapshot(context, false).selinux == "Enforcing")\n    var fallbackCalls = 0\n    val fallback = { fallbackCalls++; "Permissive" }\n    expect("kernel_enforcing_priority", reader.resolveSelinuxState({ " 1\\n" }, fallback) == "Enforcing" && fallbackCalls == 0)\n    expect("kernel_permissive", reader.resolveSelinuxState({ "0" }, fallback) == "Permissive" && fallbackCalls == 0)\n    expect("kernel_disabled", reader.resolveSelinuxState({ "DISABLED" }, fallback) == "Disabled" && fallbackCalls == 0)\n    expect("invalid_kernel_root_fallback", reader.resolveSelinuxState({ "garbage" }, fallback) == "Permissive" && fallbackCalls == 1)\n    expect("failed_kernel_root_fallback", reader.resolveSelinuxState({ throw java.io.IOException("fixture") }, { "Enforcing" }) == "Enforcing")\n    expect("missing_kernel_no_root", reader.resolveSelinuxState({ null }) == "Unknown")\n    expect("missing_both_sources", reader.resolveSelinuxState({ null }, { null }) == "Unknown")\n    println("SELINUX_SUMMARY checks=" + checks + " failures=" + failed.size + " labels=" + failed.joinToString(","))\n    if (failed.isNotEmpty()) kotlin.system.exitProcess(1)\n}\n'

def execute(source, work, compiler):
    work.mkdir(parents=True, exist_ok=False)
    for filename, content in STUBS.items():
        (work / filename).write_text(content, encoding="utf-8", newline="\n")
    (work / "SystemDiagnostics.kt").write_bytes(source)
    (work / "Driver.kt").write_text(DRIVER, encoding="utf-8", newline="\n")
    jar = work / "checks.jar"
    cmd = [*compiler, "-nowarn", "-language-version", "2.0", "-jvm-target", "17",
           *map(str, sorted(work.glob("*.kt"))), "-include-runtime", "-d", str(jar)]
    built = subprocess.run(cmd, stdin=subprocess.DEVNULL, capture_output=True, timeout=120)
    (work / "compile.log").write_bytes(built.stdout + built.stderr)
    if built.returncode:
        raise RuntimeError("Kotlin compilation failed: " + str(work))
    run = subprocess.run(["java", "-cp", str(jar), "com.waenhancer.utils.DriverKt"],
                         stdin=subprocess.DEVNULL, capture_output=True, timeout=30)
    (work / "run.log").write_bytes(run.stdout + run.stderr)
    rows = re.findall(r"(?m)^SELINUX_SUMMARY checks=(\d+) failures=(\d+) labels=(.*)$",
                      run.stdout.decode("utf-8", errors="replace"))
    if len(rows) != 1 or int(rows[0][0]) != EXPECTED_CHECKS:
        raise RuntimeError("Incomplete JVM behavioral inventory: " + str(work))
    checks, failures, labels = rows[0]
    labels = labels.strip().split(",") if labels.strip() else []
    if len(labels) != int(failures):
        raise RuntimeError("Invalid failure inventory")
    return {"checks": int(checks), "failures": int(failures), "labels": labels,
            "exit": run.returncode, "source_sha256": hashlib.sha256(source).hexdigest()}

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-root", type=Path, default=ROOT)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--mutations", action="store_true")
    args = parser.parse_args()
    kotlin_home = os.environ.get("KOTLIN_HOME")
    if not kotlin_home:
        binary = shutil.which("kotlinc")
        if not binary:
            raise RuntimeError("Kotlin compiler required")
        kotlin_home = str(Path(binary).resolve().parents[1])
    lib = Path(kotlin_home) / "lib"
    jars = sorted(lib.glob("*.jar"))
    if not (lib / "kotlin-compiler.jar").is_file():
        raise RuntimeError("Kotlin compiler distribution required")
    compiler = ["java", "-Dkotlin.home=" + kotlin_home, "-cp",
                os.pathsep.join(map(str, jars)), "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler"]
    path = args.source_root.resolve() / READER
    raw = path.read_bytes()
    out = args.out.resolve()
    out.mkdir(parents=True, exist_ok=False)
    positive = execute(raw, out / "positive", compiler)
    result = {"positive": positive, "mutants": [], "accepted": positive["exit"] == 0 and positive["failures"] == 0}
    if args.mutations and result["accepted"]:
        source = raw.decode("utf-8")
        cases = (
            ("assume_enforcing", 'return "Unknown"', 'return "Enforcing"', "unreadable_fast_is_unknown"),
            ("cache_provisional", 'selinux = cachedSelinux ?: readFastSelinuxState(),',
             'selinux = cachedSelinux ?: readFastSelinuxState().also { cachedSelinux = it },', "full_read_replaces_provisional"),
            ("cache_unknown", 'cachedSelinux = it.takeUnless { state -> state == "Unknown" }',
             'cachedSelinux = it', "unknown_not_cached"),
            ("ignore_kernel", 'runCatching { normalizeSelinuxState(kernel()) }.getOrNull()?.let { return it }',
             'runCatching { normalizeSelinuxState(null) }.getOrNull()?.let { return it }', "kernel_enforcing_priority"),
            ("skip_root", 'root = if (rootAllowed) ({ AppRestartHelper.runRootCommand("getenforce") }) else null',
             'root = null', "full_read_replaces_provisional"),
        )
        for name, before, after, expected in cases:
            if source.count(before) != 1:
                raise RuntimeError("Mutation anchor changed: " + name)
            row = execute(source.replace(before, after).encode("utf-8"), out / name, compiler)
            row.update(name=name, expected=expected, rejected=row["exit"] == 1 and expected in row["labels"])
            result["mutants"].append(row)
            result["accepted"] &= row["rejected"]
    result["source_unchanged"] = path.read_bytes() == raw
    result["accepted"] &= result["source_unchanged"]
    (out / "RESULT.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result))
    return 0 if result["accepted"] else 1

if __name__ == "__main__":
    sys.exit(main())
