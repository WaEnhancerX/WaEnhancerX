# Stability, resilience, and WhatsApp compatibility changes

This note accompanies the source-level changes proposed for Wa Enhancer X 2.0.0-beta-3. The starting point is the upstream `main` snapshot corresponding to the supplied `WaEnhancerX-main.zip`. The user supplied the original manager APK and WhatsApp 2.26.39.79 for static inspection, plus crash logs and a screenshot from a Ulefone Armor 27T Pro running Android 14 (API 34), arm64-v8a. These artifacts are **not** committed to this repository.

## Why these changes are needed

WAEX is injected into another application's process. Errors in startup sequencing, individual feature discovery, shared preferences, or a host UI invariant can terminate WhatsApp itself. In particular, a user-reported crash includes:

```
java.lang.IllegalArgumentException: Maximum number of items supported by WDSBottomBar is 5.
    at X.0hg.A02(:71)
```

The original `SeparateGroupsHook` unconditionally inserted the synthetic Groups tab (`500`) into the home tab list. On the examined WhatsApp build the bottom navigation already contains **Chats, Updates, Communities, Calls, You**, so inserting a sixth item violates the host component's five-item limit. The first binary workaround removed Meta AI (`1000`), which is not a bottom tab in the user's configuration. The final binary workaround targets Communities (`600`). This source contribution implements the equivalent **tab-list policy** in reviewable Java rather than shipping a modified DEX.

A different crash in the **hand-edited, third-party test APK** was:

```
java.lang.VerifyError: Verifier rejected class ... HookProvider.call(...)
```

That incident was caused by reusing a DEX register containing a reference for a Boolean. The binary correction used a non-conflicting register. **It is not an upstream Java source defect or evidence that the source compiles**; source-level provider authorization is represented here in Java and must be compiled using the normal Android toolchain. Do not transplant the hand-edited APK instructions into this repository.

## Behavioral changes and rationale

| Subsystem | Previous risk | Proposed source behavior | Why it matters |
| --- | --- | --- | --- |
| Xposed entry | A failing startup hook could prevent feature initialization | Hook `Instrumentation.callApplicationOnCreate`, fall back to `Application.onCreate`, initialize once per process, isolate setup operations | Protect host startup across differing Application implementations |
| Feature registry | An exception while instantiating/installing one feature could stop subsequent hooks | Lazy factories and independently guarded installations | A single incompatible feature should not disable unrelated ones |
| Version guard | Redundant lifecycle callbacks and permissive failure handling | Use `ActivityTracker` and fail closed for an unverified target version | Avoid blindly applying obfuscated hooks to unknown builds |
| Preference IPC | Missing editor semantics, repeated provider reads, non-atomic writes | Snapshot cache, batched edits, observer refreshes and failure fallback | Lower churn and improve consistency of multi-setting switches |
| Provider authorization | `ContentProvider.call()` needed explicit caller validation | Check the real Binder UID and authorized target-package scope before privileged operations | Prevent unintended callers from using cross-process settings operations |
| Preference updates | Partial batches and accidental writes to sensitive keys | Validate entire batch, protect private entries and maintain Pro entitlements | Avoid inconsistent settings and preserve intended access controls |
| Restart broadcast | A dynamic restart receiver could be called unexpectedly | Protect with a signature-level permission and check the intended target process | Limit unauthorized process termination |
| DEX cache | Permanent negative lookup caching could mask newly resolvable hooks | Expire negative entries and account for target updates | Permit recovery from temporary/unavailable lookups |
| SQLite histories | Destructive `onUpgrade()` paths removed user history | Additive schema creation/migration and cursor-scoped resource cleanup | Preserve existing edited, deleted and retained message records |
| Edit-message memory | Unbounded text cache and logs retained message content | Remove unused cache, avoid logging message text, use per-thread date formatter | Reduce memory retention and thread-safety risks |
| Recording preference | Quick toggle did not match recorder's canonical key | Connect the quick toggle to `call_recording_enabled` | Make UI choice affect the intended feature |
| Image/video preference | HD quick toggle did not update hook-consumed keys | Connect to `imagequality` and `video_maxfps` | Keep preferences coherent; does **not** repair missing DEX anchors |
| Tasker integration | Generated a deep link without launching a user action | Open WhatsApp with a prepared message when foreground access permits | Provide predictable, user-confirmed behavior; no silent sending |
| Update download | Corrupt cache reuse, untrusted redirects, partial downloads | HTTPS-only final URL, package/ZIP checks, size limits, unique temp files, cancellation cleanup | Reduce failed installs and unintended package updates |
| Root install | Shared predictable staging path, overly broad permissions and potential `su` hang | Private temporary path, least-privilege file mode and bounded subprocess wait | Improve recovery and reduce installation hazards |
| Hook result overrides | An incompatible return type can crash a host method | Check target method return type before setting a replacement | Fail safely when an obfuscated signature changes |
| Separate Groups | Six bottom tabs trigger `WDSBottomBar` exception | Remove old Groups entries and native Communities, insert Groups second when there is room, synchronize fragment mapping | Respect the five-tab invariant without arbitrarily discarding other native destinations |

### Separate Groups invariants and fallback

The isolated `SeparateGroupsTabPolicy` produces the expected order for the reported layout:

```
Before: Chats (200) · Updates (300) · Communities (600) · Calls (400) · You (700)
After:  Chats (200) · Groups (500) · Updates (300) · Calls (400) · You (700)
```

The transformation (1) removes stale synthetic Groups IDs, (2) removes native Communities IDs as the explicit replacement choice, (3) inserts Groups at index 1 only when fewer than five entries remain, and (4) retains the order of all other native destinations. The tab factory, label/icon customization, and existing fragment filtering hooks are intentionally retained. If WhatsApp presents five non-Communities tabs, the policy **does not invent a sixth** and Separate Groups will not be visible. This avoids a new host crash but is a deliberate functionality fallback.

This does **not** implement a new unread badge counter or prove that the host's fragment factory, tab selection, back navigation and existing badges behave correctly; those require device testing. Replacing Communities removes the **bottom navigation entry** and does not erase community data, but access via other routes cannot be guaranteed.

### Compatibility notes (WhatsApp 2.26.39.79)

A byte-level sample of 18 literal anchors referenced by DexKit searches found 12 present and 6 absent in the provided host APK. The six absent anchors were:

- `image/compress quality` (image-compression hook)
- `video/encoder fps` (video FPS hook)
- `pininfo/setpin/failed-already-max-pinned` (pinned-chat limit)
- `app/sendmessage/message_sent` (message event path)
- `archive/set-content-indicator-to-empty` (one archive-related search; other strategies exist)
- `balloon_incoming_normal` (chat-bubble customization)

An anchor being present does **not** establish that a hook resolves the intended method, and an anchor being absent does **not** mean every alternate lookup is invalid. This contribution deliberately avoids inventing new obfuscated signatures. Maintainers should validate each affected feature against the host's current DEX and runtime.

### Native libraries and signed test packages

The previous experimental APKs were separately signed. ZIP entry alignment for 16 KiB pages is an **APK packaging operation**, not a Java code change in this PR. Internal ELF alignment must also be verified; alignment of ZIP offsets alone is insufficient. This PR does **not** include test APK binaries, new signing keys, third-party WhatsApp binaries, modifications to the closed licensing submodule, or a change to licensing entitlement checks.

## Executed checks

From the root of this source tree:

```bash
python3 qa/test_bridge_contract.py
python3 qa/test_separate_groups_policy.py
python3 qa/check_syntax.py
python3 qa/audit_apks.py /path/to/WaEnhancerX-original.apk /path/to/WhatsApp-2.26.39.79.apk
```

- `test_bridge_contract.py`: passed; compiles the real Java preference bridge against minimal SDK stubs and tests hydration, values, grouped edits, removal/clear, failure fallback and notifications.
- `test_separate_groups_policy.py`: passed **1,277** cases, including an enumeration of subsets and permutations; proves list ordering, deduplication, idempotence and the no-sixth-entry rule for valid five-item inputs.
- `check_syntax.py`: parsed **44 Java and 88 Kotlin** sources without syntax errors using the local JDK and Kotlin PSI.
- `audit_apks.py`: ZIP/CRC checks passed for the supplied manager/WhatsApp APKs; 12/18 sample DEX strings were found.
- `git diff --check`: no whitespace errors.

**These are not Android builds or device-level integration tests.** The provided source archive lacks the private `licensing/` git submodule and the Android SDK/toolchain is unavailable in the execution environment. Consequently `./gradlew :app:assembleDebug` and instrumentation tests have **not** been run. A successful syntax parse does not verify dependency resolution, Android API symbols, R8 rules or runtime hook behavior.

## Required maintainer verification before merge

1. Restore the legitimate private `licensing/` submodule and use the repository's supported Android SDK, Gradle/JDK and signing setup; run `./gradlew :app:assembleDebug`, `./gradlew test` and normal CI.
2. On Android 14 with WhatsApp 2.26.39.79 and LSPosed (or the actual chosen hook framework), enable Separate Groups and verify the five-tab order, tap/navigation/recreation/back behavior and unread counters. Repeat with other relevant tab configurations.
3. Verify manager startup and provider calls across app/user processes; exercise a denied UID and an authorized scoped target; check Pro entitlement behavior and settings restoration.
4. Upgrade from populated SQLite databases and compare persisted edited/deleted/preserved history before/after. Test rollback and concurrent access.
5. Exercise each media and conversation option separately under the target WhatsApp release. Capture `logcat` and Xposed logs on failures; do not treat nominal version matching as proof of hook compatibility.
6. Verify interrupted downloads, HTTP downgrade redirects, invalid package names, offline failure, rooted and non-rooted installations and APK signing compatibility.
7. Check native library page-size requirements **including ELF segments** if shipping for 16 KiB page devices.

## Scope and disclosure

The work addresses stability, data retention, performance and defensive IPC checks, but this proposal **does not claim all features are functional or crash-free**. If a security-sensitive finding needs disclosure beyond the defensive code change, follow the repository's `SECURITY.md` privately rather than publishing exploit reproduction steps in a public issue.
