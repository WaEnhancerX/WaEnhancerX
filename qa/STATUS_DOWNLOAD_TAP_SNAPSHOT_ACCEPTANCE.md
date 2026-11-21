# Status download: tap-time selection contract

Reviewed against upstream/nightly `69cef264b8f57217964d4c58f75fa15fa2e6b3f2`
(2026-11-19), before applying the candidate. That revision's
`StatusDownloadHook.configureStatusMenuItem` tests the current media when the
user taps, then schedules `downloadCurrentStatus(sourceObject)`. The queued
worker re-resolves the mutable playback fragment, so it can export a later
status rather than the one the user selected. The fix retains the file chosen
at tap time and passes it to the worker; readiness, export and duplicate-save
logic are unchanged.

## Reproduce the baseline and candidate

```sh
python3 qa/test_status_download_tap_snapshot.py \
  --git-ref 69cef264b8f57217964d4c58f75fa15fa2e6b3f2 \
  --expect-reference-failure
python3 qa/test_status_download_tap_snapshot.py
python3 qa/test_database_dialog_status_log_privacy.py
python3 qa/test_changelog_assembly.py
git diff --check
```

The first command compiles and executes the **unchanged production listener**
and worker against explicit host/UI doubles with deterministic task dispatch:
6/8 cases pass, 2/8 fail specifically after navigation. The candidate passes
8/8 cases (22 checks). The production methods are extracted verbatim; a
single mutant reintroduces worker-time lookup in the same eight-case matrix:
the two navigation cases fail, while all six unaffected controls pass.
The cases cover:

- Same page, navigation before/after tap, and two taps queued on distinct pages.
- Text status and unresolved/null status (current no-op behavior retained).
- Unready media: no save and the expected long wait toast.
- Save exception: no successful export, one error toast, and type-only diagnostic.

`qa/test_status_media_export.py` also compiles the production exporters with
explicit Android storage doubles: 31/31 cases and 127 checks pass after adding
the missing `HookErrors` fixture and correcting the stale resource identifier.
Those storage tests are separate from status-selection acceptance.

Recorded environment: Linux x86_64, Python 3.12.14, OpenJDK Runtime 21.0.13.
The runtime includes `jdk.compiler` but lacks the `javac` launcher and the
`ct.sym` needed for `--release 17`; the test uses `java
com.sun.tools.javac.Main -source 17 -target 17` for this isolated fixture.
For the existing export QA, a temporary local launcher adapter uses the same
compiler options. This does **not** establish an official JDK 17, Gradle, APK
or Android build. One unrelated existing fixture, `test_media_preference_sync`,
still cannot compile `WaexPreferenceManager` because its doubles omit
`ExclusivePreferences` and `DiagnosticsRecorder`.

## Runtime acceptance still required

Use a known WAEX APK, WhatsApp variant, Android user, hook framework, and
permissions in the authorized lab. Show two distinguishable synthetic statuses
A and B. Tap **Download** on A, advance to B while the background export is
pending, and verify the gallery contains A and not an unintended B export.
Repeat with A/B taps queued, text status, an incompletely cached media file,
and duplicate-save enabled/disabled. Compare source and saved file content
hashes, MediaStore URI state and failed-row cleanup as appropriate; record APK
hash, process/user and logs. No external status/message events may be sent
without the corresponding explicit authorization.

This JVM fixture does not verify host class resolution, MediaStore/provider
behavior, actual file bytes or path replacement after selection, scheduling
order, cancellation, work profiles, WhatsApp/LSPosed injection or runtime
compatibility. The retained `File` is a path selection, not an open immutable
descriptor. No user data, preference schema or existing export files are
migrated by this patch. Roll back with a normal revert of the candidate commit
if device testing finds a regression; already exported files remain untouched.

Related history: #192 redacted diagnostics without exercising media export;
open #99 changes exporter storage safety, not this tap/worker lookup. The
reverted #75 changed asynchronous IG status rendering; this patch does not
restore that renderer or its queue.
