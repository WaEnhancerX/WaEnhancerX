#!/usr/bin/env python3
"""Compile the exact production media-property policy and test its ID contract.

Only the public declarative constants and configurePropertyOverrides() method
are extracted: all Android, DexKit, LSPosed and private runtime dependencies
remain outside this JVM contract. Requires JDK 17+; no network or APK mutation.
Use a distinct --out for baseline and candidate so failures are preserved.
"""
import argparse
import hashlib
import json
import platform
from pathlib import Path
import re
import shutil
import subprocess
import time


ROOT = Path(__file__).resolve().parents[1]
SOURCE = Path("app/src/main/java/com/waenhancer/xposed/features/media/MediaQualityBypassHook.java")
FIXTURE = Path("qa/fixtures/media_quality/MediaScreenshotPropsChecks.java")
DECLARATIONS = ("IMAGE_MAX_KB", "IMAGE_MAX_EDGE", "IMAGE_MAX_SIZE_PROPS",
                "IMAGE_QUALITY_PROPS", "IMAGE_MAX_EDGE_PROPS")
METHOD = "public static void configurePropertyOverrides(boolean imageEnabled)"


def production_fragment(source: str) -> str:
    members = []
    for name in DECLARATIONS:
        matches = list(re.finditer(
            r"(?m)^[ \t]*private static final int(?:\[\])?\s+" + name +
            r"\s*=\s*[^;]+;", source))
        if len(matches) != 1:
            raise ValueError(f"Expected exactly one production declaration for {name}; got {len(matches)}")
        members.append(matches[0].group().strip())

    start = source.find(METHOD)
    if start < 0 or source.find(METHOD, start + 1) >= 0:
        raise ValueError("Missing or duplicated production media property method")
    opening = source.find("{", start + len(METHOD))
    if opening < 0:
        raise ValueError("Missing production method body")
    # The declarative method has no string or comment with braces. Validate
    # balanced nesting rather than cutting at the first loop's closing brace.
    depth = 0
    closing = None
    for index in range(opening, len(source)):
        if source[index] == "{":
            depth += 1
        elif source[index] == "}":
            depth -= 1
            if depth == 0:
                closing = index + 1
                break
    if closing is None:
        raise ValueError("Unbalanced production media property method")
    members.append(source[start:closing])
    return ("package com.waenhancer.xposed.features.media;\n"
            "import com.waenhancer.xposed.features.customization.PropsOverrideHook;\n"
            "public final class MediaQualityBypassHook {\n"
            + "\n\n".join(members) + "\n}\n")


STRICT_REGISTRY = """package com.waenhancer.xposed.features.customization;
import java.util.concurrent.ConcurrentHashMap;
public final class PropsOverrideHook {
    public static final ConcurrentHashMap<Integer, Integer> INTEGERS = new ConcurrentHashMap<>();
    public static final ConcurrentHashMap<Integer, Boolean> BOOLEANS = new ConcurrentHashMap<>();
    public static void setIntegerOverride(int id, boolean enabled, int value) {
        if (id <= 0) throw new AssertionError("Invalid integer property ID " + id);
        if (enabled) INTEGERS.put(id, value); else INTEGERS.remove(id);
    }
    public static void setBooleanOverride(int id, boolean enabled, boolean value) {
        if (id <= 0) throw new AssertionError("Invalid boolean property ID " + id);
        if (enabled) BOOLEANS.put(id, value); else BOOLEANS.remove(id);
    }
}
"""


def sha256(p: Path) -> str:
    return hashlib.sha256(p.read_bytes()).hexdigest()


def run(argv: list[str], log: Path, timeout: int) -> int:
    with log.open("w", encoding="utf-8", errors="replace") as stream:
        try:
            return subprocess.run(argv, stdout=stream, stderr=subprocess.STDOUT,
                                  timeout=timeout, check=False).returncode
        except subprocess.TimeoutExpired:
            stream.write(f"TIMEOUT after {timeout}s\n")
            return 124


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-root", type=Path, default=ROOT)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    root = args.source_root.resolve(strict=True)
    output = args.out.resolve()
    output.mkdir(parents=True, exist_ok=False)
    start_time = time.monotonic()
    original = root / SOURCE
    fixture = ROOT / FIXTURE
    identities = {str(SOURCE): sha256(original), str(FIXTURE): sha256(fixture),
                  "qa/test_media_quality_screenshot_props.py": sha256(Path(__file__))}
    (output / "source-sha256.json").write_text(json.dumps(identities, indent=2) + "\n")
    javac = shutil.which("javac")
    java = shutil.which("java")
    if javac is None or java is None:
        raise RuntimeError("JDK 17+ javac and java are required")
    compiler_version = subprocess.run([javac, "-version"], capture_output=True,
                                      text=True, timeout=10)
    runtime_version = subprocess.run([java, "-version"], capture_output=True,
                                     text=True, timeout=10)
    (output / "toolchain.json").write_text(json.dumps({
        "host": platform.platform(),
        "javac_path": javac,
        "javac_version": (compiler_version.stdout + compiler_version.stderr).strip(),
        "java_path": java,
        "java_version": (runtime_version.stdout + runtime_version.stderr).strip().splitlines()[:2],
    }, indent=2) + "\n")

    sources = output / "sources"
    policy_path = sources / "com/waenhancer/xposed/features/media/MediaQualityBypassHook.java"
    registry_path = sources / "com/waenhancer/xposed/features/customization/PropsOverrideHook.java"
    test_path = sources / "com/waenhancer/xposed/features/media/MediaScreenshotPropsChecks.java"
    for file in (policy_path, registry_path, test_path):
        file.parent.mkdir(parents=True, exist_ok=True)
    policy_path.write_text(production_fragment(original.read_text(encoding="utf-8")), encoding="utf-8")
    registry_path.write_text(STRICT_REGISTRY, encoding="utf-8")
    test_path.write_bytes(fixture.read_bytes())
    (output / "compiled-input-sha256.json").write_text(json.dumps({
        str(p.relative_to(output)): sha256(p) for p in
        (policy_path, registry_path, test_path)
    }, indent=2) + "\n")
    classes = output / "classes"
    classes.mkdir()
    command = [javac, "--release", "17", "-d", str(classes),
               str(policy_path), str(registry_path), str(test_path)]
    compile_code = run(command, output / "javac.log", 90)
    result = {
        "source_root": str(root), "compile_returncode": compile_code,
        "production_fragment_compiled": compile_code == 0,
        "full_android_or_whatsapp_execution": False,
    }
    if compile_code == 0:
        run_code = run([java, "-cp", str(classes),
                        "com.waenhancer.xposed.features.media.MediaScreenshotPropsChecks"],
                       output / "behavior.log", 60)
        summary = re.search(r"RESULT assertions=(\d+) failures=(\d+)",
                            (output / "behavior.log").read_text(encoding="utf-8"))
        result.update({
            "behavior_returncode": run_code,
            "assertions": int(summary.group(1)) if summary else 0,
            "failures": int(summary.group(2)) if summary else None,
        })
    result["duration_seconds"] = round(time.monotonic() - start_time, 3)
    (output / "result.json").write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps(result))
    if compile_code != 0 or result.get("behavior_returncode") != 0 or result.get("failures") != 0:
        if (output / "javac.log").exists():
            print((output / "javac.log").read_text(encoding="utf-8")[-3000:])
        if (output / "behavior.log").exists():
            print((output / "behavior.log").read_text(encoding="utf-8")[-3000:])
        return 1
    print("PASS: exact declarative production method with strict registry boundary; no Android or APK")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
