#!/usr/bin/env python3
"""Regression contracts for selected receipt, props, HMA and LID hardening."""
from pathlib import Path
import os

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "app/src/main/java/com/waenhancer"


def declaration(source: str, marker: str) -> str:
    start = source.index(marker)
    brace = source.index("{", start)
    depth = 0
    for index in range(brace, len(source)):
        if source[index] == "{":
            depth += 1
        elif source[index] == "}":
            depth -= 1
            if depth == 0:
                return source[start:index + 1]
    raise AssertionError(f"Unclosed declaration: {marker}")


receipts = (JAVA / "xposed/features/privacy/HideReceiptsHook.java").read_text()
dispatch = declaration(receipts, "    private void hookOnDispatchMessage()")
job_hook = declaration(receipts, "    private void hookSendReadReceiptJob()")
assert "msg.arg1 == 419" in dispatch
assert "msg.arg1 == 89" not in dispatch
assert "msg.arg1 == 419 || msg.arg1 == 89" not in receipts
assert "if (shouldHideDeliveryReceipt(target)) param.setResult(null)" in job_hook

props = (JAVA / "xposed/features/customization/PropsOverrideHook.java").read_text()
assert "findCentralResolver(booleanGetter)" in props
assert "types[types.length - 2] != int.class" in props
assert "types[types.length - 1] != boolean.class" in props
assert "if (selected != null) return null" in props
assert "if (original instanceof Boolean) return BOOLEANS.get(id)" in props
assert "if (original instanceof Integer) return INTEGERS.get(id)" in props
settings = (JAVA / "xposed/features/customization/NewSettingsStyleHook.java").read_text()
assert "PropsOverrideHook.BOOLEANS.put" in settings
assert "PropsOverrideHook.INTEGERS.put" in settings
assert "Unknown BooleanField" not in settings

# HMA backup behavior is tested through the compiled production command builder
# and real filesystem operations, so equivalent implementations are accepted.
# The complete diagnostics/policy/root-runner gate also runs on Android 24/28/34.
from test_hma_backup_commands import run_contracts
run_contracts(out=os.environ.get("HMA_QA_COMMAND_OUT"))

calls = (JAVA / "xposed/features/privacy/CallPrivacyHook.java").read_text()
block = declaration(calls, "    private boolean shouldBlockCall(String callerJid)")
assert "resolveLidToPhone(context, callerJid)" in block
assert "findContactPrivacy(callerJid, phoneJid)" in block
identity = declaration(calls, "    private JSONObject findContactPrivacy(")
assert "getContactPrivacy(prefs, rawJid)" in identity
assert "getContactPrivacy(prefs, phoneJid)" in identity
assert "wa_address_book" in calls

print("PASS: selected upstream hardening contracts")
