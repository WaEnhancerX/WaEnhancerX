# Spanish resource completeness: acceptance and reproduction

## Change and source identity

The reference is upstream `nightly` at
`0aaffab0270a3d0832bef2895f6457f3792fd74a`.
The prepared branch is `fix/spanish-resource-completeness-20261117`.

The reference contains 1,806 default string resources, 1,560 Spanish strings,
and 1,542 explicit Latin American Spanish strings. The change adds 246 unique
translations to both Spanish configurations and copies the 18 existing Spanish
diagnostic strings into `values-b+es+419`. Both Spanish configurations now have
the same 1,806 string keys and the existing `one`/`other` recording plural.
The 18 diagnostic strings already had Spanish language fallback; they are not
18 newly translated functions. Existing resource values and default English
resources are preserved.

The independent checker is `qa/locale_resources/check_resources.py`. It reads
all strings/plurals XML files, checks duplicate/missing/extra resources,
attributes, paragraph and tab boundaries, locale consistency, local string
aliases, and complete argument/format/macro contracts. It also checks templates
marked `formatted="false"`: this attribute does not prevent `UiText.get` from
calling `String.format` when arguments are supplied.

The existing localization fixture now follows the current HMA display route:
`HmaDetailsBottomSheet` supplies `InternalLabels.hmaReason(item.reason)` as the
shared `DiagnosticsTargetCard` status, and that card displays `Text(text = status)`.
Three source mutations check raw reasons, a wrong display consumer, and a missing
status parameter. This is a source wiring check, not a Compose rendering test.

## Levels of validation

| Layer | What it checks | What it does not establish |
| --- | --- | --- |
| Existing source fixture | Resource coverage, existing routing/stable-key guards, HMA display chain | Android resource selection or screen layout |
| Independent checker | XML/alias/format contracts, including runtime-formatted strings with `formatted="false"` | AAPT2 compilation or Android execution |
| Existing AAPT2 verifier | Real compilation of public resources and an isolated linked resource table | Full WAEX build, installation, or UI behavior |
| Android table probe | Real `Resources`, locale fallback, formatting and plural selection in ART | `UiText`, preferences, provider/bridge, installed app UI, hooks or performance |

The isolated table contains public strings and plurals and is never installed.
No private module, signing key, account, app database, or licensing replacement
is required. The probe neither requests root nor restarts applications. It uses
the explicitly selected ADB device and temporary files created for the probe.

## Source and AAPT2 commands

Run from the candidate checkout. Each `--out` directory must be new; retain
earlier failures rather than overwriting evidence.

```bash
python3 qa/test_es419_localization.py
python3 qa/locale_resources/check_resources.py \
  --root . --out /path/to/new-evidence/source-contracts
python3 qa/verify_es419_resources.py \
  --aapt2 /path/to/android-sdk/build-tools/36.0.0/aapt2 \
  --out /path/to/new-evidence/aapt2
```

The source checker uses only Python's standard library. CI runs this independent
check immediately after the existing Spanish source/JVM step and preserves its
JSON evidence. CI does not run the ADB probe.

The separate existing `qa/test_es419_runtime.py` suite compiles production locale
helpers with explicit Android doubles; it requires a complete Kotlin distribution
and JDK. `qa/verify_runtime_framework.py` includes seven Robolectric localization
methods on API 34, but also requires its pinned public 2.0.3 APKs, Gradle, SDK and
other framework dependencies. Neither is silently substituted for the Android
table probe described below.

## Android command

The lab run used Windows 11, Python 3.15.0a5, Temurin 17.0.20.1, Android SDK 36,
build-tools 36.0.0, AAPT2 `2.20-13193326`, and `emulator-5560` running Android 14,
API 34, x86_64. The command below reproduces the run with fresh evidence paths.
The existing lab checkouts and toolchains are reused.

```powershell
$waexRoot = 'C:\WAEX-Lab\work\spanish-resource-completeness-20261117'
$waexSdk = 'C:\WAEX-Lab\android\sdk'
$waexJdk = 'C:\WAEX-Lab\tools\jdk17'
$waexEvidence = 'C:\WAEX-Lab\evidence\reports\waex-functional-provider-l10n-20261117\reproduction-01'

python "$waexRoot\qa\verify_es419_resources.py" `
  --aapt2 "$waexSdk\build-tools\36.0.0\aapt2.exe" `
  --out "$waexEvidence\aapt2"

python "$waexRoot\qa\locale_resources\android_probe\run_android_probe.py" `
  --root "$waexRoot" `
  --resources-apk "$waexEvidence\aapt2\resources-only.apk" `
  --java-home "$waexJdk" `
  --sdk-root "$waexSdk" `
  --build-tools 36.0.0 `
  --serial emulator-5560 `
  --out "$waexEvidence\android"
```

The runner requires `result.json` beside the resource table and compares its
source hashes with the current checkout. It compiles Java with the real SDK,
converts it with D8, and verifies SHA-256 of all three inputs after ADB transfer.
Each Android execution has a 40-second remote timeout and a bounded host timeout.

It creates one new `/data/local/tmp/waex-l10n-probe-<uuid>` directory, removes
only its three known files, and then uses `rmdir`. A cleanup failure is reported
and does not silently become PASS. It never installs the isolated APK or changes
global locale settings. Logs, source/input identities, exact commands, duration,
exit codes, structured observations and cleanup status are retained in `--out`.

## Verified result on 17 November 2026

The final R3 Android run passed for `es`, `es-419`, `es-MX`, `es-ES`, `es-CL`,
and `en`, with no change to `Locale.getDefault()` or system resource locales.

| Measurement | Result and counting unit |
| --- | --- |
| String values | PASS: 1,806 unique strings in six locales; 10,836 reads |
| Formatted strings | PASS: 125 string templates in six locales; 750 reads |
| Recording plural | PASS: quantities 0, 1, 2 and 99 in six locales; 24 selections |
| Expected wrong string value | Expected FAIL, exit 3, `NEGATIVE_EXPECTED_VALUE` |
| Expected wrong resource owner | Expected FAIL, exit 3, `NEGATIVE_RESOURCE_OWNER` |
| Expected wrong plural selection | Expected FAIL, exit 3, `NEGATIVE_PLURAL_SELECTION` |
| Expected unexpanded format | Expected FAIL, exit 3, `NEGATIVE_FORMAT_EXPANSION` |
| Global locale and cleanup | PASS: no global locale change; all own device files removed |

The four negative processes execute the same compiled probe and resource table.
A missing tool, timeout, compilation failure or unrelated exception does not count
as a detected negative control. Read counts are repetitions of resource checks,
not distinct features or independent defect fixes.

The source checker also passes 17 negative and 13 positive internal controls.
Its 127 templates per locale include the two plural forms and 13 string templates
marked `formatted="false"`; these counts overlap the Android resource checks.

The final private lab evidence is under
`C:\WAEX-Lab\evidence\reports\waex-functional-provider-l10n-20261117`:
the compiled table is `l10n-candidate\aapt2\resources-only.apk`, and the final
Android report is `android-l10n-r3\result.json`. The scripts record immutable
input hashes rather than treating a branch name or versionName as source identity.

## Oracle failures retained during development

R1 failed at `feat_app_interface_size_desc`: the oracle compared the literal
`@string/interface_size_desc` with Android's resolved value. Local alias chains
are now resolved from the raw XML value, with missing-target and cycle controls;
escaped and quoted references remain literal strings.

R2 passed the five Spanish locales and then failed at the English
`feat_anti_edit_messages_desc`. Its unescaped internal double quotes are Android
delimiters. The oracle now follows quote/whitespace state instead of stripping
only a surrounding pair. Escaped visible quotes and spaces inside quoted segments
are preserved. Comparing the full corpus changed exactly six English expected
values and no Spanish values. The app resources were not changed for either
oracle correction. R1 and R2 outputs remain failed historical runs; their
successful cleanup does not turn their resource assertions into passes.

The relevant primary contracts are:

- [Android string resource escaping and formatting](https://developer.android.com/guide/topics/resources/string-resource).
- [Java 17 Formatter argument indexing and conversions](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/Formatter.html).
- [AAPT2 ResourceParser raw-reference handling](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/tools/aapt2/ResourceParser.cpp).
- [AAPT2 StringBuilder quote, whitespace and escape handling](https://android.googlesource.com/platform/frameworks/base/+/49cb41f9d7d2/tools/aapt2/ResourceUtils.cpp).
- [AOSP StringBuilder controls](https://android.googlesource.com/platform/frameworks/base/+/940e490/tools/aapt2/ResourceUtils_test.cpp).

The independent decoder handles the unstyled corpus examined here. New markup,
external resource references or plural quantity sets require deliberate review;
the script does not silently claim complete Android resource-parser equivalence.

## Remaining release acceptance and recovery

Build and install the exact reviewed revision through the maintainer's legitimate
private build/signing pipeline, then inspect actual Spanish and es-419 screens:
reports and Telegram login, custom emojis, installer verification, themes,
runtime diagnostics and floating navigation. Check long labels, narrow displays,
large accessibility fonts, language changes and restart persistence. No report,
message, upload or installation action should be triggered merely to inspect text.

The table probe does not establish those UI outcomes, hook behavior, full APK
compatibility, or a performance improvement. Other Android versions, physical
devices, WhatsApp Business and work-profile UI remain NOT_VERIFIED for this change.

Recovery is a normal revert of the focused resource commit. It returns affected
labels to their preceding language fallback without migrating or deleting user
data. The separate QA commit can be reviewed and reverted independently.
