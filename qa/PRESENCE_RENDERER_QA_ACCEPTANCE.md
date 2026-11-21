# Current presence renderer: framework acceptance

## Problem and scope

Upstream commit `3cecef5f477d321202309b35c0e8d1b9cae22f7c`
replaced the presence overlay with ordinary dot/text children. The old QA runner
still extracted `ensureOverlay`/`layoutOverlay` and accessed `rowOverlays`.
At upstream `a0b047ae832546de1ba4fc8f8e6d21cf54750813`, that runner reproducibly
raises `ValueError: substring not found` before compiling the renderer.

This change repairs that verification path. It does **not** change production
presence behavior or restore the obsolete overlay implementation. Historical
V17/V18 reports remain historical evidence, not acceptance of the current source.

## Executed boundary

The harness extracts the current production bodies of `bindRow`, `ensureDotView`,
`ensureTextView`, `render`, `recordIdentityAlias`, `queueBindRow`,
`queuePresenceRefresh`, `clearRow`, and `stateForRow`, with the four actual tracking
fields, `dp`, and companion declarations. `PresenceStateStore`, `UiText`, and
`DisplayLocalePolicy` are compiled from production source. The listener adapter
explicitly connects the store to the extracted renderer; it is not Xposed setup.

AAPT2 compiles and links a resource-only synthetic host with the real
`id/contact_selector` entry. Android `Resources.getIdentifier` resolves this
table. The fixture never substitutes an always-successful ID lookup. The
resource APK is never installed or published and contains no WhatsApp code.

Android parent measurement/layout and native Skia drawing remain real framework
operations. Accessors only look up existing children. Pixel coordinates are
translated from the avatar child to its row before checking the rendered pixel.
The generated `R` class only supplies compile bindings for two strings; real
`UiText` takes its explicit English fallback. Localization is not validated here.

## Scenario continuity

All 25 previous scenario intents remain, with three accurate names:

| Previous name | Current name and contract |
| --- | --- |
| `failedOrGroupBindClearsPriorDecoration` | `nullQueuedBindClearsPriorDecoration`: a queued null bind clears state; no claim to execute the WhatsApp group/binder path |
| `resizeRelayoutsOverlayAndChildren` | `resizeRelayoutsAvatarAndRealChildren`: actual parent resize and avatar resize reposition measured children |
| `repeatedBindDoesNotDuplicateOverlay` | `repeatedBindReusesUniqueDotAndTextChildren`: identity, parentage, unique child counts and real weak-key backing maps |

RTL expectations follow the current `BOTTOM | END` avatar contract rather than
the removed overlay's `START | TOP` contract. Both LTR and RTL geometry are
asserted. Weak-key reflection checks ownership structure, not garbage collection.
The synthetic application explicitly uses target SDK 28 and supports RTL; it is
not a substitute for WhatsApp's actual target SDK or manifest configuration.

Three additional scenarios verify compiled host-ID selection, fallback when the
host avatar ID is genuinely absent, and pending-token release with weak keys.
Thus the positive suite contains **28 unique scenarios**, executed on **API 28,
34 and 36**, for **84 framework executions**. Repetition across SDKs does not
create 84 independent features.

## Negative controls

The regular runner's `--mutations` option compiles and executes three faults on
all three SDKs: wrong dot gravity, ignored unknown-state tombstones, and omitted
dot attachment. Every variant must complete all 84 executions and fail its named
discriminating oracle on each SDK.

`mutate_presence_rows.py` retains all 15 reviewed fault classes on API 34,
executing all 28 scenarios for each variant. The old missing-overlay-measurement,
overlay-direction and duplicate-overlay faults now target missing child
attachment, forced child LTR and duplicate dot children. It does not reintroduce
production overlays or count failed extraction/compilation as detection.

The negative classifier requires a nonzero exit, the full execution count, one
JUnit summary, declared scenario names, and assertion-origin test stacks. It
accepts JUnit `AssertionError` and `ComparisonFailure`, rejecting setup errors,
class-loading failures, null-pointer exceptions and incomplete runs. Twelve
independent Python unit controls exercise this classifier using synthetic
transcripts; these controls are not Android tests.

Seven additional unit cases check fresh/warm AAR extraction, independent output
directories, archive/output identities, ambiguous names, mismatched cached bytes
and preservation of existing evidence. These are runner-contract tests, not
extra Android scenarios or additional behavioral mutation classes.

## Reproduction

Requirements: Python 3, JDK 21+, `javac`, `kotlinc`, real Android SDK AAPT2, and
Maven/network on first use. Dependencies are pinned to Robolectric 4.17 and
JUnit 4.13.2. Set `ANDROID_SDK_ROOT`/`ANDROID_HOME` or pass `--aapt2` explicitly.
Use new output directories for every run. Both entrypoints and each execution
refuse an existing output before overwriting evidence.

```sh
python3 qa/test_presence_runner_controls.py
python3 qa/test_presence_state_replay.py
python3 qa/android_presence_row_test.py --out /tmp/waex-presence-rows --mutations
python3 qa/mutate_presence_rows.py \
  /tmp/waex-presence-mutants /tmp/waex-presence-rows/deps
```

`--deps` reuses an existing dependency directory without rewriting its files.
Both entrypoints prepare their own AAR extractions and pass an explicit runtime
classpath, independent of any other run's output. Existing extracted JARs are
checked against the AAR and replaced only in the classpath by this run's copy.
`runtime-classpath.json` records every runtime JAR hash and each AAR member's
origin. `--sdk-dir` enables verified offline
Robolectric SDK inputs. Both dependency inventories are hashed. Windows uses a
Kotlin argument file to avoid batch-launcher semicolon splitting. The compiler
classpath contains only imported APIs; the complete transitive runtime
classpath remains intact, including `test-parameter-injector`. No Kotlin
metadata check is disabled.

The CI job runs the classifier controls, positive suite, three focused mutants
and 15-class companion suite. It uploads text evidence, source extracts and
input identities, not APKs.

## Verified inputs and results (2026-11-12)

Tested production base: `a0b047ae832546de1ba4fc8f8e6d21cf54750813`.
No production file is changed by this proposal.

| Input | SHA-256 |
| --- | --- |
| `OnlinePresenceIndicatorsHook.kt` | `f619fcd8f573541cb02f677c3b2157ed6e231a38314f53821f49e234a1fc5746` |
| `PresenceStateStore.java` | `9955ef9803a57cc51e6c738e63f103b36ed084e267ab4b5f1abe91de737e8170` |
| `UiText.java` | `89f55ac1b6e21e630bd03204227cae28425df3d69afd139169d07be4cbb68156` |
| `DisplayLocalePolicy.java` | `e3c0fcdfef0e16a5f3d6c23456fc5d5bf37ce434094f0844656bacd7adc15c5a` |
| Runner | `111fca083bb07ff57432fe27f5bd031430f06dcc8ae742f978a629c664f31149` |
| Kotlin fixture | `eeabe4bfdfaea095bed0129716d6d7f2e712df38f4cb46bce5d6f0abc6fd5bcf` |

Environment: Windows 11 build 26200, Python 3.15.0a5, Temurin 21.0.13+9,
Kotlin 2.0.20, Android SDK build-tools 36.0.0 / AAPT2 `2.20-13193326`,
Robolectric 4.17 native graphics. The Linux-only checks used Python 3.12.14.
The existing Windows Python was a prerelease; this is recorded as a toolchain
limitation, not hidden behind the remote-control service's different Python.

| Check | Result and boundary |
| --- | --- |
| Upstream runner against current renderer | FAIL reproduced during extraction; not an Android product failure |
| Candidate javac/kotlinc compilation | PASS with real Android/Robolectric APIs |
| Positive native framework suite | PASS with cold AAR inputs, 28 scenarios × 3 APIs = 84 executions |
| Three focused compiled mutants | PASS detection with cold AAR inputs; mutant JUnit results intentionally FAIL on all three APIs |
| Retained companion fault classes | PASS detection with cold AAR inputs, 15/15 on API 34; each variant compiles and executes the complete 28-scenario fixture |
| Production store replay | PASS, 24 checks including 32,000 concurrent observations; these are not 32,000 features |
| Renderer structural guards | PASS, 16 source guards; not behavior proof |
| Classifier unit controls | PASS, 12 unit cases |
| AAR/evidence isolation unit controls | PASS, 7 unit cases; independent of Android execution |
| Full application build / installation | NOT_VERIFIED by this proposal |
| WhatsApp binder, subscriptions, Xposed injection and real contact presence | NOT_VERIFIED by this proposal |

Two failed preparatory runs remain distinct from acceptance: Windows Kotlin
batch classpath splitting, then incompatible unused transitive Kotlin metadata.
An intermediate mutant run exposed a classifier bug: `ComparisonFailure` was
mistaken for an infrastructure error. The final runner corrects this distinction
and still rejects all non-assertion failures.

Recorded durations are test execution times,
**not WhatsApp latency, jank or battery measurements**. The CI workflow itself
has not been executed on GitHub for this unpublished revision.

## Cold dependency correction

Independent review found that the first local candidate (`7b301385`) only passed
the companion suite with previously extracted AndroidX JARs in its reused
dependency directory. The positive runner extracted fresh AARs into its own
output, but the companion used a different output and did not receive those
JARs. This was a real cold-CI defect, not a product behavior failure.

With an isolated copy of the same dependencies excluding the two inherited
`*-classes.jar` files, the original positive run passed all 84 executions, but
the first companion variant failed all 28 setup attempts with
`NoClassDefFoundError: androidx/test/platform/app/InstrumentationRegistry`.
The classifier correctly rejected these errors as infrastructure, not detected
behavioral mutations. The original dependency cache was left untouched.

The shared preparation helper corrects that contract. The corrected revision
passed all 84 positive executions and detected the three focused mutations and
15 retained companion faults using the same isolated cold inputs. Each
entrypoint extracted its own two AndroidX JARs; every executed JVM classpath was
checked against its hashed runtime manifest. All 36 cold input files and all 38
files in the original warm cache remained unchanged. The focused and companion
fault sets overlap; they are not 18 distinct fault classes.

The first Windows unit preflight exposed an expected-path comparison using a
TEMP 8.3 alias while the helper returned resolved paths. The fixture now resolves
its existing temporary root before constructing expectations. No byte,
classpath-membership or behavioral assertion was removed. All 19 runner unit
cases then passed on Windows as well as Linux. No Android scenario ran during
that failed preflight; the final cold run repeated the full positive/focal suite.

## Remaining acceptance and recovery

The synthetic resource host is not evidence that any particular obfuscated
WhatsApp row binder or subscription contract is compatible. APK builds, private
dependencies, signatures, installation/user identity, work profiles, real
presence observations, UI preference persistence and end-to-end visible
effects require separate acceptance. No account was used by these tests.

Recovery is a normal revert of this QA-only proposal. It restores the previous QA
files without changing application data or production hooks, but also restores
the known current-renderer extraction failure. Preserve the evidence instead
of treating that reverted test state as passing. Reverting only the cold-AAR
correction restores the reproduced missing-AndroidX companion failure.
