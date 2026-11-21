#!/usr/bin/env python3
"""Compile the real status contract and verbatim production hook methods with explicit doubles."""
import argparse
from pathlib import Path
import re
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
PRIVACY = ROOT / "app/src/main/java/com/waenhancer/xposed/features/privacy"


def method(source: str, name: str, optional: bool = False) -> str:
    match = re.search(r"    private (?:void|Object) " + re.escape(name) + r"\(", source)
    if match is None:
        if optional:
            return ""
        raise AssertionError(f"missing production method {name}")
    opening = source.index("{", match.end())
    depth = 1
    cursor = opening + 1
    while depth:
        if source[cursor] == "{":
            depth += 1
        elif source[cursor] == "}":
            depth -= 1
        cursor += 1
    return source[match.start():cursor]


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, default=PRIVACY / "AntiRevokeHook.java")
    parser.add_argument("--contract", type=Path, default=PRIVACY / "StatusHookContract.java")
    parser.add_argument("--scenario", choices=("all", "target", "ack", "local", "selection", "mapper"), default="all")
    args = parser.parse_args()
    source = args.source.read_text(encoding="utf-8")
    methods = "\n\n".join([
        method(source, "initMessageClasses"),
        method(source, "hookStatusBytecodeRevocation"),
        method(source, "findArgumentOfType", optional=True),
        method(source, "setHandledResult"),
    ])
    template = (ROOT / "qa/fixtures/StatusHookBindingChecks.java").read_text(encoding="utf-8")
    assert template.count("/* PRODUCTION_METHODS */") == 1
    harness = template.replace("/* PRODUCTION_METHODS */", methods)
    with tempfile.TemporaryDirectory(prefix="waex-status-contract-") as directory:
        temp = Path(directory)
        binding = temp / "StatusHookBindingChecks.java"
        binding.write_text(harness, encoding="utf-8")
        subprocess.run([
            "javac", "--release", "17", "-d", str(temp), str(args.contract),
            str(ROOT / "qa/java/TestStatusHookContract.java"), str(binding),
        ], check=True)
        if args.scenario == "all":
            subprocess.run(["java", "-cp", str(temp), "TestStatusHookContract"], check=True)
        subprocess.run(["java", "-cp", str(temp), "StatusHookBindingChecks", args.scenario], check=True)
    if args.source.resolve() == (PRIVACY / "AntiRevokeHook.java").resolve():
        # Cache invalidation and call binding are checked alongside the compiled behavior.
        for key in ("waex_status_to_message_mapper_v2", "waex_status_key_class_v3", "waex_revoke_status_manager_v3"):
            assert '"' + key + '"' in source, key
        assert "findArgumentOfType" not in method(source, "hookStatusBytecodeRevocation")
        assert "setHandledResult(param)" not in method(source, "hookStatusBytecodeRevocation")
        print("5 status discovery/cache callsite guards passed")


if __name__ == "__main__":
    main()
