<div align="center">

<img src="app/src/main/res/drawable/ic_app_logo.webp" alt="WaEnhancer X app logo" width="128" />

# WaEnhancer X

**UI customization, automation, and privacy engine for WhatsApp on Android**

WaEnhancer X is an open-source Xposed/LSPosed module for WhatsApp & WhatsApp Business with privacy tools, anti-delete, view-once media, Meta AI removal, and UI customization.

[![Android Platform](https://img.shields.io/badge/Platform-Android%20API%2024%2B-3DDC84?style=flat&logo=android&logoColor=white)](https://developer.android.com)
[![Framework](https://img.shields.io/badge/Framework-LSPosed%20%7C%20Zygisk-orange?style=flat)](https://github.com/JingMatrix/LSPosed)
[![WaEnhancerX Downloads](https://img.shields.io/github/downloads/WaEnhancerX/WaEnhancerX/total.svg?label=GitHub%20Downloads&color=blue)](https://github.com/WaEnhancerX/WaEnhancerX/releases)
[![LSPosed Repo Downloads](https://img.shields.io/github/downloads/xposed-Modules-Repo/com.waenhancer/total.svg?label=LSPosed%20Repo%20Downloads&color=purple)](https://github.com/xposed-Modules-Repo/com.waenhancer/releases)
[![Telegram](https://telegram-badge.pages.dev/api/telegram-badge?channelId=@waenhancerx&label=WaEnhancerX%20Updates&showOnline=true)](https://t.me/waenhancerx)
[![Telegram](https://telegram-badge.pages.dev/api/telegram-badge?channelId=@waenhancerxhub&label=WaEnhancerX%20Hub&showOnline=true)](https://t.me/waenhancerxhub)
[![License: Apache 2.0](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Development Status](https://img.shields.io/badge/Status-Active%20Development-success.svg)](https://github.com/WaEnhancerX/WaEnhancerX)
[![PRs Welcome](https://img.shields.io/badge/PRs-welcome-brightgreen.svg)](CONTRIBUTING.md)

**[Latest Release](https://github.com/WaEnhancerX/WaEnhancerX/releases/latest)** · **[Telegram Community](https://t.me/waenhancerx)** · **[Documentation](docs/ARCHITECTURE.md)** · **[Report Bug](https://github.com/WaEnhancerX/WaEnhancerX/issues)**

</div>

---

## Star History

[![Star History Chart](https://api.star-history.com/chart?repos=waenhancerx/waenhancerx&type=date&legend=top-left)](https://www.star-history.com/?repos=waenhancerx%2Fwaenhancerx&type=date&legend=top-left)

---

## Contents

- [Star History](#star-history)
- [Why WaEnhancerX](#why-waenhancerx)
- [About V2 Architecture](#about-v2-architecture)
- [Key Features](#key-features)
- [Compatibility & Supported Clients](#compatibility--supported-clients)
- [Requirements](#requirements)
- [Installation Guide](#installation-guide)
- [Building from Source](#building-from-source)
- [Architecture & Design Principles](#architecture--design-principles)
- [Contributors](#contributors)
- [Community & Policies](#community--policies)
- [Important Notice & Disclaimer](#important-notice--disclaimer)
- [License](#license)

---

## Why WaEnhancerX

Most WhatsApp modifications require replacing your official application with suspicious, closed-source third-party repackaged APKs. These third-party APKs pose major account security risks, break safety attestation, and often bundle unwanted bloatware or trackers.

**WaEnhancerX takes the native Xposed framework approach:**

- **Zero Client Tampering:** Works directly on top of the unmodified, genuine WhatsApp client from Google Play.
- **Deep System-Level Integration:** Injects settings natively into WhatsApp's internal preferences screen with full search engine support.
- **Dynamic CSS Theme Engine:** Style message bubbles, action bars, backgrounds, and UI layouts without decompiling or patching XML assets.
- **Local History and Exports:** Stores preserved-message history and media exports on the device. Network-dependent features and update checks have their own settings and behavior.

---

## About V2 Architecture

The public manager and hooks use Kotlin/Java, Jetpack Compose, Hilt and Android
framework APIs. Features are grouped into packages inside `:app`, with a required
private `:licensing` module. The [architecture guide](docs/ARCHITECTURE.md)
maps the implemented components and their responsibilities.

The APK declares `minSdk 24` and `targetSdk 36`. These build settings do not imply
that every feature has been verified on every Android version or future host
release. Compatibility depends on the target APK, loaded hook framework and
individual hook behavior.

---

## Key Features

<div align="center">
<table>
<tr>
<td width="33%" valign="top">

### 🛡️ Privacy & Security
- **Anti-Delete Protection:** Prevent incoming messages from disappearing when sender deletes them.
- **View-Once Tools:** Controls for viewing and exporting view-once media on compatible host builds.
- **Self-Delete History:** Maintain local archives of messages you delete for yourself.
- **Receipt Control:** Granular control over blue ticks and delivered receipts.

</td>
<td width="33%" valign="top">

### 🎨 Customization & UI
- **CSS-like View Engine:** Fine-tune bubble colors, radiuses, and backgrounds with declarative rules.
- **Meta AI Cleanup:** Hide intrusive Meta AI icons, floating action buttons, and chat entries.
- **Material You (Monet):** Dynamic color theming aligned with system wallpaper palettes.

</td>
<td width="33%" valign="top">

### ⚡ System & Integration
- **Native Settings Injection:** Integrated right inside WhatsApp’s official settings hierarchy.
- **Built-in Search:** Instantly locate any tweak via native search bar indexing.
- **Safe Restart Policy:** Graceful, reliable WhatsApp process restart directly from the dashboard.
- **Tasker Integration:** Open a selected WhatsApp chat through the supported, user-confirmed automation flow.

</td>
</tr>
</table>
</div>

---

## Compatibility & Supported Clients

WAEX targets exact package names and evaluates configured version-name rules
before initializing its feature registry.

| Target package | Client | Scope of the declaration |
|---|---|---|
| `com.whatsapp` | WhatsApp Messenger | Declared target; version rules and feature-specific acceptance still apply. |
| `com.whatsapp.w4b` | WhatsApp Business | Declared target; version rules and feature-specific acceptance still apply. |
| Other explicitly listed packages | Alternative clients | Exact allowlist membership only; no authenticity or compatibility guarantee. |

See the [Client Compatibility Guide](docs/client-compatibility.md) for the actual
allowlist, built-in/custom rules, explicit bypass and unavailable-input states.
A matching rule or a green source test does not prove that every feature works.

---

## Requirements

1. A rooted Android device with a working Xposed-compatible framework such as
   LSPosed, configured for that device.
2. An installed target client listed in the module's scope and admitted by its
   configured version rules.
3. An Android version allowed by the APK's declared minimum SDK (currently API 24),
   plus framework and target-client compatibility. This is not a device test matrix.

The [manifest](app/src/main/AndroidManifest.xml) declares the Xposed metadata and
[arrays.xml](app/src/main/res/values/arrays.xml) declares the recommended scope.

---

## Installation Guide

1. **Download:** Select the desired Debug or Release APK from
   [GitHub Releases](https://github.com/WaEnhancerX/WaEnhancerX/releases) and read
   that release's notes.
2. **Install:** Install the APK on the Android device.
3. **Activate:** Enable WaEnhancerX in the loaded framework's manager.
4. **Set scope:** Enable **WaEnhancerX** (`com.waenhancer`) and the installed
   target, such as `com.whatsapp` or `com.whatsapp.w4b`. System Framework (`android`)
   is not part of the declared scope in this source tree.
5. **Restart and inspect:** Restart the selected processes as required by the
   framework, open the WAEX manager, and inspect its compatibility and injection
   diagnostics. Then check the configured entry point in the target app.

The current build configuration enables code minification and resource shrinking
for Release. Debug uses a different optimization configuration; it is still an APK
archive. A feature working in Debug must also be checked in optimized Release
before a release-specific issue is considered resolved.

---

## Building from Source

The repository currently declares SDK 36, JDK/toolchain 17, Android Gradle Plugin
9.0.1 and a Gradle distribution of 9.1.0 in the wrapper properties. Use an Android Studio version compatible with that
AGP release. The Kotlin catalog entry is 2.0.20; the resolved Gradle plugin/compiler
version also depends on AGP and should be recorded when validating a full build.

**A public clone alone cannot produce the configured APK.** Both Debug and Release
require the private `:licensing` module and the original configured signing key;
the build verifies its certificate during configuration. The current revision
declares a licensing URL in `.gitmodules` but contains no gitlink or pinned
licensing commit, so recursive submodule initialization alone cannot supply it.
Builders with access must obtain it separately:

```bash
git clone https://github.com/WaEnhancerX/WaEnhancerX.git
cd WaEnhancerX
git clone https://github.com/mubashardev/waex-licensing.git licensing
```

Before building, select a compatible licensing revision supplied by the
maintainers and record both commit SHAs. Configure the Android SDK and authorized
signing properties as described in [CONTRIBUTING.md](CONTRIBUTING.md), then run:

```bash
./gradlew :app:assembleDebug
```

The configured output is
`app/build/outputs/apk/debug/WaEnhancerX-v<VERSION_NAME>_debug.apk`; Release uses
`app/build/outputs/apk/release/WaEnhancerX-v<VERSION_NAME>_release.apk`.
`VERSION_NAME` comes from [gradle.properties](gradle.properties).

Public-source checks can run without the private module or signing key. See
[Development Setup and Tests](CONTRIBUTING.md#development-setup) for prerequisites,
commands and the distinction between source QA, local unit tests and device tests.

---

## Architecture & Design Principles

The manager uses `WaexPreferenceManager` and `PreferenceSchema`; injected features
read settings through `PreferenceBridgeClient` and `HookProvider`. `MainHook`
selects declared targets and invokes `VersionGuard` before `FeatureRegistry`
initializes the feature implementations.

- [Current architecture and source map](docs/ARCHITECTURE.md)
- [Client targeting and version-rule decisions](docs/client-compatibility.md)
- [Preference stores, backups and provider authorization](docs/preference-architecture.md)

These documents describe the public code. They distinguish implemented behavior
from historical design proposals and from checks requiring a full APK or device.

---

## Contributors

Thank you to everyone who has contributed code, reported issues, tested builds, or improved the documentation for WaEnhancerX!

Thanks go to these wonderful people ([emoji key](https://allcontributors.org/docs/en/emoji-key)):

<table>
  <tr>
    <td align="center" valign="top" width="180">
      <a href="https://github.com/mubashardev">
        <img src="https://avatars.githubusercontent.com/u/72978007?v=4" width="100px" alt="Mubashar Dev" /><br />
        <sub><b>Mubashar Dev</b></sub>
      </a><br />
      <a href="https://github.com/WaEnhancerX/WaEnhancerX/commits?author=mubashardev" title="Code">💻</a>
      <a href="https://github.com/WaEnhancerX/WaEnhancerX/pulls?q=is%3Apr+author%3Amubashardev" title="Project Management">📆</a>
      <a href="https://github.com/WaEnhancerX/WaEnhancerX/commits?author=mubashardev" title="Documentation">📖</a>
      <a href="https://github.com/WaEnhancerX/WaEnhancerX/releases" title="Maintenance">🚧</a>
    </td>
    <td align="center" valign="top" width="180">
      <a href="https://github.com/TheKillerMax">
        <img src="https://avatars.githubusercontent.com/u/70108066?v=4" width="100px" alt="TheKillerMax" /><br />
        <sub><b>TheKillerMax</b></sub>
      </a><br />
      <a href="https://github.com/WaEnhancerX/WaEnhancerX/commits?author=TheKillerMax" title="Code">💻</a>
      <a href="https://github.com/WaEnhancerX/WaEnhancerX/pulls?q=is%3Apr+author%3ATheKillerMax" title="Testing">⚠️</a>
      <a href="https://github.com/WaEnhancerX/WaEnhancerX/commits?author=TheKillerMax" title="Documentation">📖</a>
    </td>
    <td align="center" valign="top" width="180">
      <a href="https://github.com/wilsonmilson">
        <img src="https://avatars.githubusercontent.com/u/98002834?v=4" width="100px" alt="wilson meow" /><br />
        <sub><b>wilson meow</b></sub>
      </a><br />
      <a href="https://github.com/WaEnhancerX/WaEnhancerX/pull/49" title="Translation">🌍</a>
    </td>
    <td align="center" valign="top" width="180">
      <a href="https://github.com/damjang">
        <img src="https://avatars.githubusercontent.com/u/1283609?v=4" width="100px" alt="damjang" /><br />
        <sub><b>damjang</b></sub>
      </a><br />
      <a href="https://github.com/WaEnhancerX/WaEnhancerX/pull/58" title="Translation">🌍</a>
    </td>
  </tr>
</table>

See the complete [GitHub contribution history](https://github.com/WaEnhancerX/WaEnhancerX/graphs/contributors).

<div align="center">

Contributions of all kinds are welcome! Check out our [Contributing Guidelines](CONTRIBUTING.md) to get started.

</div>

---

## Community & Policies

Join the community, contribute improvements, or review our standards:

- 💬 **Telegram:** [Join @waenhancerx](https://t.me/waenhancerx)
- 🤝 **Contributing:** [Contribution Guidelines](CONTRIBUTING.md)
- 📜 **Code of Conduct:** [Community Standards](CODE_OF_CONDUCT.md)
- 🔒 **Security:** [Security Policy & Disclosures](SECURITY.md)
- ♿ **Accessibility:** [Accessibility Statement](ACCESSIBILITY.md)

---

## Important Notice & Disclaimer

Please read this disclaimer carefully before using the software:

- **Educational & Research Purpose:** WaEnhancerX is a proof-of-concept project intended strictly for reverse-engineering research, Android UI/UX experimentation, and educational purposes.
- **No Affiliation:** This project is independent and is not affiliated with, authorized, sponsored, or endorsed by Meta Platforms, WhatsApp LLC, or any of their affiliates.
- **Use at Your Own Discretion:** Modifying client runtimes may violate third-party Terms of Service. The maintainers and contributors assume no liability for account bans, data loss, device instability, or any consequences resulting from the use of this tool.

---

## License

WaEnhancerX is distributed under the terms of the **[Apache License 2.0](LICENSE)**.

```text
Copyright 2026 Mubashar Dev and the WaEnhancerX Organization.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0
```
