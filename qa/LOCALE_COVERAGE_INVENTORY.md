# Spanish resource coverage inventory (17 November 2026)

This is an **evidence-producing diagnostic**, not a localization fix or a substitute
for the existing source, AAPT2, Robolectric, and runtime locale acceptance tests.

The `source-and-jvm` job runs the synthetic positive/negative controls and writes
`waex-locale-coverage-inventory/locale-coverage.json` before the strict localization
checks. The report contains every missing, extra, or divergent resource name and
the SHA-256 of each relevant XML source. The diagnostic step deliberately exits
zero in **inventory** mode even if translations are incomplete; the strict
`qa/test_es419_localization.py` and AAPT2 verifiers still fail in that case.

Reproduction from the repository root:

```bash
python3 qa/test_locale_coverage_report.py
python3 qa/locale_coverage_report.py --out /tmp/new-waex-locale-evidence
python3 qa/locale_coverage_report.py --strict  # must fail if gaps remain
python3 qa/test_es419_localization.py             # existing strict acceptance
```

At upstream `0aaffab0270a3d0832bef2895f6457f3792fd74a`, the source XML
contains **1,806 default strings, 1,560 Spanish strings, and 1,542 Latin American
Spanish strings**. The gap is **246** missing `es` keys and **264** missing
`es-419` keys (no extra keys). Eighteen keys occur in `es` but not `es-419`.
The `strings.xml` subset contains 581 default names versus 440 in each Spanish
file; those partial counts must not be mistaken for the complete inventory.

Do not change the tests to accept missing translations merely to obtain green
CI. Translate and review the missing keys in a separate focused change, retain
Android formatting/escaping/quantity semantics, and rerun the full strict suite.
Android fallback to English can prevent missing-resource exceptions but does not
establish that the intended Spanish UI is complete.
