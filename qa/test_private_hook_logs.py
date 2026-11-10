#!/usr/bin/env python3
"""Privacy regression for user-facing message-history/receipt diagnostic logs.

Static checks only: no Android SDK or private APK is needed. This test prevents
known accidental JID/message-id/text leaks in selected hook log statements,
and verifies that exception messages are not interpolated into those logs.
It does not establish comprehensive privacy of every log across WAEX.
"""
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
FILES = {
    "app/src/main/java/com/waenhancer/xposed/features/privacy/BlueOnReplyHook.java": 7,
    "app/src/main/java/com/waenhancer/xposed/features/privacy/AntiRevokeHook.java": 26,
    "app/src/main/java/com/waenhancer/xposed/features/conversation/PreserveDeleteForMeHook.kt": 9,
    "app/src/main/java/com/waenhancer/xposed/features/conversation/AntiEditMessagesHook.kt": 6,
    "app/src/main/java/com/waenhancer/xposed/core/db/EditMessageStore.kt": 4,
    "app/src/main/java/com/waenhancer/xposed/features/customization/OnlinePresenceIndicatorsHook.kt": 3,
}

# Each source is scanned as an expression, including Java multi-line logs
# and Kotlin template strings. Logging exception *types* is allowed.
DYNAMIC_PII = re.compile(
    r"(?:\+\s*|\$\{\s*|\$(?!\{))"
    r"(?:keyId|key|jid|chatJid|senderJid|senderName|senderDisplayName|"
    r"chatDisplayName|textData|messageText|newMessage|phoneNumber|rawJid)\b"
)
RAW_EXCEPTION_TEXT = re.compile(
    r"\b(?:t|ex|e|error|throwable)\s*\.\s*getMessage\s*\("
    r"|\$\{\s*(?:t|ex|e|error|throwable)\s*\.\s*message\s*\}"
)
RAW_EXCEPTION_OBJECT = re.compile(
    r"\$\{\s*(?:t|ex|e|error|throwable)\s*\}"
    r"|\$(?:t|ex|e|error|throwable)\b"
    r"|\+\s*(?:t|ex|e|error|throwable)(?![\w.])"
    r"|\+\s*(?:t|ex|e|error|throwable)\.toString\s*\("
)
RAW_LITERAL = re.compile(
    r"(?:keyId|message[_ -]?id|sender[_ -]?jid|chat[_ -]?jid)\s*="
)


def hook_logs(src: str):
    """Extract balanced call bodies, tolerating parentheses inside strings."""
    marker = "XposedBridge.log("
    offset = 0
    while True:
        start = src.find(marker, offset)
        if start < 0:
            return
        i = start + len(marker)
        depth = 1
        string_quote = None
        escaped = False
        begin = i
        while i < len(src):
            ch = src[i]
            if string_quote:
                if escaped:
                    escaped = False
                elif ch == "\\":
                    escaped = True
                elif ch == string_quote:
                    string_quote = None
            elif ch in ("'", '"'):
                string_quote = ch
            elif ch == "(":
                depth += 1
            elif ch == ")":
                depth -= 1
                if depth == 0:
                    yield src[begin:i]
                    break
            i += 1
        else:
            raise AssertionError("Unbalanced XposedBridge.log call")
        offset = i + 1


def exposure(expr: str) -> bool:
    return bool(DYNAMIC_PII.search(expr) or RAW_EXCEPTION_TEXT.search(expr)
                or RAW_EXCEPTION_OBJECT.search(expr) or RAW_LITERAL.search(expr))


# Negative controls: an unsafe regression must fail this test even if it
# happens in a multi-line expression or a Kotlin string interpolation.
unsafe_samples = [
    'TAG + " messageId=" + keyId',
    'TAG + " stored " + jid',
    'TAG + " Cannot dispatch: outgoing recipient is unavailable " + jid',
    'TAG + " failed: " + t.getMessage()',
    '"$TAG text=${textData}"',
    '"$TAG error=${t.message}"',
    '"$TAG sender=$senderName"',
    'TAG + " keyId=" + keyId + "\\n" + other',
    '"$TAG setup failed: $t"',
    '"$TAG setup failed: ${t}"',
    'TAG + " setup failed: " + ex',
    'TAG + " setup failed: " + ex.toString()',
]
for sample in unsafe_samples:
    assert exposure(sample), f"Detector missed unsafe fixture: {sample}"
safe_samples = [
    'TAG + " status view is not ready"',
    'TAG + " Failed: " + t.getClass().getSimpleName()',
    '"$TAG Failed: ${t.javaClass.simpleName}"',
    'TAG + " jobs pending=" + pending.size()',
]
for sample in safe_samples:
    assert not exposure(sample), f"False-positive fixture: {sample}"

total = 0
for rel, expected in FILES.items():
    src = (ROOT / rel).read_text(encoding="utf-8")
    calls = list(hook_logs(src))
    # Upstream 93cc35d adds one reviewed, literal-only recipient failure diagnostic.
    # Keep exact counts and scan every expression; do not allow arbitrary extra logs.
    recipient_failure = 'TAG + " Cannot dispatch: outgoing recipient is unavailable"'
    if rel.endswith('/BlueOnReplyHook.java') and recipient_failure in calls:
        expected += 1
    assert len(calls) == expected, (
        f"{rel}: expected {expected} diagnostic callsites, got {len(calls)}"
    )
    for index, expr in enumerate(calls, start=1):
        assert not exposure(expr), (
            f"{rel}: unsafe logging at callsite {index}: {expr[:180]}"
        )
    total += len(calls)

blue = (ROOT / "app/src/main/java/com/waenhancer/xposed/features/privacy/BlueOnReplyHook.java").read_text()
anti = (ROOT / "app/src/main/java/com/waenhancer/xposed/features/privacy/AntiRevokeHook.java").read_text()
preserve = (ROOT / "app/src/main/java/com/waenhancer/xposed/features/conversation/PreserveDeleteForMeHook.kt").read_text()
edit = (ROOT / "app/src/main/java/com/waenhancer/xposed/features/conversation/AntiEditMessagesHook.kt").read_text()
store = (ROOT / "app/src/main/java/com/waenhancer/xposed/core/db/EditMessageStore.kt").read_text()
# Upstream removed these hot-path diagnostics. Preserve the removal while
# keeping strict exposure checks for every remaining log expression.
for removed in ('Captured hidden read job (pending=', 'No captured read jobs for reply',
                'Enqueued blue-on-reply receipt'):
    assert removed not in blue
for removed in ('Status root view still null after delay', 'No timestamp TextView in status view'):
    assert removed not in anti
assert 'XposedBridge.log(TAG + " Cannot dispatch: WaJobManager or enqueueJobMethod is not ready")' in blue
assert 'Preserved Delete-For-Me: chat=' not in preserve
online_presence = (ROOT / "app/src/main/java/com/waenhancer/xposed/features/customization/OnlinePresenceIndicatorsHook.kt").read_text()
assert 'contactIdResolved=$hasContactId' not in online_presence
assert 'Presence request failed: ${t.javaClass.simpleName}' in online_presence
assert 'Setup failed: ${t.javaClass.simpleName}' in online_presence
assert 'Setup failed: $t' not in online_presence
# No private-object reflection is performed solely to populate discarded debug strings.
assert 'val summary = param.args?.mapIndexed' not in online_presence
assert 'val fields = contact.javaClass.declaredFields' not in online_presence
assert 'value?.toString()?.take(180)' not in online_presence
assert 'if (bindDiagnostics.getAndIncrement() < 5) {\n                    }' not in online_presence
assert 'if (subscriptionDiagnostics.getAndIncrement() < 5) {\n            }' not in online_presence

assert 'Presence request failed for $key' not in online_presence


# This log-redaction PR can run alone or after the bounded-recipient privacy PR.
# The older store remains a valid *log regression fixture* on its original PR;
# the integrated variant must not reintroduce its JID-wide bypass.
if 'PendingReadReceiptStore<ReadJobRecipe>' in blue:
    assert 'PENDING_JOBS.drain(key)' in blue
    assert 'HideReceiptsHook.allowExplicitReceiptIds(key, recipe.arguments)' in blue
    assert 'HideReceiptsHook.revokeExplicitReceiptIds(key, recipe.arguments)' in blue
    assert 'HideReceiptsHook.allowBlueReplyReceipt(key)' not in blue
    assert 'lastHiddenConversationJid' not in blue
    hide = (ROOT / "app/src/main/java/com/waenhancer/xposed/features/privacy/HideReceiptsHook.java").read_text()
    assert 'BLUE_REPLY_BYPASS' not in hide
    assert 'ExplicitReadReceiptPolicy.maySend(type, target, messageId,' in hide
else:
    assert 'PENDING_JOBS.computeIfAbsent(' in blue
    assert 'HideReceiptsHook.allowBlueReplyReceipt(key)' in blue
    assert 'HideReceiptsHook.allowExplicitReceiptIds(recipe.arguments)' in blue
    assert 'lastHiddenConversationJid' in blue
if 'Cannot dispatch: outgoing recipient is unavailable' in blue:
    dispatch = blue.split('private static int sendPendingReadJobs(String jid)', 1)[1].split('private String extractRemoteJid(', 1)[0]
    assert 'if (key == null)' in dispatch and 'return 0;' in dispatch
    assert 'PENDING_JOBS.remove(key)' in dispatch or 'PENDING_JOBS.drain(key)' in dispatch
    for unsafe_fallback in ('getUserPart(', 'lastHiddenConversationJid', 'PENDING_JOBS.size() == 1'):
        assert unsafe_fallback not in dispatch, 'Recipient fallback restored: ' + unsafe_fallback
assert 'getOrFindJobManager()' in blue
assert 'SendE2EMessageJob/onRun' in blue
assert "doApplyStatusDecoration(r, keyId, ts)" in anti
assert "decorateRevokedStatusTextView(target, ts)" in anti
assert "dispatchToManagerApp(" in preserve
assert ("editStore.recordEdit(" in edit or
        "editStore.recordTransitionAsync(" in edit), "Anti-edit history writer removed"
if "editStore.recordTransitionAsync(" in edit:
    assert "fun recordTransitionAsync(" in store, "Async transition storage unavailable"
assert "getMessages(" in store

print(f"PASS: {total} sensitive-feature log callsites checked across "
      f"{len(FILES)} production files; unsafe and safe fixtures verified")

# PR #25 now includes three other independently fixture-tested log sources.
# Keep this historical entrypoint comprehensive even if PR #27's optional
# workflow has not yet merged. The scripts fail closed on missing source files.
import runpy
for regression in (
    "qa/test_exception_message_privacy.py",
    "qa/test_call_privacy_logs.py",
    "qa/test_database_dialog_status_log_privacy.py",
):
    runpy.run_path(str(ROOT / regression), run_name="__main__")
