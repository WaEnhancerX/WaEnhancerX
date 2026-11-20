# Backup import: exclusive preferences and package scopes

## Contract

`floating_bottom_bar` and `native_floating_navigation` must not both remain enabled
after their affected settings are restored. The existing restore priority from
`ExclusivePreferences.clearedOnConflict()` keeps the module bar and clears native
navigation; this is deliberately different from interactive toggle ordering.

Restoration must not depend on the package selected in the manager UI. Universal
settings, personal WhatsApp and WhatsApp Business keys are separate package scopes,
not Android users or profiles. A universal edit also affects targets inheriting that
member. Explicit false overrides and fully overridden unrelated targets retain their
meaning. An import unrelated to this pair must not repair old conflicts elsewhere.

The codec resolves conflicts against its public snapshot plus incoming records,
before committing. Added partner writes share the public editor and its touched-key
compensation. The reported count remains accepted input records, not auxiliary
normalization writes. The manager records a successful import and notifies only
after the codec returns; it performs no second, unchecked repair commit.

This does not create an atomic transaction across the public/private files. Existing
best-effort rollback and its explicit failure result remain. Concurrent writes after
the snapshot are not made transactional by this change. The native hook already
guards against a simultaneously enabled module bar; this fix addresses persisted
configuration/UI consistency and false restore success, not proof of a double-hook crash.

## Reproducible host tests

Use JDK 17+, Kotlin CLI and the six SHA-pinned Android 14 JSON sources accepted by
`test_settings_backup_json.py`. The importer fixture compiles the real manager,
codec, schema, store selector and exclusivity policy. Android storage/IO and
diagnostics/restart boundaries are strict doubles. The private compile boundary is
only the four public interface signatures already implemented by the manager; no
licensing implementation or entitlement decision is supplied.

```sh
python qa/test_import_exclusive_preferences.py \
  --json-source-dir /path/to/pinned/android-json-14 \
  --kotlin-home /path/to/kotlinc --out /new/path/candidate

python qa/test_import_exclusive_preferences.py \
  --source-root /path/to/unchanged-base \
  --json-source-dir /path/to/pinned/android-json-14 \
  --kotlin-home /path/to/kotlinc --out /new/path/baseline

python qa/mutate_import_exclusive_preferences.py \
  --json-source-dir /path/to/pinned/android-json-14 \
  --kotlin-home /path/to/kotlinc --out /new/path/negative-controls

python qa/test_scoped_settings_backup.py \
  --json-source-dir /path/to/pinned/android-json-14 \
  --kotlin-home /path/to/kotlinc --out /new/path/scoped-regression

python qa/test_settings_backup_binary_roundtrip.py
python qa/test_backup_alias_policy.py
```

The negative runner supports `--variant skip-normalization`, `skip-inheritance` or
`omit-added-rollback` for transports with short command deadlines. Each variant
must compile, complete all 24 cases, and fail its intended assertion. Tooling errors
or missing results never count as behavioral detection. Preserve `result.json` and
adjacent logs; they record commands, input hashes and durations.

## Executed development evidence

Base: `0aaffab0270a3d0832bef2895f6457f3792fd74a`. Windows 11 build 26200,
Temurin JDK 17.0.20.1, Kotlin 2.0.20, Python 3.12.14.

- Same final 24-case fixture: unchanged base 8 PASS / 16 FAIL; candidate 24 PASS.
- Removing normalization: 8 PASS / 16 intended product failures.
- Removing inherited-target handling: 23 PASS / 1 intended product failure.
- Omitting added partner keys from rollback: 20 PASS / 4 intended product failures.
- Existing scoped suite: 22/22; pinned AOSP JSON suite: 107/107.
- Existing binary suite: 341 exportable settings retain valid key/type/value data;
  alias-policy and whitespace checks also pass. These counts are not unique features.

An initial grouped mutation command exceeded the transport's effective 30-second
deadline during its third compiler invocation. That incomplete run is BLOCKED,
not a successful negative control. No matching child process remained; only the
third variant was rerun into a new directory and completed. Earlier 21-case
development runs are superseded, not additional coverage.

## Device acceptance and recovery

NOT_VERIFIED by these host tests: Android disk durability, SAF UI, installed WAEX,
the selected WhatsApp host version, LSPosed/Vector execution, work-profile behavior
and other Android versions. A separate isolated app executing these exact public
components with real Android preferences is useful additional evidence but is not
a full WAEX/WhatsApp build.

On an authorized test installation, preserve the original settings. Restore a small
fixture with distinct universal/personal/Business values, select a different target,
and verify both stored keys and the UI after manager/process restart. Exercise
commit failure with an appropriate test seam; a memory snapshot alone does not
prove durable rollback. Confirm unrelated settings and profile data remain intact.

Recovery: revert the focused change normally. It makes no startup migration and
does not rewrite existing settings without an import. Reverting code does not undo
already restored values; restore only a verified, correctly scoped recovery copy.
