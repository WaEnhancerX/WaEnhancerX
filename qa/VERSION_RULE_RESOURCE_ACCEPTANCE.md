# Version-rule resource ownership

Main `a605ee6a96725d065fd838a6f058c47f9dde6a7f` changed package-context
creation and removed the context/resource-name checks. Its source QA first
fails because the explicit `Context` double lacks Android's
`CONTEXT_INCLUDE_CODE` constant. Adding that constant alone makes production
compile and exposes a separate behavioral failure: a foreign returned context
is classified as `UNSUPPORTED` rather than `RULES_UNAVAILABLE`.

The validator must request `com.waenhancer`, receive that package's context,
and verify `com.waenhancer:array/universal_supported_versions` before reading
the array. A numeric ID alone cannot identify a resource table. Keep the static
`R.array` fallback for optimized builds, but apply the same full-name check to
it. Reading resources does not require loading application code into the host.
Missing packages, denied access, unavailable resource tables and linkage errors
remain unavailable decisions. Assertion/VM errors are not ordinary rule results.
Explicit custom rules and the existing user-selected bypass retain their
distinct decisions and are never silently enabled by this change.

## Executable checks

- `python qa/test_runtime_diagnostics.py` compiles the production utilities and
  guard with explicit Android/root doubles. It checks unexpected context
  packages, foreign or renamed arrays, numeric fallback, creation flags,
  malformed rules, recoverable exceptions and controlled fatal exceptions.
- `python qa/mutate_runtime_evidence.py --out /tmp/waex-version-negative
  --only foreign-resource-owner foreign-module-context
  resource-context-loads-code fatal-resource-error-hidden` requires each unsafe
  variant to compile and then fail a behavioral assertion.
- `python qa/verify_runtime_framework.py --out /tmp/waex-version-framework`
  compiles the unchanged production utilities against Android/Compose APIs.
  Its current five core JUnit methods include the supplied, hash-pinned public
  Debug/Release resource tables in 22 locale/variant contexts and rejection of
  a numeric fallback into the fixture application's actual resource table.
  When the localization fixture is present, seven additional resource methods
  are required (12 executions total). The harness also compiles the actual
  `LsposedScopeReader.kt`, which defines the `LsposedScopeSnapshot` type used
  by the production package detector. Omitting that source causes unresolved
  Kotlin symbols before any test can run, as observed in general CI. Including
  it is a compile-contract repair, **not** proof of LSPosed database access,
  per-user scope correctness or injection on Android.

  The framework resource expectations are tied to the verified contracts.
  `UniversalVersionValidator.readBuiltinRules` classifies a hidden module or
  foreign resource owner as `RULES_UNAVAILABLE`, even when the version would
  match a visible module's rules. Visible module resources must still accept
  `2.26.40.76` from the pinned public table and reject unknown versions;
  explicit custom rules and bypass remain distinct. No rules were read in
  the hidden/foreign cases, so `UNSUPPORTED` would be a false diagnosis.
  The localization fixture uses the current production identifiers
  `waex_ui_online`, `waex_ui_unable_to_save_setting`, and
  `waex_ui_last_seen_1_s`, replacing former hash-suffixed names removed
  during upstream resource reconciliation (`4c6f443`). The test retains
  independent Spanish text, plural, preference and fallback expectations.

  On Windows, the runner resolves its Git Bash executable from `PATH` and
  supplies a slash-separated Gradle wrapper path. Its shared localization
  builder locates the real SDK `aapt2.exe` in Windows build-tools as well as
  extensionless `aapt2` on Unix. A previous hash-verified **public** 2.0.3
  Debug/Release APK pair may be supplied with `--public-apk-cache <directory>`
  to avoid repeat downloads; each APK is read with the same size limit and
  SHA-256 gate as the direct download. Generated Kotlin Gradle-DSL string
  arguments also use slash-separated paths so Windows backslashes cannot
  become invalid escape sequences. These are tool portability and
  evidence-reuse paths, without changing the Android sources, JUnit fixture
  or resource provenance checks. Build and test results must still be assessed
  independently for the exact JDK/SDK and source revision.

## Device acceptance

On authorized Debug and optimized Release builds, test a supported and an
unsupported WhatsApp version with WAEX visible, hidden by package-visibility
configuration, and visible again after restart. Confirm the difference between
unavailable rules and an actual version mismatch. Inspect the module resource
owner and the requested context flags; do not bypass validation to hide an
access problem. Repeat with each supported target package and the actual
LSPosed/HMA setup. Framework wrappers do not reproduce those environments.

These checks validate rule provenance and failure classification. A matching
version rule is not a certificate of host authenticity or functional acceptance
of every hook. No full signed WAEX APK is built by the isolated framework test.

References:
- [Android Context.createPackageContext](https://developer.android.com/reference/android/content/Context#createPackageContext(java.lang.String,%20int))
- [Android Resources.getResourceName](https://developer.android.com/reference/android/content/res/Resources#getResourceName(int))
