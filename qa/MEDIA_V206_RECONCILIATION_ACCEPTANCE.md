# Media and incoming view-once reconciliation — WAEX 2.0.6

**Candidate:** focused reconciliation of existing PR #106 against the upstream `4750124e36223dd21d8535c14f2fb658025d8480` baseline. The older PR head is `80be2429761cd7a34d7c44d56077f02dd74f5392`.

## Retained production behavior

- Preserve the modern `MediaQualityBypassHook.configurePropertyOverrides` API, all current image-property ID tables and early invocation before custom A/B values are loaded.
- Preserve the current `ProcessImageQuality` constructor route, but never associate `getUsingStrings()` and `getUsingFields()` solely by their list positions.
- Preserve incoming-only, exact-message-key view-once state filtering, the verified setter interface, concrete method registrations and the two independently checked JID namespace families.
- Preserve the host's exceptions, unknown values and unrelated integer arguments. Only verified callbacks can modify one quality argument or raise a successful positive FPS hint below 60; this does **not** establish actual output bitrate, frame rate, resolution or original-quality delivery.
- No private licensing code, production signing keys, files, message content, contact identifiers or account credentials are introduced.

## Deliberately restricted host contract

The original host inspection used the official Messenger **2.26.43.76** APK base, versionCode `264307627`, SHA-256 `a1b3b262a86c8ed928bce8b777da69a2324607356bd03d814396a66bbc27da55`, 144,362,968 bytes. The candidate gates the narrow selector/constructor path by an exact base-APK SHA-256 and package identity; it is **not** an acceptance of arbitrary WhatsApp/Business versions or other ROMs. The APK checksum may add cold-start I/O and requires a device-level performance comparison.

The literal anchors `image/compress quality`, `video/encoder fps` and `enable_media_quality_tool` were absent from all 13 DEX of the inspected base APK. Passing synthetic method fixtures cannot prove those legacy callbacks install on this version. No new generic anchor guesses are introduced.

The native DexKit `app_process` shell probe separately matched the inspected helper class/method contracts and tested reflected field writes on three constructed host instances (**14/14** in its last recorded complete run). This is not an LSPosed callback/WhatsApp UI test, actual media upload or a full WAEX APK build.

### Contract and precedence invariants

1. Never choose the first of multiple valid-looking DexKit candidates; reject ambiguity and use new cache-key generations for changed search contracts.
2. The view-once transition is **1 to 0 only for an incoming message** with a verified, immutable primary key and unique setter. Outgoing/unknown identities and unrelated receivers are unchanged.
3. The structured quality route requires a unique concrete class, one verified `(int,int,int)` constructor, a checked field set and the exact known host format. Before writing, confirm the three distinct fields against the object's own formatted values, rejecting equal/ambiguous labels.
4. Existing dimensions must not be reduced. Explicit custom A/B properties take precedence; an unsupported alternate ID family or inconsistent/missing configuration disables this extra constructor write instead of guessing.
5. Failure while writing reflected fields disables further writes on that callback. Restoration is **best effort**, not atomic. The log distinguishes restored and incomplete rollback without including object contents.
6. A successful installed callback is not evidence of all media sub-options working. Explicitly record partial availability; do not say `ACTIVE` proves HD transfer or FPS.
7. A test fixture can model Xposed dispatch and Android APIs, but a synthetic `PASS` cannot be promoted to production WhatsApp compatibility.

## Executable regression suite

Use Python 3, JDK 17+ and the unmodified DexKit **2.2.0 classes.jar** (SHA-256 `195979d9576ba6d68a1c60bdd3aed068d4e0c5c6c99caa50bc25fcb9f1`) plus a matching Kotlin stdlib.

```sh
python3 qa/test_media_hook_contracts.py --jid-family modern
python3 qa/test_media_hook_contracts.py --jid-family legacy
python3 qa/compile_media_dexkit_api.py --dexkit-jar /path/to/dexkit-2.2.0.jar --kotlin-stdlib /path/to/kotlin-stdlib.jar
python3 qa/test_media_v206_field_contract.py --dexkit-jar /path/to/dexkit-2.2.0.jar --kotlin-stdlib /path/to/kotlin-stdlib.jar --out /tmp/waex-media-fields
python3 qa/mutate_media_v206_hardening.py --dexkit-jar /path/to/dexkit-2.2.0.jar --kotlin-stdlib /path/to/kotlin-stdlib.jar --out /tmp/waex-media-mutants
```

The complete production media/view-once hook fixtures have **237 behavioral checks per JID family** in the last complete local suite. The production field/precedence/callback fixture has **56 checks**, including a reported failed rollback. **25 mutation variants** must compile, complete their respective oracles and fail a behavioral assertion. The runner rejects compiler errors and incomplete tests as invalid mutation outcomes.

The complete public media hook classes must type-check against **real DexKit types**, not invented classes. The separate shell probe is optional and must record the actual APK, native-library and ABI identity. No account, chats, messages or private data are needed for the source gates.

## Known limits and acceptance that remains

- The exact-APK gate is an intentionally narrow compatibility decision. It does not confirm current Play/Beta, WhatsApp Business, work profiles, OEM builds or Android 16/17.
- The DEX recognizers compare checked method shapes and constants under the pinned APK. They are **not** a general-purpose semantic-equivalence prover. An unknown APK is deliberately unsupported, not automatically compatible.
- APK identity caching by path/length/mtime is an optimization and not an adversarial replacement guarantee. Re-running the package fingerprint after update is required.
- Multiple concurrent property updates need a coherent-snapshot contract; stable startup-time configuration is the tested case.
- `unhookAll()` clears retained Xposed handles, while feature-local installed-method sets may remain; reuse of an identical feature object after unhook is not certified.
- Reflected updates and rollback are not all-or-nothing; failed restoration emits a bounded, content-free diagnostic.
- The latest known full WAEX Debug/Release build remains **BLOCKED** by private `:licensing` and official signing artifacts. A standalone APK or shell probe does not substitute for the full module.
- With an authorized test build, measure native hook installation and actual host callbacks, editor UI and output, exceptions, cross-profile routing, memory and cold-start I/O on multiple WhatsApp and Android versions. Verify real media bytes and call paths separately.
- Do not publish claims of compatibility for every ROM or device based on one tested x86_64 emulator.

## Evidence

Lab-specific source and execution identities are stored in the `waex-pr106-*` evidence directories. The final gate includes both JID families, genuine DexKit API compilation, value/field semantics, negative mutation controls and unchanged-source hashes.

This file is a **source-level acceptance specification**, not a complete user-facing feature certificate.
