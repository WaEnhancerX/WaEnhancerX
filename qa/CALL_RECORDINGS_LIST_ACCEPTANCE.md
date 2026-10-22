# Call recordings: storage, playback ownership, deletion and sharing acceptance

The recorder writes through `HookProvider.create_call_recording` into
`getExternalFilesDir(null)/CallRecordings`, falling back to
`filesDir/CallRecordings` if the external directory is unavailable. Both are
owned by the module app. Reading these files does not depend on a shared-media
permission. The older `filesDir/call_recordings` directory remains supported.

`CallsHubScreen` now always enumerates those owned locations when the Recordings
tab is selected. Shared legacy directories are included only when the
appropriate storage permission is granted: `READ_EXTERNAL_STORAGE` on API 24–32
or `READ_MEDIA_AUDIO` on API 33+. Denying that optional shared-media access must
not hide the app's own recordings.

The public `Recordings/WAEX_Calls` directory remains supported on older Android
versions. `Environment.DIRECTORY_RECORDINGS` is a non-final field introduced in
API 31; it must not be resolved on older releases. The earlier path convention
uses `getExternalStorageDirectory()/Recordings/WAEX_Calls`. Other retained legacy
locations are `Music/WAEX_Calls` and `Music/WaEnhancer/Recordings`.

Enumeration and file metadata reads run in `Dispatchers.IO`; the completed list
is published on the effect's caller context. Returning to the screen rechecks
the permission and refreshes the list. A cancelled effect must not publish a
stale IO result. Unavailable directories do not remove readable results from
other directories. Both internal directory spellings and the existing external
directory are narrowly declared in `file_paths.xml` for sharing; unrelated
private files are outside those roots.

## Playback and deletion contract

The screen owns each `MediaPlayer` immediately after construction, before the
data source, preparation or start can fail. Stopping detaches that owner and
clears the playing row before invoking native operations. Release is attempted
in `finally`, including when an invalid player state rejects stop. A second or
reentrant stop cannot claim the detached player again. A completion callback
from an earlier player must not stop a replacement player. Screen disposal uses
the same cleanup. These changes preserve synchronous local-file preparation;
they do not claim lower decoder latency or asynchronous playback initialization.

A single-file delete removes its row and reports success only after
`File.delete()` returns `true`. A false result or an exception retains the row
and displays a localized failure message. The bulk-delete path already checks
the result and is unchanged. The playback cleanup and deletion changes do not
alter recording capture, audio-source selection or microphone permission.

## Reproducible framework gate

```sh
python3 qa/android_call_recordings_list_test.py --out /tmp/waex-recordings-list
```

Use JDK 21, Kotlin 2.0.20 and Maven on `PATH` (or `KOTLIN_HOME` and `--maven`).
The output directory must be new or empty. By default the runner resolves the
pinned Robolectric 4.17 framework through the existing
`android_media_preview_test.resolve_framework` and `maven_dependencies` helpers,
downloads Core 1.18.0 from public Google Maven, and lets Robolectric resolve the
instrumented Android SDKs from public Maven Central. The dependency-copy helper
uses its existing bounded retry policy; it never retries compilation or tests.

The Core AAR SHA-256 is
`311d83ac67d394076ec21d12ed2d10a44b59cb2929b7dce00e5a90a93842e37d`;
its single, bounded `classes.jar` must have SHA-256
`d1f5a319a77555df7e23858ffb5ed45a95ebb4263fa1ab7a993510ceb53555a0`.
Both identities are checked before the public API is used. `--deps`,
`--core-jar` and `--sdk-jars` remain optional offline inputs. `--maven-repo`
selects a local cache and `--maven-settings` supports an environment's normal
proxy configuration. No workspace-specific path is required by the runner.

The runner records source and framework dependency hashes and the chosen
bootstrap mode. Offline SDK inputs are also hashed. It uses Kotlin 2.0.20's
bundled coroutine JAR as its QA runtime; this standalone gate does not resolve
or validate the application's dependency graph. When porting the gate to an
older branch, include the unchanged `qa/android_media_preview_test.py` and
`qa/maven_dependencies.py` helpers if they are absent.

The suite executes 28 JUnit methods on APIs 24, 28 and 34, for 84 cases. Its
original 14 storage methods remain intact. The immediate pre-cleanup baseline
(which already contains the storage correction) fails 27 of these 84 cases;
the candidate passes all 84. The failures cover data-source, prepare and start
cleanup; rejected stop; repeated failed preparation; false and throwing delete;
stale completion; and reentrant stop on all three APIs. Success, disposal,
replacement playback, idempotence and release-exception controls also run.

The earlier storage-only comparison remains historical evidence: unchanged V39
screen/XML failed 26 of 42 cases and the first storage candidate passed 42/42.
Ten of those failures were real `NoSuchFieldError` exceptions from the production
load body on Android 24/28. Do not add the historical 42 to the final 84 as if
they were distinct current coverage.

The gate compiles and executes the exact `CallsHubScreen` state/permission,
`loadRecordings`, `LaunchedEffect`, `stopAudio`, audio-disposal, play-click and
delete-click fragments, its complete `RecordedCallItem` declaration, and the
unchanged provider's complete `create_call_recording` branch. Extracted line
ranges, source identities and staged source are retained. Source bodies are
not rewritten for baseline or candidate execution; click callbacks are wrapped
in fixture functions with the same item/playing-state bindings.

Android Context/Core permission checks, Environment APIs, ParcelFileDescriptor,
Bundle, directory enumeration, file contents and FileProvider's canonical path
strategy are real framework/library objects. Permission answers and external
volume availability are controlled through a Context wrapper. Robolectric's
public and general external storage roots are aligned to one fixture volume.
The fixed provider branch writes a small placeholder, not encoded audio.

The native `MediaPlayer` decoder and playback are explicit boundaries. A
Robolectric shadow injects the relevant checked/runtime failures, counts
stop/release attempts and dispatches controlled completion/reentrant callbacks.
Thirty repeated preparation failures per API verify that every attempted player
receives release. A `File` subclass returns false or throws for delete-failure
cases while the underlying fixture file remains on disk; the success case
deletes a real fixture file. Android Toast construction is real and its emitted
text is inspected through Robolectric. These tests establish ownership and
feedback behavior at those boundaries, not native codec resource reclamation.

Compose state, effect dispatch and lifecycle registration are explicit adapters;
the complete Compose screen is not compiled or rendered. Tests invoke the
captured refresh callback explicitly and exercise observer updates/disposal.
Android XML inflation is a boundary: the real declared XML roots feed Core's
unchanged path strategy. The test verifies internal fallback sharing and an
unrelated-private-file rejection without sending an Intent. Provider caller
authorization, Binder, actual permission dialogs, OEM scoped-storage policy,
audio recording/playback and LSPosed are outside this gate.

## Device acceptance

1. With shared-storage permission denied, open Recordings and verify that a
   previously saved module-owned recording remains visible and playable.
2. Grant access to shared audio when legacy recordings are needed. Verify the
   three retained legacy locations, then revoke permission and return. Owned
   recordings must remain visible while legacy entries disappear.
3. On API 24 and 28, open Recordings with storage permission granted. The list
   must load without a missing `DIRECTORY_RECORDINGS` field failure.
4. On a suitable test device, make external app storage unavailable and create a
   recording through the normal workflow. Verify that the internal fallback is
   listed and can be shared, including after external app storage returns.
5. Leave the screen, save another recording, and return. Check that the list
   refreshes and that a selection of a file removed elsewhere is cleared.
6. Open a large recording collection, switch tabs during loading, and navigate
   back. Verify responsiveness and that cancelled scans do not repopulate an
   inactive screen. The fixture measures thread placement, not device frame
   times, latency percentiles or actual storage speed.

7. Play a valid recording, switch to a second one, stop repeatedly, and leave
   the screen. Check that the current row reflects playback and no earlier
   completion stops the current player. Repeat with an invalid or inaccessible
   recording; the error must leave no playing row. Native resource behavior
   still needs device profiling.
8. Delete a writable recording and verify both file and row disappear. Try a
   legacy file that the OS refuses to delete; its row must remain and the
   failure message must appear. A failed delete must never report success.

This change does not establish capture of the remote participant's voice or
restore a 90 MB upload control. Follow `CALL_RECORDING_DEVICE_ACCEPTANCE.md` for
the separate call/audio route acceptance.

## Primary platform references

- [App-specific files](https://developer.android.com/training/data-storage/app-specific)
  documents permission-free access to owned external directories from API 19.
- [Android 13 granular media permissions](https://developer.android.com/about/versions/13/behavior-changes-13)
  documents `READ_MEDIA_AUDIO` for other apps' audio.
- [Environment.DIRECTORY_RECORDINGS](https://developer.android.com/reference/android/os/Environment#DIRECTORY_RECORDINGS)
  documents the field's API 31 introduction.
- [MediaPlayer state and resource management](https://developer.android.com/media/platform/mediaplayer/state-resources)
  explains state-dependent calls and releasing a finished player's resources.
