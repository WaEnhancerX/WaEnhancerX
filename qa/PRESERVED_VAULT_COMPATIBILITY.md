# Vault row identity and database compatibility

## Current baseline and user-visible behavior

This change is based on `9098c0fbf9e2ec547d0f8ac5181395006d1a2b01`.
That main already raises the helper from version 2 to 3 and prefixes the Compose
message key with the sender direction. The earlier V37 explanation of a 3-to-2
open failure is no longer the current baseline.

Two NULL `msg_id` values are nevertheless legal under both historical UNIQUE
constraints. They become the same empty logical ID when read and the same
`fromMe:msgId` list key when displayed. The details screen's LazyColumn therefore
cannot assign a distinct identity to every existing row. The store now exposes
the existing SQLite `_id`, and the production mapper uses that physical row ID as
its display key. Logical message IDs, JIDs, sender direction, and message text
remain unchanged. A stored row keeps its key across reads and reopening; an
existing CONFLICT_REPLACE operation can legitimately create a different row ID.

The current empty `onDowngrade` also admits any future database version and lets
SQLiteOpenHelper relabel it as version 3 without proving compatibility. It is
removed. The helper advances to version 4, which had already been published in
the V37 compatibility candidate, while retaining both known table layouts.

| Existing file | Behavior |
| --- | --- |
| Version 1 | Existing additive `sender_name` migration; preserve rows and sequence |
| Version 2, `UNIQUE(msg_id)` | Keep table, indexes, data, row IDs and sequence |
| Version 3, `UNIQUE(msg_id)` | Keep the current-main layout, including NULL IDs |
| Version 3, `UNIQUE(jid,msg_id,from_me)` | Keep the historical composite constraint |
| Version 4, either known layout | Open without relabeling or rebuilding |
| Unknown future version | Android rejects the downgrade and leaves the file intact |
| Fresh installation | Existing `UNIQUE(msg_id)` creation and insert behavior |

There is no table copy, DROP, index replacement, deduplication or data clear.
This patch does not restore the rejected strict capture selector. The original
local-delete hook remains byte-identical to the current baseline.

Insertion failures retain one module diagnostic with the exception class. The
module no longer includes the exception message or throwable, either of which
can expose SQLite values. This addresses this module log call; it is not a claim
that Android or third-party code never logs database errors.

## Reproducible framework validation

```sh
python3 qa/android_preserved_store_test.py --out /absolute/new/output
```

Requirements: JDK 17+, Kotlin, Maven and first-run network access. Robolectric
4.17, Android API 24/28/34 and JUnit 4.13.2 exercise **33 executions**, consisting
of **11 distinct scenarios on three SDK configurations**. The runner compiles
the complete production store and contact-identity policy. It also extracts the
exact `id` expression from the production Compose mapper and compiles that
expression separately, so the key checks execute current source behavior.

The scenarios cover fresh persistence, the additive v1 migration, unchanged v2
schema/data/sequence, both v3 constraints, both known v4 constraints, append and
replacement isolation in the composite schema, rejected future versions,
distinct NULL/direction keys across reopen, and a failing INSERT whose exception
contains a private sentinel.

Main `9098c0f` executes all 33 cases and fails **18 assertions**. The corrected
candidate passes **33/33**. Negative controls independently restore the logical
list key (six failing assertions), blanket downgrade (three), and throwable
logging (three). These counts repeat scenarios across SDKs; they are not device
test counts. The baseline and mutants must compile, run the full suite and fail
only the exact expected assertions; compilation or framework startup failure
cannot satisfy that check.

```sh
python3 qa/android_preserved_store_test.py \
  --source-root /path/to/9098c0f-worktree \
  --out /absolute/new/baseline-output \
  --expect-assertion-failures 18
```

`--deps` and `--sdk-jars` allow verified local caches and offline Android runtime
resolution. The runner saves source, fixture, dependency and SDK hashes with
compiler/JUnit logs. The CI job must require the complete 33-case result.

## Runtime acceptance and limits

These tests execute Android SQLiteOpenHelper against real SQLite files with
synthetic records. They do not render the full Compose screen or execute host
message capture, WhatsApp, native DexKit or LSPosed. The existing eight-argument
Java model constructor remains available via `@JvmOverloads`; adding the
storage field does not promise full binary compatibility for every generated
Kotlin data-class method.

Before release, upgrade populated v2/v3 installations without clearing app data,
open chats containing nullable or duplicate-direction IDs, capture a new local
Delete for me message, and reopen the manager. Confirm behavior on both known
constraints. The module cannot control the downgrade behavior of an older APK
installed afterward. This change does not establish that all missing captures
or Deleted Stories are fixed.
