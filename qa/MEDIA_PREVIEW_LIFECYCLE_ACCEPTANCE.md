# Media preview lifecycle acceptance

This change keeps one active preview per `MediaPreviewHook` instance. A new open
replaces the previous preview, and dismissing or detaching its Android window
cancels that session's HTTP call, queued work and UI callbacks. A session owns
only the temporary file that it created; an existing WhatsApp media file is never
deleted by preview cleanup. The shared executor keeps one worker and at most one
pending task, so rapid taps cannot accumulate an unbounded queue of old previews.

## Automated checks

`android_media_preview_test.py` compiles the complete production
`MediaPreviewHook.kt`, `BaseFeature.kt` and `HKDFv3.kt`; it does not extract a
replacement implementation. It first compiles them against actual Android,
DexKit 2.2 and OkHttp 4.12 APIs. It then compiles the same production sources with
the explicitly named runtime fixtures and exercises Android dialog, view,
window-detach and looper behavior through Robolectric 4.17 on API 24, 28 and 34.

The 12 scenarios run on each of the three SDKs:

1. Hold the worker and issue 100 rapid opens: retain at most one pending task,
   then download and show exactly the newest request after the worker is freed.
2. Replace a preview before its metadata callback reaches the main looper:
   display only the newest dialog.
3. Dismiss during a blocked HTTP request: cancel its call and remove its owned
   temporary file.
4. Dismiss while `newCall()` is still returning: cancel that call before execute.
5. Detach the actual dialog decor from Android's window manager: cancel the
   request even before a dialog-dismiss callback is delivered.
6. Close while a local video result is already queued for the main looper:
   prevent the result from attaching a new `VideoView` to the closed dialog.
7. Release a deliberately non-cooperative, failing old request after opening a
   replacement: preserve the replacement dialog and suppress the old error toast.
8. Complete two downloads and dismiss the old dialog: preserve the replacement's
   own temporary file and visible dialog.
9. Open an existing local media file and dismiss: retain the original file.
10. Replace a video preview and advance the Android looper by three seconds:
    stop the old video and perform no more updates on it.
11. Invoke a previously captured prepared callback after dismiss:
    prevent cancelled playback from restarting.
12. Prepare both dialogs before starting a failing old download:
    keep the already prepared replacement visible after the old error arrives.

The fixture uses latches for controlled transport races and worker barriers for
completion. It does not use throughput or timing thresholds as proof of device
performance. The progress cadence remains 100 ms and the active video update
cadence remains 500 ms; cancelled sessions stop posting those updates.

## Running

On a clean machine, use JDK 17 or later, Kotlin 2.0.20 on `PATH`, Maven, and public
network access:

```sh
python3 qa/android_media_preview_test.py --out /tmp/waex-media-preview-check
```

The runner derives `KOTLIN_HOME` from the real `kotlinc` executable when the
variable is absent. It resolves Robolectric 4.17, JUnit 4.13.2 and
`android-all:14-robolectric-10818077` with Maven's dependency plugin 3.8.1, then
extracts the AndroidX test AAR classes needed by the framework. Robolectric's
instrumented SDKs resolve from public Maven Central when `--sdk-jars` is absent.
`--maven`, `--maven-repo` and `--maven-settings` allow a Maven executable, reusable
cache or proxy settings to be supplied.

DexKit 2.2 and OkHttp 4.12.0 are downloaded separately from Maven Central. The
runner checks fixed SHA-256 values before using either API. The DexKit AAR must
also match its archive hash and contain exactly one bounded `classes.jar`; no
native libraries are extracted. Downloads have a 30-second timeout and byte
limits. A provided API path must satisfy the same pin and is never silently
replaced after a mismatch.

| Artifact | SHA-256 |
| --- | --- |
| DexKit 2.2.0 AAR | `cf4488da9f721750ce5ff4311e88356cbfcb1d4444c2b519b12d8bdb5f33f2c6` |
| DexKit 2.2.0 classes JAR | `195979d9576ba6d68a1c60bdd3e38cd3aed068d4e0c5c6c99caa50bc25fcb9f1` |
| OkHttp 4.12.0 JAR | `b1050081b14bb7a3a7e55a4d3ef01b5dcfabc453b4573a4fc019767191d5f4e0` |

For fully offline execution, provide these cached public dependencies:

- A flat directory of Robolectric 4.17 and JUnit 4.13.2 dependencies, including
  `android-all:14-robolectric-10818077`; extract `classes.jar` from the AndroidX
  test AARs into distinct files in this directory.
- The DexKit 2.2 API classes JAR and the unmodified OkHttp 4.12.0 JAR.
- A flat directory of Robolectric's instrumented Android SDK JARs for API 24, 28
  and 34. The runner enables Robolectric offline mode to use this directory.

The status-view framework runner's dependency directory can be reused when that
suite is also available. These are QA dependencies and do not replace any app
Gradle dependencies or the private licensing module.

```sh
python3 qa/android_media_preview_test.py \
  --out /tmp/waex-media-preview-check \
  --deps /path/to/robolectric-dependencies \
  --dexkit-jar /path/to/dexkit-2.2.0.jar \
  --http-api-jar /path/to/okhttp-4.12.0.jar \
  --sdk-jars /path/to/android-sdk-jars
```

Use a new output directory for each run. The runner records exact source and
dependency hashes, compiler output and the full JUnit log. A failed compilation,
missing dependency or unexpected JUnit result must fail the check.
The framework JVM has a 1 GiB heap limit; this is a QA resource bound and is not
a measurement of the app's memory use.

## Scope and remaining device acceptance

Android views, windows and loopers are framework implementations. The runtime
transport is a deterministic double, and the media-player decoder/audio path is
an explicit shadow. Message database access, Xposed registration, host reflection,
text lookup and drawable compatibility are named test boundaries. The complete
hook also compiles against real OkHttp and DexKit APIs, but these tests do not
execute an actual WhatsApp process, LSPosed injection, a CDN transfer, native
DexKit searches or a device media decoder.

The hook resolver, entry buttons, message selection, database SQL, CDN selection,
media-key normalization, HKDF and AES/decryption algorithm are preserved. The
change adds a cancellation check after metadata lookup without changing its
results. All three current entry paths call `startPreview` from Android click
listeners; the active-session transition and dialog callbacks run on the main
looper. Worker-to-UI delivery checks session identity both before posting and
when the callback executes.

On a supported device, check local and downloaded images/videos, cancel in the
middle of a real transfer, open another item rapidly, leave the host activity,
and verify playback controls, file cleanup and a later successful reopen. Cover
both newsletter and encrypted message media. Keep the existing cryptographic
format and host resolvers under separate acceptance.

Encrypted downloads still buffer the complete ciphertext before decryption and
image decoding can still allocate a full bitmap. Those memory limits are outside
this lifecycle change. A database lookup or decoder that ignores interruption may
still delay the single worker; pending preview work is bounded, and cancelled
results are suppressed, but this is not a promise that every host operation can
be interrupted immediately. These tests establish lifecycle behavior and bounded
pending work; they do not establish an APK build, host compatibility, device FPS
or a percentage improvement in speed.
