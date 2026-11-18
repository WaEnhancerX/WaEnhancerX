#!/usr/bin/env python3
"""Executable contract for all reviewed BlueOnReplyHook Xposed diagnostics.

Compiles the real log expressions with synthetic private data. This checks
diagnostic payloads, not WhatsApp receipts or Android/Xposed hook behavior.
"""

import argparse
import shutil
import subprocess
import tempfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
DEFAULT_SOURCE = (
    ROOT / "app/src/main/java/com/waenhancer/xposed/features/privacy/BlueOnReplyHook.java"
)

# Independently reviewed at nightly 0aaffab (2026-11-18): eight fixed literals
# and five exception *type* diagnostics. Changed labels require a fresh review.
REVIEWED_EXPRESSIONS = [
    'TAG + " Error initializing receipt dispatcher: " + t.getClass().getSimpleName()',
    'TAG + " Error hooking Blue on Reply: " + t.getClass().getSimpleName()',
    'TAG + " chat receipt not staged: incomplete job contract"',
    'TAG + " chat receipt not staged: job has no message IDs"',
    'TAG + " Cannot record observed receipt: constructor unavailable"',
    'TAG + " buildMinimalConstructorArgs: required argument unavailable"',
    'TAG + " Error enqueueing status receipt: " + t.getClass().getSimpleName()',
    'TAG + " Cannot dispatch: outgoing recipient is unavailable"',
    'TAG + " Cannot dispatch: WaJobManager or enqueueJobMethod is not ready"',
    'TAG + " Error enqueueing read receipt: " + t.getClass().getSimpleName()',
    'TAG + " Conversation class not found"',
    'TAG + " No Conversation method accepting Menu found"',
    'TAG + " Error adding conversation blue-tick menu: " + t.getClass().getSimpleName()',
]


def require(condition: bool, detail: str) -> None:
    # Deliberately not a Python assert: must survive -O/PYTHONOPTIMIZE.
    if not condition:
        raise AssertionError(detail)


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
                        "Log expression missing terminating semicolon",
                    )
                    calls.append(source[begin:index].strip())
                    break
            index += 1
        else:
            raise AssertionError("Unbalanced BlueOnReplyHook logger")
        offset = index + 1
    require(
        len(calls) == len(REVIEWED_EXPRESSIONS),
        f"BlueOnReplyHook inventory changed: {len(calls)} != {len(REVIEWED_EXPRESSIONS)}",
    )
    return calls


def compact_code(expression: str) -> str:
    """Discard Java whitespace outside literals, preserve every literal byte."""
    out = []
    quote = None
    escaped = False
    for ch in expression:
        if quote:
            out.append(ch)
            if escaped:
                escaped = False
            elif ch == "\\":
                escaped = True
            elif ch == quote:
                quote = None
        elif ch in ("'", '"'):
            quote = ch
            out.append(ch)
        elif not ch.isspace():
            out.append(ch)
    require(quote is None, "Unterminated string literal in logger")
    return "".join(out)


def check_reviewed_contract(expressions: list[str]) -> None:
    expected = [compact_code(expr) for expr in REVIEWED_EXPRESSIONS]
    actual = [compact_code(expr) for expr in expressions]
    require(actual == expected, "BlueOnReplyHook diagnostic expressions changed; review all sites")


def java_source(expressions: list[str]) -> str:
    calls = ";\n        ".join("XposedBridge.log(" + expr + ")" for expr in expressions)
    return """
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;

public final class WaexBlueReplyLogProbe {
    private static final String TAG = "[WAEX][BlueOnReply]";
    private static final class XposedBridge {
        static final List<String> records = new ArrayList<>();
        static void log(String value) { records.add(value); }
    }
    private static final class Log {
        static String getStackTraceString(Throwable error) {
            StringWriter writer = new StringWriter();
            error.printStackTrace(new PrintWriter(writer));
            return writer.toString();
        }
    }
    public static void main(String[] args) {
        String jid = "WAEX_PRIVATE_SYNTHETIC_JID_17Z";
        String keyId = "WAEX_PRIVATE_SYNTHETIC_KEY_41B";
        String messageId = "WAEX_PRIVATE_SYNTHETIC_MESSAGE_98K";
        String senderName = "WAEX_PRIVATE_SYNTHETIC_SENDER_02R";
        Throwable t = new IllegalArgumentException("WAEX_PRIVATE_SYNTHETIC_ERROR_63M");
        """ + calls + """;
        if (XposedBridge.records.size() != 13) {
            System.err.println("LOG_COUNT_CHANGED");
            System.exit(8);
        }
        int typeDiagnostics = 0;
        for (String record : XposedBridge.records) {
            if (record.contains("WAEX_PRIVATE_SYNTHETIC_")) {
                System.err.println("PRIVATE_DATA_IN_LOG");
                System.exit(9);
            }
            if (record.contains("IllegalArgumentException")) typeDiagnostics++;
        }
        if (typeDiagnostics != 5) {
            System.err.println("EXCEPTION_TYPE_DIAGNOSTICS_CHANGED");
            System.exit(10);
        }
        System.out.println("BLUE_REPLY_LOGS_REDACTED");
    }
}
"""


def compile_and_run(
    expressions: list[str], javac: str, java: str, destination: Path
) -> tuple[int, str]:
    destination.mkdir()
    java_file = destination / "WaexBlueReplyLogProbe.java"
    java_file.write_text(java_source(expressions), encoding="utf-8")
    compiled = subprocess.run(
        [javac, "-encoding", "UTF-8", "-d", str(destination), str(java_file)],
        capture_output=True,
        text=True,
        timeout=20,
        check=False,
    )
    require(compiled.returncode == 0, "JVM fixture compile FAIL: " + compiled.stderr[:1000])
    result = subprocess.run(
        [java, "-cp", str(destination), "WaexBlueReplyLogProbe"],
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
        parser.error("JDK unavailable: provide --javac and --java")

    source = args.source.read_text(encoding="utf-8")
    expressions = extract_calls(source)
    check_reviewed_contract(expressions)
    with tempfile.TemporaryDirectory(prefix="waex-blue-log-") as directory:
        root = Path(directory)
        code, output = compile_and_run(expressions, args.javac, args.java, root / "candidate")
        require(
            code == 0 and "BLUE_REPLY_LOGS_REDACTED" in output,
            "Production BlueOnReplyHook logging exposed synthetic private data",
        )

        target = next(
            (i for i, expr in enumerate(expressions) if "t.getClass().getSimpleName()" in expr),
            -1,
        )
        require(target >= 0, "Reviewed exception-type diagnostic absent")
        sensitive = {
            "raw_throwable": (target, "t.getClass().getSimpleName()", "t"),
            "exception_message": (target, "t.getClass().getSimpleName()", "t.getMessage()"),
            "stacktrace": (target, "t.getClass().getSimpleName()", "Log.getStackTraceString(t)"),
            "jid": (7, expressions[7], expressions[7] + " + jid"),
            "message_id": (3, expressions[3], expressions[3] + " + messageId"),
            "key_id": (2, expressions[2], expressions[2] + " + keyId"),
            "sender_name": (4, expressions[4], expressions[4] + " + senderName"),
        }
        for name, (index, old, replacement) in sensitive.items():
            altered = expressions.copy()
            altered[index] = altered[index].replace(old, replacement, 1)
            require(altered[index] != expressions[index], f"Mutant {name} did not change source")
            negative_code, negative_output = compile_and_run(
                altered, args.javac, args.java, root / name
            )
            require(
                negative_code == 9 and "PRIVATE_DATA_IN_LOG" in negative_output,
                f"Mutant {name} was not rejected for private data",
            )

    extra = source + '\nXposedBridge.log(TAG + "unreviewed");\n'
    try:
        extract_calls(extra)
    except AssertionError as error:
        require("inventory changed" in str(error), "Extra logger failed for wrong reason")
    else:
        raise AssertionError("Extra unreviewed call was accepted")

    removed = source.replace(
        'XposedBridge.log(TAG + " chat receipt not staged: incomplete job contract");',
        "",
        1,
    )
    require(removed != source, "Removal mutant did not change source")
    try:
        extract_calls(removed)
    except AssertionError as error:
        require("inventory changed" in str(error), "Removed logger failed for wrong reason")
    else:
        raise AssertionError("Removed logger was accepted")

    changed_label = expressions.copy()
    changed_label[2] = changed_label[2].replace("incomplete job contract", "job contract unknown")
    require(changed_label[2] != expressions[2], "Literal mutant did not alter expression")
    try:
        check_reviewed_contract(changed_label)
    except AssertionError as error:
        require("expressions changed" in str(error), "Literal mutant failed for wrong reason")
    else:
        raise AssertionError("Unreviewed diagnostic label was accepted")

    print(
        "PASS: 13 reviewed production logs compiled; 7 payload mutants, "
        "add/remove inventory mutants and changed-label mutant rejected"
    )


if __name__ == "__main__":
    main()
