# Target-scoped settings backup contract

The settings UI stores target overrides as `package:key`. The injected preference
bridge overlays them only in `Per Target App` mode. The old backup codec enumerated
only unqualified schema keys and used exact schema lookup on import: overrides
were silently omitted in export and ignored in binary/JSON input. The mode key
itself was also absent from the schema. Universal-only round-trip tests could pass
while a per-target configuration was lost.

## Preserved behavior and scope

The codec now enumerates actual keys in public/private snapshots and resolves a
canonical schema entry without changing the stored key. A target prefix must obey
the Android application-ID grammar (at least two dot-separated segments, starting
with letters and containing ASCII letters, digits or underscores). Validation is
iterative, not recursive regex matching. The target need not be installed when
restoring; this is package configuration, **not** Android user/profile migration.

Only schema-declared exportable settings are accepted. Unknown suffixes, secrets,
caches, derived/runtime state, nested prefixes and scoped copies of the global
mode key are excluded. Public/private routing and historical other-store fallback
are retained. Legacy aliases still apply only to unqualified unversioned JSON;
the codec does not invent target-specific alias migrations.

The binary format remains version 1. New readers keep accepting old universal
backups. An old reader cannot restore the newly supported scoped records: retaining
them in the file does not make an older WAEX version understand them.

## Reproduction

Use JDK 17+, Python 3 and a complete Kotlin CLI installation. The runner uses
unmodified Android 14 `org.json` with the existing six SHA-256 pins and strict
Android storage doubles. Pass `--json-source-dir` for an already verified offline
source directory. A missing dependency is BLOCKED, not a passed test.

```sh
python3 qa/test_scoped_settings_backup.py --out /tmp/scoped-backup
python3 qa/mutate_scoped_settings_backup.py --out /tmp/scoped-backup-mutants
python3 qa/test_backup_alias_policy.py
```

Use `--kotlin-home` when Kotlin is not on PATH, and `--source-root` to compare the
same tests against unchanged production. Output directories must be new.

The 22 scoped cases include 19 independently listed keys/values, separately authored
binary records, real legacy/versioned JSON, store priority, merge isolation, RGB/ARGB
normalization, four write-failure/rollback permutations, two failed-compensation
cases that must not report success even if in-memory values look restored,
exact duplicate rejection,
10,000/10,001-record boundaries and a long target-syntax input. The unchanged 105-case
JSON suite is a separate regression layer. Its schema-derived coverage does not
replace the independent scoped expectations.

Both deliberate mutations must compile, finish all 22 scoped cases and all 105 JSON
cases, and fail an intended assertion. Compilation errors or missing result files
never qualify as detected behavioral mutations.

## Executed comparison (2026-11-12)

The complete production codec, schema and store selector were compiled with real
Temurin JDK 17.0.20.1+1 (`javac --release 17`) and Kotlin 2.0.20 on Windows 11
build 26200. The six Android 14 JSON source hashes matched the existing pins.
The exact baseline was upstream `a0b047ae832546de1ba4fc8f8e6d21cf54750813`;
the same expanded fixture ran against both versions.

| Production input | Scoped cases | Existing JSON cases | Interpretation |
| --- | --- | --- | --- |
| Candidate | 22/22 PASS | 105/105 PASS | Pass within the stated JVM boundary |
| Unchanged upstream | 6/22 PASS, 16 FAIL | 105/105 PASS | Original data-loss defect reproduced |
| Candidate with scoped keys disabled | 6/22 PASS, 16 FAIL | 105/105 PASS | Compiled negative control rejected |
| Candidate accepting non-exportable scoped entries | 18/22 PASS, 4 FAIL | 105/105 PASS | Compiled sensitivity control rejected |

Every variant completed both fixtures after successful Java/Kotlin compilation.
The rejected controls validate the assertions, not the mutated implementations.
`qa/test_backup_alias_policy.py`, Python compilation and `git diff --check` also
passed. This is not a complete Gradle/Android build or a GitHub Actions result.

Candidate source SHA-256:

- Codec: `6729d9a6acb613b0766acf9a96d689abe1983e3752e4362276870bc27db67ace`
- Schema: `114bf117ceb40f2487d3dd5455e319bb0ef20d84e8cecda650834c3d6065117e`
- Scoped fixture: `908d99df3568ff7ded87de04386fa6599e02105672475abac602ffc93c0adc8c`

The source filenames, dependency hashes, actual commands, exits and durations
are recorded in each runner's `result.json`. Preserve its adjacent version and
runtime logs when reproducing the comparison. The expanded 22-case run supersedes
the earlier 19/20-case development runs; those are not extra unique coverage.

## Remaining device acceptance

This does not demonstrate real Android filesystem durability, atomic multi-store
transactions, SAF access, process restart or installed WhatsApp behavior. The two
stores are read separately; the change does not add isolation from concurrent
writes. Existing best-effort rollback can overwrite a concurrent write to a touched
key, and a failed compensating commit remains a reported failure. Before
release, export/import synthetic personal and secondary-package settings through
the real UI, check exact keys and stores, restart WAEX/WhatsApp and verify the
corresponding preferences and feature effects. Preserve the original backup and
test each Android user independently. No profile data, credentials or license
state is moved by this change.
