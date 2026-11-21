#!/usr/bin/env python3
"""Compile and execute the real StatusDownloadHook diagnostic expressions.

The marker is synthetic. This validates logging, not Android UI, exports or
WhatsApp/LSPosed runtime behavior.
"""

import argparse
import shutil
import subprocess
import tempfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
DEFAULT_SOURCE = (
    ROOT
    / "app/src/main/java/com/waenhancer/xposed/features/media/StatusDownloadHook.java"
)
EXPECTED_CALLS = 17  # Reviewed at nightly 0aaffab, 2026-11-18.


def require(condition: bool, message: str) -> None:
    if not condition:
        raise AssertionError(message)


def extract_calls(source: str) -> list[str]:
    marker = "XposedBridge.log("
    offset = 0
    calls = []
    while True:
        start = source.find(marker, offset)
        if start < 0:
            break
        index = start + len(marker)
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
                        "Unterminated Java log statement",
                    )
                    calls.append(source[begin:index].strip())
                    break
            index += 1
        else:
            raise AssertionError("Unbalanced StatusDownloadHook log call")
        offset = index + 1
    require(
        len(calls) == EXPECTED_CALLS,
        f"StatusDownloadHook log inventory changed: {len(calls)} != {EXPECTED_CALLS}",
    )
    return calls


def java_source(expressions: list[str]) -> str:
    calls = ";\n        ".join("XposedBridge.log(" + expr + ")" for expr in expressions)
    return """
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;

public final class WaexStatusLogProbe {
    private static final String TAG = "[WAEX][StatusDownload]";
    private static final class XposedBridge {
        static final List<String> records = new ArrayList<>();
        static void log(String line) { records.add(line); }
    }
    private static final class Log {
        static String getStackTraceString(Throwable value) {
            StringWriter writer = new StringWriter();
            value.printStackTrace(new PrintWriter(writer));
            return writer.toString();
        }
    }
    public static void main(String[] args) {
        final String privateMarker = "WAEX_SYNTHETIC_PRIVATE_STATUS_6C1";
        Throwable t = new IllegalArgumentException(privateMarker);
        Exception failure = new IllegalArgumentException(privateMarker);
        """ + calls + """;
        if (XposedBridge.records.size() != 17) {
            System.err.println("BAD_LOG_COUNT");
            System.exit(8);
        }
        boolean sawType = false;
        for (String record : XposedBridge.records) {
            if (record.contains(privateMarker)) {
                System.err.println("LEAKED_PRIVATE_EXCEPTION_TEXT");
                System.exit(9);
            }
            if (record.contains("IllegalArgumentException")) sawType = true;
        }
        if (!sawType) {
            System.err.println("LOST_EXCEPTION_TYPE_DIAGNOSTIC");
            System.exit(10);
        }
        System.out.println("STATUS_LOGS_REDACTED");
    }
}
"""


def compile_and_run(
    expressions: list[str], javac: str, java: str, root: Path
) -> tuple[int, str]:
    root.mkdir()
    java_file = root / "WaexStatusLogProbe.java"
    java_file.write_text(java_source(expressions), encoding="utf-8")
    compiled = subprocess.run(
        [javac, "-encoding", "UTF-8", "-d", str(root), str(java_file)],
        capture_output=True,
        text=True,
        timeout=20,
        check=False,
    )
    require(compiled.returncode == 0, "JVM fixture compilation failed: " + compiled.stderr[:800])
    result = subprocess.run(
        [java, "-cp", str(root), "WaexStatusLogProbe"],
        capture_output=True,
        text=True,
        timeout=20,
        check=False,
    )
    return result.returncode, result.stdout + result.stderr


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", type=Path, default=DEFAULT_SOURCE)
    parser.add_argument("--javac", default=shutil.which("javac"))
    parser.add_argument("--java", default=shutil.which("java"))
    args = parser.parse_args()
    if not args.javac or not args.java:
        parser.error("JDK missing: supply --javac and --java")

    source = args.source.read_text(encoding="utf-8")
    expressions = extract_calls(source)
    with tempfile.TemporaryDirectory(prefix="waex-status-log-") as directory:
        temp = Path(directory)
        result, output = compile_and_run(expressions, args.javac, args.java, temp / "reference")
        require(
            result == 0 and "STATUS_LOGS_REDACTED" in output,
            "Production status log leaked synthetic Throwable content",
        )

        type_expr = "t.getClass().getSimpleName()"
        index = next((i for i, expr in enumerate(expressions) if type_expr in expr), -1)
        require(index >= 0, "Reviewed Throwable diagnostic contract missing")
        mutants = {
            "raw_object": "t",
            "exception_message": "t.getMessage()",
            "stacktrace": "Log.getStackTraceString(t)",
        }
        for name, replacement in mutants.items():
            altered = expressions.copy()
            altered[index] = altered[index].replace(type_expr, replacement, 1)
            require(altered[index] != expressions[index], f"Mutant {name} did not alter source")
            code, negative_output = compile_and_run(altered, args.javac, args.java, temp / name)
            require(
                code == 9 and "LEAKED_PRIVATE_EXCEPTION_TEXT" in negative_output,
                f"Compiled negative control {name} was not rejected for private data",
            )

    try:
        extract_calls(source + '\nXposedBridge.log(TAG + "added unreviewed site");\n')
    except AssertionError as error:
        require("inventory changed" in str(error), "Extra-log mutant failed for wrong reason")
    else:
        raise AssertionError("Extra-log mutant was accepted")

    print(
        "PASS: 17 production status logs compiled and redacted; "
        "three compiled privacy mutants and an inventory mutant rejected"
    )


if __name__ == "__main__":
    main()
