# Interface-scale QA oracle after the November spacing redesign

## Problem and source history

`InterfaceScaleThemeTest.changingDensityFontScaleAndSizeDoesNotRetainPreviousMeasurements` still expected a `24.dp` page margin and 66 px after reset at density 2.75. That matched the design tokens at `WaEnhancerX/WaEnhancerX@36967a920352b16114caeb6b5ac624087faf7384` on November 8 and the test update `57d024a544c7b98aadce83d57dff144732e2aa02` on November 12.

The later production redesign `54ae5e34a32acbd9124b5e4c88aebe9dcb3c388f` on November 14 intentionally changed the six base spacing tokens to page margin 16, gutter 16, bento gap 8, large stack 16, medium stack 12 and small stack 8 dp. The QA oracle was not updated. The current upstream source tree is `0aaffab0270a3d0832bef2895f6457f3792fd74a`.

In the unchanged public GitHub Actions run `44209246175`, job `134483068429`, the pinned Robolectric/Compose test executed 12 cases with **3 FAIL** on Android API 24, 28 and 34. The JUnit XML in artifact `14385072543`, SHA256 `9176ac2d096030974288490a6d66165b8e777ec021e0571bc18d7af1a0707bee`, reports the same decisive assertion on all three APIs: `latest page margin expected:<24.0> but was:<16.0>`. The other nine cases passed. This is a stale independent QA expectation following the verified upstream redesign; it is not evidence that the product should revert to 24 dp.

## Change and expected behavior

- Update the independent page-margin oracle to 16 dp and the reset expectation to 44 px at density 2.75.
- In the same existing rapid-reconfiguration test, pin all six base spacing token values from the reviewed November 14 design change. Compare their physical pixels across 24 size/density/font-scale transitions without deriving expected values from production token objects.
- Assert the six-token count before zipping expectations with observations, so a shortened list cannot silently pass.
- Keep every existing test, Android API, Compose source, assertion about body text and font scaling, and all unrelated production code unchanged.

The new loop will reject a proportionally wrong spacing token even when a relative scaling test passes. It deliberately does not update expected values dynamically from `WaexSpacing` at runtime.

## Validation and remaining gate

**PASS, source/history verification:** original upstream Git blobs, the November 8/12 source/test contracts, the November 14 redesign diff, and the exact failing JUnit XML were read and matched. The six expected values are fixed from the historical design decision. All source changes are limited to this fixture and this acceptance note.

**NOT_VERIFIED, candidate Robolectric execution:** The full Gradle/Android/Compose suite requires the declared JDK 21, Kotlin/AGP/Android dependencies and a completed run against this candidate. The prior 3/12 FAIL belongs only to the unchanged upstream baseline. Do not infer a candidate PASS from its corrected arithmetic or from the baseline log. The runner command is:

```sh
python3 qa/verify_interface_scale_framework.py --root <candidate-root> --out <new-evidence-dir>
```

Acceptance requires 12 tests, zero failures/errors/skips and exit code 0, with source/dependency SHA256 identities retained. A controlled negative changing any pinned spacing token while keeping the fixture constant should fail the rapid-reconfiguration case; that negative has not yet been executed.

## Risks, compatibility and recovery

This is a QA-only correction. No production spacing, typography, settings, dependency, schema, hook, APK, license or signing behavior changes. The test concerns Android/Compose dimension conversion on Robolectric API 24, 28 and 34. Full WAEX UI, physical devices and WhatsApp runtime are outside this fixture.

Revert this QA commit normally if the design decision is itself reversed. A deliberate future spacing redesign should update its independent specification and test together, recording the design revision; do not make expectations follow production values automatically.
