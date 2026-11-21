# SQLite revoke fallback: identity and option scope

## Purpose and verified defects

This change constrains the existing SQLite fallback in `AntiRevokeHook`. It does
not establish the current WhatsApp status schema, and it does not replace the
separate bytecode status-manager contract work.

The reference main commit is
`f6ec7d4e6f47a246ebb413848d66358217fa9c08`. Its real SQLite callbacks compile in
the same fixture as the candidate and fail five independent scenarios:

| Scenario | Main behavior demonstrated by the fixture |
| --- | --- |
| `fallback_key` | With `_id=71` and no `key_id` column, the first SQL binding becomes a deleted-status key. |
| `ownership` | A row with a key but no ownership column is recorded before the callback decides whether deletion may be suppressed. |
| `batch` | An incoming row is recorded before a later self-owned row makes the entire deletion fall through. |
| `placeholder_scope` | Enabling status preservation alone can suppress type-15 inserts in `message` or `messages`. |
| `message_predicate` | The substring `_id=?` also matches `key_id=?`, `chat_row_id=?` and compound predicates, causing a different row to be queried as `_id`. |

These are behavioral failures after successful Java compilation, not assertions
about source text or failures caused by a missing Android toolchain.

## Candidate contract

### Status-table deletions

Only the exact table names `status` and `status_v3` use this fallback. A table
whose name merely contains `status` is not sufficient evidence. The status
option must be enabled. The existing explicit Delete-for-me context returns
before queries or effects, and a null or blank predicate is allowed to continue
as a whole-table operation.

For a scoped delete, the inspection query preserves its predicate and all bound
arguments. It accepts only an unambiguous pair of columns named `key_id` and
`from_me`, compared without case sensitivity. Similar column names, qualified
aliases and duplicate matches are rejected. Each selected row must have a
nonblank SQLite string key and an SQLite integer ownership value exactly equal
to zero. SQL arguments and numeric sender row IDs are never converted into
message keys or sender JIDs.

Every row is validated before any marker, notification or suppression. The
cursor must also close successfully. An empty result, missing schema, unknown
value type, self-owned row, duplicate key, more than 4,096 keys, or query/read/close
failure returns to the original host operation without marker side effects.
This intentionally treats a mixed or partly unknown batch as one unsupported
operation rather than publishing markers for its validated prefix.

For a complete accepted batch, the callback first suppresses the deletion with
the existing integer result `0`, records the verified keys, and requests one
generic notification. No sender was verified, so persistence and notification
receive a null sender. The marker stores are not a transaction: if persistence
or notification throws after suppression, the deletion remains suppressed and
previous marker effects are not rolled back. A fixture explicitly checks this
policy; successful persistence is not implied by successful suppression.

### Message-table deletions and placeholder inserts

The message fallback accepts only a single `_id = ?` equality with one non-null
binding; whitespace is allowed. A `key_id`, `chat_row_id`, multi-row, compound or
unrecognized predicate continues on the host path without a guessed row query.
The inspected message row must be unique, have typed ownership equal to `0` or
`1`, an integer message type, and a nonblank string key.

| Context | Behavior exercised by the fixture |
| --- | --- |
| Only status preservation is enabled; incoming message placeholder | Original insert executes, including when a same-key status marker already exists. |
| Incoming message preservation is enabled; verified incoming row or placeholder | Existing incoming message preservation remains enabled. |
| Own revoke preservation is enabled; verified own row | Original row is retained and its row-ID reservation is recorded. |
| Own placeholder follows that reservation | The reserved `_id` is removed; the original insert executes and returns its real host result. |
| `insertOrThrow` encounters a reserved own placeholder | The existing reservation sanitizer remains in use and the original operation executes. |
| Explicit Delete-for-me context | The database delete is not intercepted and no inspection query runs. |

`sanitizePreservedOwnRowId` and `PreservedOwnRowIdPolicy` are compiled unchanged
alongside the callbacks. This patch does not introduce a new host schema join,
new status detection inside the message table, or a different outbound-revoke
completion result.

## Local verification

Run from the repository root with a JDK 17 compiler available:

```sh
python3 qa/test_revoke_sqlite_contract.py
python3 qa/test_preserved_own_revoke.py
python3 qa/test_hook_return_types.py
python3 qa/test_private_hook_logs.py
```

The new suite compiles verbatim production `hookDatabaseRevocation`,
`sanitizePreservedOwnRowId` and the two new helpers. It provides explicit SQLite
cursor, ContentValues, Xposed dispatch, persistence, notification and
Delete-for-me context doubles; it also compiles the real row-ID policy. The
candidate passes **317 checks**. The source QA workflow requires this suite and
its fixture whenever `AntiRevokeHook.java` exists and executes the suite.

Seven independently compiled mutants are rejected:

1. Taking the key from the SQL binding instead of the selected key column.
2. Allowing null or text ownership to be coerced into an integer.
3. Treating any ownership integer other than `1` as incoming.
4. Publishing an early marker before the complete cursor is validated.
5. Restoring the message predicate substring match.
6. Restoring the status option in both message placeholder interception gates.
7. Restoring the status-table substring match.

The compatibility cases include both exact status table names, complete remote
batches, own and mixed batches in both row orders, query/read/close failures,
marker failure after suppression, unscoped cleanup, malformed and ambiguous
columns, value types, bounded batches, and existing own-revoke reservation
behavior. The reference source can be supplied through `--source`; each
baseline failure is isolated with `--scenario` as listed above.

## Acceptance still required on a supported device

The supplied APK analysis established a separate status-manager and key
contract. It did **not** establish that the current host uses these legacy SQL
tables/columns or any particular delete predicate. The fallback therefore
checks what its runtime cursor actually exposes and leaves unknown forms to
WhatsApp. A passing synthetic cursor is not evidence of a current host schema.

Ownership and identity also do not prove why a database delete occurred. A
filtered expiry/cleanup operation and a remote revoke may select similar rows;
this patch preserves the existing fallback policy for supported rows. The
Delete-for-me fixture covers the observed context flag, not every possible
local or asynchronous host route. Confirm normal expiry, local deletion,
remote deletion, own deletion, and mixed batches on each supported host before
claiming complete Deleted Stories acceptance.

The tests do not exercise an Android SQLite engine, LSPosed execution, the real
marker database, all concurrent transaction orders, cross-chat key collisions,
network acknowledgement, process restart, or notification rendering. The
existing key-only marker identity and separate message insert completion
contracts are not certified by this change. It must not be advertised as a
complete fix for status recovery or pending message sending.


## 18 November 2026: current nightly fixture dependency restoration

On `WaEnhancerX:nightly@0aaffab0270a3d0832bef2895f6457f3792fd74a`,
the standalone runner did **not** reach the 317 behavioral checks: it
extracted the real `isSingleRowIdDelete` method but omitted its production
`ROW_ID_WHERE` regex field, causing a `javac` missing-symbol error.
This is a QA harness defect and does not itself demonstrate a WhatsApp fault.

The follow-up:

- Extracts the **unique, exact production pattern definition** into the compiled
  fixture; if the definition is absent or no longer a supported single-line
  expression, the runner reports this rather than inventing a fixture policy.
- Adds only the `java.util.regex.Pattern` import required by that definition.
- Adds `qa/mutate_revoke_sqlite_predicate.py`: a permissive compiled
  pattern must fail a Java behavioral assertion; removal of the production
  pattern must fail the runner's source-identity check. Compile errors do not
  count as a successfully rejected behavioral mutant.
- Does not change `AntiRevokeHook`, WhatsApp data, native modules, or
  production hook behavior.

Windows 11 / Python 3.12.14 / JDK 17.0.20.1, from the isolated
`test/revoke-sqlite-pattern-dependency-20261118` worktree:

```sh
python qa/test_revoke_sqlite_contract.py --scenario all
python qa/mutate_revoke_sqlite_predicate.py
```

Current nightly before correction: **FAIL during compilation** (missing
`ROW_ID_WHERE`). Candidate: **PASS 317/317 internal checks**; both
negative controls correctly rejected (one by compiled behavior, one by
missing-source diagnostic). These are QA checks, not Android SQLite, incoming
delete, expiry/admin-delete or message-reception acceptance. In particular,
[issue #164](https://github.com/WaEnhancerX/WaEnhancerX/issues/164)
remains a live, unproven-in-lab report about erroneous delete popups:
the current SQL callback cannot distinguish all delete causes from ownership
and row ID alone. Keep that product investigation separate from this fixture fix.

Additional regression execution on the same Windows/JDK17/Python3.12 setup:
`qa/test_preserved_own_revoke.py` **PASS** (reservation/placeholder policy);
`qa/test_hook_return_types.py` **PASS** (12 checks).
`qa/test_private_hook_logs.py` **FAIL on both unchanged upstream base and
candidate**, at `BlueOnReplyHook.java` (expected 8 diagnostic callsites,
observed 13). This existing unrelated QA failure was neither hidden nor
modified by the fixture change. Do not describe the combined suite as green.
