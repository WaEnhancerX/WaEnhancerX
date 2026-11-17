# Current runtime evidence and CI acceptance (2026-11-17)

This page supersedes only the earlier runtime-clock and expired-heartbeat sections in
`RUNTIME_DIAGNOSTIC_ACCEPTANCE.md`, `CLOCK_RECOMPOSITION_ACCEPTANCE.md` and
`DASHBOARD_REFRESH_ACCEPTANCE.md`. Their historical versions remain preserved.

## Implemented production contract

1. `TargetAppManager` owns scope, runtime process snapshot, detected apps and
   refresh epoch as `StateFlow`. `Navigation.kt` attaches on `ON_RESUME`, detaches
   on `ON_PAUSE` and disposal. The receiver requests a refresh when an injected
   target reports; an explicit refresh is also supported. Concurrent requests
   are coalesced. No periodic Compose observation clock is installed.
2. `RootProbe` runs the scope and `ps` probe using one root command. An incomplete
   process-list marker, failed root call or an empty filtered process map returns
   unavailable evidence. The parser supports modern `PID NAME` and legacy ps rows;
   positive PID tokens and package-process names are required.
3. `classifyInjection` confirms injection only when the module is active, the
   current boot is known, a report with positive PID/time exists and that exact
   PID is present in the current process list. An old timestamp alone neither
   confirms nor expires a report. Missing scope/process data remain unverified.
4. A stopped target can retain prior hook/bridge/initialization descriptions,
   but the presentation labels them historical. Only a running confirmed target
   contributes runtime readiness to the aggregate, while compatibility rejection
   of any installed target still requires review.
5. Upstream `d7fd3110` intentionally treats user-added custom version rules as
   accepted runtime states. Explicit bypasses remain flagged for review. This
   PR updates QA to that policy without changing production authorization.

## Automated acceptance

Run these on a host with Kotlin 2.0+ CLI and JDK:

```sh
python3 qa/test_injection_evidence_contract.py
python3 qa/test_runtime_diagnostics.py
```

The first compiles unchanged production `InjectionEvidence.kt` and executes ten
positive/negative PID and boot controls. It checks current broadcaster, receiver,
lifecycle and coalescing source wiring. The second compiles production runtime,
version and display decision utilities against explicit doubles, executes
negative version/UI/queue checks, validates process-list parsing, and exercises
filtered `ps` selection with a controlled executable. It is **not** a live root
probe, and no shell fixture can prove an Android provider, scope or hook works.

The CI stability fixture stages unchanged `WaexColors.kt` (including `isLight`),
real updater/filter code and `InjectionEvidence.kt`. Ten evidence JUnit cases
replace the deleted Compose clock fixture. The CI runtime fixture stages only
the source files currently present; its resource JUnit tests remain mandatory.
For the standalone framework checks run:

```sh
python3 qa/verify_stability_framework.py --mode evidence --out /tmp/waex-stability-evidence
python3 qa/verify_runtime_framework.py --out /tmp/waex-runtime-framework
```

Every executable test must fail if any contract predicate is violated. No
compiler failure may be relabeled as PASS. CI's evidence artifacts include
the exact source hashes and JUnit results. These isolated tests do not compile
the complete WAEX APK or prove a particular WhatsApp/Vector pairing.

## Device checks still required

- Read the actual WAEX/Vector/WhatsApp logs for both Android users and prove
  target receiver registration, scope, provider report, PID, boot and visible
  status change across a process restart.
- Exercise unavailable root, unreadable LSPosed scope, target not running,
  explicit refresh, foreground/background and denied package visibility.
- Test supported, unsupported, custom and bypassed version decisions on the
  exact installed APK/version combinations; no bypass means functional support.
- Track I/O and UI performance separately; fewer timer callbacks alone cannot
  substantiate a latency, FPS or battery claim.

Do not describe any of these as device-verified until run on an identified device.
