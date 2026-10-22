"""Host discovery/Xposed/resource-label boundaries; Android Views and Drawables are real.

No WhatsApp APK, DexKit native scan, LSPosed runtime or GPU backdrop renderer is
present in this fixture. The complete production hook is compiled, while tests
invoke its actual selection and FAB-layout methods on Robolectric Views.
"""
STUBS = {
'com/waenhancer/utils/UiText.java': '''package com.waenhancer.utils;
public final class UiText {
 public static String get(int id,String fallback,Object...args) {return fallback;}
}''',
'com/waenhancer/xposed/features/customization/SeparateGroupsHook.java': '''package com.waenhancer.xposed.features.customization;
public final class SeparateGroupsHook {
 public static int getResumedConversationTab(){return 0;}
}''',
'com/waenhancer/xposed/core/devkit/DexSearchEngine.java': '''package com.waenhancer.xposed.core.devkit;
public final class DexSearchEngine {
 public interface DexKitClassQuery {Class<?> execute(org.luckypray.dexkit.DexKitBridge bridge,ClassLoader loader) throws Throwable;}
 public static DexSearchEngine getInstance(){return new DexSearchEngine();}
 public Class<?> findClassWithCache(android.content.Context c,ClassLoader loader,String key,DexKitClassQuery query){return null;}
}''',
'de/robv/android/xposed/XC_MethodHook.java': '''package de.robv.android.xposed;
public class XC_MethodHook {
 public static class MethodHookParam {public Object[] args=new Object[0];public Object thisObject;}
 protected void beforeHookedMethod(MethodHookParam param) throws Throwable {}
 protected void afterHookedMethod(MethodHookParam param) throws Throwable {}
}''',
'de/robv/android/xposed/XposedBridge.java': '''package de.robv.android.xposed;
public final class XposedBridge {
 public static void log(String message){}
 public static void hookMethod(java.lang.reflect.Method method,XC_MethodHook callback){throw new AssertionError("No LSPosed in fixture");}
 public static void hookAllMethods(Class<?> type,String name,XC_MethodHook callback){throw new AssertionError("No LSPosed in fixture");}
}''',
'de/robv/android/xposed/XposedHelpers.java': '''package de.robv.android.xposed;
public final class XposedHelpers {
 public static Class<?> findClassIfExists(String name,ClassLoader loader){return null;}
 public static void findAndHookMethod(Class<?> type,String name,Object... args){throw new AssertionError("No LSPosed in fixture");}
 public static Object getObjectField(Object target,String name){throw new UnsupportedOperationException("No GPU RenderNode fixture");}
 public static Object callMethod(Object target,String name,Object...args){throw new UnsupportedOperationException("No GPU RenderNode fixture");}
}'''
}
