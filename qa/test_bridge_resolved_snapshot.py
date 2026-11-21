#!/usr/bin/env python3
"""Exercise provider-to-client preference snapshots with production Java sources.

The provider's scope resolution, initialization and serialization are extracted verbatim;
the complete bridge, schema, namespace policy and error logger are compiled unchanged.
Android transport/storage are strict JVM doubles. Licensing and APK/device behavior are
outside this fixture; no replacement licensing implementation is compiled or installed.
"""
from pathlib import Path
import argparse
import ast
import hashlib
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "app/src/main/java"
BRIDGE = JAVA / "com/waenhancer/xposed/bridge/client/PreferenceBridgeClient.java"


def exactly_once(source, anchor):
    if source.count(anchor) != 1:
        raise AssertionError(f"Production fixture anchor is not unique: {anchor}")
    return source.index(anchor)


def method_source(source, signature):
    start = exactly_once(source, signature)
    opening = source.index("{", start)
    depth = 1
    end = opening + 1
    # These selected methods have no braces in string literals or comments. Fail
    # compilation if a later source edit violates that extraction assumption.
    while depth and end < len(source):
        depth += (source[end] == "{") - (source[end] == "}")
        end += 1
    if depth:
        raise AssertionError(f"Unbalanced production method: {signature}")
    return source[start:end]


def platform_stubs():
    module = ast.parse((ROOT / "qa/test_bridge_contract.py").read_text(encoding="utf-8"))
    stubs = ast.literal_eval(next(node.value for node in module.body
        if isinstance(node, ast.Assign) and any(isinstance(target, ast.Name)
        and target.id == "STUBS" for target in node.targets)))
    stubs.pop("test/BridgeContract.java")
    stubs["androidx/annotation/VisibleForTesting.java"] = (
        "package androidx.annotation; public @interface VisibleForTesting {}")
    stubs["android/content/pm/ApplicationInfo.java"] = (
        "package android.content.pm; public class ApplicationInfo { public int uid = 1000; }")
    context = stubs["android/content/Context.java"]
    stubs["android/content/Context.java"] = context.replace(
        "public Context getApplicationContext() { return this; }",
        "public Context getApplicationContext() { return this; }\n"
        "public android.content.pm.ApplicationInfo getApplicationInfo() { "
        "return new android.content.pm.ApplicationInfo(); }")
    stubs["com/waenhancer/core/preferences/WaexPreferenceManager.java"] = (
        'package com.waenhancer.core.preferences; public final class WaexPreferenceManager {'
        ' public static final String TARGET_SEEDED_KEY = "target_seeded"; }')
    manager = (JAVA / "com/waenhancer/core/preferences/WaexPreferenceManager.java").read_text(encoding="utf-8")
    if 'public static final String TARGET_SEEDED_KEY = "target_seeded";' not in manager:
        raise AssertionError("Production seed key changed")
    restart = (JAVA / "com/waenhancer/config/PreferenceRestartTracker.java").read_text(encoding="utf-8")
    for name, value in (("NEED_RESTART", "need_restart"),
                        ("PENDING_CHANGES", "pending_restart_changes"),
                        ("SHOW_RESTART_SHEET", "show_restart_sheet")):
        if f'public static final String {name} = "{value}";' not in restart:
            raise AssertionError(f"Production restart constant changed: {name}")
    stubs["com/waenhancer/utils/WhatsAppPackageDetector.java"] = (
        "package com.waenhancer.utils; public final class WhatsAppPackageDetector {"
        " public static java.util.Set<String> getSupportedPackages(android.content.Context context) {"
        " return DeclaredWhatsAppTargets.packages(); } }")
    return stubs


def provider_fixture():
    provider = (JAVA / "com/waenhancer/xposed/bridge/HookProvider.java").read_text(encoding="utf-8")
    for constant, value in (("KEY_VALUES", "values"), ("KEY_SETS", "sets")):
        if f'public static final String {constant} = "{value}";' not in provider:
            raise AssertionError(f"Production IPC bundle key changed: {constant}")
    first = exactly_once(provider, 'if ("get_all_preferences".equals(method)) {')
    prefix_start = provider.index("{", first) + 1
    prefix_end = exactly_once(provider, 'all.put("waex_pro_active",')
    tail_start = provider.index("if (callerUid != context.getApplicationInfo().uid)", prefix_end)
    tail_end = provider.index("return encodePreferences(all);", tail_start) + len("return encodePreferences(all);")
    return """package com.waenhancer.xposed.bridge;
import android.content.*;
import android.os.Bundle;
import java.util.*;
import com.waenhancer.config.PreferenceSchema;
import com.waenhancer.config.PreferenceRestartTracker;
import com.waenhancer.utils.WhatsAppPackageDetector;
public final class ProviderSnapshotFixture {
    public static final String KEY_VALUES = "values";
    public static final String KEY_SETS = "sets";
    private final Context context;
    private final int callerUid = 2000;
    public ProviderSnapshotFixture(Context context) { this.context = context; }
    private String targetPackageForCaller(Context context, int ignored) { return context.getPackageName(); }
    public Bundle snapshot(SharedPreferences prefs) {
""" + provider[prefix_start:prefix_end] + provider[tail_start:tail_end] + "\n}\n" + (
        method_source(provider, "private Bundle encodePreferences(") + "\n" +
        method_source(provider, "private void initializeTarget(") + "\n}\n")


def run_fixture(bridge_text, *, runtime_api=False, expected_failure=None):
    stubs = platform_stubs()
    stubs["com/waenhancer/xposed/bridge/ProviderSnapshotFixture.java"] = provider_fixture()
    stubs["test/BridgeResolvedSnapshotChecks.java"] = (ROOT / "qa/java/BridgeResolvedSnapshotChecks.java").read_text(encoding="utf-8")
    stubs["com/waenhancer/xposed/bridge/client/PreferenceBridgeClient.java"] = bridge_text
    sources = [JAVA / path for path in (
        "com/waenhancer/config/PreferenceSchema.java",
        "com/waenhancer/utils/DeclaredWhatsAppTargets.java",
        "com/waenhancer/xposed/bridge/ProviderPreferenceScopePolicy.java",
        "com/waenhancer/xposed/utils/HookErrors.java")]
    with tempfile.TemporaryDirectory(prefix="waex_bridge_resolved_") as directory:
        out = Path(directory)
        for name, content in stubs.items():
            path = out / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content, encoding="utf-8")
        classes = out / "classes"
        classes.mkdir()
        release_options = ["-source", "17", "-target", "17"] if runtime_api else ["--release", "17"]
        compilation = subprocess.run(["javac", *release_options, "-d", str(classes),
                        *map(str, out.rglob("*.java")), *map(str, sources)],
                       timeout=60, capture_output=True, text=True)
        if compilation.returncode:
            print(compilation.stdout + compilation.stderr, end="")
            raise SystemExit(compilation.returncode)
        result = subprocess.run(["java", "-cp", str(classes), "test.BridgeResolvedSnapshotChecks"],
                                capture_output=True, text=True, timeout=45)
        if expected_failure:
            if result.returncode == 0 or expected_failure not in result.stderr:
                raise AssertionError(f"Expected regression was not detected: {result.stdout}\n{result.stderr}")
            print(f"PASS negative control: {expected_failure}")
        else:
            print(result.stdout, end="")
            if result.returncode:
                print(result.stderr, end="")
                raise SystemExit(result.returncode)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--negative-controls", action="store_true")
    parser.add_argument("--runtime-api", action="store_true",
                        help="Use runtime APIs with source/target 17 when ct.sym is absent; this does not verify API 17 compatibility.")
    args = parser.parse_args()
    bridge = BRIDGE.read_text(encoding="utf-8")
    print("BRIDGE_SHA256", hashlib.sha256(BRIDGE.read_bytes()).hexdigest(),
          "JAVA_API", "runtime (source/target 17)" if args.runtime_api else "release 17", flush=True)
    run_fixture(bridge, runtime_api=args.runtime_api)
    if args.negative_controls:
        marker = "// Every confirmed snapshot is already resolved for this target."
        exactly_once(bridge, marker)
        run_fixture(bridge.replace(marker, "view = applyTargetConfiguration(view);\n" + marker),
                    runtime_api=args.runtime_api,
                    expected_failure="provider restart request was discarded")
        original = "return all == null ? null : applyTargetConfiguration(copyValues(all));"
        exactly_once(bridge, original)
        run_fixture(bridge.replace(original, "return all == null ? null : copyValues(all);"),
                    runtime_api=args.runtime_api,
                    expected_failure="disk fallback did not select the target override")


if __name__ == "__main__":
    main()
