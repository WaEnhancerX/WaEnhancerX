# WaEnhancer X

**UI customization, automation, and privacy tools for WhatsApp on Android**

![Platform](https://img.shields.io/badge/Platform-Android-green.svg)
![Framework](https://img.shields.io/badge/Framework-LSPosed%20%7C%20Zygisk-orange.svg)
[![WaEnhancerX Downloads](https://img.shields.io/github/downloads/WaEnhancerX/WaEnhancerX/total.svg?label=WaEnhancerX%20Downloads)](https://github.com/WaEnhancerX/WaEnhancerX/releases)
[![LSPosed Repo Downloads](https://img.shields.io/github/downloads/xposed-Modules-Repo/com.waenhancer/total.svg?label=LSPosed%20Repo%20Downloads)](https://github.com/xposed-Modules-Repo/com.waenhancer/releases)
![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)
![Status](https://img.shields.io/badge/Status-Active%20Development-success.svg)

WaEnhancerX is an Android module that adds interface customization, automation, and privacy options to the official WhatsApp app. It runs through LSPosed and integrates its settings into the app, so you do not need to install a modified or third-party WhatsApp APK.

## Star History

[![Star History Chart](https://api.star-history.com/chart?repos=waenhancerx/waenhancerx&type=date&legend=top-left)](https://www.star-history.com/?repos=waenhancerx%2Fwaenhancerx&type=date&legend=top-left)

## Important notice

Please read this section before installing or using the project.

- **Educational use:** WaEnhancerX is a proof-of-concept project intended for research, Android reverse-engineering, and UI/UX experimentation.
- **No affiliation:** This project is not affiliated with, authorized by, or endorsed by Meta Platforms, WhatsApp LLC, or any of their subsidiaries.
- **Use at your own risk:** Modifying an official app may violate its terms of service. The developers are not responsible for account restrictions, data loss, device instability, or other problems caused by using this module.

## About V2

WaEnhancerX V2 is an independent, clean-room rewrite built from scratch.

- It is not a fork of V1 or another third-party module.
- It does not reuse code, logic, or file structures from earlier versions or GPL-licensed projects.
- Its hooking system is designed for stability on modern Android versions, including Android 12 through Android 15 and later.
- The project is available under the Apache License 2.0.

## Features

- **Meta AI cleanup:** Removes supported Meta AI interface elements from chats and action buttons.
- **Custom view engine:** Lets you change backgrounds, chat bubble colors, and layouts using CSS-like rules.
- **Integrated settings:** Adds WaEnhancerX options to the app's native settings interface, including search support.
- **Privacy tools:**
  - Prevent incoming messages from being removed when their sender deletes them.
  - Open and save view-once media.
  - Keep a local history of messages you delete for yourself.
- **Tasker support (Soon):** Control supported privacy settings and actions through Android intent broadcasts.

## Requirements

Before installing WaEnhancerX, make sure your device has:

1. Root access through Magisk or KernelSU.
2. Zygisk enabled and a working LSPosed installation. EdXposed is not supported.
3. A supported version of the official WhatsApp app from Google Play.

## Installation

1. Download the latest `WaEnhancerX-v2.x.x.apk` from the [Releases page](../../releases).
2. Install the APK on your device.
3. Open LSPosed Manager and enable the WaEnhancerX module.
4. Select System Framework and WhatsApp in the module's scope.
5. Reboot your device.
6. Open WhatsApp and find the WaEnhancerX options in the main settings menu.

## Community & Policies

- [Telegram Community](https://t.me/waenhancerx)
- [Contributing Guidelines](CONTRIBUTING.md)
- [Code of Conduct](CODE_OF_CONDUCT.md)
- [Security Policy](SECURITY.md)
- [Accessibility Statement](ACCESSIBILITY.md)

## License

WaEnhancerX is licensed under the [Apache License 2.0](LICENSE). You may use, modify, and distribute it in open-source or proprietary projects as long as you include the original copyright notice and a copy of the license.

Copyright 2026 Mubashar Dev and the WaEnhancerX Organization.

