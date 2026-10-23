# Home header restart acceptance

## Scope and observed failure

The baseline `HomeScreenHeaderActionsHook` set a process-wide boolean before
calling `decor.postDelayed(..., 350L)`. It ignored a rejected post and never
released that boolean. The callback retained the Activity and did not check
whether its window had detached or the Activity had been destroyed.

The five positive confirmation callbacks for Ghost on/off, Freeze on/off and
DND also called `apply()` before requesting that restart. The actual
`PreferenceBridgeClient` queues `apply()` on its I/O executor. A 350 ms delay
does not acknowledge that write. An empty bridge `commit()` is not a substitute:
the bridge returns immediately for an empty edit.

The change commits the actual nonempty edit on a bounded background executor
and posts the restart only after that edit reports success. This uses the
existing bridge and provider acknowledgement path. It does not change the
provider, preference keys, DND precedence, host hook discovery, restart endpoint
or module activation logic.

## Resulting behavior

- A manual restart is deferred to a main-thread callback without writing any
  preferences. Repeated callbacks while that request is pending are deduplicated.
- Ghost, Freeze and DND restart only after their selected value is acknowledged.
  Rejected writes show the existing save error and allow the action to be retried.
- A different confirmation while a request is pending is explicitly rejected
  with a localized message stating that this second change was not saved. It
  can be retried afterward. It is not silently discarded or added to a queue.
- Requests hold weak references to the Activity and decor. Detach cancels the
  pending restart; the callback also rejects destroyed or finishing Activities.
  An old completion cannot release a newer request's ownership.
- Rejected or throwing callback posts release ownership. Cleanup-post failures
  cannot hide the original scheduling failure. Restart endpoint exceptions also
  release ownership and show an error when a usable Activity remains.
- The writer has one worker and one queue slot; cancelled tasks are purged.
  There is no added persistence timeout or assumption that a delay proves success.

Cancellation is **not a transactional rollback**. An authorized edit already
submitted to the provider may still persist after the Activity is destroyed.
Cancelling its request prevents stale restart/UI work and releases ownership;
it does not promise to undo a remote write. An unresolved provider call is also
not treated as success. If the main looper rejects a worker's result and its
cleanup post, the code releases ownership and logs the failure; no UI delivery
can be promised on that unavailable queue.

## Framework regression runner

```bash
python3 qa/android_home_restart_test.py \
  --out /tmp/waex-home-restart-framework \
  --maven-repo /tmp/waex-home-restart-m2
```

Use a new output directory. JDK 17 or later, `javac`, the Kotlin compiler and
Maven must be available. The runner pins Robolectric 4.17, JUnit 4.13.2 and
Android compilation classes from `android-all:14-robolectric-10818077`.
It resolves its declared Maven dependencies and extracts AAR class JARs when
`--deps` is omitted. The local paired audit reused previously resolved JARs and
offline SDKs; it did not repeat clean dependency resolution for this runner.

For an existing dependency and SDK cache:

```bash
python3 qa/android_home_restart_test.py \
  --out /tmp/waex-home-restart-framework-offline \
  --deps /path/to/resolved/jars \
  --sdk-jars /path/to/flat/android-all-instrumented-directory
```

The complete, unmodified production files copied from `--source-root` are
`HomeScreenHeaderActionsHook`, Kotlin `BaseFeature`, `PreferenceBridgeClient`,
`DndPreferencePolicy` and `ArchivedChatsPreferencePolicy`. The test invokes
the real confirmation callbacks and uses the real bridge with a controlled
ContentProvider that can hold or reject its acknowledgement.

The 19 scenarios execute on API 24, 28 and 34, requiring exactly **57 cases**:
manual defer/deduplication; rejected decor post; destroyed, detached and already
destroyed targets; a Ghost write held beyond 350 ms; rejected and throwing main
posts; rejected write and retry; Ghost off, Freeze on/off and DND on/off writes;
a different pending choice and retry; restart endpoint exception; reentrant
menu callback; destruction during a blocked write; and a secondary cleanup
post exception.

Android Activity, window/decor, View, Handler and ContentResolver operations
use the framework under Robolectric. Two declared fault shadows reject or
throw selected callback posts and otherwise delegate to framework methods.
Xposed/DexKit lookup and installation, sheet presentation, menu icons, resource
lookup/localization and the process termination endpoint are explicit external
fixtures. Lookup/installation paths are not executed. The restarter records
weak Activity references and never launches or kills a process.

The runner records source, test, dependency, SDK and external-fixture hashes.
For paired runs, display fixtures come from the runner's checkout, even when
`--source-root` points to an older baseline, so their bytes remain identical.
Actual resource compilation and locale parity are checked separately by
`qa/test_es419_localization.py` and `qa/verify_es419_resources.py`.

The exact baseline `1c6dae5e0bbaa7980a0e3dc3fecbb7773016b7eb` executes all 57
cases and fails 51 assertions: 45 `AssertionError` and six JUnit
`ComparisonFailure` instances. The candidate passes all 57 using the same
test and external fixtures. These counts describe scenarios across three
APIs, not 51 independent user defects. A separate negative control on the
candidate before the cleanup catch fails only that scenario on all three APIs.

`--expect-assertion-failures 51` accepts only that complete known baseline
result. The default requires 57 successful cases; zero tests, compilation
errors, runtime exceptions and a mismatched failure count are rejected.

## Device acceptance still required

On an authorized Android/LSPosed device, check each confirmation from each
visible header/quick-menu entry, both toggle directions, a rapid second choice,
write failure/retry, navigation or destruction while saving, and the manual
restart button. Reopen both manager and host to inspect the selected values,
then verify the actual privacy/DND behavior and injection status separately.

These framework results do not establish host APK compatibility, process
relaunch success, injection verification, every setting's persistence, issue
#48's device-specific cause, heap retention measurements or a performance gain.
No application was restarted or killed by this test runner.
