# Preserved-message dispatch — current-source standalone acceptance (2026-11-18)

## Identity and scope

- Base: WaEnhancerX/WaEnhancerX main 0aaffab0270a3d0832bef2895f6457f3792fd74a (tree fbe9294da04536595b314eb556f145d025d37457).
- Windows 11 WAEX-Lab, JDK 17.0.20.1, Kotlin 2.0.20, Python 3.15, Robolectric 4.17, JUnit 4.13.2, Android APIs 24/28/34.
- Changes: qa/android_preserved_dispatch_test.py plus this acceptance note; no production hook, provider, schema, license or signature logic is modified.
- Production inputs: real PreserveDeleteForMeHook, BaseFeature, HookProvider, PreservedMessageStore, HookRegistrar, ViewHookDispatcher, DexKit cache policies, HookErrors and HostFields. Explicitly staged source SHA-256 values are computed by the runner.
- Exact DexKit 2.2.0 classes JAR SHA-256: 195979d9576ba6d68a1c60bdd3e38cd3aed068d4e0c5c6c99caa50bc25fcb9f1.
- Existing GitHub PR #141 fixed only the older BuildConfig contract and is not a complete, passing current-source result. This branch incorporates that boundary and the additional drift present in the November current main.

## Why the fixture failed before tests

The old standalone stage omitted real HookErrors/HostFields, the Android-generated BuildConfig compile boundary, and a VisibleForTesting annotation. Its unrelated provider-service facades also represented older interfaces: DiagnosticsRecorder, ThemeRepository.Companion.assetFile, WaexPreferenceManager.TARGET_SEEDED_KEY and target-aware TranscriptionRequestHandler overloads.

The revised stage preserves the real production dispatcher/store/hook. It only provides explicit compile-only boundaries for the unavailable application build metadata and out-of-scope services. Each unrelated service method throws AssertionError if invoked. Production declaration and provider-call-site regex guards reject interface drift. BuildConfig uses conspicuous sentinel values (-1, "preserved-dispatch-compile-fixture"), never claims a signed/licensed APK identity.

## Negative attempts retained

- candidate-01: javac support PASS; Kotlin FAIL (missing real HostFields).
- candidate-02: Java, Kotlin and lifecycle PASS; provider test javac FAIL (unrepresented current service interfaces).
- candidate-03: a one-element Python contract tuple lacked its trailing comma; Python regex parsing failed. No tests ran.
- candidate-04: all four source-compilation phases PASS. A monolithic 99-case JUnit launch was terminated by SentinelX foreground ceiling at 25 seconds after partial output. That partial output is not a PASS.
- Final source differs from candidate-04 only in whitespace indentation of the HostFields list entry. It contains the identical production file list, fixture definitions and test contract strings. Python py_compile and git diff --check pass after this edit.

## Completed JUnit verification

The unmodified PreservedDispatchContractTest.java fixture defines 33 @Test methods and @Config(sdk={24,28,34}); there are 99 unique method/API combinations. To respect the remote foreground cap without reducing coverage, the **same compiled production/test classes** from candidate-04 were run three times with Robolectric's enabled-SDK selector. Each invocation ran exactly 33 tests.

| API | Result | JUnit time | Evidence SHA-256 |
|---|---|---:|---|
| 24 | PASS 33/33, 0 failures | 10.59s | 5be637e1c9878d040c31fed3cf67a76511deddf3c08707129d367f89770fe167 |
| 28 | PASS 33/33, 0 failures | 12.167s | 17da9c476a60dbebff79c6cc86d49bc1ca2053afd9ca9990430040140ce1d581 |
| 34 | PASS 33/33, 0 failures | 12.105s | 8f9e898e76a3658f950c74d6a9011e9b20b4e0fcf7fdc4d2166eeda5000805a9 |

Logs are under C:\WAEX-Lab\evidence\waex-preserved-dispatch-20261118\junit-api24-probe.log, junit-api28-probe.log and junit-api34-probe.log. Their exit codes were 0 and their final lines contained OK (33 tests). Compilation logs and the exact staged sources/classes are under candidate-04. The earlier monolithic attempt timed out and did not produce result.json.

Reproduce on a normal host (without SentinelX's 25-second foreground ceiling) from the exact source revision:

~~~powershell
$env:JAVA_HOME = 'C:\WAEX-Lab\tools\jdk17'
$env:PATH = 'C:\WAEX-Lab\tools\jdk17\bin;C:\WAEX-Lab\tools\kotlin-2.0.20\kotlinc\bin;' + $env:PATH
$root = 'C:\WAEX-Lab\work\preserved-dispatch-current-20261118'
& 'C:\Program Files\Python315\python.exe' "$root\qa\android_preserved_dispatch_test.py" --source-root $root --out 'C:\WAEX-Lab\evidence\waex-preserved-dispatch-20261118\reproduction-new' --deps 'C:\WAEX-Lab\evidence\qa-current-source-fixtures-20261113\preserved-dispatch-r1\deps' --dexkit 'C:\WAEX-Lab\evidence\qa-current-source-fixtures-20261113\preserved-dispatch-r1\dexkit-2.2.0.jar' --maven-repo 'C:\Users\tkmremote\.m2\repository'
~~~

For a prepared candidate-04 class directory, invoke the same JUnitCore command that the script constructs with -Drobolectric.enabledSdks=24, 28 or 34 separately, retaining all its --add-opens, classpath and Maven-repo arguments. Confirm 33 tests and exit 0 in each run. The selector is a runtime filter over the unchanged @Config matrix; no test source was edited or skipped.

## Validated and not validated

PASS: source staging and compilation, 99 independent Robolectric method/API cases, Android ContentResolver/provider/SQLite shadows, synthetic host message graph, UID negative cases, failed persistence acknowledgement, warning throttling and nonblocking original-delete behavior.

NOT_VERIFIED: live WhatsApp hook execution or remote recipient effect, actual Android Binder cross-process transport, full signed APK build, native DexKit host scan, failures of real device storage/kernel, Android versions outside 24/28/34, WhatsApp Business, battery or jank.

Recovery is to discard/revert this QA-only branch. No data migration, APK installation or private module export is part of this patch.