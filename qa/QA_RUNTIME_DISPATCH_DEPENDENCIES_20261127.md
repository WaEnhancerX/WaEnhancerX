# Current runtime and preserved-dispatch QA dependencies

## Scope and provenance

This QA-only candidate is based on nightly
`c089458569564c241e6e2cddbd5a4de301299f28`. It does not change application,
hook, provider, preference, authorization or licensing behavior.

Recent source-QA runs for PRs #246–249 failed before the preserved-dispatch
tests because the isolated stage omitted `HookProviderIpc` and the lazy
`PrivacyPrefs` type referenced by the current `BaseFeature`. The runtime job
for #247 progressed beyond localization and exposed the same missing IPC
helper in `WhatsAppPackageDetector`.

- Reuse the two real `HookProviderIpc.java` staging additions from
  [PR #137](https://github.com/WaEnhancerX/WaEnhancerX/pull/137), reviewed at
  `6938c55ff3798c9cdaf9cc35b7a85df5a7ec5e36`. Its other lifecycle, startup and
  provider regressions remain separate; this is not another IPC implementation.
- Reuse the exact signature guard and fail-closed lazy `PrivacyPrefs.of`
  boundary from the existing C52 candidate. Preserved-message cases do not
  call this privacy service. An unexpected call throws `AssertionError`;
  the fixture does not return a permissive privacy answer.
- Preserve the support classes, source-contract guards and explicit service
  boundaries inherited from
  [PR #185](https://github.com/WaEnhancerX/WaEnhancerX/pull/185). Its historical
  99-case result is not reused as acceptance of this version. The current
  unchanged fixture has 35 methods across API 24, 28 and 34: 105 cases.
- C52's report-coherence changes, its larger C22 dependency and case-inventory
  infrastructure, C64's contact-reader fixture and C75's diagnostic/stability
  changes are not imported. The two descriptive C75 edits in
  `verify_runtime_framework.py` can coexist with this source-list addition.

Removing only these intended additions from the Python AST reproduces the
baseline AST of both runners. Existing test bodies, fixtures, acceptance
counts, negative cases, source guards and dependency identity checks remain
unchanged.

## Fresh reference/candidate comparison

| Suite | Reference | Candidate |
|---|---|---|
| Preserved dispatch | Kotlin compilation fails on `PrivacyPrefs` and `HookProviderIpc`; no JUnit cases run | All four compilation phases pass; 105 tests, zero failures |
| Runtime/resource framework | With the same locale prerequisite, Kotlin compilation fails on `HookProviderIpc`; no JUnit reports | 12 tests, zero failures, errors or skips: five runtime tests and seven localization tests |

The runtime suite covers 22 compiled-resource contexts, using its existing
hash-pinned public 2.0.3 debug/release APK tables. This is resource testing,
not installation or validation of a current full WAEX APK. The original
AAPT2 guard also passed: 1,832 compiled string names, 3,664 exact Spanish
values, plural forms and four table-parser controls.

Execution used Temurin JDK `21.0.13.1+2-LTS`, `javac --release 17`, Kotlin
`2.0.20` targeting JVM 17, Python `3.11.18`, Gradle `9.1.0`, AGP `9.0.1`,
Robolectric `4.17` and JUnit `4.13.2`. All 39 cached dependency-bundle members
were verified against their size/SHA256 manifest before use. The existing
DexKit 2.2.0 JAR and public APK pins were also checked. No dependencies were
downloaded and no Android device was operated.

The dispatch runner executed directly with its original `--deps`,
`--sdk-jars` and `--dexkit` options. The runtime runner executed through a
local launch adapter: it substituted cached native Gradle for the Windows
Bash wrapper, supplied valid stdin, added `--offline`, and set Robolectric's
offline SDK directory through an external init script. Both variants used
the same adapter. The production sources, test sources and expected counts
were unchanged; this is not a claim of a new direct Windows-wrapper pass.

## Separate locale prerequisite

The standalone c089 resource table is missing the Spanish bubble-control
strings. For both runtime variants, the test worktrees used only the two
`ui_strings.xml` files from
[PR #247](https://github.com/WaEnhancerX/WaEnhancerX/pull/247), head
`1638bc14c46870433fa29e9019fd1ac550199523`. Each file has SHA256
`6e656b8df758d008ea815b0c0fd96d43f627f0faebf41dcc8830546f1078a2f4`.

That overlay is explicitly separate from this patch. Without the locale fix,
the original locale guard remains a prerequisite and must keep failing.
The repair does not bypass the guard or claim the locale work is merged.

## Exact evidence identities

| Input or artifact | SHA256 |
|---|---|
| Candidate dispatch runner | `b0e7abefd5c3703d01fc1d7a43b81f5b1b106e6cc223fc45403ec10bbb32644d` |
| Candidate runtime runner | `0931c3ed5a855ce055a23684359ded6e5512518af46f7c4dd99a2b78cac66d7c` |
| Unchanged dispatch fixture, raw Windows compiled bytes | `b1cd28e9e1460f47a8e75a0376a5805685b46976242f05a87b5bdadba43ddb92` |
| Unchanged dispatch fixture, canonical Git LF bytes | `501f6c898bacd3fa4ecfb8398a40837672010e68cee9d994226e1661cfe3f184` |
| Real IPC helper, raw Windows compiled bytes | `796a044092ebf56ef7008702d4d1902ad1d628f3901fcb6ae81a5145d9a23272` |
| Real IPC helper, canonical Git LF bytes | `869dff38f76682aeb990e6c34d9ae15addbab7aad30a0d87c024488fb5822984` |
| Evidence archive, 42 records/logs/source records | `9837ee62f179e529be30bdd020000da3a218ebb2809023ed461a243d4dba278c` |
| Separate source-normalization receipt | `d42ebb764796fafa872df77f4d8d868e1445ba203cdd36f10819bb32a7e29db6` |

The local C78 evidence archive retains both failing references, both passing
candidate results, exact launch records, compilation logs, two runtime JUnit
XML reports, dependency inventories and source hashes. After execution, all
21 staged dispatch production sources and all runtime source inputs were
checked against their recorded hashes. The 11 dispatch source-contract
inputs were checked too. The archive is 53,091 bytes.

Windows Git uses `core.autocrlf=true`: the IPC helper and dispatch fixture
are `i/lf w/crlf`. Their compiled copies are byte-identical to that checkout,
with respectively 36 and 558 CRLF pairs and no standalone CR. Replacing only
`b'\r\n'` with `b'\n'` reproduces exactly `git show c089:<path>`. These raw
compiled hashes and canonical Git hashes identify different byte encodings
of the same source; neither substitutes for the other.

The separate `SOURCE_NORMALIZATION.json` checks 72 input records: 22 dispatch
production/test inputs, 11 compile-only contracts and 39 runtime inputs.
Seventy match their c089 blobs after that single transformation; the two
locale overlays match the separately pinned #247 bytes. The preexisting
compile-only contract collector hashes `read_text(encoding='utf-8').encode()`,
which normalizes newlines, whereas the recorded compiled inputs use raw-byte
hashes. This supplementary check changed no original receipts or archive and
reran no tests.

## Acceptance boundary

These results qualify the isolated QA dependency closure and the original
test cases. `HookProviderIpc` is the real production class; `PrivacyPrefs`
remains an explicitly guarded, unexecuted service boundary in this suite.
The tests do not establish physical Binder transport, actual WhatsApp
effects, native DexKit discovery, ART/LSPosed execution, a full module APK
build, or complete privacy-feature behavior. Hosted QA on the final
published commit remains pending. Retain the remaining #137 work and the
separate C52/C64/C75 candidates when reconciling their overlapping history.
