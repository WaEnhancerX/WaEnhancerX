# WaEnhancerX 2.0.6 Spanish resource and harness acceptance

Both values-es and values-b+es+419 receive55 missing string identifiers, restoring explicit coverage of1,547 default keys. Positional placeholders, formatting flags and literal ${t} templates are preserved. The upstream timestamp example is intentionally unchanged rather than silently editing its semantics.

## Commands

    python3 qa/test_es419_localization.py
    python3 qa/test_es419_runtime.py --kotlin-home /path/to/kotlinc
    python3 qa/mutate_es419.py --out /tmp/waex-l10n-mutants
    python3 qa/verify_es419_resources.py --aapt2 /path/to/aapt2 --out /tmp/waex-l10n-table

Set KOTLIN_HOME to the same complete distribution for mutation subprocesses. Wrappers on PATH alone are not assumed to reveal the actual installation. Sources, generated fixtures and logs use explicit UTF-8. The Kotlin compiler is invoked through its JVM main class and runtime classpaths use os.pathsep.

## Evidence

Real AAPT2 compile/link/dump:1,547 names, default/es/es-419 qualifiers,3,094 exact Spanish values, plurals and four parser controls. JVM:3,224 production resource/format/locale checks plus10,000 cache-hit reads; Kotlin:7 picker/context checks. Four behavioral mutants compile and are rejected. Dedicated and mixed XML files are included in mutation staging.

The Windows recheck used PYTHONUTF8=0, explicit UTF-8 IO and JDK17/Kotlin2.0.20. It is not proof of every Windows locale or every launcher layout. Linux CI must run separately. AAPT2 was36.0.0 locally; source XML checks do not replace runtime resource selection.

## Limits

No private licensing module is substituted and no complete signed APK is built by this suite. Android Context/resources remain explicit JVM doubles in runtime tests. Visual layout, accessible labels and editor review on the complete APK remain pending. A locale PASS does not prove DenyList repair, parallel downloads, custom props or other labelled features work.
