# Tasker runtime fixture: lazy privacy dependency

## Scope and reference

This QA-only candidate is based on
`c089458569564c241e6e2cddbd5a4de301299f28` plus the two existing fixture
changes from #142 head `23e3bbdcb7e880d71e0da23786bef01880dad743`.
The #142 runner and changelog blobs were independently retrieved and reproduced
before adding this correction. Production sources and preference schema are unchanged.

The current production `BaseFeature` declares `PrivacyPrefs.of(context, prefs)`
through a lazy property. The Tasker fixture compiles the complete `BaseFeature`
but previously omitted that dependency, so Kotlin could not reach the existing
18 callback scenarios.

## Boundary contract

The added Kotlin fixture exposes the matching static factory signature through
`@JvmStatic`. Its factory always throws `AssertionError` if called. The runner
first requires the real Java factory declaration and the real lazy property
shape. A missing or changed declaration fails before compilation.

The actual `PrivacyPrefs.java` is read and hashed as a compile-only contract;
it is not compiled or substituted for production privacy behavior. The existing
Tasker hook, `BaseFeature.kt` and `TaskerMessagePolicy.java` remain the actual
compiled implementation. The new result field separates these two categories.

All 18 #142 scenarios, their complete `TEST` literal, the 11 existing boundary
sources and the compiler-command structure remain identical. The existing
Windows tool discovery, platform classpath separator, failure handling and
required scenario count are retained.

## Validation on 2026-11-27

| Check | Observed outcome |
|---|---|
| c089 production + unchanged #142 runner | Kotlin compilation fails on the missing PrivacyPrefs symbol and its derived type errors; no callback case executes |
| Same production + candidate runner | Both compilation steps succeed; all 18 existing callback scenarios pass |
| Real factory signature and lazy declaration | Accepted |
| Changed factory return type | Rejected before compilation |
| Eager replacement for the lazy declaration | Rejected before compilation |
| Reflective call to the compiled static fixture factory | Correct descriptor; invocation raises the intended AssertionError |

The execution used Temurin JDK `21.0.13.1+2-LTS`, Java `--release 17`,
Kotlin `2.0.20` with JVM target 17, and Python `3.11.18`, entirely offline.
The reference failure was reused from C79 R5 only after confirming exact
production and runner byte identity; it was not rerun. All 932 tracked source
entries in the isolated candidate checkout remained unchanged during execution.

| Input | SHA256, raw LF bytes |
|---|---|
| Actual BaseFeature.kt | `20473282d33ec3183989f4311baf445052eb3647f86a347d440a11f580fbbd29` |
| Actual TaskerIntegrationHook.kt | `1b91d1ad6d19818c4ad4911ad095e1d10e8a3cf82bc02f5b041872e978280951` |
| Actual TaskerMessagePolicy.java | `2dc8e12928787898d2deb10144c8d6a1c208c88d573b87a14eb14fb8fd55f141` |
| PrivacyPrefs.java contract, not compiled | `7977ce4b9c51f72660d9c68b154a08d2342c4d15337236cbae5c83b597e2ec83` |
| Candidate runner | `05c7a27eba61961c97d29388535caba05eddc12bbeb8751b1ab2d7f98d413807` |
| Unchanged TEST literal | `523fd85939ee3d656dc5da321ce0c39f02bc51982ebb718b1c7478e31aeb65a8` |

The retained reference compilation log has SHA256
`881c137e8494a1b32d7040a68b24699167d3b77165536366378d49d2bd270437`.
The candidate execution receipt has SHA256
`f3ef4889cf5247693815fb1b24e18bcfd4f3e7c81da4523989ddb8c6b97a9ee7`.

## Branch integration

The old #142 head has an earlier BaseFeature and no PrivacyPrefs.java.
Its historical acceptance does not qualify this newer dependency contract.
The reviewed candidate therefore reconciles the existing two-file QA delta
onto c089. Updating that PR requires reconciling its base as well as retaining
the reviewed fixture delta; adding this guard to the old tree alone would fail
because the required production declaration is absent.

## Reproduction and limits

Run `python qa/test_tasker_runtime_gate.py --out PATH_TO_NEW_OUTPUT_DIRECTORY`
with a complete JDK and Kotlin CLI distribution on PATH. The output directory
retains compilation logs, test logs and the runner's source-hashed result.

Android, Xposed, DexKit, preferences, ActivityTracker and the main looper remain
explicit in-process boundaries. No Android broadcast, WhatsApp message, device
operation, network download or UI action occurs in this suite. These results
do not establish native injection, real Tasker profile delivery, privacy-service
behavior, provider refresh latency, a complete APK or a complete Source QA pass.
