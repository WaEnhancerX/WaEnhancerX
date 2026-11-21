# Chinese localization acceptance

## Change scope

Baseline: `9afc43d9582eb7afa84f9ce45eed746252332241` (upstream `main`).

- Complete Simplified Chinese (`values-zh-rCN`) and add independently selectable
  Hong Kong / Taiwan Traditional Chinese (`values-zh-rHK`, `values-zh-rTW`).
- Each Chinese locale contains **1,840 string resources** (including two resource
  aliases) and **one plural resource** with the source `one` / `other` selectors.
- Simplified Chinese adds **298 keys**: 280 previously missing resource keys and
  18 extracted Emoji-screen strings. It updates **321 existing string values**
  and corrects the recording plural's missing measure word.
- Register `zh-HK` / `zh-TW` in `LocaleHelper` and retain both resource qualifiers
  in `androidResources.localeFilters`; keep the stored language codes unchanged.
- Localize visible text and accessibility labels in `CustomEmojisScreen` without
  changing its download, deletion, font-selection or restart behavior.
- Preserve existing contributors, including the original Simplified Chinese
  translation contributor, and propose a separate README translation credit for
  Walter498. Credit presentation is not enforced by the resource tests.

The supplied 325-entry review pack was checked against current English resources.
Existing Chinese text was retained where suitable; terminology, broken machine
translations and formatting defects were corrected. Traditional variants started
from OpenCC conversion and received regional terminology and per-key review; this
is not a claim that every legacy sentence received equally deep editorial review.

## Reproducible checks

```sh
python3 qa/test_chinese_localization.py
python3 qa/verify_chinese_resources.py \
  --aapt2 /path/to/android-sdk/build-tools/36.0.0/aapt2 \
  --out /new/path/chinese-resource-evidence
```

The dedicated `Chinese localization resource checks` workflow also invokes the
repository's real Kotlin compiler/PSI parser on the two changed Kotlin files.

Source checks cover unique keys and full coverage; resource types and formatting
attributes; positional arguments, literal percent and `${t}` templates; boundary
whitespace; Tasker parameter names; technical terms; region-specific terminology;
language registration and packaging; Emoji call-site references and deletion
wording; and Android aliases. They include negative controls.

The AAPT2 verifier actually compiles the complete public resource tree and links
an isolated default/CN/HK/TW string table. It compares **5,520 Chinese resource
configurations**, including the **six alias references**, against the source XML,
checks four plural configurations, and rejects five corrupted table controls.
It writes the tool version, command results, resource-table dump and SHA-256
identities. A quoted `"@string/..."` is explicitly rejected as a literal, not
mistaken for a working alias.

## Boundaries and remaining acceptance

These checks are not a full WAEX APK build, Compose type-check, emulator test,
LSPosed/WhatsApp injection test or visual acceptance. A full configured build
still needs the private licensing project and the maintainer's signing setup,
as documented in `CONTRIBUTING.md`.

On a maintainer build, check all three language choices; long diagnostics and
installer warnings; Emoji download/delete/clear/restart flows; compact labels and
accessibility descriptions; notification and report screens; and system-language
fallback. External exception messages, font-pack names, server content and brand
names can remain in their originating language.

The new English Emoji resources deliberately fall back to English in locales not
translated by this change. This PR does not certify repository-wide source QA:
at the baseline, the existing Spanish / es-419 / Slovenian inventories already
omit 263 / 281 / 281 default string keys respectively. The 18 newly externalized
English strings add 18 fallback keys for each of those locales. These other-locale
coverage gaps should not be reported as passing Chinese or whole-project tests.
