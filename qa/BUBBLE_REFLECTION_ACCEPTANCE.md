# Bubble reflection: explicit older API acceptance

## Change and invariant

The candidate based on `c089458569564c241e6e2cddbd5a4de301299f28`
replaces exactly three `Method.parameterCount` reads in
`ChatBubbleColorsHook.kt` with `Method.parameterTypes.size`.

The wrapper still accepts one or two parameters with an integer first
parameter; the border still has three parameters; the border direction remains
the second argument and other directions remain the first. Factory ordering,
palette handling, target scope and preference precedence are unchanged by this
patch. The existing source policy requires an explicit older reflection API;
see [the platform-contract and compiler-backport discussion](ANDROID24_COMPATIBILITY.md).

This is a source compatibility refactor. It makes these operations independent
of the parameter-count backport. It is not a demonstrated API 24 crash fix
and makes no performance claim.

## Focused validation, 2026-11-27

| Check | Reference | Candidate |
|---|---|---|
| Unmodified `qa/test_android24_compatibility.py` | Compiles the real `HookReturnTypeGuard`, then rejects the three expected bubble source sites; zero forbidden references in that guard's bytecode | Passes: 324 production source files and one production classfile examined |
| Retained C11 exact selection/direction fixture | 8/8 behavior checks pass; three `getParameterCount` calls in the extracted JVM bytecode | 8/8 behavior checks pass; zero such calls |
| Explicit composition with #250 preference precedence | #250 is a separate change | Complete composed hook, actual `BaseFeature`, schema and alias policy compile; all 16 cases pass for each of two target packages |

The fresh source-policy and composition runs used Temurin JDK
`21.0.13.1+2-LTS`, javac `--release 17`, Kotlin `2.0.20` with JVM target 17
for the composed hook, and Python `3.11.18`. The composition fixture uses
explicit Android, DexKit, hook-registration, logging and lazy privacy boundaries.
It exercises the actual preference reader, hook registration and color callback;
it does not execute Android rendering, live IPC or real host injection.

The C11 behavior receipt was reused only after comparing the complete archived
source with the current source, allowing exactly CRLF-to-LF conversion, and
verifying the four extracted selection/direction fragments against the fixture.
The eight checks cover candidate selection, empty or wrong signatures, wrapper
and border directions, missing arguments and wrong argument types. They were
not rerun. The new composition run uses the unchanged #250 runner with an
explicit alternate hook source; all 951 tracked entries in the original local
integration snapshot were byte-checked before and after execution.

| Hook source | SHA256 of canonical LF bytes |
|---|---|
| c089 reference | `941f050c737577e87485379c422d429b2e8a9eeb27b85a57eed23d2da0de0f67` |
| This standalone candidate | `6d001368c918fe057eb099223cf885434946e65e8bfd26f2cddbf4e6b0b18647` |
| Explicit candidate + #250 composition | `f246e9d830cad304f722aae398869e451804541878672126f4206f482845031f` |

## Preserved counterevidence from Android 7 (API 24)

The earlier C22 test used disposable APKs on an Android 7/API 24 x86_64
emulator with real Android drawables, canvas and preferences, and synthetic
lookup/registration. Both the reference and candidate logged 21 passing checks
and 12 matching pixel observations.

The original differential receipt remains `accepted: false`: its oracle expected
the reference to fail, but the reference passed. DEX disassembly explains why:
the reference contains a D8-generated `ExternalSyntheticBackport0` using
`Method.getParameterTypes`; both APKs refer to that older reflection method.
This refutes a crash claim for the tested reference APK. It does not establish
acceptance of a current complete module or every host/device combination.

The receipt SHA256 is
`aad6c74c64b2a7cc14d46894346478f4832178e00456ee865711fafe93d262fc`;
the separate DEX disassembly receipt is
`dee295f4eae2c30d1c9ea79d470712c8c339edba5a1bf4c5ce43f486cd1f4195`.
These historical observations were preserved, not repeated or relabeled as a
successful crash regression.

## Reproduction and limits

Run `python qa/test_android24_compatibility.py` with a complete JDK on PATH.
Run the same command in an unchanged c089 checkout to reproduce the three
expected source-policy rejections after successful guard compilation.

For the separate #250 combination, use its unchanged
`qa/test_bubble_color_precedence.py --source PATH_TO_COMPOSED_HOOK
--kotlin-home PATH_TO_KOTLIN`. The composition is exactly the three reflection
substitutions plus #250's primary/legacy preference correction; applying these
two source deltas in either order produces identical bytes.

No new emulator, physical-device or UI operation was performed. No dependency
was downloaded. The current checks establish explicit source policy and scoped
JVM behavior; they do not establish a complete APK build, live WhatsApp behavior,
resolution of reported click failures, or a complete Source QA pass.
