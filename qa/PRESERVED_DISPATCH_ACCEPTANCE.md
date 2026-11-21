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
