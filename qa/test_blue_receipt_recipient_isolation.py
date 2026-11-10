#!/usr/bin/env python3
"""JDK-only privacy regression for exact-recipient read receipt dispatch and bounds.

Does not execute Android/LSPosed, native WhatsApp, or privacy receipt traffic.
"""
from pathlib import Path
import subprocess
import tempfile
import re

root = Path(__file__).resolve().parents[1]
src = root / 'app/src/main/java/com/waenhancer/xposed/features/privacy'
blue = (src/'BlueOnReplyHook.java').read_text(encoding='utf-8')
assert 'PendingReadReceiptStore<ReadJobRecipe>' in blue
assert 'PENDING_JOBS.drain(key)' in blue
# Both routing paths must parse only complete, unambiguous JIDs.
assert 'ReceiptJidPolicy.canonical(value)' in blue
assert re.search(r'if \(key == null\) \{[^}]*return 0;\s*\}', blue), 'Unknown recipient must return before draining'
assert blue.index('if (key == null)') < blue.index('PENDING_JOBS.drain(key)')
assert 'sendPendingReadJobs(remoteJid)' in blue
assert 'extractOutgoingJobJid(param.thisObject)' in blue
assert 'XposedHelpers.getObjectField(job, "jid")' in blue
assert 'extractRemoteJid(' not in blue
assert 'extras.keySet()' not in blue
assert 'ConversationRecipientPolicy.resolve(candidates)' in blue
assert 'Cannot verify chat recipient' in blue
assert 'String receiptJid = remoteJid' not in blue
assert 'lastHiddenConversationJid' not in blue
assert 'getUserPart(' not in blue
assert 'PENDING_JOBS.size() == 1' not in blue
assert 'PENDING_JOBS.remove(candidateKey)' not in blue
assert 'HideReceiptsHook.allowBlueReplyReceipt(key)' not in blue
assert 'HideReceiptsHook.allowExplicitReceiptIds(key, recipe.arguments)' in blue
assert 'HideReceiptsHook.revokeExplicitReceiptIds(key, recipe.arguments)' in blue
assert 'getOrFindJobManager()' in blue
assert 'SendE2EMessageJob/onRun' in blue
assert 'new PendingReadReceiptStore<>(128, 64)' in blue
# Exception messages can contain private JIDs, so diagnostics show type only.
assert 't.getMessage()' not in blue
assert blue.count('t.getClass().getSimpleName()') >= 4
hide = (src/'HideReceiptsHook.java').read_text(encoding='utf-8')
assert 'ReceiptJidPolicy.canonical(value)' in hide
assert 'JID_PATTERN' not in hide
assert 'JID_PATTERN' not in blue
assert 'matcher.find()' not in hide
assert 'matcher.find()' not in blue
assert 'BLUE_REPLY_BYPASS' not in hide
assert 'isBlueReplyBypassed' not in hide
assert 'allowBlueReplyReceipt' not in hide
assert 'new RecipientReceiptAllowance(BLUE_REPLY_WINDOW_MS, 4096)' in hide
assert 'EXPLICIT_RECEIPT_IDS.isAllowed(canonicalJid(recipient), messageId' in hide
assert 'if (isExplicitReceiptAllowed(target, messageId)) return;' not in hide
assert 'ExplicitReadReceiptPolicy.maySend(type, target, messageId,' in hide
assert 'hideDelivery, EXPLICIT_RECEIPT_IDS,' in hide
assert 'hasExplicitReceiptIds(jid, param.args)' in hide
assert 'String captureJid = canonicalJid(jid);' in hide
assert 'captureJid = findJidInArguments' not in hide
assert 'captureJid = findJidInObject' not in hide
assert 'SystemClock.elapsedRealtime()' in hide
assert 'EXPLICIT_RECEIPT_IDS.isAllowed(' in hide
assert 'new ConcurrentHashMap<>()' not in hide
# Receipt isolation does not depend on a per-message informational log.
assert 't.getMessage()' not in hide

with tempfile.TemporaryDirectory(prefix='waex_exact_receipts_') as dest:
    subprocess.run(['javac','--release','17','-d',dest,
                    str(src/'ReceiptJidPolicy.java'),
                    str(root/'qa/java/TestReceiptJidPolicy.java'),
                    str(src/'PendingReadReceiptStore.java'),
                    str(root/'qa/java/TestPendingReadReceiptStore.java'),
                    str(src/'BoundedReceiptAllowance.java'),
                    str(root/'qa/java/TestBoundedReceiptAllowance.java'),
                    str(src/'RecipientReceiptAllowance.java'),
                    str(src/'ExplicitReadReceiptPolicy.java'),
                    str(root/'qa/java/TestExplicitReadReceiptPolicy.java'),
                    str(root/'qa/java/TestRecipientReceiptAllowance.java'),
                    str(src/'ConversationRecipientPolicy.java'),
                    str(root/'qa/java/TestConversationRecipientPolicy.java'),
                    str(root/'qa/java/TestReceiptPropertyStress.java')], check=True)
    subprocess.run(['java','-cp',dest,
                    'com.waenhancer.xposed.features.privacy.TestReceiptJidPolicy'],check=True)
    subprocess.run(['java','-cp',dest,
                    'com.waenhancer.xposed.features.privacy.TestPendingReadReceiptStore'],check=True)
    subprocess.run(['java','-cp',dest,
                    'com.waenhancer.xposed.features.privacy.TestBoundedReceiptAllowance'],check=True)
    subprocess.run(['java','-cp',dest,
                    'com.waenhancer.xposed.features.privacy.TestRecipientReceiptAllowance'],check=True)
    subprocess.run(['java','-cp',dest,
                    'com.waenhancer.xposed.features.privacy.TestExplicitReadReceiptPolicy'],check=True)
    subprocess.run(['java','-cp',dest,
                    'com.waenhancer.xposed.features.privacy.TestConversationRecipientPolicy'],check=True)
    subprocess.run(['java','-cp',dest,
                    'com.waenhancer.xposed.features.privacy.TestReceiptPropertyStress'],check=True)
print('PASS: recipient+message exact permissions, fail-closed routing and bounded storage invariants')
