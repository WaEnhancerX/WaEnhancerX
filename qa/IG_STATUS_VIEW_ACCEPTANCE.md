# Native IG status row and hook lifecycle acceptance

## Current baseline

This change targets `9098c0fbf9e2ec547d0f8ac5181395006d1a2b01`. Main now uses a
native HorizontalScrollView and a host recycler-header controller. The previous
V37 custom-AdapterView collapse fix should not be reapplied to that implementation.

Current refresh methods enqueue one callback per request, remove and recreate
all cells on every callback, and leave pending work and adapter observers alive
when a row is detached. Clearing `IGStatusView`'s adapter does not clear the
inner row. The hook also stores Activity-backed headers in a static strong list.

## Production behavior

- Coalesce pending view refreshes and adapter notifications. A request made while
  detached is represented by the normal refresh on reattachment, not a queued
  runnable. Reset the pending flag before delivery so reentrant changes survive;
  recover when posting is rejected.
- Cancel pending refreshes and unregister the observer on detach, then register
  exactly once and render current data on reattachment. Propagate a null adapter.
- Reuse cells only at compatible positions and view types, from the same adapter
  and resource configuration. Invalidation, a different adapter, a changed view
  type, or a configuration change replaces the affected cells. An IG placeholder
  with no ViewHolder must be replaced before it can become a populated row.
- Restore horizontal scroll after native layout determines the new content
  width. Preserve the current horizontal/vertical parent gesture behavior.
- Register actual injected headers through weak references; remove detached or
  dead entries and register a reattached view again. Copy active recipients out
  of the registry lock before delivering updates.
- Associate recycler-header retries with the scrolling View. Wait for attachment,
  cancel scheduled work on detach, resume if the same view reattaches, reject a
  destroyed/finishing Activity, and release the retry listener on success or
  exhaustion. Keep the maximum of 31 attempts with 100 ms between attempts.

The existing native scroller, synchronous adapter binding, host data extraction,
reflection/DexKit selectors, controller ownership checks, SQL, image loaders and
playback behavior remain in place. This does not restore the rejected asynchronous
full-adapter replacement.

## Framework runner

```sh
python3 qa/android_ig_status_view_test.py --out /absolute/new/output
```

Requirements: Python 3, JDK 17+, Kotlin, Maven and network on the first run. The
runner pins Robolectric 4.17, JUnit 4.13.2 and the Android API dependency. It obtains
DexKit 2.2.0 from its public Maven AAR and verifies both AAR and classes-JAR SHA-256.
`--dexkit`, `--deps` and `--sdk-jars` reuse already verified local dependencies.
Output must be a fresh directory so stale classes cannot affect the result.

The runner compiles the **complete** production IGStatusHook, IGStatusView,
HorizontalListView, IGStatusAdapter, BaseFeature, DexSearchEngine, DexCacheManager
and DexNegativeLookupCache. It uses real Android Views, Activity lifecycle,
measurement/layout, main Looper, ListView headers and touch dispatch through
Robolectric API 24, 28 and 34.

There are **96 executions: 32 scenarios across three SDK configurations**. The
23 view/adapter scenarios cover bursts, rejection/reentrancy, attachment, observer
ownership, null/replacement adapters, compatible reuse, invalidation, view types,
configuration changes, placeholder recovery, scrolling, initial rendering,
gestures and the bounded count reconciliation. Nine hook scenarios execute the
callbacks actually registered by IGStatusHook, including header creation, status
constructor publication, weak registration, detach/reattach, Activity replacement,
cleared-reference pruning and recycler retry ownership.

Xposed registration/dispatch, the host Fragment/controller, contact resolution,
icons, display text and activity tracking are explicit fixtures. The production
cache is seeded with the fixture status-update class. Native DexKit discovery is
unavailable in this environment; only its public API and unavailable-native path
are exercised. The fixture Fragment is an AndroidX-shaped boundary, not the full
AndroidX runtime. Host navigation deliberately fails if a fixture attempts it.

## Results and controls

| Tree/control | Executions | Expected assertion failures |
| --- | ---: | ---: |
| Unchanged main `9098c0f` | 96 | 48 |
| Corrected candidate | 96 | 0 |
| Remove both coalescing guards | 96 | 6 |
| Reuse incompatible view types | 96 | 3 |
| Retain detached registry entries | 96 | 6 |
| Treat an IG placeholder as a populated holder | 96 | 3 |
| Retain and execute a detached refresh | 96 | 3 |

Each baseline/control must compile, run all cases and produce exactly its expected
assertions. Missing tests or compilation/runtime initialization failures are not
accepted. The initial narrower 63-case experiment remains separate evidence and
must not be added to 96 as unique coverage.

```sh
python3 qa/android_ig_status_view_test.py \
  --source-root /path/to/9098c0f-worktree \
  --out /absolute/new/baseline-output \
  --expect-assertion-failures 48
```

The synthetic 10,000-request bursts produce one pending callback per attached
layer. Detached bursts enqueue none. A same-type eight-row refresh reuses all
eight existing cells, while changing one view type creates one replacement.
These are callback/allocation counts, not measured device FPS. Every executed
refresh still performs the current synchronous adapter binding and its IO.

The header wait/retry controls already pass on main under the fixture. They are
positive compatibility controls and lifecycle hardening, not evidence that main
was inserting headers into detached or destroyed real WhatsApp screens.
Weak-reference pruning is tested by explicitly clearing a reference; no claim
about garbage-collector timing or measured heap savings is made.

## Device acceptance and remaining risks

Verify initial home rendering on supported WhatsApp builds, both ListView and
recycler hosts where applicable, horizontal and vertical gestures, rapid updates,
configuration changes, leaving/reopening the screen, Activity recreation and
selected-status playback. Repeat with a large real status list while recording
frame timing and memory before claiming an improvement in device lag or FPS.

The generic host `void(View,Integer)` controller discovery and obfuscated data
selectors remain unchanged and need native/device compatibility evidence. The
shared status list is still published via its existing clear/add sequence, and
the adapter still does synchronous reflection, SQLite and bitmap work. This patch
reduces obsolete work and ownership retention; it does not prove that every
status feature, resource or host schema works on every supported APK.
