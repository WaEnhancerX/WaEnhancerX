# PR #25 — expanded privacy-log review scope

Proposed title: **fix(privacy): redact sensitive message, call, media and IPC diagnostics**

The original PR #25 changed six production files and the source-level
`qa/test_private_hook_logs.py` regression (58 selected diagnostic callsites).
Three independent fork safety fixes were added to the **same existing PR**,
rather than introducing duplicate upstream PRs. All ten added Git blobs were
verified unchanged against an existing green integration QA run.

## Added production files

- `xposed/bridge/client/PreferenceBridgeClient.java`: prevent raw exception
  messages reaching error logs and cached preference-bridge diagnostic status.
- `xposed/features/privacy/CustomPrivacyHook.kt` and
  `xposed/features/media/MediaPreviewHook.kt`: report exception classes
  without embedding private fields/identifiers from Throwable messages.
- `xposed/features/privacy/CallPrivacyHook.java`: redact 9 error logs.
- `xposed/core/db/MessageStore.kt`,
  `xposed/core/components/WaexBottomSheet.kt`, and
  `xposed/features/media/StatusDownloadHook.java`: redact 18 error
  callsites, preserving user-facing status-save error notifications.

## Added QA and reproducibility

- `qa/test_exception_message_privacy.py`
- `qa/test_call_privacy_logs.py`
- `qa/test_database_dialog_status_log_privacy.py`

Run `python3 qa/test_private_hook_logs.py`; it now runs these three
regressions too. Each can also be invoked directly and is registered as an
optional source check in PR #27's `.github/workflows/source-qa.yml`.

Previous independent integrated verification:
[Source/JVM CI #38488345188](https://github.com/TheKillerMax/WaEnhancerX/actions/runs/38488345188).
Do not interpret a passing source suite as an Android/LSPosed runtime pass.

## Integration

Files `AntiRevokeHook.java`, `AntiEditMessagesHook.kt` (PR #17) and
`BlueOnReplyHook.java` (PR #35) overlap this PR's original diff.
Merge/resolve semantically, preserving #17's bounded state, #35's exact
recipient receipt authorization and #25's sanitized diagnostics.

Upstream main, APK releases, CI permissions and device configuration are
unmodified. This is **not** a comprehensive log privacy audit.
