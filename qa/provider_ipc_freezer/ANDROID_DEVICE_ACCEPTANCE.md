# Provider IPC: controlled Android process-death acceptance

This optional test accompanies the `HookProviderIpc.call` change. It verifies
the Android operating-system contract behind unstable provider references. It
**does not** install the candidate WAEX build, execute its hooks inside
WhatsApp, or claim that the real cached-app freezer failure is already fixed.

## Scope and safety

- Compile a **separate, test-only APK**, package
  `io.github.tkmbrain.waex.providerdependencyprobe`. It contains a synthetic
  provider in `:provider` and an Activity/client in another app process.
- In `stable` mode the client holds an `acquireContentProviderClient` lease;
  in `unstable` mode it holds an `acquireUnstableContentProviderClient` lease.
  The identical synthetic provider returns its own PID on `ping`.
- The runner checks exact device serial, Android user, package, provider PID,
  process name and Android UID before using existing `su` to issue `SIGKILL`
  **only** to the synthetic `:provider` process. It never kills `com.whatsapp`
  or `com.waenhancer`, changes module scope, or sends messages.
- The test also checks `dumpsys activity exit-info`. A PASS requires the
  stable client to disappear with `DEPENDENCY DIED` and the unstable client
  to survive and report a controlled provider failure. Both provider deaths
  must be recorded as `SIGNALED`; disappearance for another reason fails.
- Test artifacts and a disposable local signing key stay in the specified
  output directory. Do not publish binary APKs or raw device logs.
- This is **manual**, not part of source QA/CI. It requires a rooted,
  authorized Android device, JDK 17, Android SDK 36/build-tools 36.0.0,
  Python 3 and an explicit device serial/user. A new output path is required
  for each run. Existing synthetic installs are never overwritten implicitly.

## Reproduction

The first command builds, signs, checks and installs an isolated APK in the
personal user. Pass the absolute SDK/JDK paths applicable to your host:

```powershell
python qa/provider_ipc_freezer/android_dependency_probe.py `
  --out C:\WAEX-Lab\evidence\provider-dependency\user0-first `
  --sdk C:\WAEX-Lab\android\sdk `
  --jdk C:\WAEX-Lab\tools\jdk17 `
  --serial emulator-5560 --user 0
```

Repeat without reinstalling; the runner checks that the installed APK's
SHA-256 is identical to the original signed input:

```powershell
python qa/provider_ipc_freezer/android_dependency_probe.py `
  --out C:\WAEX-Lab\evidence\provider-dependency\user0-repeat `
  --sdk C:\WAEX-Lab\android\sdk `
  --jdk C:\WAEX-Lab\tools\jdk17 `
  --serial emulator-5560 --user 0 `
  --existing-apk C:\WAEX-Lab\evidence\provider-dependency\user0-first\provider-dependency-probe.apk `
  --reuse-installed
```

For a second Android user, reuse exactly the same globally installed test
package and bytes, without importing or registering any WhatsApp account:

```powershell
python qa/provider_ipc_freezer/android_dependency_probe.py `
  --out C:\WAEX-Lab\evidence\provider-dependency\user10 `
  --sdk C:\WAEX-Lab\android\sdk `
  --jdk C:\WAEX-Lab\tools\jdk17 `
  --serial emulator-5560 --user 10 `
  --existing-apk C:\WAEX-Lab\evidence\provider-dependency\user0-first\provider-dependency-probe.apk `
  --install-existing
```

If a user already has the exact synthetic APK installed, use
`--reuse-installed` instead of `--install-existing`; never force a mismatched
package/signature into place. The test leaves its own synthetic application
installed for reproducible follow-up, but stops/restarts **only that test
package** during each comparison. Do not uninstall an earlier test fixture
without checking whether another lab task still uses it.

## Executed reference evidence, 13 November 2026

System: Windows 11 host, real JDK 17, Python 3.15.0a5, Android SDK 36,
`emulator-5560` Android 14/API 34/x86_64, root already available.
Same synthetic APK SHA-256 in both users:
`5c7981409dd511b396ef093fae7b00f1e6f3fd00e9fdf0d8f7917aa1c9dcbd37`.

| Run | Android user / provider UID | Stable client | Unstable client | Result |
| --- | --- | --- | --- | --- |
| Reference | `0` / synthetic app UID | Killed after provider SIGKILL | Survived; `DeadObjectException` | PASS |
| Same bytes, repeated | `0` / `10207` | `DEPENDENCY DIED` | Survived; `DeadObjectException` | PASS |
| Same bytes, work profile | `10` / `1010207` | `DEPENDENCY DIED` | Survived; `DeadObjectException` | PASS |

Additional strengthened runs confirmed the exact Android exit causes:

- **user 0:** both providers `SIGNALED`, stable client `DEPENDENCY DIED`,
  unstable client still PID-live; synthetic state `SURVIVED`.
  Private `result.json` SHA-256:
  `5c0e0e4bed34a3c8f4d3d458d855a2f43ff584913853da34ac8ab4c69fcaf3ed`.
- **user 10:** the same control/response and UID isolation; private
  `result.json` SHA-256:
  `d5a4ff562c5b45a44d920d9a0cb17b41db6e0b0a6eb19f1d82d6f179c1997af8`.
- Test runner SHA-256:
  `85b3a27d44b7b78463c907d5cacf8ebf2cc3a52551adc72ce34677d0e6b52cb9`.
  Private evidence path:
  `C:\WAEX-Lab\evidence\waex-provider-dependency-20261113`.

The first device run exercised the baseline but did not yet enforce the
`ApplicationExitInfo` oracle. The later strengthened runs exercised that
additional independent check and confirmed the original observation. Do not
count internal ADB commands, JSON fields or repeated executions as distinct
product features.

## What the result does and does not demonstrate

**Demonstrated:** on this Android 14 platform, a stable provider reference
causes the operating system to kill a dependent client when the provider
process dies, whereas an unstable reference allows the client to survive and
handle `DeadObjectException`. The control holds for users 0 and 10 under the
declared conditions, with synthetic data and exact installed APK bytes.

**NOT_VERIFIED:** WAEX's proposed `HookProviderIpc.call` executing inside
WhatsApp/Vector, natural Android freezer timing, full WAEX Gradle/private
dependency build, lossless retries of every WAEX operation, Android versions
other than API 34, WhatsApp Business, physical devices and long-term battery
or performance. The provider itself still dies; unstable IPC isolates the
caller but does not prevent the server freezer kill. A full acceptance test
must compare installed reference versus candidate WAEX on the same WhatsApp
revision and profiles, including feature effects and provider recovery.

Relevant primary references:

- [Android ContentResolver unstable provider clients](https://developer.android.com/reference/android/content/ContentResolver#acquireUnstableContentProviderClient(android.net.Uri))
- [AOSP cached-app freezer](https://source.android.com/docs/core/perf/cached-apps-freezer)
- [AOSP ContentProviderClient dead-Binder behavior](https://android.googlesource.com/platform/frameworks/base/+/HEAD/core/java/android/content/ContentProviderClient.java)
