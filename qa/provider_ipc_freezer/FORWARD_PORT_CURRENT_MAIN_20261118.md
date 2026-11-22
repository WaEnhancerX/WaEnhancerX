# Unstable provider IPC — forward-port to 2.0.8-era main (18 November 2026)

## Scope and base

This branch is a forward-port of the existing upstream [PR #137](https://github.com/WaEnhancerX/WaEnhancerX/pull/137), **not** a claim that its historical non-mergeable head is installable. The exact base is `WaEnhancerX/WaEnhancerX:main@0aaffab0270a3d0832bef2895f6457f3792fd74a` (tree `fbe9294da04536595b314eb556f145d025d37457`). Worktree `C:\WAEX-Lab\work\provider-ipc-forward-port-20261118`, branch `fix/provider-unstable-ipc-forward-port-20261118`.

The original production commit `13873542a57e8ac959302641f8baeb93a4c3fd41` could not be cherry-picked cleanly: six conflicts reflected subsequent startup deferral, background hook reporting, HookErrors diagnostics, and current preference fixture contracts. Each conflict was resolved by preserving the newer behavior and using the unstable one-shot provider call. The original test-only follow-up commit `9dd7d543e990982d21b63dc41f66153c9cecd4c2` was then applied without conflicts. No main files or installed WhatsApp/WAEX binaries were changed.

## Specific compatibility correction beyond #137

The original test initially **FAILED as intended** against the new base: `qa/provider_ipc_freezer/verify.py` found two new direct stable calls outside the old patch:

1. `TargetExitReporter.java` → `report_target_exit_history`; the existing comment incorrectly described the old stable call as an unstable route.
2. `CssThemeHook.kt` → `theme_asset`, where returning a ParcelFileDescriptor must retain its existing response and resource-close contract.

Both now use `HookProviderIpc.call()`. The bridge URI, methods, arguments, Bundles and caller context remain unchanged. There are **16 total helper calls in current production source** (14 under `xposed/` and 2 in `WhatsAppPackageDetector.kt`), with **no remaining direct `getContentResolver().call/contentResolver.call` sites** in the tracked Java/Kotlin source search. The verifier reports 14 in its defined `xposed/` scope, not 16.

The provider helper is **byte-for-byte the same Git blob as original PR #137**: `7cc0f8d2be1acd0dede2333f0dabd32078a80076`. The synthetic Android dependency probe is also identical to #137: `d8fc9b349f0beace60acc00839d46cf414a6ba05`. The historical Android 14 experimental result applies to the isolated **platform mechanism** under those inputs; it does not establish WAEX application integration.

## Current-source fixtures brought up to date

- The preference bridge fixture now compiles **real** `PreferenceSchema.java` and **real** `HookErrors.java`, with only the `VisibleForTesting` annotation stub. The duplicate `PreferenceRestartTracker` stub and duplicate Context.getPackageName introduced by merge were removed. It continues to check independent schema/restart constants rather than silently accepting missing inputs.
- The hook-startup fixture now rejects a direct synchronous terminal report and only accepts a verified **deferred** report sequence after `initializeAll()`. It extracts the production `countEnabledStartupFeatures()`, takes the real slow-hook threshold, and uses a fail-closed disabled progress-notification fixture. An explicitly synchronous executor is used **only to count report operations deterministically**, not as evidence of Android scheduler behavior.

## Tests on this exact staged candidate

Lab: Windows11 `DESKTOP-N0OOG51`, Temurin JDK17, Kotlin compiler2.0.20, Python3.15 and Android SDK34/36; local candidate derived from main above. Full source syntax check PASS for **155 Java + 150 Kotlin** files (syntax only; initial infrastructure invocation without KOTLIN_HOME was BLOCKED, then corrected; no dependency installation needed).

| Status | Reproduction | Result / limits |
|---|---|---|
| PASS | `python qa/provider_ipc_freezer/verify.py` | 46 Java helper-contract assertions, 14 scoped xposed wiring sites, zero direct stable calls, **5/5 mutants rejected**; JVM fixtures |
| PASS | `python qa/test_bridge_contract.py` | Real preference source/schema/errors; hydration, type coercion, snapshots, atomic edits, listeners, explicit IPC failure; Java warns one unchecked Set cast |
| PASS | `python qa/test_startup_hook_reporting.py --source-root <worktree> --out <new-evidence-dir>` | **13 cases, 241 checks, 0 failures**, 3 source-guard negative cases; JDK fixture, not target timing |
| PASS | `python qa/test_provider_scope.py` | Official, Business and clone caller-scope policy |
| PASS | `python qa/test_provider_derived_keys.py` | 30 key/health/clear policy assertions |
| PASS | `python qa/test_preference_write_types.py` | 42 type/removal combinations with negatives |
| PASS | `python qa/test_backup_alias_policy.py` | 41 current aliases, 4 cross-type collision protections, two independent privacy settings |
| PASS | `javac -source 8 -target 8 -cp android-{34,36}.jar ...HookProviderIpc.java` | Actual Android API34 and API36 signatures; compiler bootstrap warning only |
| PASS | `python qa/check_syntax.py --root app/src/main/java` | 155 Java / 150 Kotlin syntax. Not Android compilation |
| PASS | `git diff --cached --check` | No whitespace errors or unresolved paths |

Evidence output directory: `C:\WAEX-Lab\evidence\waex-provider-ipc-forwardport-20261118\`, including startup JDK fixture `startup-pass-3/result.json`, compiled helper under `javac-android-34` and `javac-android-36`. Earlier failed synthetic fixtures remain recorded as baseline/negative, not counted as candidate PASS.

## Android experiment identity and limitations

The included `android_dependency_probe.py` is the unchanged controlled experiment from the older PR. Its **13 November** Android14/API34 personal/work-user test proved that a synthetic stable client dies with `DEPENDENCY DIED` when its synthetic provider is SIGKILLed, while an unstable client survives; signatures, PIDs, app identity and exit-info were independently checked. It was **not re-run** on this forward-port, because the full runner needs a longer uninterrupted foreground execution window than the available SentinelX call limit. Its test result cannot be reassigned to the current **WAEX app** candidate.

**NOT_VERIFIED:** full release APK build from this branch (authentic private dependency unavailable); installed end-to-end WhatsApp hooks; Android versions besides tested synthetic API34; actual freezer recovery frequency; recorded call audio; media/asset descriptor behavior in WhatsApp; latency/ANR/battery; real work-profile account messaging. Official 2.0.8 remains installed as the reference, NOT this branch. Stable reference source-freezer exits remain historical baseline, not confirmed fixed.

## Risk, rollback and handoff

Unstable clients intentionally prevent keeping a stable provider dependency. They cannot prevent WAEX provider death or guarantee that a non-idempotent provider operation finished before Binder failure. There is **no automatic replay** after ambiguous errors; SecurityException still propagates; provider permissions, caller UID and profile isolation are untouched. Preserve asynchronous reporting introduced since #137. No database or preference migration, no modified WhatsApp data, no messages and no public APK. Recover by not merging or via normal Git revert if integration proves a regression; no force push.

PR will be opened by the user. Do not merge both this replacement branch and old #137 blindly; the new branch supersedes the old patch after a reviewer confirms base/head and the independent application-level acceptance gate.
