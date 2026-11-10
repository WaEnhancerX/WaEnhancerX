# Tasker incoming events: honor the current preference at dispatch

## Problem and resulting behavior

TaskerIntegrationHook previously checked tasker_integration when installing the
hook. An incoming-message callback installed while enabled continued to emit
com.waenhancer.MESSAGE_RECEIVED after the preference was disabled.

The callback now reads the preference before extracting incoming identifiers and
again immediately before sendBroadcast. A disabled/missing preference or a
preference-read exception suppresses the event through the existing guarded
callback. Changes made during host-field extraction are caught by the final
read.

The outgoing automation action, receiver registration, exact event/extras
contract, status exclusion, foreground checks, package-constrained deep links,
launch throttle and explicit user confirmation flow are preserved.

These reads observe the current SharedPreferences implementation. With
PreferenceBridgeClient, this is the latest published preference snapshot; the
change does not add synchronous IPC to each message or bound cross-process
ContentObserver refresh latency. Enabling the feature in a process that started
disabled retains its existing restart requirement.

## Executable checks

Run with Python 3, JDK 17+ and kotlinc available:

    python3 qa/test_tasker_runtime_gate.py
    python3 qa/test_tasker_policy.py

To preserve evidence, select a new output directory:

    python3 qa/test_tasker_runtime_gate.py --out /tmp/waex-tasker-candidate

The new fixture compiles the complete production TaskerIntegrationHook.kt,
BaseFeature.kt and TaskerMessagePolicy.java. It installs and invokes the incoming
and external-command callbacks through explicit Android/Xposed/DexKit doubles.
All broadcasts are captured in memory; no external app receives them.

Its 18 scenarios cover disabled startup, Android 13+ and legacy receiver
registration, exact action/extras, disabling an installed callback, removing the
preference, malformed/denied preference reads, changes during JID/message-ID
extraction, reenabling an installed callback, status exclusion, malformed host
arguments, a queued command disabled before UI launch, same-package draft
navigation, an unrelated action and an unfocused activity. Every incoming case
also checks that the host result and argument-array identity are preserved.

## Negative controls

    python3 qa/test_tasker_runtime_gate.py --source-root /path/to/v38 --out /tmp/waex-tasker-v38 --allow-test-failure

Compilation and completion of all 18 scenarios are mandatory even for a negative
control. Normal CI must omit --allow-test-failure.

The candidate passed 18/18 scenarios. The reviewed V38 snapshot compiled and
completed the same set with seven failures. A mutation retaining the entry check
but removing the final read compiled and failed the two mid-callback transition
cases.

## Runtime acceptance still required

The fixture does not exercise native DexKit/LSPosed injection, Android broadcast
delivery, actual Tasker profiles, provider refresh timing or a real WhatsApp UI.

On a supported device, enable the integration and receive a message with an
authorized Tasker profile. Disable the option, allow the preference bridge to
publish its update, and verify that later messages generate no Tasker event.
Reenable within the same already-hooked process and verify event delivery
resumes. Confirm that an automation request still opens a draft only while the
selected WhatsApp activity is visible and requires the user to send it.
