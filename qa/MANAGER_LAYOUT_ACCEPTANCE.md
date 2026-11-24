# Manager counter and navigation layout regression coverage

## What these changes address

The current nightly dashboard already gives its counter column the remaining
width and has a 30 dp refresh control, not the older “View all” row. Reconciliation
preserves that structure, its current status hints and refresh binding. The
counter text is bounded to two lines so a long translated label cannot claim
unlimited height. The refresh target retains its width and click behavior.

The current nightly navigation capsule has a fixed 72 dp height, a 64 by 32 dp
selection thumb and centered labels capped at two lines. Reconciliation retains
those labels, semantics, pager selection and dimensions, using 72 dp as a minimum
instead of a fixed height so large text can expand the capsule. It does not
restore the old 54 dp capsule or force the current labels onto one line.
The inner row requests the intrinsic height of its ordinary Column/Text children;
it does not request intrinsic measurements from the outer BoxWithConstraints.

## Automated command and dependencies

Use JDK 21 and the project's Kotlin 2.0.20 CLI. Each output directory must be new
so source snapshots and test evidence cannot be silently overwritten.

```sh
python qa/android_manager_layout_test.py --out /tmp/waex-layout-qa
```

The layout runner uses the checked-in Gradle wrapper only to resolve dependencies.
`qa/fixtures/manager_layout/dependencies.txt` selects Android variants and uses
the exact versions from `gradle/libs.versions.toml`, including Compose BOM
2026.03.01. It rejects desktop dependencies and records resolved coordinates and
SHA-256 values. The optional `--deps` input must have the generated
`../resolved-sha256.json` manifest. `--gradle` can supply an existing Gradle binary.

For CLI compilation, the layout runner downloads the SHA-256-pinned
`org.jetbrains.kotlin:kotlin-compose-compiler-plugin:2.0.20` artifact. This is the
non-embeddable variant for the non-embeddable CLI compiler, at the same version as
the project's Compose plugin. The relocated `compose-compiler.jar` bundled in the
Kotlin ZIP is not appropriate for this CLI combination. No metadata compatibility
check is disabled. `--compose-plugin` can supply the same pinned artifact offline.

The runner reuses `qa/android_media_preview_test.py` for public download,
compiler-discovery and process helpers, and records `qa/maven_dependencies.py`
with that unchanged shared helper in its source identities. Both helper files are
included unchanged. It does not execute MediaPreviewHook or use Maven for the
layout dependency graph.

## Required behavioral results

| Android APIs | Current nightly negative control | Reconciled candidate |
| --- | --- | --- |
| 28, 34 | 10 cases, exactly 2 assertion failures | 10 cases, zero failures |

Add `--baseline-root PATH_TO_NIGHTLY_SOURCE` to execute the immutable nightly
negative control before the candidate. The current baseline is commit
`9972e49fac9bfb650c25ee31fde0da9425b67175`. Its two assertion failures are the
2x-font-scale navigation-height case on API 28 and 34. The complete suite
compiled and ran for both inputs; there were no initialization failures. The
historical V39 comparison (commit `66559468acc0b2f44a1c319ee98f9ed0be27157a`,
six failures) is not the oracle or evidence for this revised layout. A
compilation error, dependency/network
error, missing SDK or skipped test is never accepted as a behavioral negative
control. `--sdk-jars` can supply the exact API 28/34 instrumented SDKs offline.

On 2026-11-21 the Linux run completed in 75.99 seconds including project Gradle
dependency resolution and both compilations/runs, using Temurin 21.0.13.1+2,
Kotlin 2.0.20, Compose BOM 2026.03.01 and Robolectric 4.17. The tested staged tree
was `4d77709ca3f456ab9f08916763a8ef0e0951c431`, reconciling original PR head
`8c7ae91b7df7aa9a8998e9605919d16c705e4b11` with the fixed nightly above; later
changes only update this document. The dependency graph was resolved using the
project wrapper, with the previously downloaded SHA-256-verified compiler plugin
and verified offline instrumented SDKs. Two earlier attempts failed before
compilation: an inherited proxy configuration, then a shared Gradle cache lock.
Those failures are environment evidence, not failed layout assertions. They
were retained; no lockfiles, pins, TLS checks or test expectations were bypassed.

Layout tests copy the two complete layout expressions verbatim, record their
source ranges/hashes, and execute actual Compose Text, Row, Column,
BoxWithConstraints, semantics and native Android text measurement. They compile
the complete typography, icon, color and radius sources. The original Outfit TTF
files are loaded through a fixture file-font boundary instead of Android R font
IDs. The surrounding app data/navigation and fourteen string-resource lookups
are explicit fixtures; labels are read from the checkout's English and Spanish
XML resources. Seven newer status-hint strings are absent in Spanish and use the
actual default-resource fallback; this does not claim complete translation.

The layout cases cover a narrow Spanish counter/action row, a narrow Spanish
navigation capsule, retained refresh binding/width, 2× font scale, full label
semantics, five non-overlapping
click targets at a wide English width, and right-to-left label containment. The
vertical text oracle compares the actual paragraph and line bounds with the
allocated height. `TextLayoutResult.didOverflowHeight` alone is insufficient:
it also includes `didExceedMaxLines`, which can be true for a valid single-line
ellipsis. Expected horizontal truncation must neither hide nor falsely report
vertical clipping.

## Practical limits

This suite measures two production layout fragments. It does not build the
complete manager APK, execute all MainDashboardScreen or Navigation behavior,
render the full screen, run TalkBack, or certify OEM/device accessibility. The
entire PermissionsActivity, WhatsApp injection, frosted host-bar GPU rendering,
startup latency and frame rate are outside this suite.

The successful normal bootstrap must also execute without `--deps`, `--gradle`
or `--compose-plugin`: wrapper/plugin verification, Android dependency resolution,
manifest generation, actual compilation and the 10 layout cases all remain gates.
No PASS from dependency copying is a substitute for executing those cases.

## Primary API references

- [Compose intrinsic measurements](https://developer.android.com/develop/ui/compose/layouts/intrinsic-measurements)
- [Compose text layout configuration](https://developer.android.com/develop/ui/compose/text/configure-layout)
- [Kotlin Compose compiler artifact variants](https://kotlinlang.org/docs/compose-compiler-migration-guide.html#using-compose-compiler-without-gradle-plugins)
- [Kotlin JVM environment attributes](https://kotlinlang.org/docs/whatsnew20.html#new-attribute-to-distinguish-jvm-and-android-published-libraries)
