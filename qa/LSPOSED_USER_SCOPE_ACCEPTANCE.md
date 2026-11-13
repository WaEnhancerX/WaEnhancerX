# LSPosed Android-user scope acceptance

## Problem and source contract

The LSPosed `scope` table associates a target package with a **user_id**;
the `modules` table supplies the module identity, its enabled flag and the
join key `mid`. The previous WAEX reader queried only the package name,
module name and enabled flag, so it treated a package selected in the
personal profile as selected in a work profile (and vice versa). Its
unqualified persistent cache could also report an old positive when the
framework database could not be read.

The Android UID formula used here follows the Android Open Source Project's
`UserHandle.getUserId(uid) = uid / PER_USER_RANGE`, where
`PER_USER_RANGE = 100000`. The public `Process.myUid()` API supplies the
current WAEX process UID; `UserHandle.getIdentifier()` is not public SDK
API and is intentionally not used.

Primary Android contract: <https://android.googlesource.com/platform/frameworks/base/+/f9bba13/core/java/android/os/UserHandle.java>

## Acceptance

- The SQL result contains only declared WAEX targets belonging to the
  **enabled WAEX module** and the **current Android user**.
- User 0, work user 10, user 11, disabled modules and unrelated modules are
  independently distinguishable. Equal package names across users do not
  collapse user scope.
- A successfully read empty scope is reachable and empty. A missing DB,
  unknown vendor schema or query error is **unavailable** with no positive
  scope assertion. The reader no longer guesses from arbitrary table text.
- No pre-change package-only shared-preference cache is promoted into an
  active scope result. The process-local cache retains only the latest
  observation and is invalidated on read failure.
- `--mutation-control` must compile a modified reader that incorrectly
  hardcodes user 0 and show that the same suite rejects it.
- When available, `--baseline-ref` must compile the unmodified base reader
  and show that the suite rejects its cross-user positive. A controlled
  baseline failure is evidence for the regression, **not** a passing
  baseline.

## Reproduce

```bash
python3 qa/test_lsposed_scope_reader.py \
  --baseline-ref a0b047ae832546de1ba4fc8f8e6d21cf54750813 \
  --mutation-control --out scope-user-results.json
```

Run with JDK 17+ and a full Kotlin CLI. On the WAEX-Lab Windows host,
pass `--java`, `--javac`, `--kotlinc` and `--kotlin-stdlib` to the existing
JDK 17 and Kotlin 2.0.20 installations. The harness compiles **the actual
production `LsposedScopeReader.kt`**, using strict fake Android services;
it also evaluates the exact SQL query with Python's SQLite engine.

The fake root command only creates a temporary test database marker. It does
not alter LSPosed, installed modules, Android profiles or WhatsApp. The
mocked SQLite layer checks query shape and bind arguments, and rejects
unexpected operations. This is source-level JVM/SQL acceptance, **not** a
live hook callback or verified WhatsApp user-10 effect.

## Live regression boundary

On the recorded Android 14 / API 34 emulator, LSPosed's **real** database
already selected `com.whatsapp` under both `user_id=0` and `user_id=10` for
enabled WAEX. Consequently this fix does not establish the cause of the
reported work-profile feature failure, and does not claim to repair that
runtime symptom. A separately signed and installed candidate, matching
runtime injection report and observable feature effect are still required
for E2E acceptance. Keep account data and visible communications out of
this isolated scope test.
