# Preserved messages: asynchronous acknowledgement acceptance

This document replaces the old synchronous sender acceptance contract for PR #87. Current production already queues manager persistence; success returned from dispatch means enqueue acceptance, not a committed vault row.

## Scope

The complete production sender, provider, SQLite store, lifecycle ownership, cache and policies compile in the standalone runner. Android is Robolectric API 24/28/34. Xposed dispatch, host message identities and unrelated provider services are explicit boundaries, not native injection. Restart/transcription/private licensing services fail if the suite unexpectedly calls them.

## Run

    python3 qa/android_preserved_dispatch_test.py --out /tmp/waex-preserved

Provide --deps, --dexkit, --sdk-jars and --maven for verified cached/offline dependencies as documented by --help. Windows uses the actual Kotlin compiler JVM entry point; the same pinned distribution supplies stdlib. Java 17+ and Kotlin 2.0.20 are required by the tested toolchain. Use --source-root for unchanged production and --expect-assertion-failures only for explicit negative controls.

## Required results

33 methods on each of API 24/28/34: **99 observed JUnit executions**. All must complete. Repetitions are not new coverage. Compilation or bootstrap failures are not successful negative controls.

The existing 28-method matrix covers confirmed/failed inserts, missing and old providers, denied UIDs, SQLite errors, private error text, warning throttling, Delete-for-me continuity and source-message preservation.

Five additional methods establish: the original Delete-for-me completes while the actual provider worker is held by a latch; Throwable logs retain private markers so the oracle can detect leaks; after-callbacks use the invocation snapshot even after self-unhook; after-callback order is reversed; original throwables survive scope cleanup.

Production sources of official 2.0.6 complete this 99-test suite with 39 assertion failures. The candidate completes with zero. A deliberate worker log(Throwable) mutation compiles and completes with three privacy failures. The test logger must never sanitize the defect being tested.

FIFO barriers are used by tests and teardown only, after the host callback returns. They are not a main-thread production wait or durable delivery guarantee. Unsupported callback-priority/reflection details remain outside this fixture.

## Production guarantee and limits

On the worker, true persistence acknowledgement is accepted; false, null, old empty responses and exceptions use the existing bounded payload-free warning. Local deletion does not wait for manager Binder or vault SQLite. Metadata capture still happens on the original path.

The executor remains unbounded/non-durable. Process termination can lose queued work. Nested Delete-for-me scope restoration is a separate pre-existing edge case, not repaired by this change. Arbitrary cross-profile Binder behavior and actual host selectors are NOT_VERIFIED. The complete signed APK requires the maintainer's legitimate build path.

## Device acceptance

Use authorized test accounts and signed contributor builds. Verify local deletion with a blocked/unavailable manager, successful vault storage, denied inserts, bounded sanitized logs, reboots, managed profiles and Debug/Release separately. Never count an enqueued operation, ACTIVE label or a passing JVM fixture as a confirmed real-device saved message.

PR #87 must retain its existing history and storage guarantees when reconciled with main; do not force-push or restore the former synchronous sender.

## Follow-up: nested Delete-for-me scope (2026-11-19 candidate)

An outer Delete-for-me operation can invoke the same hooked method again before
returning. The previous `ThreadLocal<Boolean>` was cleared by every after-callback,
including a nested Runnable that was not recognized by the before-callback. The
remaining outer database delete therefore ran with `isDeleteForMeInProgress()`
false and its preservation path was skipped.

The scope now holds the identities of qualifying `MethodHookParam` invocations
for the current thread. Only the matching after-callback removes a scope entry;
ending an inner qualified or unqualified invocation cannot clear its caller.
An empty set removes the thread-local reference. The original host deletion,
asynchronous manager dispatch, warning policy and provider/SQLite contracts are
unchanged.

The fixture adds two independent methods: one nested qualified invocation and
one unrecognized nested Runnable. The expected suite size is now **35 methods
across three API-shaped Robolectric environments, 105 executions**. Both cases
must fail against unchanged pre-fix production while the existing 33 methods
remain unaffected. Run `python3 qa/android_preserved_dispatch_test.py --out
/path/to/new-output` with the previously verified dependencies. The published
99-execution result above belongs to the previous revision, not to this
candidate. Actual current-device scope, host method resolution, process lifetime,
managed-profile Binder access and complete signed APK remain NOT_VERIFIED until
separately exercised.

The current-main fixture also compiles the real `HookErrors` and `HostFields`
helpers required by `DexSearchEngine` and `PreserveDeleteForMeHook`. Its
compile-only generated `BuildConfig`
is derived from the checked Gradle namespace and version properties, rather
than an independent hardcoded version. This repairs a pre-test `javac` contract
failure seen on `main@39bb7ad` (missing `HookErrors` and generated
`BuildConfig`); it does not assert that Gradle or a complete signed APK builds.
Other newly required provider endpoints (transcription, diagnostic recorder,
theme asset lookup and the target-seeding constant) use typed compile-only
fixtures guarded by their current production declarations; out-of-scope service
calls throw if exercised. A changed declaration must fail the fixture rather
than silently select a different behavior.

### Reproducible laboratory result — 2026-11-19

**Environment:** `DESKTOP-N0OOG51` Windows 11, Temurin JDK 17.0.20.1,
Kotlin CLI 2.0.20, Maven 3.9.16, Python 3.15; Android API 24/28/34
Robolectric fixtures (not an installed WhatsApp/WAEX process).
Production base: upstream `main@39bb7ad57dbfcb324f713ce46d681720946150b9`.
Dependencies were read from the existing accessible
`C:\Users\tkmremote\.m2\repository`; no private `:licensing` stub or
release signing material was used.

- **Candidate PASS:** all standalone Java/Kotlin/provider fixture compile
  stages exit 0; JUnit 105 tests, zero failures, accepted=true.
  `C:\WAEX-Lab\work\preserved-scope-nested-qa-20261119\candidate-r6`;
  `result.json` SHA-256
  `3e8b993f468205d4f99f4bfbce8007901b3252e1a4af221480c311d2a999a173`.
- **Unmodified production negative control PASS (expected failures):** same
  runner, fixtures, dependencies and 105-test matrix, with
  `--source-root C:\WAEX-Lab\work\preserved-scope-base-20261119`
  and `--expect-assertion-failures 6`. JUnit exit 1 as expected: six
  assertion failures, exclusively
  `nestedQualifyingInvocationCannotEndOuterDeleteScope` and
  `nestedUnrecognizedRunnableCannotEndOuterDeleteScope` on API 24/28/34.
  The runner accepted that negative control (exit 0), not the defective
  implementation. `baseline-r1/result.json` SHA-256
  `7da484a32e8e3f766b4e2a3aadfe4a876268a07f806ab6c62db8e7d1c0c0f4d35`.
- **NOT_VERIFIED:** on-device Xposed scope/SQLite delete, actual WhatsApp
  host method mapping, persisted Manager vault record, other app versions,
  Android work-profile behavior, full Gradle build, signed APK and release.
  This controlled fixture is evidence of the nested-scope fix only.

### Nightly-targeted confirmation

The intended upstream PR base is **`nightly@eee15668e7dab08b4cfff90b6be441e91d73021b`**,
not `main` (the two branches had diverged by 3 versus 19 commits on
2026-11-19). The four touched files were blob-identical at those bases;
the candidate change was cherry-picked into the dedicated Nightly worktree.

- **Candidate PASS** on source commit
  `099423be6bf72b13511f57813682719e7d00a763` (tree
  `9350cff7bc0051b945ab9aae7050726ed0e89238`): all four compilation
  stages PASS; **105 JUnit cases, zero failures**, across API 24/28/34.
  Output `nightly-candidate-r1/result.json` SHA-256
  `4c77d5ac50ccac64442bb6522999397c76d725168294a798a6b03f52e08b0439`.
- **Original Nightly negative control PASS** (expected original failures),
  detached worktree `preserved-scope-base-nightly-20261119` at the exact
  Nightly base: 105 JUnit cases, **6 assertion failures**, precisely the two
  newly introduced nested-scope cases on each API level. The runner's
  `--expect-assertion-failures 6` returns exit 0 only for that exact
  controlled failure count. Output `nightly-baseline-r1/result.json`
  SHA-256 `ecc5f20591ff8424a7d102c759a5c6dbacf462e9fef21161e63a9b60fa6ab4e1`.
- Runner, JDK/Kotlin/Maven and dependency cache as above; Gradle and a signed
  WAEX APK were not invoked. Repeating the fixture after a documentation-only
  commit is unnecessary when all compiled source and test blobs stay identical;
  any production, fixture or toolchain change invalidates that equivalence.
