#!/usr/bin/env python3
"""Run JDK regression tests against the production Dex cache (no Android device needed).

Java stubs are only used for Android/Xposed framework types; both cache classes
are compiled directly from the production source tree. This does NOT validate
native DexKit scanning, LSPosed injection or compatibility with future WhatsApp.
"""
from pathlib import Path
import argparse
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
DEX_SRC = ROOT / "app/src/main/java/com/waenhancer/xposed/core/devkit"
STUBS = {
    "androidx/annotation/NonNull.java": "package androidx.annotation; public @interface NonNull {}\n",
    "androidx/annotation/Nullable.java": "package androidx.annotation; public @interface Nullable {}\n",
    "android/os/SystemClock.java": "package android.os; public final class SystemClock { public static volatile long now; public static long elapsedRealtime() { return now; } }\n",
    "android/os/Build.java": "package android.os; public final class Build { public static final class VERSION { public static int SDK_INT = 28; } public static final class VERSION_CODES { public static final int P = 28; } }\n",
    "android/content/pm/PackageInfo.java": "package android.content.pm; public class PackageInfo { public int versionCode; public long code; public long lastUpdateTime; public long getLongVersionCode() { return code; } }\n",
    "android/content/pm/PackageManager.java": "package android.content.pm; public class PackageManager { public final PackageInfo info = new PackageInfo(); public PackageInfo getPackageInfo(String pkg, int flags) { return info; } }\n",
    "android/content/SharedPreferences.java": """package android.content;
public interface SharedPreferences {
  String getString(String name, String fallback);
  long getLong(String name, long fallback);
  Editor edit();
  interface Editor { Editor clear(); Editor remove(String key); Editor putString(String key, String val);
     Editor putLong(String key, long val); void apply(); }
}
""",
    "android/content/Context.java": """package android.content;
import android.content.pm.PackageManager;
public abstract class Context {
  public static final int MODE_PRIVATE = 0;
  public abstract Context getApplicationContext();
  public abstract PackageManager getPackageManager();
  public abstract String getPackageName();
  public abstract SharedPreferences getSharedPreferences(String name, int mode);
}
""",
    "de/robv/android/xposed/XposedBridge.java": """package de.robv.android.xposed;
public class XposedBridge { public static void log(String text) {} }
""",
    "de/robv/android/xposed/XposedHelpers.java": """package de.robv.android.xposed;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
public final class XposedHelpers {
  public static Class<?> findClass(String name, ClassLoader loader) {
    try { return Class.forName(name, false, loader); }
    catch (ClassNotFoundException e) { throw new IllegalStateException(e); }
  }
  public static Method findMethodExact(Class<?> cls, String name, Class<?>... args) {
    try { return cls.getDeclaredMethod(name, args); }
    catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
  }
  public static Field findField(Class<?> cls, String name) {
    try { return cls.getDeclaredField(name); }
    catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
  }
}
""",
}
parser = argparse.ArgumentParser()
parser.add_argument("--baseline-manager", type=Path)
arguments = parser.parse_args()
manager_source = arguments.baseline_manager or DEX_SRC / "DexCacheManager.java"
with tempfile.TemporaryDirectory(prefix="waex_dex_cooldown_") as directory:
    root = Path(directory)
    sources = []
    for path, contents in STUBS.items():
        file = root / path
        file.parent.mkdir(parents=True, exist_ok=True)
        file.write_text(contents)
        sources.append(str(file))
    sources.extend(map(str, [
        DEX_SRC / "DexNegativeLookupCache.java",
        manager_source,
        ROOT / "qa/TestDexNegativeLookupCache.java",
        ROOT / "qa/TestDexCacheManager.java",
    ]))
    subprocess.run(["javac", "--release", "17", "-d", str(root), *sources], check=True)
    print("COMPILE_OK: complete production DexCacheManager and DexNegativeLookupCache", flush=True)
    for test in ["TestDexNegativeLookupCache", "TestDexCacheManager"]:
        subprocess.run(["java", "-cp", str(root),
                        "com.waenhancer.xposed.core.devkit." + test], check=True)

    # Load the unchanged production class against an older PackageInfo that has
    # no getLongVersionCode method. A Build check must avoid resolving that call.
    legacy = root / "legacy"
    package_info = legacy / "android/content/pm/PackageInfo.java"
    package_info.parent.mkdir(parents=True)
    package_info.write_text("package android.content.pm; public class PackageInfo { public int versionCode; public long code; public long lastUpdateTime; }\n")
    subprocess.run(["javac", "--release", "17", "-d", str(legacy), str(package_info)], check=True)
    declaration = subprocess.run(["javap", "-classpath", str(legacy), "android.content.pm.PackageInfo"],
                                 check=True, capture_output=True, text=True).stdout
    assert "getLongVersionCode" not in declaration, "legacy fixture must lack the API 28 method"
    for api in [24, 25, 27]:
        subprocess.run(["java", "-cp", str(legacy) + ":" + str(root),
                        "com.waenhancer.xposed.core.devkit.TestDexCacheManager", str(api)], check=True)

manager = manager_source.read_text()
engine = (DEX_SRC / "DexSearchEngine.java").read_text()
assert "mFailedKeys.shouldSkip(" in manager
assert "mFailedKeys.recordFailure(" in manager
assert "mFailedKeys.clearFailure(" in manager
assert "mFailedKeys.clear();" in manager
assert "SystemClock.elapsedRealtime()" in manager
assert "new DexNegativeLookupCache(FAILED_RETRY_MS, MAX_FAILED_KEYS)" in manager
assert "META_SCHEMA" in manager and "META_MODULE_VERSION" in manager
assert "mKeyLocks.computeIfAbsent(" in manager
assert "findMethodWithCache(" in engine and "findClassWithCache(" in engine
assert "findMethodsWithCache(" in engine and "findClassesWithCache(" in engine
assert "mFailedKeys.contains(" not in manager
print("PASS: 9 DexKit/cache integration invariants")
