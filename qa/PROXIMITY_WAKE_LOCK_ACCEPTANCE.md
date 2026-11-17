# Proximity wake-lock acceptance

## Scope and observed result

The candidate prevents proximity-driven screen-off during the tested WhatsApp voice-note playback sequence by adapting the application's proximity wake lock to a partial wake lock at acquisition. The existing selective sensor-event behavior is retained.

This is **scoped acceptance of the public hook in a real, registered WhatsApp process**, using a private standalone laboratory module. It is not acceptance of a complete WAEX APK, its settings/provider delivery, private features, every Android version, or every WhatsApp build. No APK is distributed with this change.

## Exact tested inputs

Source baseline: WaEnhancerX/WaEnhancerX commit **0aaffab0270a3d0832bef2895f6457f3792fd74a**. At execution, the candidate consisted of that baseline plus the changed public hook and new policy identified below. A later Git commit may package the same bytes; test reuse requires verifying those input identities.

| Input | SHA-256 |
| --- | --- |
| Modified ProximitySensorHook.java | 98bc33bf5dc94381aabdf842c4895d723537a90f07b9a154149f1bdf8c1dc3f2 |
| New ProximityWakeLockPolicy.java | b3e1c0e5c227a5093db4d682fd9c877b95072b1b9370adedb0ee2e8055686ba1 |
| Unchanged ProximityEventPolicy.java | 2022407559e1d3e2331510117da3f10f67ba7ae0ce63b469483cbb4ca87fac9e |
| Unchanged BaseFeature.kt | 14e854b61cec4b471eecc589eb0130dda3c95ebadb4186f64d78af9915cd8f6f |
| Unchanged HookRegistrar.java | cc5db21b21b29c5ad3f5b13b88435fffcd04e1ba4d074a89e18585d691e9624d |
| Unchanged ViewHookDispatcher.java | a4f3a6c991e9f0b3ec08e8d4ddd0056271e1c5a0d947d5602a84c88bc52401b8 |
| Unchanged HookErrors.java | 87b44652dcae5dde74f3fffe64c970659771f0bafa508eb7b7a8c54a5e9d3bfc |
| Seven-source closure recorded by the lab | 0f298aa59fd05503b302a9c4946528fe3ebfcaeb1550e74bbdb4af50500aa399 |
| TestProximityWakeLockPolicy.java | 9eb5314b205b0ae1eb3ca51ef0a6fec05066189c59ee13a2cb5e03bd66bb084f |
| test_proximity_wake_lock_policy.py | fc9ba6bce94eca70521638f8500dc8dd56c7121c5c2e7d12e527cbb229aacc8e |
| Corrected test_proximity_ui_wiring.py | 76dfab21d0b5911608b61f35c883d032823f9a681a573959076a86fbd0f818b8 |

The private test APK had SHA-256 **4966507aae3fbd9c7c55ec0c0e8f02e5072307b78dd0ea5a97718b554ceff8cf**. This digest identifies the executed laboratory artifact; it is not a download or a claim that a complete WAEX APK was built. The standalone controller supplied isolated test preferences and explicit hook installation while executing the seven public source files above. Full FeatureRegistry, the WAEX settings UI/provider bridge and private feature implementations were outside that artifact.

### Environment

| Component | Tested value |
| --- | --- |
| Host application | WhatsApp Messenger, com.whatsapp |
| WhatsApp version | 2.26.43.76, versionCode 264307627 |
| WhatsApp base APK | SHA-256 a1b3b262a86c8ed928bce8b777da69a2324607356bd03d814396a66bbc27da55 |
| Android | AOSP Android 14, API 34, x86_64 emulator |
| Native translation | libndk_translation.so available; this does not constitute a physical ARM64 test |
| Hook framework | Vector 2.2, build 3080 |
| Module compile API | Xposed API 82 |
| Build tools | Android SDK 36 / Build Tools 36.0.0 |
| JVM/Kotlin build inputs | Temurin 17.0.20.1+1; Kotlin 2.0.20 |
| Application scope | Main com.whatsapp process in the personal Android user, with an existing registered account |

Runtime observations were executed and recorded by the laboratory operator on 2026-11-17. The independent design reviewer examined the source and acceptance boundaries; that review is not an additional device execution.

## Results and what each result establishes

| Layer | Result | Scope |
| --- | --- | --- |
| Production policy + Java fixture through the new portable runner | PASS; 0.712 s on the real Temurin 17 environment | Integer-level selection, flag preservation/refusal, restoration policy and per-acquisition option policy |
| Four deliberately incorrect policy variants | Compiled and rejected by behavioral assertions | Negative controls for removing high flags, converting other levels, restoring the whole integer and admitting prohibited flags |
| Existing sensor-policy and exception-privacy checks | PASS on the correctly configured JDK environment | Their source/JVM contracts; not a complete application build |
| UI alias regression with its corrected fixture | PASS | Checks the same required alias through the current centralized PreferenceKeyAliases implementation; the old inline-alias assertion is not reported as passing unchanged |
| Sensor observation suite in the injected process | PASS; six cases | Real sensor observations within that suite; not a six-device compatibility matrix |
| Voice-note OFF → ON → OFF comparison | PASS; one three-phase sequence, 27.644 s | Visible screen/power-state behavior and active playback observations on the exact host/environment |
| Wake-lock lifecycle suite | PASS; seven cases in 494 ms | Six cases using real platform wake-lock behavior and one explicitly synthetic throwable control |
| Early pause during active playback | PASS; pause at 0.700 s | Fresh accessibility-node action; active playback and its PARTIAL lock stopped before the three-second sample completed |
| Ordinary screen sleep/wake, OFF and ON | PASS; two cases, 19.583 s overall | Completed display transitions observed within a strict three-second observation deadline per transition; no proximity-driven screen-off in either case |
| Lifecycle cleanup | PASS | Preferences restored, no forced lock cleanup required, and no owned test locks remained in system_server |
| Full WAEX integration | NOT_VERIFIED | Settings/provider delivery, full module lifecycle and interactions with all other features remain separate |
| Physical devices, work profile, WhatsApp Business, calls, other Android/WhatsApp versions | NOT_VERIFIED | No compatibility is inferred from the tested emulator/host combination |

These rows must not be added into a single inflated test total: sensor cases, lifecycle cases, Java assertions, mutation controls and phases of one playback sequence are different units.

### Real voice-note comparison

With the option OFF, the observed WhatsApp wake lock was PROXIMITY_SCREEN_OFF_WAKE_LOCK; the screen turned off on NEAR and the audio usage was USAGE_VOICE_COMMUNICATION.

With the option ON, the observed wake lock was PARTIAL_WAKE_LOCK; the screen remained on, the display did not report proximity-driven screen-off, and an active AudioTrack was observed with USAGE_UNKNOWN.

Returning the option to OFF restored the reference behavior. Each phase's idle observation showed no retained application proximity/partial lock from playback and no active audio. AudioTrack activity and usage metadata establish those observations; they are not an acoustic-quality or speaker-fidelity measurement.

Early pause was checked using the current Play/Pause control rather than its pre-playback position, which moves when playback starts. The action occurred at 0.700 s; the following observation showed no active audio, no retained playback wake lock and the Play control restored. This is a pause result, not coverage of every exit or cancellation path.

Ordinary screen sleep and wake were tested during actual playback with the option OFF and ON. Both phases reached display OFF and then ON within a strict three-second observation deadline for each transition. Recorded sleep/wake observation intervals were 0.667/0.247 s for the reference and 1.586/0.342 s for the candidate. These are single instrumented transition observations, not a latency comparison or performance claim. An earlier fixed-delay observation caught the display's pending fade-to-off state; it was retained as an inconclusive transition sample and replaced by bounded completion polling. A preliminary polling runner could exceed its nominal deadline during a capture; the final runner constrained each subprocess to the remaining deadline and verified completion within it.

### Seven lifecycle cases

1. An object created with the option OFF is reused through OFF → ON → OFF acquisitions with complete releases between modes.
2. An object created with the option ON is acquired after switching OFF; creation does not permanently freeze the converted level.
3. Two reference-counted acquisitions require two releases; the first release retains the second reference.
4. A non-reference-counted object is acquired twice and released once; real held state clears.
5. A dedicated, pre-original synthetic throwable is propagated by identity, and the temporary flags are restored. This is not an induced system-server/Binder failure.
6. acquire(timeout) holds and is released by the real platform timer, with local flags restored before the caller resumes. The explicit release after timeout is validated for the API 27+ counter model used by Android 14.
7. release(RELEASE_FLAG_WAIT_FOR_NO_PROXIMITY) preserves the real lifecycle and local flags in the fresh FAR setup.

The observer reads flags at entry to acquireLocked. It does not count Binder transactions: an additional reference-counted acquire can enter this method without sending a new acquisition to system_server. The WAIT case validates lifecycle in the declared setup; it is not a test of waking an already proximity-dark display while the sensor remains NEAR.

## Behavioral contract and design rationale

The hook is limited to the injected target application. It verifies a private zero-argument void acquireLocked method, a mutable integer flags field, the final Binder token, the expected owner package and the token monitor already held by the caller.

For a supported proximity acquisition while either existing preference alias is enabled:

- Replace only the wake-lock level with PARTIAL.
- Preserve supported high flags.
- Decline conversion if ACQUIRE_CAUSES_WAKEUP or ON_AFTER_RELEASE is present; Android documents those combinations as incompatible with PARTIAL.
- Execute the original acquisition, references, held state, Binder call, timer scheduling and release behavior.
- Restore the original level in the after callback on success or throwable, retaining high bits changed during the call.
- Preserve a different level installed by another interceptor rather than overwriting it.
- Log fixed diagnostic text and exception class only; do not include exception messages or application content.

No new preference or data migration is introduced. The existing disable_sensor_proximity and proximity_audios behavior is preserved.

### Applying a toggle to an already-held object

Changing the option does not migrate a lock already retained by system_server. For a reference-counted object, another acquire may only increase counters. A complete release followed by a new acquisition, or the existing application-restart procedure, gives a defined boundary for the new mode.

A non-reference-counted acquire can send Binder again while held, so its next acquisition can update the existing token's properties. The implementation does not force that operation or change the application's reference-counting choice.

Partial wake locks create a CPU demand that proximity locks do not represent by themselves. Correct release is therefore part of acceptance. No battery, frame-rate or startup-performance improvement is claimed.

### Restoration and vendor boundaries

AOSP's logging-importance setter can update a high flag without the token monitor. Restoring only the level avoids overwriting an observed high-bit change. It is not a guarantee of atomicity against arbitrary unsynchronized writers or other modules.

Missing/incompatible fields, methods, owner or monitor leave the original behavior intact with a bounded diagnostic. A reflection failure after the first write is diagnosed; restoration is not claimed to be infallible. OEM changes and other module interactions require their own acceptance.

## History and primary sources

[PR #34](https://github.com/WaEnhancerX/WaEnhancerX/pull/34), merged at 12253167a3acc80ac320d43764d09c5af4810394, corrected sensor-type isolation and the visible preference key. It expressly left open the possibility that WhatsApp used another screen-off mechanism. [Issue #31](https://github.com/WaEnhancerX/WaEnhancerX/issues/31) remains the related report, and its original physical-device configuration has not been tested here.

The earlier [Tttim0o proposal e770a4e42c934be707fd9d96a26802d3010c9c58](https://github.com/Tttim0o/WaEnhancerX/commit/e770a4e42c934be707fd9d96a26802d3010c9c58) already identified the system proximity wake lock and proposed conversion at newWakeLock creation. That precedent is credited. The present candidate changes the acquisition boundary to cover existing/reused objects, restores the client level, preserves high-bit metadata, refuses unsupported PARTIAL combinations and adds real-process evidence. No associated proximity PR, review rejection or reversion was found through the checked commit/issue endpoints; this is not a claim of prior maintainer approval.

The detailed Android 14 contract was checked against:

- [PowerManager.java at android-14.0.0_r1](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-14.0.0_r1/core/java/android/os/PowerManager.java), blob d1c10fa46aaa1c3f01a35d33d55ca228d00320f2: acquire overloads, counters, timeout release and flag compatibility.
- [PowerManagerService.java at the same tag](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-14.0.0_r1/services/core/java/com/android/server/power/PowerManagerService.java), blob d82f7a56a8300345c5f0e7bdbd96889af7718593: per-token acquisition/release and proximity requests.
- [DisplayPowerController.java](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-14.0.0_r1/services/core/java/com/android/server/display/DisplayPowerController.java), blob 9d31572c7d763e27e902036c8fadc9bf1672c5ff, and [DisplayPowerProximityStateController.java](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-14.0.0_r1/services/core/java/com/android/server/display/DisplayPowerProximityStateController.java), blob c0747867b620345c063bb2810799cf05a46afbba: WAIT can maintain an already proximity-dark display until FAR.

The acquisition shape was also inspected in AOSP release sources covering API 24–36. That is static contract evidence, not runtime coverage. In particular, API 24–26 use an older single-counter model, so the API 27+ post-timeout explicit-release expectation must not be copied to them.

## Reproduce repository QA

Use an existing complete JDK 17 or newer and Python 3. Set JAVA_HOME if the desired JDK is not the one on PATH. The new runner uses JAVA_HOME as a complete java/javac pair, otherwise PATH, and reports the selected executables, versions, input hashes, commands and elapsed time.

From the repository root:

    python qa/test_proximity_wake_lock_policy.py

The runner compiles the unchanged production ProximityWakeLockPolicy.java together with qa/TestProximityWakeLockPolicy.java using --release 17 and UTF-8, then executes its main method in a temporary class directory. Compilation and execution have explicit timeouts. Source changes during the run invalidate the result. Exit codes are 0 for PASS, 1 for a failed compilation/fixture and 2 for unavailable tooling or an incomplete timed-out operation. The output states the Android/Xposed/lifecycle exclusions.

Retain the existing adjacent regressions:

    python qa/test_proximity_event_isolation.py
    python qa/test_proximity_ui_wiring.py

The event-isolation runner invokes java/javac through PATH; setting JAVA_HOME alone does not change that command lookup. Put the selected JDK's bin directory on the current process PATH before running it. The UI wiring checker is a static source check and uses the corrected fixture identified above. That fixture follows the existing miscellaneous_enhancements → disable_sensor_proximity mapping into PreferenceKeyAliases and checks that both catalog lookup paths delegate to it. It preserves the mapping requirement; it does not change production aliases or relax the expected destination. Include this fixture correction when reproducing the reported UI result.

A complete WAEX build uses the project's declared dependencies and build procedure. The standalone laboratory artifact does not establish that such a full build succeeded.

## Repeat manual device acceptance

1. Record the exact module commit/source hashes, compiled APK digest, host package and all APK identities, Android/API/ABI, framework build, user/profile and effective process. Verify the intended package/user has module scope and injection. A shared version name is insufficient.
2. Use an authorized account and a reproducible local/test voice note. Begin with FAR, playback idle, normal interactive screen state and no retained lock from the previous phase. Record relevant screen, power and audio state privately; avoid publishing raw account or conversation data.
3. With the option OFF and the required application restart completed, start playback and apply NEAR/FAR. Establish the actual screen-off reference and active playback.
4. Stop playback, return to FAR and verify the lock and active audio are gone. Enable the candidate, apply the same restart boundary, repeat the same sample and NEAR/FAR sequence. Observe both the visible display and proximity-related power state. A hook log alone is insufficient.
5. Return to OFF and repeat the reference. Check manual stop, completion, exit, power-button screen-off and return. Confirm the candidate does not force the display on against ordinary user controls.
6. Exercise real lock reuse, reference counting, non-reference-counted acquisition, timeout, throwable restoration and release flags with disposable probe objects if a test harness is available. Separate synthetic exception injection from platform operations. Release only owned probe locks and restore the test preferences.
7. Treat a stale WAIT-for-FAR display state separately: normalize FAR before a fresh trial, then investigate any transition from a previously held PROX lock as its own case. Do not clear release flags globally to make a screen test pass.
8. Keep each device/version/profile result separate. Repeat on a physical device, calls, work profile, WhatsApp Business and additional WhatsApp/Android versions before changing their NOT_VERIFIED status.

Useful read-only diagnostics for an explicitly selected device include:

    adb -s <serial> shell getprop ro.build.version.release
    adb -s <serial> shell getprop ro.build.version.sdk
    adb -s <serial> shell getprop ro.product.cpu.abi
    adb -s <serial> shell dumpsys package com.whatsapp
    adb -s <serial> shell dumpsys power
    adb -s <serial> shell dumpsys display
    adb -s <serial> shell dumpsys audio

These commands provide context and observations; none alone proves hook execution, acoustic output or compatibility. Minimize/sanitize records before sharing them.

## Recovery and remaining acceptance

Disable the existing option and restart the target application to end the old acquisition context. Restore the previous module revision through a normal reversible change if necessary. Do not delete application data or alter shared sensor/power services. Any disposable probe locks and observers must be cleaned up, and their test preferences read back after restoration.

The tested result covers voice-note screen/power behavior and the declared lifecycle cases on one Android/WhatsApp combination. Full WAEX settings/provider integration, work-profile isolation, calls, physical devices, other host versions, vendor frameworks, unrelated feature regressions and measured power/performance remain NOT_VERIFIED.

During post-test recovery, after the standalone laboratory module had been disabled, one fresh WhatsApp start terminated with a native SIGABRT inside the unchanged installed WAEX 2.0.6 libdexkit.so. A subsequent start with the same module state succeeded. The crash, original tombstone and process-exit record were retained privately. Its cause is unresolved; it is not attributed to this candidate, and the successful retry does not establish a fix. This observation further limits any claim about the complete installed module's startup stability.
