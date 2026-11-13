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
require("DiagnosticsRecorder.setAdvancedEnabled(context, enabled)" in screen,
        "advanced WhatsApp exit collection must require its own UI consent")
require("Build.VERSION.SDK_INT >= 30" in screen and "captureEnabled && !captureChanging" in screen,
        "advanced UI switch must require supported Android and main capture")

provider_source = (SOURCE / "xposed/bridge/HookProvider.java").read_text(encoding="utf-8")
require(provider_source.index("if (!isTrustedCaller(context, callerUid))")
        < provider_source.index("Binder.clearCallingIdentity()"),
        "provider must authenticate Binder UID before clearing identity")
require(provider_source.index("if (committed) {") <
        provider_source.index("DiagnosticsRecorder.recordHookHealth(context"),
        "journal must only record after successful authenticated provider commit")
require('"report_target_exit_history".equals(method)' in provider_source
        and "callerOwnsPackage(context, callerUid, pkg)" in provider_source
        and "DiagnosticsRecorder.isAdvancedEnabled(context)" in provider_source,
        "advanced process exits must be accepted only for authorized UID/package and consent")
require("extras.getLongArray(\"exit_times\")" in provider_source
        and "extras.getIntArray(\"exit_reasons\")" in provider_source
        and "DiagnosticsRecorder.recordTargetExitHistory(" in provider_source,
        "provider must accept bounded primitive-only exit metadata through recorder")

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

schema_source = (SOURCE / "config/PreferenceSchema.java").read_text(encoding="utf-8")
require("KEY_DIAGNOSTIC_TARGET_EXIT_ENABLED" in schema_source
        and "KEY_DIAGNOSTIC_TARGET_EXIT_SINCE" in schema_source,
        "advanced consent and cutoff must use explicitly declared schema keys")
require("KEY_DIAGNOSTIC_TARGET_EXIT_ENABLED, Type.BOOLEAN, Sensitivity.RUNTIME, Store.PUBLIC" in schema_source
        and "KEY_DIAGNOSTIC_TARGET_EXIT_SINCE, Type.LONG, Sensitivity.RUNTIME, Store.PUBLIC" in schema_source,
        "consent must be device-local, non-exportable and readable by the target bridge")
recorder_source = (SOURCE / "utils/DiagnosticsRecorder.java").read_text(encoding="utf-8")
require("KEY_DIAGNOSTIC_TARGET_EXIT_SINCE, 0L" in recorder_source
        and "KEY_DIAGNOSTIC_TARGET_EXIT_ENABLED, false" in recorder_source,
        "disabling the main recorder must revoke advanced consent and cutoff")
require("privateStore.getStringSet(" in recorder_source
        and "SEEN_TARGET_EXITS_PREFIX + target" in recorder_source
        and "MAX_SEEN_KEYS" in recorder_source,
        "duplicate OS ring-buffer events must be bounded in private settings")
require("recordTargetExit(now, since, target" in recorder_source,
        "validated target exit records must reach the shared journal")
require("recordTargetExitScan(now, target, 0, 0)" in recorder_source
        and "recordTargetExitScan(now, target, times.length, recorded)" in recorder_source,
        "empty and nonempty OS histories need a bounded receipt")

reporter = (SOURCE / "xposed/utils/TargetExitReporter.java").read_text(encoding="utf-8")
require("Build.VERSION.SDK_INT < 30" in reporter
        and "target.equals(context.getPackageName())" in reporter,
        "the OS exit query must run on API 30+ within the WhatsApp-owned package")
require("getHistoricalProcessExitReasons(\n                    context.getPackageName(), 0, TargetExitContract.MAX_EVENTS)" in reporter,
        "OS exit history query must be limited to caller's own package and bounded results")
require("new Thread(" in reporter and "AtomicBoolean STARTED" in reporter
        and "THREAD_PRIORITY_BACKGROUND" in reporter,
        "host collection must be one-shot on a low-priority worker")
require("getTraceInputStream" not in reporter and "getDescription" not in reporter
        and "logcat" not in reporter.split("private static final class Api30")[1],
        "WhatsApp stack/ANR traces or unrestricted logcat must never leave the host")
require("history == null" in reporter and "history.isEmpty()" not in reporter,
        "an empty OS ring buffer must still be reported, not silently dropped")
require("context.getContentResolver().call(" in reporter
        and '"report_target_exit_history"' in reporter,
        "host metadata must use the existing authenticated ContentProvider bridge")

main_hook = (SOURCE / "xposed/MainHook.java").read_text(encoding="utf-8")
require("TargetExitReporter.scheduleOnce(appContext, packageName, advancedConsent" in main_hook
        and "KEY_DIAGNOSTIC_TARGET_EXIT_ENABLED" in main_hook
        and "KEY_DIAGNOSTIC_TARGET_EXIT_SINCE" in main_hook,
        "hook initialization must pass both consent flags and the cutoff timestamp")

default = ET.parse(APP / "res/values/diagnostic_capture_strings.xml").getroot()
spanish = ET.parse(APP / "res/values-es/diagnostic_capture_strings.xml").getroot()
default_keys = {element.get("name") for element in default}
spanish_keys = {element.get("name") for element in spanish}
require(default_keys == spanish_keys, "diagnostic English and Spanish strings must match")
require(len(default_keys) >= 15, "all diagnostic actions must have localized labels")

print(f"PASS diagnostic Android integration static checks={checks}")
