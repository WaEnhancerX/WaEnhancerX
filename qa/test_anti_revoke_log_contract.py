#!/usr/bin/env python3
"""JVM probe for the actual AntiRevoke native-render failure diagnostic.

Compiles the extracted production log statement with synthetic identifiers.
This checks logging behavior, not WhatsApp, Android, or hook installation.
"""

import argparse
import re
import shutil
import subprocess
import tempfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
DEFAULT_SOURCE = (
    ROOT
    / "app/src/main/java/com/waenhancer/xposed/features/privacy/AntiRevokeHook.java"
)
EXPECTED_LOG_SITES = 32  # +1 type-name-only diagnostic for an unsafe revoke result type (2026-11-24).
LOG_PREFIX = re.compile(
    r"catch\s*\(\s*Throwable\s+renderFailure\s*\)\s*\{\s*XposedBridge\.log\("
)


def require(condition: bool, message: str) -> None:
    """Keep privacy checks active even with Python -O / PYTHONOPTIMIZE."""
    if not condition:
        raise AssertionError(message)


def extract_actual_log(source: str) -> str:
    count = source.count("XposedBridge.log(")
    require(count == EXPECTED_LOG_SITES, (
        f"AntiRevokeHook diagnostic inventory changed: {count} != {EXPECTED_LOG_SITES}"
    ))
    matches = list(LOG_PREFIX.finditer(source))
    require(len(matches) == 1, "Expected one native-render failure catch/logger")
    index = matches[0].end()
    begin = index
    depth = 1
    quote = None
    escaped = False
    while index < len(source):
        ch = source[index]
        if quote:
            if escaped:
                escaped = False
            elif ch == "\\":
                escaped = True
            elif ch == quote:
                quote = None
        elif ch in ("'", '"'):
            quote = ch
        elif ch == "(":
            depth += 1
        elif ch == ")":
            depth -= 1
            if depth == 0:
                require(
                    source[index + 1 :].lstrip().startswith(";"),
                    "Native-render log expression missing terminating semicolon",
                )
                return source[begin:index].strip()
        index += 1
    raise AssertionError("Unbalanced native-render log expression")


def jvm_source(expression: str) -> str:
    return """
public final class WaexRevokePrivacyLogProbe {
    private static final String TAG = "[WAEX][AntiRevoke]";
    private static final class XposedBridge {
        static String record;
        static void log(String message) { record = message; }
    }
    public static void main(String[] args) {
        String keyId = "WAEX_SYNTHETIC_MESSAGE_ID_74A";
        Throwable renderFailure =
            new IllegalStateException("SYNTHETIC_PRIVATE_EXCEPTION_" + keyId);
        XposedBridge.log(""" + expression + """);
        String recorded = XposedBridge.record;
        if (recorded == null || recorded.contains(keyId)
                || recorded.contains("SYNTHETIC_PRIVATE_EXCEPTION_")
                || !recorded.contains("IllegalStateException")) {
            System.err.println("REJECTED_PRIVATE_VALUE");
            System.exit(9);
        }
        System.out.println("PROBE_LOG_REDACTED");
    }
}
"""


def compile_and_run(expression: str, javac: str, java: str, root: Path) -> tuple[int, str]:
    root.mkdir()
    source_file = root / "WaexRevokePrivacyLogProbe.java"
    source_file.write_text(jvm_source(expression), encoding="utf-8")
    compile_result = subprocess.run(
        [javac, "-encoding", "UTF-8", "-d", str(root), str(source_file)],
        capture_output=True,
        text=True,
        timeout=20,
        check=False,
    )
    require(compile_result.returncode == 0, (
        "java compilation failed: " + compile_result.stderr[:800]
    ))
    execute_result = subprocess.run(
        [java, "-cp", str(root), "WaexRevokePrivacyLogProbe"],
        capture_output=True,
        text=True,
        timeout=20,
        check=False,
    )
    return execute_result.returncode, (
        execute_result.stdout + execute_result.stderr
    )


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", type=Path, default=DEFAULT_SOURCE)
    parser.add_argument("--javac", default=shutil.which("javac"))
    parser.add_argument("--java", default=shutil.which("java"))
    args = parser.parse_args()
    if not args.javac or not args.java:
        parser.error("JDK executables missing: supply --javac and --java")

    source = args.source.read_text(encoding="utf-8")
    expression = extract_actual_log(source)

    with tempfile.TemporaryDirectory(prefix="waex-revoke-log-") as directory:
        temp = Path(directory)
        code, output = compile_and_run(
            expression, args.javac, args.java, temp / "candidate"
        )
        require(code == 0 and "PROBE_LOG_REDACTED" in output, (
            "Production log leaked synthetic ID or exception text"
        ))

        class_name = "renderFailure.getClass().getSimpleName()"
        require(class_name in expression, "Reviewed exception-type contract changed")
        mutants = {
            "message_id": expression.replace(class_name, "keyId", 1),
            "exception_message": expression.replace(
                class_name, "renderFailure.getMessage()", 1
            ),
            "exception_object": expression.replace(class_name, "renderFailure", 1),
        }
        for name, mutant in mutants.items():
            require(mutant != expression, f"Negative control {name} made no change")
            returncode, mutant_output = compile_and_run(
                mutant, args.javac, args.java, temp / name
            )
            require(returncode == 9 and "REJECTED_PRIVATE_VALUE" in mutant_output, (
                f"Negative control {name} was not rejected by execution"
            ))

    # Inventory control: an added literal callsite must not silently pass.
    extra = source + '\nXposedBridge.log(TAG + " extra literal");\n'
    try:
        extract_actual_log(extra)
    except AssertionError as error:
        require(
            "inventory changed" in str(error),
            "Extra diagnostic mutant failed for an unrelated reason",
        )
    else:
        raise AssertionError("Negative control extra diagnostic was accepted")

    print(
        "PASS: real AntiRevoke log compiled/executed without synthetic data; "
        "three compiled negative controls and one inventory mutant rejected"
    )


if __name__ == "__main__":
    main()
