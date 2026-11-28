#!/usr/bin/env python3
"""Compile the real bubble hook, BaseFeature reader and schema/alias policies.

The Android, hook lifecycle/sink and DexKit doubles expose preference and callback
behavior without claiming Android rendering, live IPC or WhatsApp acceptance.
Requires a complete JDK and Kotlin CLI distribution; downloads no dependencies.
"""
import argparse
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "app/src/main/java/com/waenhancer"
HOOK = SRC / "xposed/features/conversation/ChatBubbleColorsHook.kt"

JAVA = {
    "android/content/Context.java": """package android.content;
        public class Context { private final String pkg; public Context(String pkg){this.pkg=pkg;}
        public String getPackageName(){return pkg;} }""",
    "android/content/SharedPreferences.java": """package android.content;
        public interface SharedPreferences { boolean contains(String key); boolean getBoolean(String key,boolean fallback);
        int getInt(String key,int fallback); String getString(String key,String fallback); }""",
    "android/graphics/ColorFilter.java": "package android.graphics; public class ColorFilter {}",
    "android/graphics/Rect.java": "package android.graphics; public class Rect {}",
    "android/graphics/PorterDuff.java": "package android.graphics; public class PorterDuff { public enum Mode { SRC_IN } }",
    "android/graphics/PorterDuffColorFilter.java": """package android.graphics;
        public class PorterDuffColorFilter extends ColorFilter { public final int color;
        public PorterDuffColorFilter(int color,PorterDuff.Mode mode){this.color=color;} }""",
    "android/graphics/drawable/Drawable.java": """package android.graphics.drawable;
        public class Drawable { private android.graphics.ColorFilter colorFilter;
        public android.graphics.ColorFilter getColorFilter(){return colorFilter;}
        public void setColorFilter(android.graphics.ColorFilter value){colorFilter=value;} }""",
    "de/robv/android/xposed/XC_MethodHook.java": """package de.robv.android.xposed;
        public abstract class XC_MethodHook {
          protected void afterHookedMethod(MethodHookParam param) throws Throwable {}
          public static class MethodHookParam { public Object result; public Object[] args; public java.lang.reflect.Member method; }
          public static class Unhook { public void unhook(){} }
        }""",
    "de/robv/android/xposed/XposedBridge.java": """package de.robv.android.xposed;
        public class XposedBridge {
          public static final class Registration { public final java.lang.reflect.Method method;
            final XC_MethodHook callback; Registration(java.lang.reflect.Method m,XC_MethodHook c){method=m;callback=c;} }
          public static final java.util.List<Registration> installed=new java.util.ArrayList<>();
          public static void reset(){installed.clear();} public static void log(String text){}
          public static XC_MethodHook.Unhook hookMethod(java.lang.reflect.Member method,XC_MethodHook callback){
            installed.add(new Registration((java.lang.reflect.Method)method,callback));return new XC_MethodHook.Unhook();}
          public static void apply(Registration r,Object result,Object[] args) throws Throwable {
            XC_MethodHook.MethodHookParam p=new XC_MethodHook.MethodHookParam();p.method=r.method;p.result=result;p.args=args;
            r.callback.afterHookedMethod(p); }
        }""",
    "com/waenhancer/xposed/utils/HookErrors.java": """package com.waenhancer.xposed.utils;
        public final class HookErrors { public static void swallowed(String site,Throwable failure){
          throw new AssertionError("Unexpected swallowed failure in fixture: "+site,failure); } }""",
    "com/waenhancer/xposed/core/privacy/PrivacyPrefs.java": """package com.waenhancer.xposed.core.privacy;
        public class PrivacyPrefs { public static PrivacyPrefs of(android.content.Context c,android.content.SharedPreferences p){
          throw new AssertionError("Privacy settings are outside this fixture"); } }""",
    "org/luckypray/dexkit/query/matchers/MethodMatcher.java": """package org.luckypray.dexkit.query.matchers;
        public class MethodMatcher { public static MethodMatcher create(){return new MethodMatcher();}
        public MethodMatcher addUsingString(String value){return this;} }""",
    "org/luckypray/dexkit/query/FindMethod.java": """package org.luckypray.dexkit.query;
        public class FindMethod { public static FindMethod create(){return new FindMethod();}
        public FindMethod matcher(org.luckypray.dexkit.query.matchers.MethodMatcher m){return this;} }""",
    "com/waenhancer/xposed/core/devkit/DexSearchEngine.java": """package com.waenhancer.xposed.core.devkit;
        public class DexSearchEngine {
          public static int queries; public static java.lang.reflect.Method[] guarded;
          public interface Query { java.lang.reflect.Method[] execute(Bridge bridge,ClassLoader loader); }
          public static class MethodData { private final java.lang.reflect.Method method;
            MethodData(java.lang.reflect.Method method){this.method=method;}
            public java.lang.reflect.Method getMethodInstance(ClassLoader loader){return method;} }
          public static class Bridge { public java.util.List<MethodData> findMethod(Object query){
            java.util.List<MethodData> out=new java.util.ArrayList<>();
            for(java.lang.reflect.Method method:guarded)out.add(new MethodData(method));return out;} }
          public static DexSearchEngine getInstance(){return new DexSearchEngine();}
          public java.lang.reflect.Method[] findMethodsWithCache(android.content.Context c,ClassLoader loader,String key,Query query){
            queries++;return query.execute(new Bridge(),loader);}
        }""",
}

LIFECYCLE = """package com.waenhancer.xposed.core
import de.robv.android.xposed.XC_MethodHook
object HookRegistrar {
    @JvmField val BRIDGE = Bridge()
    @JvmField val HELPERS = Any()
    class Bridge {
        fun log(text: String) = de.robv.android.xposed.XposedBridge.log(text)
        fun hookMethod(method: java.lang.reflect.Member, callback: XC_MethodHook) =
            de.robv.android.xposed.XposedBridge.hookMethod(method, callback)
    }
    fun registerOwner(owner: Any) {} fun unregisterOwner(owner: Any) {}
    fun begin(owner: Any) {} fun end(owner: Any) {}
}
object ViewHookDispatcher { fun unregister(owner: Any) {} }
"""


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, default=HOOK, help="Alternate exact hook source for the baseline comparison")
    parser.add_argument("--kotlin-home", type=Path,
                        default=Path(os.environ["KOTLIN_HOME"]) if os.environ.get("KOTLIN_HOME") else None)
    args = parser.parse_args()
    home = args.kotlin_home
    if home is None:
        launcher = shutil.which("kotlinc") or shutil.which("kotlinc.bat")
        if launcher:
            home = Path(launcher).resolve().parent.parent
    if home is None or not (home / "lib/kotlin-compiler.jar").is_file():
        parser.error("Set --kotlin-home or KOTLIN_HOME to a complete Kotlin distribution")
    java, javac = shutil.which("java"), shutil.which("javac")
    if not java or not javac:
        parser.error("A complete JDK with java and javac is required")

    # These source checks bind the tested canonical OFF to the existing UI write/read path.
    ui = (SRC / "ui/screens/settings/BubbleColorsScreen.kt").read_text(encoding="utf-8")
    assert re.search(r'getBoolean\(\s*ChatBubbleColorsHook.PREF_ENABLED,\s*'
                     r'preferenceManager.getBoolean\("bubble_colors", false\)', ui)
    assert 'putBooleans(mapOf(ChatBubbleColorsHook.PREF_ENABLED to isChecked))' in ui

    with tempfile.TemporaryDirectory(prefix="waex-bubble-precedence-") as folder:
        out = Path(folder)
        classes = out / "classes"
        classes.mkdir()
        sources = []
        for name, source in JAVA.items():
            path = out / "doubles" / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(source, encoding="utf-8")
            sources.append(path)
        sources += [SRC / "config/PreferenceSchema.java",
                    SRC / "ui/screens/settings/StyleBooleanAliasPolicy.java"]
        subprocess.run([javac, "--release", "17", "-d", str(classes), *map(str, sources)], check=True, timeout=30)
        lifecycle = out / "Lifecycle.kt"
        lifecycle.write_text(LIFECYCLE, encoding="utf-8")
        jar = out / "checks.jar"
        subprocess.run([java, "-cp", str(home / "lib") + os.sep + "*",
                        "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-kotlin-home", str(home),
                        "-jvm-target", "17", "-classpath", str(classes), "-include-runtime", "-d", str(jar),
                        str(SRC / "xposed/core/BaseFeature.kt"), str(args.source), str(lifecycle),
                        str(ROOT / "qa/kotlin/BubbleColorPreferenceChecks.kt")], check=True, timeout=90)
        # BaseFeature scopes its cache to one target per process, as the real host does.
        failures = 0
        for target in ("com.whatsapp", "com.whatsapp.w4b"):
            result = subprocess.run([java, "-cp", str(jar) + os.pathsep + str(classes),
                                     "BubbleColorPreferenceChecks", target], timeout=30)
            failures += result.returncode != 0
        if failures:
            raise SystemExit(1)


if __name__ == "__main__":
    main()
