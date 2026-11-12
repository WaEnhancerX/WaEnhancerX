#!/usr/bin/env python3
"""Run actual manager/store/schema classes against strict in-memory Android doubles.

No Android runtime, disk persistence, proprietary licensing behavior or UI is
claimed. Unused platform/backup/restart operations throw instead of returning
permissive success values. Only the four public CustomAbPropsStore signatures
implemented by WaexPreferenceManager are represented at the compile boundary.
"""
import argparse
import ast
from pathlib import Path
import subprocess
import tempfile

HERE = Path(__file__).resolve().parent
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--source-root', type=Path, default=HERE.parent)
args = parser.parse_args()
root = args.source_root.resolve()
tree = ast.parse((root / 'qa/test_bridge_contract.py').read_text(encoding='utf-8'))
values = [n.value for n in tree.body if isinstance(n, ast.Assign)
          and any(isinstance(t, ast.Name) and t.id == 'STUBS' for t in n.targets)]
if len(values) != 1:
    raise SystemExit('Expected one literal platform fixture')
reference = ast.literal_eval(values[0])
stubs = {name: reference[name] for name in ('android/content/SharedPreferences.java', 'android/net/Uri.java')}
stubs.update({
    'android/content/Context.java': '''package android.content;
public abstract class Context {
    public static final int MODE_PRIVATE = 0;
    public abstract SharedPreferences getSharedPreferences(String name, int mode);
    public abstract ContentResolver getContentResolver();
    public abstract android.content.pm.ApplicationInfo getApplicationInfo();
    public abstract java.io.File getFilesDir();
}''',
    'android/content/ContentResolver.java': '''package android.content;
public abstract class ContentResolver {
    public void notifyChange(android.net.Uri uri, Object observer) { throw new AssertionError("Unused notifyChange"); }
    public java.io.InputStream openInputStream(android.net.Uri uri) { throw new AssertionError("Unused input stream"); }
    public java.io.OutputStream openOutputStream(android.net.Uri uri, String mode) { throw new AssertionError("Unused output stream"); }
}''',
    'android/content/pm/ApplicationInfo.java': 'package android.content.pm; public class ApplicationInfo { public String dataDir; }',
    'dagger/hilt/android/qualifiers/ApplicationContext.java': 'package dagger.hilt.android.qualifiers; public @interface ApplicationContext {}',
    'javax/inject/Inject.java': 'package javax.inject; public @interface Inject {}',
    'javax/inject/Singleton.java': 'package javax.inject; public @interface Singleton {}',
    'com/waenhancer/licensing/customprops/CustomAbPropsStore.java': '''package com.waenhancer.licensing.customprops;
public interface CustomAbPropsStore {
    boolean getBoolean(String key, boolean fallback);
    void putBoolean(String key, boolean value);
    String getString(String key, String fallback);
    void putString(String key, String value);
}''',
    'com/waenhancer/config/PreferenceRestartTracker.java': '''package com.waenhancer.config;
public final class PreferenceRestartTracker {
    public static void onPreferenceChanged(android.content.SharedPreferences p, String key) {
        throw new AssertionError("Getter tests must not mutate restart state");
    }
}''',
    'com/waenhancer/core/preferences/SettingsBackupCodec.java': '''package com.waenhancer.core.preferences;
public final class SettingsBackupCodec {
    public static final SettingsBackupCodec INSTANCE = new SettingsBackupCodec();
    public int encode(android.content.Context c, java.io.OutputStream out) { throw new AssertionError("Unused backup encode"); }
    public int decode(android.content.Context c, java.io.InputStream in) { throw new AssertionError("Unused backup decode"); }
}''',
})
src = root / 'app/src/main/java/com/waenhancer'
sources = [src / p for p in ('config/PreferenceSchema.java', 'config/PreferenceStores.java',
                             'core/preferences/WaexPreferenceManager.java')]
with tempfile.TemporaryDirectory(prefix='waex_string_scope_') as temp:
    work = Path(temp)
    for name, content in stubs.items():
        path = work / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding='utf-8')
    classes = work / 'classes'; classes.mkdir()
    subprocess.run(['javac', '--release', '17', '-Xlint:unchecked', '-d', str(classes),
                    *map(str, sorted(work.rglob('*.java'))), *map(str, sources),
                    str(HERE / 'java/PreferenceManagerStringScopeChecks.java')], check=True)
    subprocess.run(['java', '-cp', str(classes), 'test.PreferenceManagerStringScopeChecks'], check=True)
