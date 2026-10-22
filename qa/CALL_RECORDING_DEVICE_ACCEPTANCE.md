# Call recording: device acceptance

`python3 qa/test_call_recording_lifecycle.py` compiles the complete production hook
and exercises its installed callbacks with explicit Android, Xposed, storage,
host and scheduler doubles. It verifies call identity, event ordering,
cancellation, cleanup, bounded scheduling and rollback of incomplete hook
installation. It does not record audio or run
LSPosed, native DexKit, a WhatsApp call, or an Android application build.

The callback signatures and immutable call identity/state fields were inspected
in the supplied WhatsApp 2.26.40.76 APK. The optional `--dexkit-jar` and
`--kotlin-stdlib` arguments compile the complete hook against the real DexKit API;
that check does not execute its native search. These checks establish the
reviewed source contract, not compatibility with every host version or device.

## Ending-event overload regression checks

`python3 qa/test_call_recording_event_contract.py` compiles the complete production
event contract and checks 20 declaration variants for each of the two supported
callback packages in isolated class loaders (40 Java reflection cases). It checks
the verified exact signature, a unique typed alternative, unrelated overloads,
wrong return/static/native/abstract contracts, same-suffix classes from another
package, and ambiguous typed alternatives. An exact, usable signature takes
precedence; otherwise exactly one usable instance `void` method beginning with
the expected `CallLogInfo` class is required. A first-match or suffix-only result
does not establish the lifetime of a call.

`python3 qa/test_call_recording_lifecycle.py --host-variant typed-ending` repeats
the complete recorder lifecycle with a controlled alternate trailing-argument
signature. The fixture dispatches the method actually registered by the hook and
checks that its original arguments/result remain unchanged. This demonstrates
source handling of that declaration shape; it does not identify another supported
WhatsApp release. Run the on-device acceptance below for every actual host version.

## Run on each supported device/build combination

Record the module commit, Debug/Release build identity, WhatsApp version,
Android version, LSPosed version, output format and selected audio route. Use
consenting participants and the normal module permissions/settings.

1. With recording disabled, make and answer calls. No recording should be
   created. Enable recording and restart the host if required for hook setup.
2. Let an outgoing call ring, then cancel before it connects. Repeat with an
   unanswered incoming call. Neither should create an audio file.
3. Connect a call and keep it active for several minutes. A recording should
   begin after the existing two-second delay. Intermediate call statistics
   should not split or stop it. Check that the saved file has the expected
   duration and can be played.
4. Navigate away from the call screen, recreate it, rotate the device, then
   return. UI destruction must not end a continuing call's recording.
5. End the call from each participant in separate attempts. Recording and
   descriptor ownership must end once. Repeated terminal callbacks must not
   create additional files or prevent a later call from recording.
6. End one call and quickly start another, including ending during the initial
   delay. A cancelled task or a late event from the first call must not start or
   stop capture for the second call.
7. Disable recording during the initial delay and during active capture. The
   pending start must be cancelled, or capture must stop and close its output.
   Re-enabling alone must not start capture without a new confirmed active-call
   event.
8. Exercise storage/recorder failures using a suitable test device. The call UI
   must remain responsive; failed outputs must be removed; a later supported
   recording attempt must still work.
9. Repeat relevant cases with M4A and, on supported Android versions, Opus. Test
   microphone, wired/Bluetooth routes and speakerphone separately. Confirm
   what is actually audible in the file; successful `MediaRecorder.start()`
   does not establish that both participants were captured.

## Audio-source limitation

The existing `call_recording_use_root` preference changes the order in which
`MediaRecorder` audio sources are tried. It does not implement a separate root
audio pipeline. This lifecycle correction preserves that order and does not
claim to fix a missing remote participant's audio.

The separate `call_recording_allow_mic_fallback` public Boolean defaults to `true`,
so existing installations keep the same source order. In **Call audio** mode,
turning **Allow microphone fallback** off limits the next recording attempt to
`VOICE_CALL`. If Android rejects source setup, preparation or start, the attempt
closes and removes its output instead of trying microphone sources. The switch
acknowledges the preference write before changing its displayed state. It does
not restart an active recorder, grant privileges or establish that the remote
participant is present when `VOICE_CALL` starts successfully.

Successful source selection reports `source`, `fallback` and
`remote_audio=unverified` through the Xposed log. Source rejection reports the
source and exception class only. Up to one success and one rejection per source,
plus one start failure, are emitted per identified call session; repeated host
callbacks cannot produce an unlimited stream of these messages. No call ID,
contact, recording path, audio payload or exception message is logged.

`python3 qa/android_call_recording_audio_test.py --out /tmp/waex-call-audio`
executes 26 JUnit methods on Android API 24, 28 and 34 with Robolectric 4.17
(78 cases). It compiles the complete recorder, event contract, BaseFeature,
preference schema, stores, preference manager and backup codec. Android
SharedPreferences, Bundle, ContentResolver and ParcelFileDescriptor are real
framework objects. Host declarations, Xposed dispatch, scheduling, storage
authorization and native audio operations are explicit fixtures. A recorder
shadow checks source order, prepare/start failure, cleanup and configuration;
it produces no sound or encoded recording. The full CallsHub Compose screen,
real cross-process provider, audio policy and LSPosed are not exercised.

For [issue #84](https://github.com/WaEnhancerX/WaEnhancerX/issues/84), the reporter
uses WhatsApp **2.26.37.73** on Samsung S911B / Android 16. The supplied host APK
used for event-contract inspection is **2.26.40.76**. Neither the version rule
nor this fixture establishes Samsung audio-route support or validates the
reporter's callback signatures. Reproduce that exact combination with both
participants' consent and record the selected source diagnostics before
classifying microphone-only output as a capture failure or a routing limitation.

Android documents `VOICE_COMMUNICATION` as a microphone source tuned for voice
communications and restricts `VOICE_CALL` capture to the system
`CAPTURE_AUDIO_OUTPUT` permission. Device-specific audio routing and concurrent
input policy can still cause microphone-only or silent output. See the official
[MediaRecorder.AudioSource reference](https://developer.android.com/reference/android/media/MediaRecorder.AudioSource)
and [sharing audio input guide](https://developer.android.com/media/platform/sharing-audio-input).

## Unsupported host behavior

Installation rejects an incompatible named callback contract before registering
the recording hooks. Events without an identified call are ignored, so they
cannot affect another call's recording. A host that stops exposing an ending
call's identity requires a separately verified terminal event contract; it must
not be marked compatible solely because these JVM fixtures pass.
