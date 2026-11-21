# Runtime framework staging after the injection-clock removal

## Exact failure and source decision

Upstream `WaEnhancerX/WaEnhancerX@0aaffab0270a3d0832bef2895f6457f3792fd74a` still lists `ui/components/RuntimeObservationClock.kt` as a compilation input in `qa/verify_runtime_framework.py`. Production commit `1b029ec88d9c4f7fbc88261f3bd2a9b16b16e342` (2026-11-13) deliberately removed that composable and switched target injection reporting to live PID/boot checks, with no heartbeat expiry. The runtime framework's actual JUnit resource/compatibility tests do **not** call or import the removed clock; this stage entry had become an unused prerequisite.

On Windows 11 with Python 3.15 and integrity-checked cached public WAEX 2.0.3 debug/release APK resource tables, the unchanged baseline failed **before Gradle or JUnit** with `FileNotFoundError: .../ui/components/RuntimeObservationClock.kt`. Its original CI job `134483068271` failed at this same removed path. This is a stale test-build source inventory, not a product runtime failure.

## Correction

Remove only that retired source path from the stage list; retain all seven current production runtime sources, the optional declared-targets Java file, the real resource/context/version tests, strict resource ownership and the existing 12-case full acceptance requirement. Update the runner's descriptive metadata to say it compiles current runtime utilities. Do not reintroduce old timestamp windows, fabricate the deleted clock class or suppress test failures.

`qa/verify_stability_framework.py` has a **different** old-clock test and still requires a separate semantic rewrite; this change does not silently claim to repair it.

## Versioned experiments and results

1. **FAIL as expected, upstream/main baseline:** `python qa/verify_runtime_framework.py --root <baseline> --out <new-baseline> --public-apk-cache <verified-2.0.3-apks>` returns exit1 on missing clock, before Android tests.
2. **Candidate on upstream/main:** copied all current runtime sources after the deletion, then independently failed in `qa/verify_es419_resources.py` at the pre-existing untranslated `custom_emojis_active_style` (`qualifiers ['']`). This is a *second source-tree problem*, not caused by the clock correction. Upstream PR [#175](https://github.com/WaEnhancerX/WaEnhancerX/pull/175) adds that value to `values-es/strings.xml` and related translations; do not remove localization assertions to obtain a green build.
3. **Control with the exact #175 production/resource tree:** its local worktree tree `c0c878a0d72b73b31f8e06909ed5cc03b3f3f549` matches remote PR #175 head `1950cdd88d514e3c83c04de9d0a3616fb4291579`. The unchanged runtime stage on that tree still fails on the removed clock. Applying this identical three-line fix makes the Spanish localization verification **PASS**: 1,806 compiled string names, three locale configurations, 3,612 Spanish values, plurals and four table-parser controls. Full framework sources and JUnit fixture were staged with source SHA256 identities, then Gradle 9.1.0 began configuration.
4. **NOT_VERIFIED:** the full candidate Android/Compose/Robolectric JUnit outcome. SentinelX's 29-second foreground limit terminated Gradle without a final `result.json` or JUnit XML. No product installation or WhatsApp runtime was involved. The current candidate cannot be reported as 12/12 PASS from these partial runs.

Pinned public 2.0.3 APK inputs, recovered without re-downloading: debug SHA256 `c4531d92851c5f251614e8af822f340737981adeb7128341463f8897e3d42114`, release SHA256 `f19d64239b493785b48540dce068a32a295f33bd33b18e48b46d168896c4ac0f`. Host: Windows 11, SDK 36, JDK 17.0.20.1, Gradle 9.1.0; official CI uses JDK 21. Existing local experiment evidence: `C:\WAEX-Lab\evidence\waex-runtime-framework-stage-20261118`. A separate JVM test already compiled the **actual** current `InjectionEvidence.kt`: 14 assertions PASS and two deliberately compiled negative mutations rejected; this is not an Android-framework result.

## Integration gate, risk and recovery

This is a QA-only source-inventory fix with **no changes** to injection, compatibility rules, hooks, resource tables or private modules. It is mergeable independently of #175, but the overall runtime job still needs the Spanish resources in #175 to pass. Re-run the same pinned 12-case framework after that dependency is present, check 12/12 and zero skips/failures, and include source/dependency hashes. A normal revert restores the previous runner; do not add a fake clock to mask incompatibility.
