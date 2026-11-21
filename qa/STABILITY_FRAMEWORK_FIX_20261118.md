# Stability framework fixture after the WAEX runtime redesign

## Current Nightly reconciliation — 2026-11-21

The runner is reconciled with `nightly@9972e49fac9bfb650c25ee31fde0da9425b67175`.
Keep the current release-index and channel sources and all twelve updater tests;
the earlier five-updater result below predates those additions. The current
acceptance inventory is **30 methods: twelve update, three filter and fifteen
PID/boot injection methods**, requiring zero failures, errors or skips. Fifteen
counts JUnit methods, not fifteen independent behaviors: overlapping assertions
from the two original fixtures are deliberately preserved.

The real WAEX color model, filter dependencies, shared `HookErrors` implementation
and strict out-of-scope fixtures are retained. The removed observation clock is
not staged. The canonical `evidence` suite contains the ten original evidence
methods plus all five clock-fixture methods, including timestamp extremes,
multiple live PIDs, module/boot precedence and restart/re-report checks. These
are plain JVM/JUnit tests of the real classifier; they do not need Android APIs.
The legacy `clock` CLI spelling selects this same suite exactly once. CI selects
it from the existing `InjectionEvidence.kt`, not the removed clock source, and a
selection regression rejects missing fixtures and checks the actual shell step.
The shared runner, fixture and selection regression are identical in PRs #166
and #182 to avoid incompatible duplicate suites. This requires fresh exact-head
QA and maintainer review of the workflow-path changes under rule R5.

Static source-presence and independent JUnit-method inventory checks do not
constitute execution of the thirty methods. Historical
results below remain tied to their original inputs and are not reused as a
passing result for this merge.

Fresh Linux verification with Python 3.12.14, Temurin 21.0.13.1+2, Kotlin 2.0.20
and JUnit 4.13.2 compiled the unchanged production classifier and shared evidence
fixture: all fifteen methods passed. A separate source mutant incorrectly
accepting an unrelated live PID compiled, then failed exactly three process-
change/restart assertions. The candidate/reference compile times were 4.039s
and 4.488s; JUnit times were 0.017s and 0.020s. These are not app performance
measurements. The identical shared fixture/production inputs in PR #166 justify
reusing only this focused JVM result. Seven actual CI-selection regressions also
passed normally and under optimized Python in each branch. Full Android/Compose
acceptance still requires the thirty-method suite; no physical-device result is
claimed.

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
and entire stability workflow job match the reconciled PR #166 inputs, so this
result applies to both by exact input identity, not by shared version labels.
The later documentation-only commits do not change those executed inputs.

This closes the local full-stability-suite block only. It does not establish
full APK, physical Android/WhatsApp, real Xposed/LSPosed injection or remote review
acceptance. R5 maintainer handling and fresh exact-head CI still apply.

## Historical preparation and results

## Failures reproduced on the exact public base

Base: `WaEnhancerX/WaEnhancerX@0aaffab0270a3d0832bef2895f6457f3792fd74a`.

1. The unchanged `--mode clock` runner fails **before Gradle**, because it copies `ui/components/RuntimeObservationClock.kt`, which upstream commit `1b029ec88d9c4f7fbc88261f3bd2a9b16b16e342` removed. Its original test also imports `RuntimeObservationTime`, `rememberRuntimeObservationClock`, and `isRecentTimestamp` and asserts pulse/lifecycle behavior that no longer exists in WAEX. Windows reproduction: `FileNotFoundError`, exit 1, at `C:\WAEX-Lab\evidence\waex-stability-framework-20261118\baseline-clock-01`.
2. The real GitHub Actions stability job `134483068286` reaches Android/Compose Kotlin compilation in `--mode update`, then fails on **seven** unresolved `.isLight` references in the actual `WaexModalBottomSheet.kt`: the fixture `WaexTheme.colors` was typed as Material3 `ColorScheme`, whereas production exposes the custom `WaexColors` model with `isLight`. This is a fixture contract mismatch, not evidence that the app fails compilation.

## Changes and acceptance contract

- Keep the existing `--mode clock` CLI spelling and `StabilityClockTest.kt` fixture filename so existing GitHub Actions detection continues to run it. Stage **the real** `utils/InjectionEvidence.kt`, without the deleted clock. Replace its obsolete Compose heartbeat test with **five** independent plain JUnit tests for the current PID/boot classifier: live PID independent of timestamp age, module/boot priority, missing/stale reports, unavailable/stopped/changed process discrimination, and injection re-report after process restart. This intentionally changes the tested product contract; it does not claim to test a clock that no longer exists.
- For `--mode update`, stage **the real** `ui/designsystem/WaexColors.kt` and expose `LocalWaexColors.current` from the theme adapter, matching the production `WaexTheme.colors` getter. The test adapter still does not load actual fonts, design theme composition or licensing. The framework's existing **five updater** and **three filter** test expectations remain unchanged; `clock` now requires **five**, so `all` requires **13** (5+3+5) with zero failures, errors and skips.
- No production source, actual hooks, package data, Xposed installation, secret/licensing check, signature or WhatsApp account was modified.

## Experimental evidence and limitations

- **PASS:** `kotlinc 2.0.20 -jvm-target 17` compiled the current production `InjectionEvidence.kt` together with the actual new five-case JUnit fixture, using `junit-4.13.2.jar` and JDK 17.0.20.1. Independent `org.junit.runner.JUnitCore com.waenhancer.fixture.StabilityClockTest` returned **OK (5 tests)**, exit 0; compiled jar SHA256 `8A0C2015B7D26D2A45829E65E12408C18BC7DB04BB56B23B5CD8DD2D030B7AF6`.
- **PASS negative control:** a separate temporary source copy changed `reportedPid !in runningPids` into `runningPids.isEmpty()`, incorrectly accepting an unrelated live PID. This mutant **compiled successfully**, then the **same five tests** exited 1 with precisely two expected failures: `PROCESS_CHANGED` expected, `CURRENT` observed. Mutant source SHA256 `061096abf2d819e788146670f9dbc34a007ab70b1cea9fae8a6d2f285571e907`; it is evidence-only, not committed or included in the PR.
- **NOT_VERIFIED:** full Android/Compose `--mode update` candidate. The source and JUnit fixture were copied with identities, and Gradle 9.1.0 advanced to `:probe:preDebugBuild`; SentinelX's 25-second foreground limit stopped the run, before Kotlin compile/JUnit and before `result.json`. A narrower offline `:probe:compileDebugKotlin` attempt also timed out. No hidden background process or PASS may be inferred. Evidence directory: `C:\WAEX-Lab\evidence\waex-stability-framework-20261118\candidate-update-01`.
- **NOT_VERIFIED:** full Gradle modes `filter`, `clock`, `all`, official CI on this candidate, Android module injection and WhatsApp runtime effects. The five compiled JVM/JUnit tests are deliberately **not** called Robolectric or an installed Android test.

Toolchain: Windows 11 elevated private lab, Python 3.15, JDK 17.0.20.1, Kotlin 2.0.20, Gradle 9.1.0, SDK 36. CI uses JDK 21, and the check must be rerun on the exact submitted head. All local changes are limited to two QA fixtures/runner files plus this note.

## Integration and recovery

The acceptance gate is `python3 qa/verify_stability_framework.py --out <fresh-path>` on the candidate, requiring its 13 real JUnit tests and zero skips/failures. Include the original failures as regression controls and verify that the real theme's `isLight` getter is type-correct without permissive stubs. If compile reveals additional dependencies, diagnose each without suppressing tests. These source-only QA changes can be reverted by a normal commit; the real WhatsApp installation and laboratory account were untouched.

## CI follow-up after opening upstream PR #182

The **exact** PR #182 head `4b21422fdf452669d3ad138474547a97c724e29a` ran [stability job `134603029996`](https://github.com/WaEnhancerX/WaEnhancerX/actions/runs/44245407734/job/134603029996). Its real Kotlin/Compose source compilation progressed beyond **all seven prior `isLight` errors**. Java compilation then failed because `RootCommandRunner.java` refers to the real `HookErrors.swallowed(...)`, but the isolated runner had never copied `HookErrors.java`. The standalone filter mode has the same dependency through its dispatcher and filter hook.

The follow-up **stages the unchanged production `xposed/utils/HookErrors.java` exactly once** whenever update or filter is selected, checks its byte hash and supplies a single XposedBridge logging-only fixture in both modes; unexpected hook installation still throws `AssertionError`. This fixes source dependency closure without replacing the diagnostics implementation with a permissive stub. It is **not** a completed Android build result; rerun the workflow on the new SHA and retain any further failures as evidence.

## Next CI boundary: update PASS, isolated filter dependencies

On the published follow-up head `0f53b7301b9ab5b7c5bce8b8f2abf289c21853f1`, [stability job `134607555945`](https://github.com/WaEnhancerX/WaEnhancerX/actions/runs/44246787707/job/134607555945) returned **PASS for update: five Android/Compose/Robolectric JUnit tests, zero failures/errors/skips**. It then reached `--mode filter`, where Kotlin compilation reported missing `HostResourceIds`, `LayoutPassThrottle`, and `LocalWaexColors`. The first two are real current filter dependencies; the third is an unnecessary updater theme contract leaking into the filter-only adapter.

The next narrowly scoped correction stages the real `xposed/utils/HostResourceIds.java` and `xposed/features/customization/LayoutPassThrottle.kt` for filter mode, while using Material3's independent color scheme **only when the mode has no updater consumer**. Update or combined modes still use real `WaexColors`; all previously passing update tests remain required. Filter's three cases and the five PID/boot cases remain unchanged. **Filter/all/clock NOT_VERIFIED on this revision until new CI finishes.**
