# Preserved-message persistence acknowledgements

## Contract

The sender calls the manager's `record_preserved_message` provider endpoint.
The endpoint returns an explicit `success` Boolean only after the store reports
the result of its message INSERT/REPLACE. A blank message ID, failed insertion,
or store exception is not an acknowledged copy.

The existing `insertPreservedMessage(...): Unit` entry point remains available
with its void JVM descriptor. `tryInsertPreservedMessage(...): Boolean` supplies
the new result without changing that public entry point. An optional contact-name
refresh follows the INSERT; failure of that refresh does not deny a copy that
SQLite has already inserted.

The sender treats a negative, missing, or legacy empty response as unconfirmed.
A legacy receiver can store a message and still provide no acknowledgement, so
the diagnostic says **not confirmed**, without asserting that the copy was lost.

Recognizing a local selection remains independent of confirming its stored copy.
The recognized-selection result still determines the local Delete-for-me scope
used by AntiRevoke. Storage failure does not change the original invocation,
cancel the delete, or prevent the existing after-callback from clearing the
scope. The discovery predicates and key extraction are unchanged.

## Diagnostic and cost

The Xposed warning is a fixed message with no message text, ID, JID, contact name,
exception message, or stack. It is emitted at most once every 30 elapsed seconds
per process across hook instances. The callback emits one warning decision after
processing its batch. Successful acknowledged copies take no warning path.

This bound applies to the new sender warning. Existing module-side SQLite error
diagnostics remain separate. The change adds no background work, retry loop,
queue, or additional database query. It does not provide a recovery strategy
when storage or the provider is unavailable.

## Framework regression suite

Run from the repository root with JDK, Kotlin CLI, Python 3, Maven, and public
dependency access:

```sh
python3 qa/android_preserved_dispatch_test.py --out /tmp/waex-preserved-dispatch
```

The output directory must be new. The shared Maven resolver keeps the pinned
dependency coordinates, limits dependency-resolution retries, and preserves each
attempt's log. Compilation errors and JUnit failures are not retried by that
dependency helper.

For an offline run, provide the reviewed dependencies, all three instrumented
Android SDK JARs, and the pinned DexKit classes JAR:

```sh
python3 qa/android_preserved_dispatch_test.py \
  --deps /path/to/robolectric-dependencies \
  --sdk-jars /path/to/instrumented-android-jars \
  --dexkit /path/to/dexkit-2.2.0.jar \
  --out /tmp/waex-preserved-dispatch-offline
```

The runner requires **all 84 cases: 28 scenarios on Android API 24, 28, and 34**.
Its ordinary invocation fails on any assertion, compilation failure, missing
case, or initialization failure. The explicit baseline/control option requires
the exact requested assertion-failure count and still requires all 84 cases.

The compiled production code includes the complete sender, provider, store,
BaseFeature, caller/preference/contact policies, declared target set, and DexKit
search/cache support classes. The suite uses Robolectric ContentProvider,
ContentResolver, SQLiteOpenHelper, file-backed SQLite, and Binder/package shadows.
It records source, fixture, dependency, and SDK SHA-256 identities.

### Scenarios

| Area | Checked behavior |
| --- | --- |
| Successful write | Explicit positive acknowledgement; saved text, JID, ID, and timestamp survive closing and reopening the store |
| Rejected write | Blank ID and a trigger-aborted SQLite INSERT return a negative acknowledgement with zero copied rows |
| Auxiliary failure | A trigger-aborted contact-name UPDATE still returns success for the completed INSERT |
| Public API | The void entry point remains callable; the new Boolean API distinguishes a successful and failed insertion |
| Transport | Positive and negative provider results propagate; legacy empty replies and absent providers remain unconfirmed |
| Caller boundary | The complete provider rejects an undeclared UID before writing |
| Diagnostics | Fixed payload-free text, process-wide throttling across instances, another warning after 30 elapsed seconds, and two full captures that acquire the warning monitor in reverse order |
| Batch handling | One confirmed copy remains stored when another insertion fails; the batch issues one warning decision |
| Original execution | Actual callbacks registered by the production class preserve scope, leave the synthetic original delete callable, and clean the scope afterwards |
| SQLite fallback | Complete fallback callback confirms successful copies and diagnoses failed copies while preserving the original delete |
| Scope filters | Empty selections, excluded type 15, and a disabled option preserve their existing behavior |
| Schema rejection | An unknown future version is not relabelled, written, or acknowledged |

## Interpretation and remaining acceptance work

The Xposed dispatcher, host Runnable, selected-message graph, and host SQLite
schema are explicit synthetic fixtures. The package-detector boundary delegates
to the real declared-target policy. Unrelated private licensing and update
endpoints fail the fixture if invoked.

Passing this suite demonstrates the acknowledgement and callback contracts under
those boundaries. It does not demonstrate native DexKit discovery, an actual
WhatsApp/LSPosed session, kernel Binder IPC between processes, a physical disk
failure, or a complete Gradle APK build. Device acceptance should exercise the
existing supported host selection, the manager's vault display, both local-delete
paths, and a temporarily unavailable provider while checking the diagnostic and
the unchanged original delete behavior. No copy recovery is promised when the
provider or storage is unavailable.
