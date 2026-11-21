# Manual-read retry fixture: restore the production utility imports

## Reproduced failure

On `WaEnhancerX/WaEnhancerX@0aaffab0270a3d0832bef2895f6457f3792fd74a`, `qa/test_manual_receipt_retry.py` extracts ten unchanged production declarations from `BlueOnReplyHook.java` into a synthetic Java class. Two extracted declarations refer to `HostFields.declared` and `HookErrors.swallowed`. The real production file imports both utilities, and the test's pre-existing `STUBS` map already compiles their corresponding strict Java fixture classes. The generated synthetic class omitted the imports, so the Java compiler could not resolve their simple names.

The unchanged baseline was reproduced on Windows 11 using JDK 17.0.20.1, Kotlin 2.0.20, Python 3.15 and the integrity-checked public DexKit 2.2.0 `classes.jar`. `compile-production.log` (SHA256 `1350b33b32cdbbb85697da080a179fbf7b87fead93365c9f21fb598e1f615d26`) contains two `cannot find symbol` errors, precisely at `HostFields.declared` and `HookErrors.swallowed`. Baseline exit code: **1**; its behavioral matrix did **not** run. This is a fixture compile failure, not a demonstrated product failure.

## Focused correction and validation

Import `com.waenhancer.xposed.utils.HookErrors` and `com.waenhancer.xposed.utils.HostFields` into the generated synthetic class. No production Java/Kotlin, mock method behavior, extraction markers, runtime assertion, WhatsApp data or dependency changes are made. The fixture continues extracting the actual production declarations byte-for-byte.

With that exact two-line change, the same test compiled and ran to completion **twice**, in separately preserved output directories: **14 scenarios, 400 checks, 0 failed scenarios, exit 0** each time. The two independently recorded `result.json` files are byte-identical (SHA256 `2d08a4e3c45a0f488057ec1ae42ec54169e5c0ba2ad9e191f77ad9b3f1aa6438`). The compiler log for the production/fixture Java step is empty on success, SHA256 `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855`.

Three other *distinct* unchanged receipt contracts passed against this same source root: `test_receipt_job_execution_contract.py` (65 checks), `test_played_receipt_dispatch_contract.py` (51 checks) and `test_blue_receipt_recipient_isolation.py` (its own routing/concurrency assertions). These are not additional manual-retry scenarios. The five compiling manual-retry mutation cases were **NOT_VERIFIED** in this run.

Reproduce on a host with Java and Kotlin distributions:

```sh
export WAEX_QA_DEXKIT_JAR=/path/to/verified/dexkit-2.2.0-classes.jar
python3 qa/test_manual_receipt_retry.py --source-root . --out /new/evidence/manual-retry-01 --kotlinc /path/to/kotlinc
python3 qa/test_receipt_job_execution_contract.py
python3 qa/test_played_receipt_dispatch_contract.py
python3 qa/test_blue_receipt_recipient_isolation.py
```

The Maven Central DexKit 2.2.0 AAR used here is SHA256 `cf4488da9f721750ce5ff4311e88356cbfcb1d4444c2b519b12d8bdb5f33f2c6`; its contained `classes.jar` is SHA256 `195979d9576ba6d68a1c60bdd3e38cd3aed068d4e0c5c6c99caa50bc25fcb9f1`. The helper rejects a mismatched jar.

## Scope and recovery

This validates Java/Kotlin fixture compilation and synthetic callback execution on the stated toolchain, **not** LSPosed injection, real WhatsApp host methods, server-side receipts, Android API compatibility or a signed WAEX APK. If production code changes its utility names/contracts, adapt the imports only after reviewing the new production source; a normal revert of this fixture-only commit restores the prior state. No permission or licensing boundary changes.
