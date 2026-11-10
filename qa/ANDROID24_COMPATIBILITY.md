# Explicit older APIs and legacy cache acceptance

The app declares `minSdk = 24`. The shared source policy and cache checks below
apply to the current public tree. They must remain runnable independently of
feature branches that the maintainer has reverted.

## Native platform contracts and compiler backports

| Detected call | Native Android API floor | Explicit older operation used here |
|---|---|---|
| `List.of(E)` | 30 | `Collections.singletonList(T)`, available since API 1. The sole element is the non-null `String.class.getName()`, and the list is only used for structural signature equality. |
| `Method.getParameterCount()` | 26 | `Method.getParameterTypes().length`, available since API 1. Kotlin uses `parameterTypes.size` instead of the synthetic `parameterCount` property. |
| `Constructor.getParameterCount()` | 26 | `Constructor.getParameterTypes().length`, available since API 1. |
| `PackageInfo.getLongVersionCode()` | 28 | Use the long accessor from API 28; use the existing `versionCode` field on earlier Android versions. |

Primary Android references:

- [List.of](https://developer.android.com/reference/java/util/List#of(E)) and [Collections.singletonList](https://developer.android.com/reference/java/util/Collections#singletonList(T)).
- [Method parameter count and types](https://developer.android.com/reference/java/lang/reflect/Method) and [Constructor parameter count and types](https://developer.android.com/reference/java/lang/reflect/Constructor).
- [PackageInfo version APIs](https://developer.android.com/reference/android/content/pm/PackageInfo#getLongVersionCode()).

Native API introduction levels do not by themselves establish APK incompatibility. The reviewed [R8 backport registry](https://r8.googlesource.com/r8/+/master/src/main/java/com/android/tools/r8/ir/desugar/BackportedMethodRewriter.java) registers `Method.getParameterCount` below API 26 and the one-element `List.of` factory below API 30. These compiler backports do not require the project to enable `coreLibraryDesugaring`; the Method provider was added in [this 2023 commit](https://r8.googlesource.com/r8/+/a7563f86014d44f961f40fc109ab1c1073f2ee4e). Replacing 30 Method calls and one List factory therefore removes reliance on those backports; it does not demonstrate 31 failures in the built APK. No provider for `Constructor.getParameterCount` or `PackageInfo.getLongVersionCode` appeared in that reviewed file, but that scoped observation does not substitute for checking the final DEX or device behavior.

The original correction covered 31 reviewed reflection sites in 17 files, one
List factory and cache version lookup. The later feature reverts removed some
of those classes and restored two `Method` count calls. Their equivalent
`parameterTypes` length/empty checks retain the current selectors and do not
restore the reverted feature behavior. Where the Java selector also examines
parameter elements, it reuses the same array. DexKit
`getParamCount`/`paramCount` calls are a separate API and are unchanged.

## Required regression checks

Run `python3 qa/test_android24_compatibility.py` with the JDK available. This
requires the targeted explicit older APIs in source and JVM bytecode before
D8/R8 processing. It compiles the complete production `HookReturnTypeGuard`
without Android, DexKit or host doubles and inspects its generated classfiles
for the targeted member symbols. It also scans every production Java/Kotlin
source file for the factory and reflective-count spellings; the command reports
the actual counts for the tested tree. It has no dependency on the removed
view-once resolvers, preserved-message selector or their deleted test fixtures.

Use `--source-root PATH` to run the policy against a controlled source snapshot.
An unchanged `main` snapshot after the reverts fails for the two restored source
sites once the stale fixture dependency is removed. A compiled guard variant
that uses `Method.getParameterCount()` must be rejected for both the source site
and JVM member symbol. These controls validate the policy and do not predict
final APK failures. Compilation or dependency errors fail the command and are
not treated as successful policy rejections.

Run `python3 qa/test_dex_negative_cache.py` for actual production cache behavior. It first compiles against the modern PackageInfo shape, then loads the same production class with a legacy PackageInfo double whose bytecode has no `getLongVersionCode` method. It performs 38 cache checks for each of API 24, 25, 27 and 28 models, including host changes, unchanged-version preservation, cache retries and persisted descriptors. The API 28 model changes the upper version bits while retaining the low bits, so truncating the version to an int fails validation.

The old manager compiles, passes the modern model and fails host-version persistence on the legacy API 24 model: its catch handles the missing long accessor but exits before completing version validation and cache invalidation. `--baseline-manager PATH` reproduces that behavior using the complete earlier source. This separate behavioral control supports the cache correction. Both regression commands are mandatory in `.github/workflows/source-qa.yml`.

The remaining receipt-observation, preserved-own-revoke and return-type suites
validate their current contracts separately, with their documented platform
doubles. The policy does not claim that reverted feature suites still execute.

## Remaining device acceptance

These checks cover the APIs identified in this audit. The source policy does not inspect the final DEX or execute D8/R8. The checks are not a complete Android API lint, SDK compilation, Android emulator run, LSPosed injection check or certification of every app feature on Android 7.

Before publishing a build, test the integrated result on an Android 7/API 24 or 25 device: initialize the module, reopen the same host version without discarding valid descriptors, update the host and verify old descriptors are invalidated, and exercise the affected selectors and view-once actions. Also check an API 28+ device so long version codes and existing discovery behavior remain intact. Follow each feature's device acceptance guide for its host/UI behavior.
