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

The executor remains unbounded/non-durable. Process termination can lose queued work. The original PR #87 did not repair the separate nested Delete-for-me scope edge case; the follow-up below does. Arbitrary cross-profile Binder behavior and actual host selectors are NOT_VERIFIED. The complete signed APK requires the maintainer's legitimate build path.

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

### Historical Nightly-targeted confirmation (before the evening updates)

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

### Current Nightly forward-port and contributor rules

The replacement proposal is based on upstream
`nightly@69cef264b8f57217964d4c58f75fa15fa2e6b3f2`. This base includes the
Nightly 2.1.0 release metadata. The preceding `eee15668` results remain
historical evidence; they are not claimed as a new run of this base.

The production hook, runner and Java fixture retain the exact blobs published
at `c6394f31b0d417ddf0ecc8be1017d346b1894fe9`. A dedicated fragment under
`changelog.d/` follows contributor rule R14 and the existing changelog format;
the shared `changelog.txt` is unchanged. The previous draft PR #203 cannot
receive automatic review by a later push or by marking it ready, because the
contributor workflow reviews the final commit at the opened event. It must
not be described as approved or merged.

### Current-base controlled comparison — 2026-11-19

The matrix was executed again against the exact current Nightly base above,
not inferred from the earlier runs. Toolchain and offline dependencies are the
same verified JDK 17.0.20.1, Kotlin 2.0.20, Python 3.15 and Robolectric caches.
The generated `BuildConfig` now contains version code `20100001` and version
name `2.1.0-nightly.1+5703662` from this base.

- **Candidate PASS:** Windows source commit
  `fa7b48ba8bbc2cf788b4067dfa0031daa024da4e`, tree
  `ece72359e3daec3df2cc0a8b5f51783d0e097741`; all compilation stages pass,
  JUnit exit 0, **105 executions, zero failures**, 64.936 seconds.
  `C:\WAEX-Lab\work\preserved-scope-69cef-qa-20261119\candidate-r1\result.json`
  SHA-256 `58cfd4f7f311294444c7c92d1e2ea46efd0a36dbb2179d7023ea9c6adb4ed747`.
- **Unmodified Nightly product FAIL:** identical runner, fixtures and
  dependencies with `--source-root` pointing to detached `69cef264`;
  JUnit exit 1, **105 executions, six assertion failures**, 65.159 seconds.
  Only the two new nested-invocation methods fail, on each API 24/28/34.
  The explicit negative-control driver accepts those expected failures; it
  does not make the unchanged product correct.
  `baseline-r1\result.json` SHA-256
  `fa41746c2bae2ad9b4c87bdde8f6b9f2f2052b9a3ee2623fb0f64b9fb697fb8f`.
- Matrix report SHA-256
  `1356159d9b28ecbb81b69e7ac9e58830be6dca4fb8b20c603c7c77084c1d42e9`.
  The local forward-port commit `d13e75919e2a925753a9b69409f6d5e344dd7e40`
  has the exact same tree as the executed candidate. The final proposal adds
  only this acceptance documentation and the R14 changelog fragment; compiled
  production, fixture and runner blobs are unchanged.

Real WhatsApp/Xposed acceptance, work-profile behavior, actual host selectors,
the full Gradle build and a complete signed WAEX APK remain **NOT_VERIFIED**.
The independent public-source lab probe is not a substitute for those results.
