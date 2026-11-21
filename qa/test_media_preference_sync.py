#!/usr/bin/env python3
"""Check source-level Media Hub wiring and execute production mapping policy with JDK.

Does not require proprietary APKs, Gradle or an Android SDK; does not verify LSPosed runtime.
"""
import argparse
from pathlib import Path
from xml.etree import ElementTree as ET
import re
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "app/src/main/java/com/waenhancer"
POLICY = SRC / "ui/screens/media/MediaQuickSettingPolicy.java"
HARNESS = ROOT / "qa/TestMediaQuickSettingPolicy.java"
parser = argparse.ArgumentParser()
parser.add_argument("--manager-source", type=Path,
                    default=SRC / "core/preferences/WaexPreferenceManager.java",
                    help="Exact manager source to compile (allows executable negative controls)")
args = parser.parse_args()
manager_source = args.manager_source.resolve(strict=True)

with tempfile.TemporaryDirectory(prefix="waex_media_policy_") as output:
    subprocess.run(["javac", "--release", "17", "-d", output,
                    str(POLICY), str(HARNESS)], check=True)
    subprocess.run(["java", "-cp", output,
                    "com.waenhancer.ui.screens.media.TestMediaQuickSettingPolicy"], check=True)

# Compile the production preference manager against minimal Android/Hilt doubles,
# then exercise commit atomicity, schema validation and failure/notification behavior.
STUBS = {
    "android/net/Uri.java": """package android.net;
public final class Uri {
 private final String value;
 private Uri(String value) { this.value = value; }
 public static Uri parse(String value) { if (value == null) throw new NullPointerException(); return new Uri(value); }
 @Override public String toString() { return value; }
}
""",
    "android/content/pm/ApplicationInfo.java": """package android.content.pm;
public class ApplicationInfo { public String dataDir; }
""",
    "android/content/ContentResolver.java": """package android.content;
import android.net.Uri; import java.io.InputStream; import java.io.OutputStream;
public abstract class ContentResolver {
 public abstract void notifyChange(Uri uri, Object observer);
 public InputStream openInputStream(Uri uri) { throw new AssertionError("Unexpected backup read"); }
 public OutputStream openOutputStream(Uri uri, String mode) { throw new AssertionError("Unexpected backup write"); }
}
""",
    "android/content/SharedPreferences.java": """package android.content;
import java.util.Map;
public interface SharedPreferences {
 boolean getBoolean(String key, boolean fallback);
 String getString(String key, String fallback);
 java.util.Set<String> getStringSet(String key, java.util.Set<String> fallback);
 int getInt(String key, int fallback);
 float getFloat(String key, float fallback);
 boolean contains(String key);
 Map<String, ?> getAll();
 Editor edit();
 interface Editor {
  Editor putBoolean(String key, boolean value);
  Editor putString(String key, String value);
  Editor putStringSet(String key, java.util.Set<String> value);
  Editor putInt(String key, int value);
  Editor putLong(String key, long value);
  Editor putFloat(String key, float value);
  Editor remove(String key);
  boolean commit();
  void apply();
 }
}
""",
    "android/content/Context.java": """package android.content;
import android.content.pm.ApplicationInfo;
import java.io.File;
public abstract class Context {
 public static final int MODE_PRIVATE = 0;
 public abstract SharedPreferences getSharedPreferences(String name, int mode);
 public abstract ContentResolver getContentResolver();
 public abstract ApplicationInfo getApplicationInfo();
 public abstract File getFilesDir();
}
""",
    "dagger/hilt/android/qualifiers/ApplicationContext.java": """package dagger.hilt.android.qualifiers;
public @interface ApplicationContext {}
""",
    "javax/inject/Inject.java": """package javax.inject;
public @interface Inject {}
""",
    "javax/inject/Singleton.java": """package javax.inject;
public @interface Singleton {}
""",
    "com/waenhancer/core/preferences/SettingsBackupCodec.java": """package com.waenhancer.core.preferences;
import android.content.Context; import java.io.InputStream; import java.io.OutputStream;
public class SettingsBackupCodec {
 public static final SettingsBackupCodec INSTANCE = new SettingsBackupCodec();
 public int encode(Context c, OutputStream o) { throw new AssertionError("Unexpected backup encode"); }
 public int decode(Context c, InputStream i) { throw new AssertionError("Unexpected backup decode"); }
}
""",
    # The private :licensing dependency is not distributed with the public source.
    # Match the four actual @Override signatures without permissive defaults.
    "com/waenhancer/licensing/customprops/CustomAbPropsStore.java": """package com.waenhancer.licensing.customprops;
public interface CustomAbPropsStore {
 boolean getBoolean(String key, boolean fallback);
 void putBoolean(String key, boolean value);
 String getString(String key, String fallback);
 void putString(String key, String value);
}
""",
    # Restart-sheet behavior has separate tests. Make this boundary observable and
    # fail on unexpected arguments rather than concealing manager notifications.
    "com/waenhancer/config/PreferenceRestartTracker.java": """package com.waenhancer.config;
import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.List;
public final class PreferenceRestartTracker {
 public static final List<String> changes = new ArrayList<>();
 public static void onPreferenceChanged(SharedPreferences prefs, String key) {
  if (prefs == null || key == null || key.isEmpty()) throw new AssertionError("Invalid restart input");
  changes.add(key);
 }
}
""",
}
with tempfile.TemporaryDirectory(prefix="waex_media_manager_") as output:
    stub_paths = []
    for name, content in STUBS.items():
        source = Path(output) / name
        source.parent.mkdir(parents=True, exist_ok=True)
        source.write_text(content)
        stub_paths.append(str(source))
    sources = stub_paths + [
        str(SRC / "config/PreferenceSchema.java"),
        str(SRC / "config/PreferenceStores.java"),
        str(manager_source),
        str(POLICY),
        str(ROOT / "qa/TestMediaPreferenceBatch.java"),
    ]
    subprocess.run(["javac", "--release", "17", "-d", output, *sources], check=True)
    subprocess.run(["java", "-cp", output,
                    "com.waenhancer.core.preferences.TestMediaPreferenceBatch"], check=True)

ui = (SRC / "ui/screens/media/MediaStatusHubScreen.kt").read_text()
privacy_ui = (SRC / "ui/screens/privacy/GlobalPrivacySettingsScreen.kt").read_text()
privacy_keys = privacy_ui.split("val privacyKeys = remember {", 1)[1].split("var waexMenuStyle", 1)[0]
manager = manager_source.read_text()
recorder = (SRC / "xposed/features/media/CallRecordingHook.java").read_text()
media = (SRC / "xposed/features/media/MediaQualityBypassHook.java").read_text()
schema = (SRC / "config/PreferenceSchema.java").read_text()

checks = {
    "actual recorder key": 'ENABLED = "call_recording_enabled"' in recorder,
    "actual image-quality key": 'isEnabled("imagequality", false)' in media,
    "actual FPS key": 'isEnabled("video_maxfps", false)' in media,
    "public boolean recorder key": '"call_recording_enabled", Type.BOOLEAN, Sensitivity.PUBLIC_SETTING, Store.PUBLIC' in schema,
    "public boolean image key": '"imagequality", Type.BOOLEAN, Sensitivity.PUBLIC_SETTING, Store.PUBLIC' in schema,
    "public boolean FPS key": '"video_maxfps", Type.BOOLEAN, Sensitivity.PUBLIC_SETTING, Store.PUBLIC' in schema,
    "policy drives switch reads": 'MediaQuickSettingPolicy.isEnabled(key)' in ui,
    "policy drives writes": 'MediaQuickSettingPolicy.changesFor(key, value)' in ui,
    "atomic batch used by screen": 'preferenceManager.putBooleans(changes)' in ui,
    "save failure does not update switch": 'if (preferenceManager.putBooleans(changes)) {' in ui,
    "lifecycle refresh": 'Lifecycle.Event.ON_RESUME' in ui,
    "Privacy initializes catalogue view-once key": '"download_view_once"' in privacy_keys,
    "Privacy reads downloader policy": 'MediaQuickSettingPolicy.isEnabled("download_view_once")' in privacy_ui,
    "Privacy commits both download keys": 'preferenceManager.putBooleans(MediaQuickSettingPolicy.changesFor(key, value))' in privacy_ui,
    "Privacy refreshes downloader after returning": 'Lifecycle.Event.ON_RESUME' in privacy_ui and
        'settingsState["download_view_once"] = enabled' in privacy_ui,
    "Media routes downloader reads through policy": '|| key == "downloadviewonce" || key == "download_view_once"' in ui,
    "profile duplicate-save sub-setting is conditional": 'item.key == "download_profile" && (settingsState[item.key] ?: false)' in ui,
    "profile duplicate-save defaults to current behavior": 'getBoolean("download_profile_save_duplicates", true)' in ui,
    "profile duplicate-save key is public": '"download_profile_save_duplicates", Type.BOOLEAN, Sensitivity.PUBLIC_SETTING, Store.PUBLIC' in schema,
    "saved profile identities are non-exported cache": '"download_profile_saved_identities", Type.STRING_SET, Sensitivity.CACHE, Store.PUBLIC' in schema,
    "status duplicate-save sub-setting is conditional": 'item.key == "status_downloader" && (settingsState[item.key] ?: false)' in ui,
    "status duplicate-save defaults to current behavior": 'getBoolean("status_download_save_duplicates", true)' in ui,
    "status duplicate-save key is public": '"status_download_save_duplicates", Type.BOOLEAN, Sensitivity.PUBLIC_SETTING, Store.PUBLIC' in schema,
    "saved status identities are non-exported cache": '"status_download_saved_identities", Type.STRING_SET, Sensitivity.CACHE, Store.PUBLIC' in schema,
    "Privacy uses localized failure feedback": 'context.getString(R.string.view_once_setting_save_failed)' in privacy_ui,
    "no automatic old alias migration": 'putBoolean("call_recording_enabled", true)' not in ui,
    "batch method defined": bool(re.search(
        r'public boolean putBooleans\((?:java\.util\.)?Map<String, Boolean> values\)', manager)),
    "batch editor used": 'PreferenceStores.publicStore(context).edit()' in manager,
    "single commit": 'if (!editor.commit()) return false;' in manager,
    "reject non-Boolean or private schema": 'definition.type != PreferenceSchema.Type.BOOLEAN' in manager and
        'definition.store != PreferenceSchema.Store.PUBLIC' in manager,
    "notify after successful commit": manager.index('if (!editor.commit()) return false;') <
        manager.index('notifyChange();', manager.index('public boolean putBooleans(')),
    "no world-readable data directory permission regression":
        "fixFilePermissions" not in manager and
        ".setReadable(true, false)" not in manager and
        ".setExecutable(true, false)" not in manager,
}
for label, passed in checks.items():
    if not passed:
        raise AssertionError(f"Media Hub integration regression: {label}")
print(f"PASS: {len(checks)} Media Hub/schema/consumer/writer structural assertions")
for folder, expected in [("values", "Unable to save setting"),
                         ("values-es", "No se pudo guardar el ajuste"),
                         ("values-b+es+419", "No se pudo guardar el ajuste")]:
    resources = ET.parse(ROOT / "app/src/main/res" / folder / "view_once_settings.xml").getroot()
    feedback = resources.find("string[@name='view_once_setting_save_failed']")
    assert feedback is not None and feedback.text == expected, f"missing localized feedback in {folder}"
print("PASS: independent default/es/es-419 save-failure resources")
