# DexKit query-pool shutdown regression

## Product change and supported cause

The version catalog at upstream `0aaffab0270a3d0832bef2895f6457f3792fd74a`
selects `org.luckypray:dexkit:2.2.0`. This change selects the official `2.3.0`
artifact. It does not change WAEX resolver rules, licensing, signing, permissions,
cache policy, or the existing Java bridge lease protocol.

A real WhatsApp startup with the previously installed WAEX 2.0.6 produced
`SIGABRT` inside its x86_64 `libdexkit.so`. The installed native file is byte
identical to the official 2.2.0 AAR's x86_64 library:
SHA-256 `df6cf23bc8605a5378fe414311a8d3b2db310623ee4f3947d0ac276ff60b10fc`,
ELF Build ID `4333ff51e3be939545ca01e391b67f0e82186622`.
This identifies the dependency, not the complete source revision of that WAEX APK.

An ordinary Android application, using only its own synthetic classes and the
official dependency, reproduced the abort twice. Its first native backtrace has
the same nine DexKit relative PCs as the WhatsApp failure:
`1c065, 1c142, 29ef1, 1a4dd, 36b9f, 1a4dd, 34bf4, 1a4dd, 2db30`.
No WAEX hooks, Xposed framework, WhatsApp code, account data, or permissions are
needed by that reproducer. The exact native error argument was not captured;
the matching stack is not an independent observation of a particular pthread
error code.

The official [DexKit fix c3d201f1dcbc647bd3e9f98bc1bd0e5d50d6ba81](https://github.com/LuckyPray/DexKit/commit/c3d201f1dcbc647bd3e9f98bc1bd0e5d50d6ba81)
changes ownership and shutdown of the query scheduler and worker pool, waiting
for in-flight work and joining workers before destroying their supporting state.
It is included in the [2.3.0 release](https://github.com/LuckyPray/DexKit/releases/tag/2.3.0),
published on 2026-09-22. The release also includes worker-local cleanup changes.
The matching failure, controlled dependency substitution and upstream fix support
using that release for this shutdown failure. They do not prove that every
unrelated native crash has the same cause.

The read-only publication review on 2026-11-17 found no version-catalog or
DexKit-pool replacement in the changed-file lists of all 29 then-open upstream
PRs and the one open fork PR. Prior engine/cache work remains relevant, including
merged #107/#123 and open #138; it does not contain this native dependency fix.
No rejected Java serialization workaround is reintroduced.

## Isolated Android test

`qa/dexkit-lifecycle/MainActivity.java` is a permission-free ordinary Activity.
It accepts a unique run ID, 1–1024 rounds and 1–4 native workers, and deliberately
restricts this recorded experiment to Android API 34. The builder creates 16
fixture classes containing 16 distinct marker methods each. A 256-round run
covers all 256 positive identities, with an absent-marker query in each round.
Even rounds query sequentially; odd rounds use two independent Java callers.

Each round creates a bridge over the test app's own installed APK, configures the
worker count, executes the queries and closes the bridge. Exact expected method
descriptors and empty negative results are specified independently of DexKit's
returned results. The general scheduler is exercised without an exact declaring
class shortcut. There is no journal I/O between query completion and `close()`.

The evidence journal records entry and completed closure. `FINISHED/PASS` alone
is insufficient: the host must also observe the final log marker emitted after
evidence synchronization, a living unchanged process after an additional
observation interval, and no new crash/tombstone. A cooperative 30-second timer
does not interrupt blocked native code; enforce an external deadline and stop
only the test package if it exceeds it. Do not run this workload indefinitely.

No APK, private key, dependency binary, full tombstone or user data belongs in
this PR. Builds and evidence should be placed outside the source checkout.

## Verified inputs and reproduction

Recorded toolchain: Windows 11 build 26200, Temurin JDK 17.0.20.1+1, Python
3.15.0a5, Android SDK platform 36, Build Tools 36.0.0 (D8, aapt2, zipalign and
apksigner), adb 37. The Activity ran on an Android 14/API 34 x86_64 emulator.
Linux/macOS operation of the parameterized builder is not established by these
Windows executions.

Obtain these public files from Maven Central into a dedicated dependency
directory. The builder requires their exact hashes and performs no network
requests. `dexkit-2.2.0` and `dexkit-2.3.0` are under
`https://repo.maven.apache.org/maven2/org/luckypray/dexkit/<version>/`.
The other coordinates are `com.google.flatbuffers:flatbuffers-java:23.5.26`,
`org.jetbrains.kotlin:kotlin-stdlib:1.5.0` and `org.jetbrains:annotations:13.0`.
Original POM/license metadata is preserved; no third-party license is changed.

| Input | SHA-256 |
| --- | --- |
| dexkit-2.2.0.aar | `cf4488da9f721750ce5ff4311e88356cbfcb1d4444c2b519b12d8bdb5f33f2c6` |
| dexkit-2.3.0.aar | `8df532c4126b58490667f71127dc57aef62ccf3578d1d17330f2a1cb8836218e` |
| dexkit-2.2.0.pom | `01d99eec00d290bc2ab740bbda67c52668dc041f15a51276272894a1b0eb132e` |
| dexkit-2.3.0.pom | `3cd1c82d60f25654ae54810b29bab93c00c7a2c2e6d2126522aebd312cea7c07` |
| flatbuffers-java-23.5.26.jar | `8d10cac2ea9878896077ba437d76fdb1b9a07f55a863c560bb8a024b04103f8b` |
| kotlin-stdlib-1.5.0.jar | `52283996fe4067cd7330288b96ae67ecd463614dc741172c54d9d349ab6a9cd7` |
| annotations-13.0.jar | `ace2a10dc8e2d5fd34925ecac03e4988b2c0f851650c94b8cef49ba1bd111478` |

The AARs' published SHA-256 sidecars and ZIP CRCs were checked. The older common
JARs were checked against their published SHA-1 sidecars, with independently
recorded SHA-256 and ZIP integrity; absent SHA-256 URLs were retained as failed
retrievals. The test uses the actual common runtime dependencies, not DexKit
doubles. The x86_64 native libraries have no dynamic C++ runtime dependency.

Example PowerShell build, adapting paths to existing verified tools and using
new output directories for both variants:

```powershell
python qa/dexkit-lifecycle/build.py --variant baseline `
  --jdk-root C:/WAEX-Lab/tools/jdk17 --sdk-root C:/WAEX-Lab/android/sdk `
  --dependencies C:/WAEX-Lab/work/dexkit-deps `
  --out C:/WAEX-Lab/evidence/dexkit-baseline
python qa/dexkit-lifecycle/build.py --variant candidate `
  --jdk-root C:/WAEX-Lab/tools/jdk17 --sdk-root C:/WAEX-Lab/android/sdk `
  --dependencies C:/WAEX-Lab/work/dexkit-deps `
  --out C:/WAEX-Lab/evidence/dexkit-candidate
```

Each build creates its own development key unless a private `--signing-dir` is
explicitly supplied. This is a separate test identity, not a WAEX signing key.
The APKs have distinct package names ending in `dexkitbaseline` and
`dexkitcandidate`; both declare no permissions. Before installation, verify the
selected device, Android user, package absence or an owned prior test identity,
APK hash, signature and `BUILD.json`. After installation recheck the installed
APK hash. Do not overwrite an unrecognized existing test package.

For each declared package, force-stop only that test package, generate a fresh
12-character hexadecimal run ID, and launch
`<package>/org.thekillermax.waexlab.dexkitlab.MainActivity` with
`--ez run true --es run_id <id> --ei rounds 256 --ei workers 4` using
`adb -s <verified-serial> shell am start -W --user <verified-user>`.
Read its journal from
`/sdcard/Android/data/<package>/files/run-<id>.jsonl` and its matching
`WAEX_DK_LAB` log line. Require 256 `AFTER_CLOSE` records, a final successful
256-round result, the post-fsync `FINAL:<id>:PASS:256` marker, unchanged living
PID across an additional two-second observation, and no new native failure.
Retain unexpected exits and partial journals. Root is only useful for collecting
system tombstones; the application itself does not request or use it.

Afterward stop both owned test packages. Keep the experiment APKs and evidence
private; uninstalling them is optional after preserving their owned evidence.
The WhatsApp installation and its data do not need to be changed for this test.

## Recorded comparison

Harness source SHA-256:
`9eb95e14003fb786deb3788740a0e8cdd388b85947243b05247e6dece786672a`.
Both builds have identical 16-file generated fixture hash arrays. Their
dependency versions, native payloads and package/BuildConfig identities differ
deliberately. The full `BUILD.json` files identify tools, common JARs and sources.

| Input and run | Observed outcome | Interpretation |
| --- | --- | --- |
| 2.2.0, `2aa160409c28` | `FAIL`: 7 completed rounds, native SIGABRT, process gone | Reproduced dependency shutdown failure; first native stack matches the original WhatsApp stack |
| 2.2.0, `2ed654c16f8e` | `FAIL`: 9 completed rounds, process gone and new tombstone inventory | Repeated failure; do not count it as a separate functional defect |
| 2.3.0, `5b6a2f8eabff` | `PASS`: 256 rounds, synchronized evidence, stable PID and no new tombstone | Pass for this isolated dependency workload |
| 2.3.0, `25be11a57813` | `PASS`: 256 rounds, same post-run checks | Repetition of the same matrix, not 256 additional identities |

Candidate journals reached completion in 2.100 and 2.272 seconds. These elapsed
values establish bounded completion only; they are not a performance, battery or
WhatsApp startup benchmark. A negative-control failure validates the experiment's
sensitivity; it does not make the failing reference acceptable.

Installed original test APK SHA-256:
baseline `7e5c07bd2c4564bf9a07bc5b3de6718035b4cb6710db8cb524843d75144ee28e`;
candidate `06eb3d74ed3b64a6f291f6b1f777d98b6b8ff78f4af2335d06deca2bfff548e8`.
The candidate x86_64 native payload is
`e415bf389949691fd5136b7574f76a28e94b06f8d7d3125ad52e9e66c9b9f0fe`.

The parameterized builder included here was then run with the same verified
Windows tools, dependencies and development key. The resulting candidate APK
has SHA-256 `3faf3fba2304ba8888e664b19d16d0c9f9d2b5431d77a9f12f393287580d488a`.
Its complete nine-entry ZIP payload set is byte-identical to the installed test
candidate, including manifest, resources, DEX, native library, experiment metadata,
POM and all three v1 signature entries. Both APK signatures verify; their whole
APK hashes differ. Runtime evidence is reused by that complete payload identity,
not by version name. This repacked APK was not reinstalled. In particular,
`classes.dex` has SHA-256
`83eb46f6dd3c0485ab68e6b8d45b163204e43373963d1a43f83813268559c458` in both.

## Limits and acceptance still needed

The dependency comparison is an actual Android execution, not a host-JVM mock.
It is also not a full WAEX build or complete WhatsApp acceptance. The original
WhatsApp was 2.26.43.76/code 264307627 and installed WAEX was 2.0.6/code 20600;
neither APK was replaced by this experiment. Their full APK identities and the
private original crash are recorded in the private laboratory checkpoint.

The 2.3.0 release contains additional search and cache changes. Before treating
the complete module as accepted, run a compatible full WAEX build, cold and warm
resolver discovery, numeric and string matchers, real hook installation/execution
and the affected observable effects. WAEX's numeric matchers, including the
floating-point query used by StickerOutlineHook, warrant targeted regression.
No absent cache/batch API usage should be inferred merely from a text search.

Other Android versions, physical ARM devices, work-profile injection, WhatsApp
Business, other WhatsApp releases, full WAEX source/API compilation and every
resolver remain `NOT_VERIFIED` for this dependency update. The AAR's minSdk 21
metadata and a successful API 34 test do not establish universal compatibility.
The original WAEX remains installed; its native failure has not been claimed
fixed in-place by running the separate candidate test package.

Recovery is a normal revert of the one version-catalog change. No data migration
is introduced. Stop owned test processes before collecting or removing their
files. Preserve the failing reference and its evidence; do not clear diagnostic
logs or replace the original account data to obtain a passing result.
