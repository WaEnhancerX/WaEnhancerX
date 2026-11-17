# Target identity and runtime evidence acceptance

> Historical specification for the pre-refactor heartbeat/observation-clock design.
> The current PID/boot and event-driven contract is in
> [CURRENT_RUNTIME_EVIDENCE_ACCEPTANCE.md](CURRENT_RUNTIME_EVIDENCE_ACCEPTANCE.md).
> Historical timings and test counts below are not claims about current source.

The manager's active-module indicator, the host's version-policy decision, its
stored initialization report, and evidence of a recently running injected process
are different observations. None certifies every feature.

## New runtime decision contract

`UniversalVersionValidator.evaluate` returns the reason for permission or denial.
It preserves built-in/custom rules and explicit existing bypass semantics. A
missing module context, invalid resource namespace/table or unreadable settings
is not a valid ruleset rejecting that version. Numeric resource IDs are only
resolved against verified module-owned Resources, never the WhatsApp table.

The Boolean compatibility API remains available. Both an unknown decision and
an explicit unsupported version keep hooks paused. `COMPATIBILITY_UNAVAILABLE`
is carried by VersionGuard/MainHook and permitted by HookProvider's existing
validated health-report route. Caller authorization, derived-key protections,
signing, entitlements and preference write permissions remain unchanged.

## Process observations and presentation

Process output must cover exactly the requested targets and contain valid,
nonduplicate package records and positive PIDs. A failed/partial/root-denied
observation invalidates cached proof, rather than becoming an empty successful
process list. An empty PID record following pidof's no-match exit remains a
successful observation of absence. Permission/API/tool failures are unknown.

Positive injection evidence still requires the current boot, the reported PID
and a heartbeat no older than the existing 25-second window. Root observations
now expire against elapsedRealtime, independently of wall-clock heartbeat age.
The legacy Boolean `isHooked` represents only confirmed current evidence.

The overview and details use the same presentation helper. Historical bridge and
initialization values are marked "Last report (not current)" after evidence expires.
Current target-paused/error reports take precedence over locally admitted version
rules. Empty or unknown target sets never yield a positive compatibility summary.
An explicit bypass is displayed as unverified, not successful compatibility.

A lifecycle-STARTED clock invalidates positive on-screen evidence within a
five-second display interval without starting new root commands or enumerating
packages on each tick. Refresh/resume/report events retain their normal probes.
This can turn an idle page into unconfirmed; it deliberately cannot manufacture
new positive evidence. Boot/time observation races and the non-atomic relationship
of independent stored health reports still require device acceptance.

## Reproduction and testing

Run `python3 qa/test_runtime_diagnostics.py` and
`python3 qa/test_startup_reconciliation.py`. The former compiles the production
validator, VersionGuard, inspector, evidence and presentation classes, with the
unchanged data model extracted from the detector. Android Resources/Context,
preferences, root and the bottom-sheet builder are explicit controlled doubles.
It also executes the exact generated shell using a fake pidof executable.
Source guards check actual UI/transport/lifecycle call sites.

Full Compose screens, runtime permissions, HMA/Vector, Binder, ROOT on Android and
LSPosed are not executed by these tests. The display strings remain English like
the previous detailed diagnostics; localization/layout acceptance is still needed.

Before release, build using authorized dependencies/signing and verify both
WhatsApp packages: resource visibility denied/recovered, truly unsupported versions,
root denial/timeouts, PID changes, restarts, expired heartbeat, pause and failure
states, long-open screens and refresh recovery. Do not declare every feature
working or ask users to disable privacy/version protections just to obtain a
green status. Missing resources cannot be corrected by clearing user data.


## Continuation after interrupted publication

The validated staged commits were recovered before moving any functional branch.
The final follow-up additionally requires an end-of-probe sentinel and timestamps
process observations before, not after, the root command. A slow or truncated
probe cannot manufacture fresh positive evidence. Successful no-match pidof output
still represents observed absence; unavailable output remains unknown.

Queued compatibility notices capture a decision-generation token. A callback from
an older check neither displays an obsolete pause nor clears the newer check's
one-notice flag. Explicit custom version allowances, like bypasses, remain usable
but are presented as unverified rather than a verified compatibility result.

The expanded production/JVM test exercises 165 checks and seven emitted-shell
controls. The shell uses a fake pidof and does not run Android root. A separate
framework runner compiles public utilities and the lifecycle clock against real
Android/Compose dependencies and exercises compiled release/debug resources.
These do not replace a full WAEX build or HMA, Binder, Vector and LSPosed acceptance.
