"""Controlled Dex class results and explicit constructor-callback dispatch.

Selectors compile against real DexKit; no target APK is scanned. The two hooks,
class-selection policy and ViewVisibilityGuard are complete production sources.
"""
STUBS = {
'com/waenhancer/xposed/core/devkit/DexSearchEngine.java': '''package com.waenhancer.xposed.core.devkit;
public final class DexSearchEngine {
 public interface DexKitClassQuery {Class<?> execute(org.luckypray.dexkit.DexKitBridge bridge,ClassLoader loader) throws Throwable;}
 public static Class<?> metaTarget,channelTarget;
 public static int metaQueries;
 public static void reset(){metaTarget=null;channelTarget=null;metaQueries=0;}
 public static DexSearchEngine getInstance(){return new DexSearchEngine();}
 public Class<?> findClassWithCache(android.content.Context c,ClassLoader loader,String key,DexKitClassQuery query){
  if(key.contains("meta_ai")){metaQueries++;return metaTarget;}
  if(key.contains("channel_rec"))return channelTarget;
  return null;
 }
}''',
'de/robv/android/xposed/XposedBridge.java': '''package de.robv.android.xposed;
public final class XposedBridge {
 private static final java.util.Map<Class<?>,java.util.List<XC_MethodHook>> constructors=new java.util.LinkedHashMap<>();
 public static void reset(){constructors.clear();}
 public static void log(String message){}
 public static void hookAllConstructors(Class<?> type,XC_MethodHook callback){
  constructors.computeIfAbsent(type,key->new java.util.ArrayList<>()).add(callback);
 }
 public static void dispatchConstructor(Object target) throws Throwable {
  XC_MethodHook.MethodHookParam param=new XC_MethodHook.MethodHookParam();param.thisObject=target;
  for(java.util.Map.Entry<Class<?>,java.util.List<XC_MethodHook>> item:constructors.entrySet())
   if(item.getKey().isInstance(target))for(XC_MethodHook callback:item.getValue())callback.afterHookedMethod(param);
 }
}'''
}
