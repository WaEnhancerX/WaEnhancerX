# Stability framework fixture after the WAEX runtime redesign

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
