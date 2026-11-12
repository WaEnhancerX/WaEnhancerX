#!/usr/bin/env python3
"""Static integration checks: Android URI grant, consent, provider and UI wiring.

These checks do not substitute for an APK build or device/Telegram share test.
"""

from pathlib import Path
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / "app/src/main"
SOURCE = APP / "java/com/waenhancer"
ANDROID = "{http://schemas.android.com/apk/res/android}"
checks = 0


def require(condition: bool, message: str) -> None:
    global checks
    checks += 1
    if not condition:
        raise AssertionError(message)


manifest = ET.parse(APP / "AndroidManifest.xml").getroot()
providers = manifest.findall(".//provider")
provider = next(
    (item for item in providers
     if item.get(ANDROID + "name") == "androidx.core.content.FileProvider"),
    None
)
require(provider is not None, "Android manifest must register FileProvider")
require(provider.get(ANDROID + "authorities") == "${applicationId}.fileprovider",
        "share authority must match manager package")
require(provider.get(ANDROID + "exported") == "false",
        "diagnostic FileProvider must not be exported")
require(provider.get(ANDROID + "grantUriPermissions") == "true",
        "temporary URI permission grants are required")
hook_provider = next(
    (item for item in providers
     if item.get(ANDROID + "name") == "com.waenhancer.xposed.bridge.HookProvider"),
    None
)
require(hook_provider is not None and hook_provider.get(ANDROID + "process") is None,
        "manager and hook provider must share the journal's in-process serialization")
file_paths = ET.parse(APP / "res/xml/file_paths.xml").getroot()
require(any(element.tag == "cache-path" and element.get("path") == "."
            for element in file_paths), "FileProvider must include private cache directory")

screen = (SOURCE / "ui/screens/settings/SystemHealthScreen.kt").read_text(encoding="utf-8")
require("FileProvider.getUriForFile(" in screen, "UI must obtain a content URI")
require("Intent.ACTION_SEND" in screen, "user-mediated export via share intent")
require("Intent.EXTRA_STREAM" in screen, "attach generated ZIP")
require("Intent.FLAG_GRANT_READ_URI_PERMISSION" in screen,
        "recipient must have temporary read permission")
require("Intent.createChooser(" in screen, "user must choose a destination app")
require("DiagnosticsRecorder.setEnabled(context, enabled)" in screen,
        "UI must call consent API")

provider_source = (SOURCE / "xposed/bridge/HookProvider.java").read_text(encoding="utf-8")
require(provider_source.index("if (committed) {") <
        provider_source.index("DiagnosticsRecorder.recordHookHealth(context"),
        "journal must only record after successful authenticated provider commit")

manager_source = (SOURCE / "app/WaexApp.kt").read_text(encoding="utf-8")
require("DiagnosticsRecorder.installManagerCrashCapture(this)" in manager_source,
        "manager startup must install opt-in crash handler")
activity_source = (SOURCE / "app/MainActivity.kt").read_text(encoding="utf-8")
require("navController.currentScreen.javaClass.simpleName" in activity_source,
        "navigation must capture static Screen class, not dynamic route contents")
require("DiagnosticsRecorder.recordScreen(this@MainActivity" in activity_source,
        "navigation events must be connected to the recorder")

preferences_source = (SOURCE / "core/preferences/WaexPreferenceManager.java").read_text(encoding="utf-8")
require("recordChanged(key, \"PUT\")" in preferences_source,
        "successful setting writes must produce a value-free breadcrumb")
require("recordChanged(key, \"REMOVE\")" in preferences_source,
        "preference deletion must be distinguishable from setting a value")
require("DiagnosticsRecorder.recordSettingsTransfer(context, \"IMPORT\"" in preferences_source,
        "successful settings imports must be observable")

environment_source = (SOURCE / "utils/DiagnosticsEnvironment.java").read_text(encoding="utf-8")
require("getHistoricalProcessExitReasons(context.getPackageName(), 0, 16)" in environment_source,
        "only own-package OS exit reasons may be requested")
require("getTraceInputStream" not in environment_source
        and "getDescription()" not in environment_source,
        "private ANR traces and free-form OS descriptions must not be collected")
require("DiagnosticsSnapshot environment" in
        (SOURCE / "utils/DiagnosticsRecorder.java").read_text(encoding="utf-8"),
        "one-shot environment snapshot must be wired to explicit export")
require("DiagnosticsRecorder.recordHookSummary(context, pkg, hooksJson)" in provider_source,
        "hook failure summaries must reuse existing authenticated IPC")

default = ET.parse(APP / "res/values/diagnostic_capture_strings.xml").getroot()
spanish = ET.parse(APP / "res/values-es/diagnostic_capture_strings.xml").getroot()
default_keys = {element.get("name") for element in default}
spanish_keys = {element.get("name") for element in spanish}
require(default_keys == spanish_keys, "diagnostic English and Spanish strings must match")
require(len(default_keys) >= 15, "all diagnostic actions must have localized labels")

print(f"PASS diagnostic Android integration static checks={checks}")
