# Typed DEX cache generation — current-main reconciliation

This updates PR #107 against main 4750124e36223dd21d8535c14f2fb658025d8480. Preserve the upstream typed descriptor implementation rather than replacing it with the older per-kind cache.

## Contract

Keep typed class/method/field keys, arrays, schema, method return and field type validation, telemetry, key locks and visible module version/reinstall metadata. Supplement that identity with compiled BuildConfig version code/name and a descriptor epoch, so a hidden manager package cannot hide a module-only generation change. Advance the epoch for incompatible selector semantics shipped under unchanged version strings/codes.

A host identity validation exception disables disk descriptor reads, writes and public eviction for that process while memory/resolution remain available. No root, package-visibility repair, cross-profile permission or blind disk clearing is added.

Recheck failed-lookup cooldown inside the key monitor: callers already waiting when a leader records a miss must not each repeat the same resolver. The controlled waiting-cohort result is not a guarantee of single-flight under arbitrary concurrent eviction/reconfiguration.

## Commands and evidence

    python3 qa/test_dex_negative_cache.py
    python3 qa/mutate_dex_cache_generation.py --evidence /tmp/waex-dex-mutants

The full production cache and negative-cache class compile on JDK 17+. Android, package metadata, preferences and applicable JSON boundaries are explicit JVM fixtures. API-shaped runs24/25/27/28 are not Android device executions.

The suite passes 66 cache integration checks for each API-shaped fixture, 24 direct cooldown assertions, 32,000 concurrent negative-cache iterations and nine source integration checks. Additional JVM processes share a real descriptor file while old, corrected and hotfix target classes all remain resolvable. Each fresh generation resolves five descriptor kinds once and invalidates once; each unchanged restart performs zero new resolutions and zero invalidations. Class/method arrays are covered along with individual class,method,field descriptors.

A visible same-version module reinstall still invalidates by timestamp. When the manager package is hidden, compiled code/name changes still invalidate. Unknown host metadata leaves stored descriptors unchanged, including array results and eviction attempts.

Official current main compiles and fails the generation experiment by returning an obsolete descriptor. Six compiling mutants fail specific assertions: ignore generation, clear every launch, omit name identity, trust unvalidated disk, omit the post-wait cooldown check, or persist unknown-host results. The healthy candidate is required to pass before evaluating mutants.

## Limits

No native DEX search, Android SharedPreferences disk fault, actual HMA/work-profile Binder path, phone startup benchmark or complete candidate APK is certified. No source QA operation count is converted into saved milliseconds. The branch has no dependency on a private licensing implementation for these tests and does not bypass it for a full build.

On a legitimate build, verify unchanged cold launches, module-only update with unchanged host, host update, hidden-manager lookup, profile isolation and all enabled selector consumers. Confirm exact installed versions and descriptor identities without publishing message/account data. Retain existing cache consumers and their tests when integrating sibling PRs.
