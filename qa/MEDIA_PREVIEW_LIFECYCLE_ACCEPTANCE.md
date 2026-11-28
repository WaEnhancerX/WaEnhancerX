# Media preview lifecycle verification

This carries the session-lifecycle implementation from PR #90 at `110f70413d91009a13d4354c008047a3da2b9e3f` onto nightly `1a34d225056d92850536193812b4ddc87b05c98e`. Only MediaPreviewHook changes in production; current BaseFeature, HookErrors and host-resource helpers are retained.

## Required framework result

The positive runner requires **21 methods × API 24/28/34 = 63 JUnit executions**, zero failures and a successful process exit. It compiles the complete hook and current lifecycle helpers against real Android, DexKit 2.2 and OkHttp 4.12 APIs. The original 20 methods remain, with a Back-event regression added.

Back must be consumed by the actual Dialog, invoke Android cancel once and close it before cleanup is credited. A deliberately late synthetic response must not attach an image to the closed view or leave a created temporary file. The test checks HTTP cancellation, response closure, no late error, successful reopening and unchanged borrowed-file bytes. The unchanged nightly source violated these Back oracles on all three SDKs; the session implementation satisfied them.

An investigative ACTION_OUTSIDE dispatch was not consumed and did not close the unchanged fullscreen dialog on any of the three SDKs, on either source. Its close-precondition failures are preserved in the experiment record; they are not successful cancellation cases. The source only enables cancelability and does not change the separate outside-touch policy. This does not establish a physical outside gesture or reproduce a reported process crash.

## Reproduction

Use JDK 17+, a complete Kotlin distribution, Python and Maven, or provide existing pinned caches. Every output directory must be fresh.

```sh
python3 qa/android_media_preview_test.py --out /tmp/waex-preview
python3 qa/mutate_media_preview_lifecycle.py --out /tmp/waex-preview-controls --deps /tmp/waex-preview/deps --dexkit-jar /tmp/waex-preview/public-apis/dexkit-2.2.0.jar --http-api-jar /tmp/waex-preview/public-apis/okhttp-4.12.0.jar
```

`--sdk-jars` selects instrumented offline API24/28/34 JARs. The runner verifies the DexKit/OkHttp pins and records source/dependency hashes. The mutation driver retains the existing five deliberate regressions; compilation failures never count as behavioral detections. Historical runs of that driver are not relabeled as current integration results.

## Boundaries

Robolectric supplies dialogs, windows, views and loopers. Transport, host discovery, Xposed dispatch, text/tint helpers and native player behavior are explicit boundaries. Current BaseFeature's unrelated lazy PrivacyPrefs type has a throwing/counting boundary; every case requires zero calls. The host ActivityTracker boundary likewise fails if invoked.

The fixture uses synthetic rows/files and local PNG bytes, not WhatsApp media or chat persistence. No installed APK, private licensing module, physical Android gesture, host selector, encrypted CDN delivery, codec, full Compose UI, ARM64/16 KiB-page compatibility or process-crash acceptance is established. Whole-buffer decryption and full-size image decoding remain inherited limits.

The previous 20 cases, the focused investigation and the final composed runner overlap; do not add their repeats into unique coverage. Required exact-commit public QA remains a separate gate.
