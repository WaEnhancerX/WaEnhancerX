# Floating bar, visibility and overlay regression coverage

## What these changes address

FloatingBottomBarHook no longer explicitly refreshes drawable state or invalidates
an unchanged selected item on every reconciliation. Native active indicators are
mutated only when their background, opacity or visibility needs changing. A new
selection starts from the last target of the exact transition owned by WAEX;
an unrelated background supplied by the host is preserved as the starting layer.
A zero-height attached bar waits for its first actual layout instead of posting
recurring 100 ms retries. That listener is removed after layout or detachment.

The Meta AI and channel hooks validate that their cached/discovered target is a
single View class. Missing, non-View and ambiguous matches cannot be reported as
a successful installation. Resolved Views use a local visibility guard after a
host bind restores them. The guard does not erase host LayoutParams, register a
hook on every View, or poll on every frame. Detachment removes its observer;
reattachment reconciles the same resolved View. Unchanged hidden Views require no
visibility writes. The guard rereads the supplied preferences on reconciliation.
After disabling a preference, a subsequent host bind or restart may restore the
host's visibility; it does not guess the host's previous state. Enabling a hook
that was not installed still follows the app's normal restart workflow.

Overlay handling opens Android settings, falls back to App info if the overlay
activity is absent or denied, and handles an unavailable fallback without a
crash. The manager provides conditional guidance for Android restricted settings
and an explicit App info action. It neither grants the permission nor bypasses
Android restrictions. The existing permission status check remains the authority.

## Automated commands

Use JDK 21 and the project's Kotlin 2.0.20 CLI. The runner accepts `--deps`,
`--dexkit-jar`, `--core-api-jar` and `--sdk-jars` for offline reuse. Otherwise it
resolves public Maven artifacts. Each output directory must be new so evidence
cannot be silently overwritten.

```sh
python qa/android_floating_bottom_bar_test.py --out /tmp/waex-floating-qa
python qa/android_floating_bottom_bar_test.py --view-visibility --out /tmp/waex-visibility-qa
```

The runner reuses `qa/android_media_preview_test.py` and `qa/maven_dependencies.py`
for public dependency and process helpers. It does not execute MediaPreviewHook or
substitute it for any appearance implementation.

### Reproducible public bootstrap

The workflow downloads the official Kotlin 2.0.20 compiler, verifies its SHA-256
and bounded archive contents, and checks the selected executable before either
suite runs. It does not use the runner image's unpinned Kotlin installation.

AndroidX Core 1.18.0 is downloaded separately as its official Google Maven AAR.
Both the AAR and its complete `classes.jar` are size-bounded and SHA-256 verified;
an optional `--core-api-jar` must have that same identity. The project catalog
must still declare 1.18.0. DexKit 2.2.0 uses the existing pinned public bootstrap.
Robolectric and annotation dependencies retain their fixed Maven coordinates.
The bootstrap, classpath and instrumented Android SDK identities are recorded
alongside the execution logs.

This avoids asking Maven for nonexistent JAR variants of Core's Android
transitive dependencies. The former default bootstrap failed during dependency
resolution on integration commit `4e68f7302707dd98645ae4a518bea0faddc58d7e`;
neither appearance suite reached compilation or test execution. Earlier local
results used supplied dependencies and did not prove that default route. The
failure is a deterministic QA configuration defect, not a product regression or
a transient network failure.

The corrected route compiles and executes the unchanged production classes and
all existing cases against real Core classes. Its standalone Robolectric
classpath does not resolve or validate the entire app's Gradle dependency graph,
and the tests do not establish behavior of unexercised Core APIs or transitive
libraries. A missing runtime class, skipped case or dependency failure remains a
failed gate. Public-bootstrap validation must run both commands above without
`--deps`, `--core-api-jar`, `--dexkit-jar` or `--sdk-jars`.

### Localization scope

The four `overlay_permission.xml` files provide the same three keys in English,
Spanish, Latin American Spanish and Slovenian. Their XML structure, non-empty
values, placeholder consistency and matching Spanish variants are checked when
preparing the focused change.

The global es-419 and Slovenian gates passed in the earlier combined V39/V40
checkout used to prepare this focused change. That result does not cover later
integration additions: source QA on `4e68f7302707dd98645ae4a518bea0faddc58d7e`
reported the unrelated missing Slovenian `call_recording_delete_failed` key.
The gates also do not pass on the isolated upstream-main parent
`a565e1a1a50ebfe5d633e8cd0d43c6dc6025a8ac`: that parent already lacks 14 unrelated
Spanish keys and 16 Slovenian keys. The appearance stage has the identical gaps;
the overlay additions do not introduce them. The appearance workflow therefore
runs the scoped hook suites. The shared integration source-QA workflow retains
the global localization gates. A green appearance job must not be described as
proof that all localization on its isolated branch is complete.

## Required behavioral results

| Suite | Android APIs | V39 negative control | Candidate |
| --- | --- | --- | --- |
| Floating bar and overlay | 24, 28, 34 | 30 cases, exactly 18 assertion failures | 51 cases, zero failures |
| Meta AI, channel binding and selectors | 24, 28, 34 | 36 cases, exactly 30 assertion failures | 51 cases, zero failures |

To run the immutable V39 negative control before a candidate, add
`--baseline-root PATH_TO_V39_SOURCE` to the corresponding command. The baseline
must be commit `66559468acc0b2f44a1c319ee98f9ed0be27157a`. A compilation error,
dependency/network error, missing SDK or skipped test is never an accepted
behavioral negative control.

Floating/visibility tests compile the complete production hook classes, actual
BaseFeature and the new helpers against real Android and DexKit APIs. The
DexKit search results, Xposed registration/dispatch and host resource identifiers
are controlled boundaries. Android Views, drawable state, Drawables, Handler,
attachment, layout, preferences and Intents execute in Robolectric.

## Device acceptance still required

These tests do not build the complete APK, execute LSPosed, scan a supplied
WhatsApp APK or validate selectors against WhatsApp 2.26.37.1/2.26.40.76. They do
not measure startup latency, frame time, FPS, GPU blur or the frosted-bar edge
shown in the report. The screenshot alone does not establish its rendering cause.

The tested overlay boundary verifies launch/fallback Intents and unchanged actual
permission state. It does not automate an OEM restricted-permission dialog or
certify the entire PermissionsActivity Compose screen.

The existing IGStatus/HideHomeTabs interaction continues to preserve the Updates
tab when IGStatus is enabled. This is an explicit pre-existing guard in
HideHomeTabsHook. A settings screenshot with both options enabled does not prove
that removing the guard preserves every status entry point or update-data path.
Changing that interaction requires target-APK/device evidence and is not part of
the verified fixes here.

On a supported, user-owned test device, verify each target package and profile
separately: hook installation diagnostics, host visibility after bind/reopen,
normal status access with the IGStatus combination, FAB offsets after detach and
rotation, frosted-bar rendering, and optional overlay settings navigation. Keep
these results distinct from the automated framework evidence.

## Primary API references

- [Android restricted settings](https://support.google.com/android/answer/12623953)
- [Android overlay settings action](https://developer.android.com/reference/android/provider/Settings#ACTION_MANAGE_OVERLAY_PERMISSION)
