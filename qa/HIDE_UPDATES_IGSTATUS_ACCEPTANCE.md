# Hide Updates versus IGStatus — source regression (17 November 2026)

## Reproduction and source evidence

Upstream [issue #168](https://github.com/WaEnhancerX/WaEnhancerX/issues/168)
reports WAEX 2.0.8, WhatsApp 2.26.45.94, Android 16 / Vector, with
`hidetabs=[300]` and `igstatus=true`. The user requests hiding Updates, but
the tab remains visible.

At source `0aaffab0270a3d0832bef2895f6457f3792fd74a`, the hook read the
selected IDs and then executed `hidden.remove(UPDATES)` when `igstatus=true`,
unconditionally cancelling ID 300. That is an independently demonstrable
preference-precedence defect. It does **not** prove the host's obfuscated
tab-list method still resolves on WhatsApp 2.26.45.94.

Native IGStatus uses WhatsApp's stories row on Chats (the production
`PropsOverrideHook` documents this). It does not require ignoring a separate
user choice to hide the Updates navigation tab.

## Correction

- Parse stored selected IDs in `TypedStyleHookPolicy.parseHiddenTabIds` without
  coupling to unrelated layout preferences.
- Preserve the explicit selection in `HideHomeTabsHook` for both model
  filtering and visible MenuItem filtering.
- Keep the existing lazy/startup policy, DexKit method resolution, activity
  refresh, and missing-target error reporting unchanged.

## Executable controls

```sh
python3 qa/test_hide_home_tabs_precedence.py
```

The script compiles real Java 17 production policy code and exercises ten
independent input/result assertions. It also rejects the original IGStatus
suppression and two policy regressions. It verifies source-level wiring but
does not substitute for installing and observing the new hook on WhatsApp.

## Android acceptance still required

With an authorized test account and an APK built with the maintainer's real
private dependencies, confirm on target WhatsApp versions that selecting only
Updates (300) hides its model item and dock entry when IGStatus is ON and OFF.
Re-enable it and verify the tab returns after the appropriate process restart.
Repeat for other selected tabs and the personal/work profile separately. Record
the injection, registration, execution, error log, and visible effect distinctly.

If DexKit cannot find a unique compatible method, fail diagnostically rather
than claiming functional support. This PR does not address unrelated tabs,
version-specific obfuscation, or signed release production.
