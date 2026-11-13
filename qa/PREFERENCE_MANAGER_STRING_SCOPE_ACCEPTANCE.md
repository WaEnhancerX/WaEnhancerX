# Target-scoped string preference reads

## Contract and defect

`WaexPreferenceManager.getString()` first performs a strict typed read. A
legacy non-String value may throw `ClassCastException`; the existing recovery
contract converts that value with `String.valueOf`.

In Per Target App mode, the strict read uses `package:key`, but the old
conversion reads bare `key`. As a result, it returns the universal setting or
the caller's default instead of the selected target's stored value. The same
defect applies to schema-routed private settings, without implying that their
contents leave private storage.

Resolve the key and select its store once for this read. The conversion uses
that same pair even if the selected target or configuration mode changes while
the strict read is in progress. This is not a transaction across arbitrary
preference writers or an Android-user/profile routing change.

Preserve existing semantics:

- Valid strings and missing-value defaults are unchanged, including null.
- An absent scoped key does not gain new universal-value inheritance.
- Universal mode and a null selected target retain their behavior.
- Wrong-typed configuration mode keeps the default-returning behavior.
- A conversion snapshot failure returns the caller's default.
- Other runtime failures from the initial strict read are not newly swallowed.
- Reads do not migrate, write, commit, notify or update restart metadata.

## Reproduction

Use Python 3 and a complete JDK 17 or newer on PATH:

```sh
python3 qa/test_preference_manager_string_scope.py
python3 qa/mutate_preference_manager_string_scope.py --out /tmp/waex-string-scope-mutants
```

The first runner compiles the entire production manager, preference schema and
store selector with `javac --release 17`. The Android preferences, Context,
annotations and the private interface signature remain explicit test doubles.
Unused backup, notification, filesystem and restart operations fail instead of
reporting fabricated success.

For a baseline comparison, run the current fixture with `--source-root` pointing
to an unchanged upstream checkout:

```sh
python3 qa/test_preference_manager_string_scope.py --source-root /path/to/upstream-checkout
```

At upstream `a0b047ae832546de1ba4fc8f8e6d21cf54750813`, all 15 scenarios execute
and 10 fail. The candidate completes the same 15 scenarios with 63 assertions
and zero failures. Failure short-circuiting within a scenario means assertion
totals are not comparable measures of unique coverage.

The two negative variants compile and complete all 15 scenarios:

- Reading the universal key during recovery fails 10 scenarios.
- Re-resolving the target during recovery fails both controlled target/mode
  interleaving scenarios.

A compiler failure, missing dependency or incomplete fixture is not an accepted
negative control. The workflow runs this focused suite before unrelated suites
and preserves mutation logs and input identities.

The existing Media Hub preference regression also passed against the candidate
manager and byte-identical remaining inputs: 167 manager/schema/failure
assertions, the policy checks, structural checks and save-failure resources.

## Limits and manual acceptance

These are JVM checks with strict in-memory doubles, not Android
SharedPreferences disk, Binder/provider, work-profile, UI, full APK, licensing
implementation, or WhatsApp hook acceptance. No performance improvement is
claimed. No preference or backup file is rewritten by the production change.

In an authorized candidate build, inspect a disposable configuration containing
different universal and per-target values. Verify target switching, valid
strings, historical wrongly typed values, null/default behavior and persistence
after process restart. Check Android users/profiles separately from target
packages. Reverting the focused getter and its QA additions restores the prior
behavior; there is no data migration to undo.
