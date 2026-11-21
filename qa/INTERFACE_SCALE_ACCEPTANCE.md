# Interface size: proportional scaling and accessibility

## Behavioral contract

The app's interface-size preference scales the complete theme once: Compact
1.00, Standard 1.08, Comfortable 1.16 and Large 1.25. Font sizes, line heights,
letter spacing, design-system gaps, radii and fixed dp dimensions must retain
their proportions. The Android font-scale preference remains independent and
keeps Compose's Android nonlinear font conversion. A configuration or setting
change must update measurements immediately, including returning to Compact.

`WaexTheme` applies this through its `LocalDensity`. Its typography and spacing
tokens therefore stay in their base sp/dp units. Immutable typography/spacing
objects can be reused while density changes. The preference's stored values,
four scale factors, base design, fonts, colors and shape mapping remain the
existing product choices.

## Required framework gate

```bash
python3 qa/verify_interface_scale_framework.py --out /tmp/waex-interface-scale
```

The destination must not already exist. The runner builds an isolated public
Android library with this checkout's Gradle wrapper, AGP/Kotlin/Core/Compose BOM
and coroutine versions. It copies all six production theme/token files, the two
Outfit fonts and the test fixture unchanged. It does not replace the theme with
a test implementation or load the private licensing module.

`InterfaceScaleThemeTest` uses real `Composition`, `Recomposer`, `MaterialTheme`,
Android contexts and Compose density conversion. Four scenarios run on Android
API 24, 28 and 34: exactly 12 executions, with zero failures, errors or skips.

| Scenario | Required coverage |
| --- | --- |
| Proportional gaps and fixed dimensions | Four interface sizes at display densities 1.0, 2.75 and 3.5; six spacing tokens, a card radius and fixed dp dimensions |
| Text proportions | Ten WAEX and fifteen Material3 roles; font size, line height and letter spacing across all interface sizes |
| Accessibility | System fontScale 1.0, 1.3 and 2.0; compare with Android `Density(Context)`, including nonlinear conversion, across direct sp values and all 25 text roles |
| Reconfiguration | Twenty-four size/density/font-scale changes in one live composition; verify current values and reset to Compact |

The independent `interface-scale-framework` workflow job is mandatory. It
preserves source SHA256 identities, resolved dependency coordinates/hashes,
compiler output, JUnit XML and the aggregate result, including a failed build.
The optional TestParameterInjector dependency remains available at runtime;
its newer Kotlin metadata is kept outside the Kotlin 2.0 compile classpath.
No metadata compatibility check is disabled.

Dependency hashes use a strict Gradle artifact view of external module
components. The local Android library has several secondary outputs (classes,
resources and manifest); selecting them through the legacy artifact collection
is ambiguous. Its production source identities are recorded separately. This
filter changes only identity collection and leaves dependency resolution errors
and every framework test mandatory.

### Current page-margin contract (upstream November 2026)

The design-system page margin is now **24 dp** at Compact, not the 16 dp
used in the original October 19 fixture. Upstream deliberately changed the
base margin from 16 to 20 dp in `ff900d71249f7b4944143971b3869595b4de3e25`
(October 30), then from 20 to 24 dp in
`36967a920352b16114caeb6b5ac624087faf7384` (November 8).
The reconfiguration test retains its independent fixed expected value for the
current design: 24 dp converted at the active density, once per size factor.
At density 2.75, the expected Compact margin and reset value are **66 px**;
Large is **82.5 px**. All 24 repeated configuration changes and all 12
Robolectric executions remain mandatory.

On Windows, the runner resolves `bash` to the executable in `PATH` and passes
the Gradle wrapper path with forward slashes. This avoids Windows choosing
the WSL launcher instead of Git Bash, or a backslash path causing `gradlew`
to resolve its wrapper JAR from the wrong directory. It does not alter
Linux CI's compiler or test semantics.

## Historical October 19 regression controls (16 dp design)

The complete theme from upstream commit
`9098c0fbf9e2ec547d0f8ac5181395006d1a2b01` compiled and failed all 12 behavioral
executions. The corrected theme passed all 12. A separately compiled mutant
which reset the system font scale to 1.0 failed the six accessibility and
reconfiguration executions, while the six ordinary-scale executions passed.

At density 2.75 and fontScale 1.0, the original October 19 design's real
Compose conversions produced the following **historical** measurements; the
page-margin row is not the current 24 dp design contract:

| Quantity | Compact | Previous Large | Corrected Large |
| --- | ---: | ---: | ---: |
| WAEX bodyMd / Material bodyMedium, base 14 sp | 38.5 px | 60.15625 px | 48.125 px |
| Page margin, base 16 dp | 44 px | 68.75 px | 55 px |
| Fixed reference, 16 dp | 44 px | 55 px | 55 px |

The former Large setting applied 1.25 twice to token-based text and gaps,
producing 1.5625 times their Compact sizes. Fixed dp values grew by 1.25. The
fix removes that mismatch without replacing Android accessibility scaling.

## Scope and device acceptance

These are logical-to-physical dimension checks through the complete composed
theme. The local control uses the real resolved Compose AARs and Robolectric
with compile-only synthetic font resource IDs; it does not rasterize glyphs.
The CI fixture additionally compiles the original font resources with AGP.
Neither execution proves a complete WAEX APK, an injected WhatsApp screen,
LSPosed behavior, measured frame rate or device-specific layout quality.

On a device, verify the settings screen, dashboard, navigation and permission
screen at every size; change Android font size while the app is active; check
long translated labels, landscape/small screens and touch targets. Check each
dialog and bottom sheet separately: a separate Compose window can establish
its own density, and the theme conversion fixture does not validate that
window's inherited locals. This PR does not claim to resolve or reproduce
such a separate-window defect.
