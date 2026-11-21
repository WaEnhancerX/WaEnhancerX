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
real updater/filter code and `InjectionEvidence.kt`. The current reconciliation
with `nightly@9972e49fac9bfb650c25ee31fde0da9425b67175` preserves twelve updater
methods, three filter methods, and fifteen plain-JVM evidence methods. The
evidence fixture retains all ten earlier evidence methods and all five methods
from PR #182, including timestamp extremes, multiple PIDs, precedence and
restart/re-report assertions. Overlapping assertions are not counted as new
functional behaviors. The canonical `evidence` mode and legacy `clock` alias
select this fixture once; `all` requires thirty methods, not thirty verified
features. CI selects the existing classifier and rejects a missing fixture.
The shared runner, evidence fixture and selection regression have identical
bytes in PRs #166 and #182. Current source dependencies, real colors/HookErrors
and strict out-of-scope service boundaries are preserved. No earlier test result
or review transfers automatically to the reconciled head. Both branches need
fresh QA and R5 maintainer review of their workflow-path changes.

Fresh Linux verification with Python 3.12.14, Temurin 21.0.13.1+2, Kotlin 2.0.20
and JUnit 4.13.2 compiled the unchanged production classifier and canonical
evidence fixture: all fifteen methods passed. A separate source mutant replacing
the reported-PID membership predicate with an empty-set predicate compiled, then
failed exactly three assertions about process change/restart. That is a valid
negative control, not a passing mutant. The runner, evidence fixture, selection
regression and production classifier have matching bytes between PRs #166/#182;
this focused JVM evidence is reused only for those identical inputs. Seven
selection tests also passed normally and under Python optimization, including
current CI selection, missing-fixture rejection and legacy-clock exclusion.

The production source-guard runners explicitly refuse optimized Python because
their existing assertions must not disappear. JVM fixture results do not certify
the full thirty-method Android/Compose suite or actual Android/LSPosed injection.

A full `--mode all` attempt used verified Gradle 9.1.0, Android SDK 36 revision 2
and build-tools 36.0.0, but stopped after 27.730s before source compilation:
Gradle could not resolve the pinned `com.android.library:9.0.1` plugin marker
from its configured repositories. Zero tests ran. This is a dependency-resolution
block, not an Android test failure or evidence of success. No plugin/dependency
pin or acceptance count was changed to make the attempt pass; full acceptance
remains unverified pending dependency resolution and exact-head execution.

### Fresh full-framework result after isolated transport correction

The dependency-resolution block above was reproduced with one info-level
diagnostic: inherited `GRADLE_OPTS` proxy endpoint flags overrode the isolated
launcher's current platform proxy settings, producing network-unreachable errors
before repository responses. The launcher now replaces only those endpoint flags
with the platform's current `HTTPS_PROXY`; the same CA, dependency pins and test
expectations remain intact. No repository source/configuration was changed for
this transport correction, and the initial failed attempt is retained.

The subsequent full suite completed **30 methods: 12 updater, 3 filter and 15
evidence**, with **zero failures, errors or skips**, in **297.286s** on 21 November
2026. Real Android/Compose production sources and tests compiled through Gradle
9.1.0/AGP 9.0.1 and SDK 36. Updater/filter use Robolectric; the evidence classifier
methods remain plain JVM tests. All three JUnit XML reports and build logs were
retained. The source manifest's 53 entries, shared runner/fixture/helpers/wrapper
and entire stability workflow job match the reconciled PR #182 inputs, so this
result applies to both by exact input identity, not by shared version labels.
The later documentation-only commits do not change those executed inputs.

This closes the local full-stability-suite block only. It does not establish
full APK, physical Android/WhatsApp, real Xposed/LSPosed injection or remote review
acceptance. R5 maintainer handling and fresh exact-head CI still apply.

The CI runtime fixture stages only
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
