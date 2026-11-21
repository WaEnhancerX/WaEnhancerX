# Lightweight verification and APK compatibility preflight

These checks are **diagnostics**, not evidence that WhatsApp hooks are working. They do not need the Android SDK, root, LSPosed, or a device. A full Gradle build and real-device tests are still required before merging behavior changes.

## Available scripts on this branch

| Script | Requirements | Scope |
| --- | --- | --- |
| `audit_apks.py` | Python 3.10+ standard library; **locally supplied** manager and WhatsApp APKs | ZIP CRC, manifest presence, DEX headers and SHA-1/Adler-32, ABI directory summary, 18 sampled DEX string-pool literals |
| `test_audit_apks.py` | Python 3.10+ | Synthetic malformed ZIP and DEX fixtures, correct multidex enumeration, CLI exit codes and JSON output |
| `audit_native_alignment.py` | Python 3.10+; locally supplied APK | Bounded ELF32/ELF64 parsing, 64-bit load-segment alignment and stored native ZIP offsets; distinct structural and alignment exit codes |
| `test_native_alignment.py` | Python 3.10+ | Synthetic ELF/ZIP corruption, endian/class, alignment, ABI and CLI regressions; see `NATIVE_ALIGNMENT_ACCEPTANCE.md` |
| `dex_source_anchor_inventory.py` | Python 3.10+; **locally supplied** WhatsApp APK | Generate a full, source-derived list of `addUsingString` literals and report which occur in DEX string tables. An absent anchor blocks **only that literal search**, not all feature implementations. |
| `test_dex_source_anchor_inventory.py` | Python 3.10+ | Synthetic source/DEX fixtures for complete anchor discovery, strict exit status, malformed checksums and table-only matching |
| `feature_contract_inventory.py` | Python 3.10+; project source | Read-only catalog/schema/direct Xposed-key reference inventory. Lists review flags, **not** proven broken features or executed hooks. |
| `test_feature_contract_inventory.py` | Python 3.10+ | Current-source smoke checks and synthetic tests for duplicate keys, source references and legacy alias metadata |
| `preference_type_audit.py` | Python 3.10+ | Report literal get/put/isEnabled calls incompatible with PreferenceSchema; distinguish writes from intentional legacy reads. |
| `test_preference_type_audit.py` | Python 3.10+ | Synthetic key type and comment-filtering fixtures plus current-source smoke checks. |
| `test_hook_return_types.py` | Python 3 and JDK | Hook return/parameter contract checks |
| `test_host_class_relocations.py` | Python 3 and a **locally supplied** WhatsApp APK | Exact host class definitions for a known version (expected to change across WhatsApp releases) |
| `test_monet_wiring.py` | Python 3 | Source-level dynamic-theme wiring, not UI instrumentation |
| `android_call_type_test.py` | JDK, Kotlin, Python 3 and Maven; public dependencies bootstrap automatically | Complete phone/WhatsApp selector and installer on Android 24/28/34 with explicit host/Xposed fixtures; see `CALL_TYPE_ACCEPTANCE.md` |

This table is a starting point, not a complete list of the current QA gates.
Use the scripts and workflow present in the checked-out revision to determine
which checks are available and required. Historical PR states do not establish
whether a script is present in that revision.

## Run the APK audit

From the repository root, with **your own** installed/version-matched APK copies:

```sh
python3 qa/audit_apks.py /path/to/WaEnhancerX.apk /path/to/WhatsApp.apk
python3 qa/audit_apks.py /path/to/WaEnhancerX.apk /path/to/WhatsApp.apk --json
python3 -m unittest discover -s qa -p 'test_audit_apks.py' -v
```

`--json` prints a structured report (schema version 1) with filename, SHA-256, ZIP/DEX counts, ABI folders and the sampled literal status. It intentionally does not output absolute local file paths. The APKs are only **read**, never modified or copied into the repository.

Exit codes: **0** = archive/DEX checks passed; **2** = a malformed or unreadable APK; **3** = sampled strings absent with the optional `--strict-anchors` flag. Without `--strict-anchors`, missing literals are reported but do not fail the run, because they may legitimately change across WhatsApp versions.

## Check all literal DexKit anchors from the current code

```sh
python3 qa/dex_source_anchor_inventory.py /path/to/WhatsApp.apk --source-root app/src/main/java
python3 qa/dex_source_anchor_inventory.py /path/to/WhatsApp.apk --source-root app/src/main/java --json
python3 -m unittest discover -s qa -p 'test_dex_source_anchor_inventory.py' -v
```

This second script inventories the current checked-out Java/Kotlin source, rather
than relying on the **18 historical samples** in `audit_apks.py`. Different
source revisions can have different numbers of anchors. It does not execute
DexKit or prove that the host method's class, arguments, and return value match.
Avoid claiming a feature is fixed from the presence of an anchor alone.

## Review advertised feature preference wiring

```sh
python3 qa/feature_contract_inventory.py
python3 qa/feature_contract_inventory.py --markdown > feature_inventory.md
python3 qa/feature_contract_inventory.py --json > feature_inventory.json
python3 qa/test_feature_contract_inventory.py
```

The inventory identifies catalog keys absent from `PreferenceSchema` and keys
without **direct** references in the public Xposed source. This is a review
queue, **not proof a feature is broken**: direct references may be hidden behind
constants, dynamic names or the private `:licensing` module, while even
literal references in a hook do not prove the hook installed successfully.

## Inspect literal preference type mismatches

```sh
python3 qa/preference_type_audit.py
python3 qa/preference_type_audit.py --json
python3 qa/test_preference_type_audit.py
# Fail on mismatched writes in the checked-out source:
python3 qa/preference_type_audit.py --fail-on-writes
```

The earlier version-2.0.2 snapshot, reviewed before the Archive/Anti-Revoke UI
correction, contained two direct schema-mismatched writes to
`anti_revoke_indicator` and `deleted_message_color`. Those counts describe that
historical source, not the current checkout. Run the audit against the intended
revision to obtain its current mismatched-write count. Guarded legacy reads in
AntiRevokeHook are reported for manual classification.

This is a literal-source review only: dynamic keys, indirect write helpers,
private licensing code and legacy fallback semantics cannot be proven by
matching method calls alone.

## Inspect declared DEX fields without guessing obfuscated names

For a locally supplied, authorized WhatsApp APK, inspect field declarations in
SendE2EMessageJob and check whether its jid field actually exists:

    python3 qa/dex_field_inventory.py /path/to/WhatsApp.apk --class-descriptor 'Lcom/whatsapp/jobqueue/job/SendE2EMessageJob;' --expected-field jid
    python3 qa/dex_field_inventory.py /path/to/WhatsApp.apk --class-descriptor 'Lcom/whatsapp/jobqueue/job/SendE2EMessageJob;' --expected-field jid --fail-on-missing
    python3 qa/dex_field_inventory.py /path/to/WhatsApp.apk --class-descriptor 'Lcom/whatsapp/jobqueue/job/SendE2EMessageJob;' --expected-field jid --expected-type 'Ljava/lang/String;' --fail-on-missing
    python3 qa/test_dex_field_inventory.py

The tool reads the DEX class_defs, class_data_item, and encoded field tables
(not just string matches). On the user-supplied WhatsApp 2.26.39.79 APK,
SendE2EMessageJob declares a jid string field and also has obfuscated
typed JID fields. The latter **do not** establish which field represents the
outgoing conversation; do not infer it from name or field order.

The scanner refuses oversized ZIP/DEX inputs, duplicate DEX entries or class definitions, and can require the expected field type descriptor in addition to its name. Synthetic regressions cover these fail-closed cases.

This is structural metadata verification only. It neither validates the value
read at runtime nor proves the intended receipt is released. Do not redistribute
the host APK, and test any hook behavior separately on a verified device.

## What the report does *not* establish

- **DEX strings are not hooks.** The 18 literals are a historical sample, not a complete list of hooks or the result of executing DexKit's method-matcher predicates. A present string can refer to the wrong code; an absent string may have alternate resolution paths.
- **ZIP CRC and DEX checksums are integrity checks, not authentication.** They do not validate the APK signing certificate, trusted release source, package identifier, or update provenance.
- **ABI directory listing is not ELF analysis.** It does not verify 16 KiB page-size ELF segment alignment or APK ZIP entry alignment.
- **An archive is not a running app.** No output can prove compatibility with WhatsApp 2.26.39.79, WhatsApp Business, modified clones, newer/older versions, Android API levels, LSPosed, preferences IPC, UI tabs, or message-history migrations.
- **Binary manifest version detection is intentionally omitted.** Finding a version-like byte sequence inside binary AXML would not reliably identify the installed package/version.

The auditor caps the number of ZIP entries and uncompressed input size to avoid accidentally processing excessive files. Do not commit or redistribute proprietary WhatsApp APKs, DEX dumps or private signing keys. Handle diagnostic reports according to your organization's privacy and disclosure policies.

For PRs affecting runtime hooks, also run the repository's supported Android Gradle/CI checks and test representative official WhatsApp, WhatsApp Business and supported-clone targets on an authorized device.

## Localized catalog source (October 2026)

`feature_contract_inventory.py` and `test_catalog_schema_alias_contract.py` read
`FeatureCatalog.kt` and its authoritative `preference_catalog.json` asset. Titles
come from default Android string resources, not copied historical English text.
The reader preserves the reviewed canonical route overrides and first-placement
deduplication. The current 95 placements represent 93 distinct feature keys.
`catalog_line` is null for asset entries; `catalog_source` identifies the JSON.
Legacy inline source remains supported for historical audit fixtures.

Run `python3 qa/test_catalog_source.py` for positive/negative extraction fixtures.
This validates source metadata, not resource linking, Android locale fallback,
Pro entitlements or functionality of any hook. UI source guards follow the current
localized rendering path and the relocated Monet selector; preference, API-level,
save-failure and unsafe-editor assertions remain enabled. No production source
or host APK is changed by this QA follow-up.

The localized reader extends the upstream JSON migration; its synthetic fixture
retains schema/alias assertions and supplies the same routing/resources as production.
