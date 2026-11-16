#!/usr/bin/env python3
"""Exercise production media hook + DexSearchEngine + DexCacheManager on the JVM.

Expected-correct assertions never depend on whether source is baseline/candidate.
Android, Xposed and DexKit metadata are strict fixtures, NOT device acceptance.
The disk-cache scenario uses a separate seeding JVM and a real properties file.
No real APK, WhatsApp account, native hook, codec or network operation is used.
"""
from __future__ import annotations

import argparse
import ast
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import sys
import time


HARNESS = r'''package fixture;
import android.content.*;
import android.content.pm.*;
import java.io.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import com.waenhancer.xposed.core.devkit.*;
import com.waenhancer.xposed.features.media.MediaQualityBypassHook;
import de.robv.android.xposed.*;
import org.luckypray.dexkit.DexKitBridge;

public final class MediaWarmCacheProbe {
  public static final String CLASS_KEY="class:waex_process_image_quality_class";
  public static final String APK="/synthetic-waex-probe/base.apk";
  public static String scenario;
  public static final List<String> violations=new ArrayList<>(), assertions=new ArrayList<>();
  public static int classQueries,methodQueries,metadataReads,createCalls,closeCalls;
  public static AssertionError unexpected(String message) {
    violations.add(message);return new AssertionError(message);
  }
  public static void require(boolean condition,String message) {
    if(!condition)assertions.add(message);
  }
  public static final class Quality {
    public int maxKb,quality,maxEdge;
    public Quality(int kb,int percent,int edge){maxKb=kb;quality=percent;maxEdge=edge;}
    @Override public String toString(){return "ProcessImageQuality(maxKb="+maxKb+", quality="+quality+", maxEdge="+maxEdge+")";}
  }
  public static final class Encoder {
    public Integer value=24;public RuntimeException failure;
    public Integer fps(){if(failure!=null)throw failure;return value;}
  }
  public static final class FilePrefs implements SharedPreferences {
    private final Path path;private final Map<String,Object> values=new LinkedHashMap<>();
    public FilePrefs(Path file)throws IOException {
      path=file;if(file!=null&&Files.exists(file)){
        Properties p=new Properties();try(InputStream input=Files.newInputStream(file)){p.load(input);}
        for(String key:p.stringPropertyNames()){
          String text=p.getProperty(key);
          if(text.startsWith("s:"))values.put(key,text.substring(2));
          else if(text.startsWith("l:"))values.put(key,Long.valueOf(text.substring(2)));
          else if(text.equals("b:true")||text.equals("b:false"))values.put(key,Boolean.valueOf(text.substring(2)));
          else throw unexpected("unsupported persisted preference type");
        }
      }
    }
    @Override public boolean contains(String key){return values.containsKey(key);}
    @Override public String getString(String key,String fallback){return values.containsKey(key)?(String)values.get(key):fallback;}
    @Override public long getLong(String key,long fallback){return values.containsKey(key)?(Long)values.get(key):fallback;}
    @Override public boolean getBoolean(String key,boolean fallback){return values.containsKey(key)?(Boolean)values.get(key):fallback;}
    @Override public Editor edit(){return new Editor(){
      boolean clear;Map<String,Object> edits=new LinkedHashMap<>();
      public Editor clear(){clear=true;return this;}
      public Editor remove(String key){edits.put(key,null);return this;}
      public Editor putString(String key,String value){edits.put(key,value);return this;}
      public Editor putLong(String key,long value){edits.put(key,value);return this;}
      public Editor putBoolean(String key,boolean value){edits.put(key,value);return this;}
      public void apply(){
        if(clear)values.clear();
        for(Map.Entry<String,Object> entry:edits.entrySet()){
          if(entry.getValue()==null)values.remove(entry.getKey());else values.put(entry.getKey(),entry.getValue());
        }
        if(path==null)return;
        Properties p=new Properties();
        for(Map.Entry<String,Object> entry:values.entrySet()){
          Object value=entry.getValue();
          String prefix=value instanceof String?"s:":value instanceof Long?"l:":value instanceof Boolean?"b:":null;
          if(prefix==null)throw unexpected("unsupported outgoing preference type");
          p.setProperty(entry.getKey(),prefix+value);
        }
        try{
          Files.createDirectories(path.getParent());Path temporary=path.resolveSibling(path.getFileName()+".pending");
          try(OutputStream output=Files.newOutputStream(temporary)){p.store(output,"Synthetic WAEX probe preferences");}
          Files.move(temporary,path,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
        }catch(IOException failure){throw new UncheckedIOException(failure);}
      }
    };}
  }
  public static final class TestContext extends Context {
    public final FilePrefs cache;private final PackageManager manager=new PackageManager();
    public TestContext(Path file)throws IOException{cache=new FilePrefs(file);}
    public Context getApplicationContext(){return this;}
    public String getPackageName(){return "com.whatsapp";}
    public PackageManager getPackageManager(){return manager;}
    public ApplicationInfo getApplicationInfo(){return manager.getApplicationInfo("com.whatsapp",0);}
    public SharedPreferences getSharedPreferences(String name,int mode){
      if(!name.equals("waex_dex_cache")||mode!=0)throw unexpected("unexpected preferences store");return cache;
    }
  }
  private static String hex(String value){return HexFormat.of().formatHex(value.getBytes(StandardCharsets.UTF_8));}
  private static void exerciseConstructor(String kind,FilePrefs preferences)throws Throwable {
    List<XC_MethodHook> callbacks=XposedBridge.constructors.getOrDefault(Quality.class,List.of());
    boolean unavailable=kind.equals("D_init_fail")||kind.equals("D2_warm_init_fail");
    require(callbacks.size()==(unavailable?0:1),"constructor registrations expected "+(unavailable?0:1)+" but got "+callbacks.size());
    Quality value=new Quality(1024,75,4096);
    XC_MethodHook.MethodHookParam param=new XC_MethodHook.MethodHookParam();
    param.args=new Object[]{1024,75,4096};param.thisObject=value;
    RuntimeException original=new IllegalStateException("synthetic-constructor-failure");
    if(kind.equals("E_disable"))preferences.edit().putBoolean("imagequality",false).apply();
    if(kind.equals("F_constructor_error"))param.setThrowable(original);
    if(kind.equals("H_null_receiver"))param.thisObject=null;
    for(XC_MethodHook callback:callbacks){
      try{callback.afterForTest(param);}catch(Throwable failure){assertions.add("constructor callback threw "+failure.getClass().getSimpleName());}
    }
    boolean unchanged=unavailable||kind.equals("E_disable")||kind.equals("F_constructor_error")||kind.equals("H_null_receiver");
    require(value.maxKb==(unchanged?1024:51200),"wrong maxKb after callback");
    require(value.quality==(unchanged?75:100),"wrong quality after callback");
    require(value.maxEdge==(unchanged?4096:6000),"wrong maxEdge after callback");
    require(param.getThrowable()==(kind.equals("F_constructor_error")?original:null),"constructor throwable identity changed");
  }
  private static void exerciseFps(FilePrefs preferences)throws Throwable {
    Method fps=Encoder.class.getDeclaredMethod("fps");
    require(XposedBridge.methods.getOrDefault(fps,List.of()).size()==1,"FPS callback not registered exactly once");
    Encoder encoder=new Encoder();
    for(Integer value:new Integer[]{24,60,120,0,-1,null}){
      encoder.value=value;XC_MethodHook.MethodHookParam result=XposedBridge.invoke(fps,encoder);
      Integer expected=value!=null&&value>0&&value<60?Integer.valueOf(60):value;
      require(Objects.equals(result.getResult(),expected),"FPS changed protected result "+value);
      require(!result.hasThrowable(),"FPS introduced throwable for "+value);
    }
    encoder.failure=new IllegalStateException("synthetic-codec-failure");
    require(XposedBridge.invoke(fps,encoder).getThrowable()==encoder.failure,"FPS masked host exception");
    encoder.failure=null;encoder.value=24;preferences.edit().putBoolean("video_maxfps",false).apply();
    require(Integer.valueOf(24).equals(XposedBridge.invoke(fps,encoder).getResult()),"FPS changed result while disabled");
  }
  public static void main(String[] args)throws Throwable {
    if(args.length!=3)throw new IllegalArgumentException("scenario cache-file preinitialize-control");
    scenario=args[0];Path store=Path.of(args[1]);boolean preinitialize=Boolean.parseBoolean(args[2]);
    boolean seed=scenario.equals("seed"), corrupt=scenario.equals("corrupt");
    TestContext context=new TestContext(store);
    if(corrupt){
      String raw=context.cache.getString(CLASS_KEY,null);
      if(raw==null||!raw.contains(hex(Quality.class.getName())))throw unexpected("corruption seed is not a valid production descriptor");
      context.cache.edit().putString(CLASS_KEY,raw.replace(hex(Quality.class.getName()),hex("fixture.MissingQuality"))).apply();
      System.out.println("SETUP_OK corrupt production descriptor owner, preserve metadata");return;
    }
    if(scenario.startsWith("B_")||scenario.equals("C_invalid_disk")||scenario.equals("D2_warm_init_fail")){
      require(context.cache.getString(CLASS_KEY,null)!=null,"persisted descriptor missing at new JVM entry");
    }
    DexSearchEngine engine=DexSearchEngine.getInstance();
    require(engine.getBridge()==null,"bridge not closed at fresh JVM entry");
    FilePrefs preferences=new FilePrefs(null);
    preferences.edit().putBoolean("imagequality",!scenario.equals("G_fps"))
      .putBoolean("video_maxfps",scenario.equals("G_fps")).putString("module_config_mode","Universal").apply();
    engine.acquireLease();
    try{
      if(preinitialize)engine.initialize(context);
      new MediaQualityBypassHook(context,MediaWarmCacheProbe.class.getClassLoader(),preferences).hook();
    }finally{engine.releaseLease();}
    DexCacheManager.Diagnostics stats=DexCacheManager.getInstance(context).diagnostics();
    if(scenario.startsWith("B_")||scenario.equals("D2_warm_init_fail")){
      require(stats.diskHits()==1,"warm case did not consume exactly one disk descriptor");
      require(stats.memoryHits()==0,"warm case incorrectly used process memory cache");
      require(classQueries==0,"warm descriptor incorrectly re-ran class search");
    }
    if(scenario.equals("C_invalid_disk")){
      require(stats.invalidations()==1,"invalid class descriptor was not evicted once");
      require(classQueries==1,"invalid descriptor did not perform exactly one recovery query");
      String repaired=context.cache.getString(CLASS_KEY,"");
      require(repaired.contains(hex(Quality.class.getName())),"invalid descriptor was not repaired");
    }
    boolean unavailable=scenario.equals("D_init_fail")||scenario.equals("D2_warm_init_fail");
    if(unavailable){
      require(engine.getBridge()==null,"failed initialization fabricated a bridge");
      require(createCalls>0,"initialization failure fixture never executed");
      require(metadataReads==0,"metadata accessed without available bridge");
      require(XposedBridge.logs.stream().anyMatch(line->line.contains("initialize DexKitBridge")),"initialization failure was not diagnosed");
    }else if(!scenario.equals("G_fps")){
      require(metadataReads==1,"structured metadata was not read exactly once");
      if(!scenario.startsWith("B_"))require(classQueries==1,"cold class resolution not executed exactly once");
    }
    if(scenario.equals("G_fps"))exerciseFps(preferences);else exerciseConstructor(scenario,preferences);
    engine.close();
    require(engine.getBridge()==null,"bridge did not close at experiment end");
    if(seed)require(context.cache.getString(CLASS_KEY,null)!=null,"seed did not persist production descriptor");
    require(violations.isEmpty(),"unexpected fixture calls: "+violations);
    System.out.println("RESULT scenario="+scenario+" status="+(assertions.isEmpty()?"PASS":"FAIL")
      +" diskHits="+stats.diskHits()+" memoryHits="+stats.memoryHits()+" classQueries="+classQueries
      +" metadataReads="+metadataReads+" creates="+createCalls+" closes="+closeCalls
      +" constructorCallbacks="+XposedBridge.constructors.getOrDefault(Quality.class,List.of()).size()
      +" strictViolations="+violations.size()+" failures="+assertions.size());
    for(String failure:assertions)System.out.println("ASSERT_FAIL "+failure);
    for(String log:XposedBridge.logs)System.out.println("PRODUCTION_LOG "+log);
    if(!assertions.isEmpty())System.exit(1);
  }
}
'''


EXTRA_STUBS = {
    "de/robv/android/xposed/XposedHelpers.java": '''package de.robv.android.xposed;
import java.lang.reflect.*;import fixture.MediaWarmCacheProbe;
public final class XposedHelpers {
 public static Class<?> findClass(String name,ClassLoader loader){
  if(Boolean.getBoolean("waex.probe.forceDiskMiss")&&name.equals(MediaWarmCacheProbe.Quality.class.getName()))
   throw new IllegalStateException("injected descriptor reconstruction miss");
  try{return Class.forName(name,false,loader);}catch(ClassNotFoundException failure){throw new IllegalStateException(failure);}
 }
 public static Method findMethodExact(Class<?> owner,String name,Class<?>... types){
  try{return owner.getDeclaredMethod(name,types);}catch(ReflectiveOperationException failure){throw new IllegalStateException(failure);}
 }
 public static Field findField(Class<?> owner,String name){
  try{return owner.getDeclaredField(name);}catch(ReflectiveOperationException failure){throw new IllegalStateException(failure);}
 }
}
''',
    "android/os/Build.java": '''package android.os; public final class Build {
 public static final String[] SUPPORTED_ABIS={"fixture"};
 public static final class VERSION{public static int SDK_INT=34;}
 public static final class VERSION_CODES{public static final int P=28;}}
''',
    "android/content/pm/ApplicationInfo.java": '''package android.content.pm;
public final class ApplicationInfo {public String sourceDir,nativeLibraryDir;}
''',
    "android/content/pm/PackageManager.java": '''package android.content.pm;
import fixture.MediaWarmCacheProbe;
public final class PackageManager {
 public PackageInfo getPackageInfo(String name,int flags){
  if(flags!=0||(!name.equals("com.whatsapp")&&!name.equals("com.waenhancer")))throw MediaWarmCacheProbe.unexpected("unknown package identity lookup");
  PackageInfo info=new PackageInfo();info.code=name.equals("com.whatsapp")?264307627L:206L;
  info.versionCode=(int)info.code;info.lastUpdateTime=123456789L;return info;
 }
 public ApplicationInfo getApplicationInfo(String name,int flags){
  if(flags!=0||(!name.equals("com.whatsapp")&&!name.equals("com.waenhancer")))throw MediaWarmCacheProbe.unexpected("unknown application identity lookup");
  ApplicationInfo info=new ApplicationInfo();info.sourceDir=MediaWarmCacheProbe.APK;
  info.nativeLibraryDir="/synthetic-waex-probe/no-native-libraries";return info;
 }
}
''',
    "android/content/SharedPreferences.java": '''package android.content;
public interface SharedPreferences {
 boolean contains(String key);String getString(String key,String fallback);long getLong(String key,long fallback);
 boolean getBoolean(String key,boolean fallback);Editor edit();
 interface Editor {Editor clear();Editor remove(String key);Editor putString(String key,String value);
  Editor putLong(String key,long value);Editor putBoolean(String key,boolean value);void apply();}
}
''',
    "android/content/Context.java": '''package android.content;
import android.content.pm.*;
public abstract class Context {public static final int MODE_PRIVATE=0;
 public abstract Context getApplicationContext();public abstract String getPackageName();
 public abstract PackageManager getPackageManager();public abstract ApplicationInfo getApplicationInfo();
 public abstract SharedPreferences getSharedPreferences(String name,int mode);}
''',
    "com/waenhancer/xposed/MainHook.java": '''package com.waenhancer.xposed; public final class MainHook {public static String sModulePath=null;}''',
    "com/waenhancer/xposed/core/BaseFeature.java": '''package com.waenhancer.xposed.core;
import android.content.*;
public abstract class BaseFeature {
 protected final Context context;protected final ClassLoader classLoader;protected final SharedPreferences prefs;
 protected BaseFeature(Context c,ClassLoader l,SharedPreferences p){context=c;classLoader=l;prefs=p;}
 public abstract void hook()throws Throwable;public abstract String getName();
 protected boolean isEnabled(String key,boolean fallback){
  if(prefs.contains(key))return prefs.getBoolean(key,fallback);
  String pkg=context.getPackageName();
  if(pkg!=null&&!pkg.isEmpty()&&prefs.contains(pkg+":"+key))return prefs.getBoolean(pkg+":"+key,fallback);
  if("Universal".equalsIgnoreCase(prefs.getString("module_config_mode","Universal"))
   &&(prefs.getBoolean("com.whatsapp:"+key,false)||prefs.getBoolean("com.whatsapp.w4b:"+key,false)))return true;
  return fallback;
 }
}
''',
    "com/waenhancer/xposed/features/customization/PropsOverrideHook.java": '''package com.waenhancer.xposed.features.customization;
import fixture.MediaWarmCacheProbe;
public final class PropsOverrideHook {
 public static void setIntegerOverride(int id,boolean enabled,int value){throw MediaWarmCacheProbe.unexpected("unexpected property configuration entry point");}
 public static void setBooleanOverride(int id,boolean enabled,boolean value){throw MediaWarmCacheProbe.unexpected("unexpected property configuration entry point");}
}
''',
    "de/robv/android/xposed/XC_MethodHook.java": '''package de.robv.android.xposed;
public abstract class XC_MethodHook {
 protected void beforeHookedMethod(MethodHookParam p)throws Throwable{}
 protected void afterHookedMethod(MethodHookParam p)throws Throwable{}
 public final void afterForTest(MethodHookParam p)throws Throwable{afterHookedMethod(p);}
 public final void beforeForTest(MethodHookParam p)throws Throwable{beforeHookedMethod(p);}
 public static final class Unhook {}
 public static final class MethodHookParam {
  public Object thisObject;public Object[] args;private Object result;private Throwable throwable;
  public boolean hasThrowable(){return throwable!=null;}public Throwable getThrowable(){return throwable;}
  public Object getResult(){return result;}public void setResult(Object value){result=value;throwable=null;}
  public void setThrowable(Throwable value){throwable=value;result=null;}
 }
}
''',
    "de/robv/android/xposed/XposedBridge.java": '''package de.robv.android.xposed;
import java.util.*;import java.lang.reflect.*;import fixture.MediaWarmCacheProbe;
public final class XposedBridge {
 public static final List<String> logs=new ArrayList<>();
 public static final Map<Class<?>,List<XC_MethodHook>> constructors=new HashMap<>();
 public static final Map<Method,List<XC_MethodHook>> methods=new HashMap<>();
 public static void log(String value){logs.add(value);}
 public static Set<XC_MethodHook.Unhook> hookAllConstructors(Class<?> type,XC_MethodHook callback){
  if(type!=MediaWarmCacheProbe.Quality.class||type.getDeclaredConstructors().length!=1)throw MediaWarmCacheProbe.unexpected("wrong constructor target");
  constructors.computeIfAbsent(type,k->new ArrayList<>()).add(callback);return Set.of(new XC_MethodHook.Unhook());
 }
 public static XC_MethodHook.Unhook hookMethod(Method target,XC_MethodHook callback){
  if(target.getDeclaringClass()!=MediaWarmCacheProbe.Encoder.class||!target.getName().equals("fps"))throw MediaWarmCacheProbe.unexpected("wrong method target");
  methods.computeIfAbsent(target,k->new ArrayList<>()).add(callback);return new XC_MethodHook.Unhook();
 }
 public static XC_MethodHook.MethodHookParam invoke(Method method,Object receiver)throws Throwable{
  XC_MethodHook.MethodHookParam param=new XC_MethodHook.MethodHookParam();param.thisObject=receiver;param.args=new Object[0];
  for(XC_MethodHook cb:methods.getOrDefault(method,List.of()))cb.beforeForTest(param);
  try{param.setResult(method.invoke(receiver));}catch(InvocationTargetException failed){param.setThrowable(failed.getCause());}
  for(XC_MethodHook cb:methods.getOrDefault(method,List.of()))cb.afterForTest(param);return param;
 }
}
''',
    "org/luckypray/dexkit/query/enums/StringMatchType.java": '''package org.luckypray.dexkit.query.enums;public enum StringMatchType{StartsWith,Contains}''',
    "org/luckypray/dexkit/query/matchers/ClassMatcher.java": '''package org.luckypray.dexkit.query.matchers;
import org.luckypray.dexkit.query.enums.StringMatchType;
public final class ClassMatcher {public String anchor;public StringMatchType match;
 public static ClassMatcher create(){return new ClassMatcher();}
 public ClassMatcher addUsingString(String value,StringMatchType mode){anchor=value;match=mode;return this;}}
''',
    "org/luckypray/dexkit/query/matchers/MethodMatcher.java": '''package org.luckypray.dexkit.query.matchers;
import org.luckypray.dexkit.query.enums.StringMatchType;
public final class MethodMatcher {public String anchor;public StringMatchType match;
 public static MethodMatcher create(){return new MethodMatcher();}
 public MethodMatcher addUsingString(String value,StringMatchType mode){anchor=value;match=mode;return this;}}
''',
    "org/luckypray/dexkit/query/FindClass.java": '''package org.luckypray.dexkit.query;import org.luckypray.dexkit.query.matchers.ClassMatcher;
public final class FindClass {public ClassMatcher matcher;public static FindClass create(){return new FindClass();}
 public FindClass matcher(ClassMatcher value){matcher=value;return this;}}
''',
    "org/luckypray/dexkit/query/FindMethod.java": '''package org.luckypray.dexkit.query;import org.luckypray.dexkit.query.matchers.MethodMatcher;
public final class FindMethod {public MethodMatcher matcher;public static FindMethod create(){return new FindMethod();}
 public FindMethod matcher(MethodMatcher value){matcher=value;return this;}}
''',
    "org/luckypray/dexkit/result/ResultList.java": '''package org.luckypray.dexkit.result;
public final class ResultList<T> extends java.util.ArrayList<T>{public T firstOrNull(){return isEmpty()?null:get(0);}}
''',
    "org/luckypray/dexkit/result/ClassData.java": '''package org.luckypray.dexkit.result;
import fixture.MediaWarmCacheProbe;
public final class ClassData {public Class<?> getInstance(ClassLoader loader)throws ClassNotFoundException{
 Class<?> value=Class.forName(MediaWarmCacheProbe.Quality.class.getName(),false,loader);
 if(value!=MediaWarmCacheProbe.Quality.class)throw MediaWarmCacheProbe.unexpected("wrong classloader");return value;}}
''',
    "org/luckypray/dexkit/result/FieldData.java": '''package org.luckypray.dexkit.result;import java.lang.reflect.Field;
public final class FieldData {private final Field field;public FieldData(Field f){field=f;}
 public Field getFieldInstance(ClassLoader loader){return field;}}
''',
    "org/luckypray/dexkit/result/UsingFieldData.java": '''package org.luckypray.dexkit.result;
public final class UsingFieldData {private final FieldData field;public UsingFieldData(FieldData f){field=f;}public FieldData getField(){return field;}}
''',
    "org/luckypray/dexkit/result/MethodData.java": '''package org.luckypray.dexkit.result;
import java.lang.reflect.*;import java.util.*;import fixture.MediaWarmCacheProbe;
public final class MethodData {private final Method method;public MethodData(Method value){method=value;}
 public Method getMethodInstance(ClassLoader loader){return method;}
 public List<String> getUsingStrings(){
  if(method.getDeclaringClass()!=MediaWarmCacheProbe.Quality.class)throw MediaWarmCacheProbe.unexpected("unexpected string metadata");
  return List.of("ProcessImageQuality(maxKb=",", quality=",", maxEdge=");
 }
 public List<UsingFieldData> getUsingFields(){
  if(method.getDeclaringClass()!=MediaWarmCacheProbe.Quality.class)throw MediaWarmCacheProbe.unexpected("unexpected field metadata");
  try{List<UsingFieldData> fields=new ArrayList<>();for(String name:List.of("maxKb","quality","maxEdge"))
   fields.add(new UsingFieldData(new FieldData(MediaWarmCacheProbe.Quality.class.getField(name))));return fields;
  }catch(ReflectiveOperationException failed){throw new AssertionError(failed);}
 }
}
''',
    "org/luckypray/dexkit/DexKitBridge.java": '''package org.luckypray.dexkit;
import java.lang.reflect.*;import fixture.MediaWarmCacheProbe;import org.luckypray.dexkit.query.*;
import org.luckypray.dexkit.query.enums.StringMatchType;import org.luckypray.dexkit.result.*;
public final class DexKitBridge {
 private boolean closed;
 public static DexKitBridge create(String path){
  MediaWarmCacheProbe.createCalls++;
  if(!path.equals(MediaWarmCacheProbe.APK))throw MediaWarmCacheProbe.unexpected("unrecognized APK path");
  if(MediaWarmCacheProbe.scenario.equals("D_init_fail")||MediaWarmCacheProbe.scenario.equals("D2_warm_init_fail"))
   throw new IllegalStateException("synthetic-native-index-unavailable");return new DexKitBridge();
 }
 private void open(){if(closed)throw MediaWarmCacheProbe.unexpected("use after bridge close");}
 public void close(){open();closed=true;MediaWarmCacheProbe.closeCalls++;}
 public ResultList<ClassData> findClass(FindClass query){
  open();MediaWarmCacheProbe.classQueries++;
  if(query.matcher==null||!"ProcessImageQuality(".equals(query.matcher.anchor)||query.matcher.match!=StringMatchType.StartsWith)
   throw MediaWarmCacheProbe.unexpected("unrecognized class query");
  ResultList<ClassData> result=new ResultList<>();result.add(new ClassData());return result;
 }
 public ResultList<MethodData> findMethod(FindMethod query){
  open();MediaWarmCacheProbe.methodQueries++;ResultList<MethodData> result=new ResultList<>();
  if(query.matcher==null||query.matcher.match!=StringMatchType.Contains)throw MediaWarmCacheProbe.unexpected("unrecognized method matcher");
  String anchor=query.matcher.anchor;
  if(!anchor.equals("image/compress quality")&&!anchor.equals("video/encoder fps"))throw MediaWarmCacheProbe.unexpected("unrecognized method anchor");
  if(anchor.equals("video/encoder fps")&&MediaWarmCacheProbe.scenario.equals("G_fps")){
   try{result.add(new MethodData(MediaWarmCacheProbe.Encoder.class.getDeclaredMethod("fps")));}
   catch(ReflectiveOperationException failure){throw new AssertionError(failure);}
  }
  return result;
 }
 public MethodData getMethodData(Method method){
  open();MediaWarmCacheProbe.metadataReads++;
  if(Boolean.getBoolean("waex.probe.unexpectedMetadata"))throw MediaWarmCacheProbe.unexpected("injected unexpected metadata boundary call");
  if(method.getDeclaringClass()!=MediaWarmCacheProbe.Quality.class||!method.getName().equals("toString")||method.getParameterCount()!=0)
   throw MediaWarmCacheProbe.unexpected("unrecognized metadata request");return new MethodData(method);
 }
}
''',
}


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def inherited_stubs(path: Path) -> dict[str, str]:
    """Read the literal only; never import/execute another QA runner."""
    tree = ast.parse(path.read_text())
    for node in tree.body:
        if isinstance(node, ast.Assign) and any(isinstance(t, ast.Name) and t.id == "STUBS" for t in node.targets):
            return ast.literal_eval(node.value)
    raise ValueError("No literal STUBS dictionary in recorded QA source")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-root", required=True, type=Path)
    parser.add_argument("--out", required=True, type=Path, help="new evidence directory outside the source tree")
    parser.add_argument("--java", default=shutil.which("java") or "java")
    parser.add_argument("--preinitialize-control", action="store_true",
                        help="external priming control, NOT a product fix; run B only")
    parser.add_argument("--negative-control", choices=["swallowed-boundary-error", "forced-disk-cache-miss"],
                        help="intentional fixture fault; a completed behavioral FAIL is expected")
    args = parser.parse_args()
    if args.preinitialize_control and args.negative_control:
        parser.error("controls must run separately")
    root = args.source_root.resolve()
    out = args.out.resolve()
    if out == root or out.is_relative_to(root):
        parser.error("evidence must be outside the source tree")
    if out.exists():
        parser.error("evidence directory exists; select a fresh path (no overwrite)")
    production_dir = root / "app/src/main/java/com/waenhancer/xposed"
    production = [production_dir / "core/devkit" / filename for filename in (
        "DexSearchEngine.java", "DexCacheManager.java", "DexNegativeLookupCache.java", "HookReturnTypeGuard.java")]
    production.append(production_dir / "features/media/MediaQualityBypassHook.java")
    template = root / "qa/test_dex_negative_cache.py"
    identities = {str(p.relative_to(root)): sha256(p) for p in [*production, template]}
    stubs = inherited_stubs(template)
    stubs.update(EXTRA_STUBS)
    stubs["fixture/MediaWarmCacheProbe.java"] = HARNESS
    out.mkdir(parents=True)
    generated = out / "generated"
    classes = out / "classes"
    classes.mkdir()
    sources = []
    for path, content in stubs.items():
        target = generated / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(content)
        sources.append(str(target))
    report = {
        "schema": "WAEX_MEDIA_WARM_CACHE_V1", "source_root": str(root),
        "source_sha256": identities, "runner_sha256": sha256(Path(__file__)),
        "mode": "EXTERNAL_PREINITIALIZE_CONTROL" if args.preinitialize_control else
                "NEGATIVE_CONTROL_"+args.negative_control if args.negative_control else "EXPECTED_CORRECT_CONTRACT",
        "limits": "JVM; actual production hook, engine and both cache classes. Strict Android/DexKit/Xposed fixtures. No native DexKit, APK, ART, codec or UI acceptance. Source/target17 on JDK21 is not Java17 API-surface verification.",
        "commands": [], "cases": []}
    report["git_head"] = subprocess.run(["git", "-C", str(root), "rev-parse", "HEAD"], text=True, capture_output=True, check=True).stdout.strip()
    report["git_status"] = subprocess.run(["git", "-C", str(root), "status", "--short"], text=True, capture_output=True, check=True).stdout
    report["java_version"] = subprocess.run([args.java, "-version"], text=True, capture_output=True, check=True).stderr.strip()

    def execute(label: str, command: list[str], timeout: int = 45) -> subprocess.CompletedProcess:
        start = time.monotonic()
        completed = subprocess.run(command, text=True, capture_output=True, timeout=timeout)
        (out / (label + ".stdout.txt")).write_text(completed.stdout)
        (out / (label + ".stderr.txt")).write_text(completed.stderr)
        report["commands"].append({"label": label, "argv": command, "exit": completed.returncode,
                                   "duration_seconds": round(time.monotonic()-start, 6)})
        return completed

    compile_command = [args.java, "-m", "jdk.compiler/com.sun.tools.javac.Main", "-source", "17", "-target", "17",
                       "-d", str(classes), *sources, *map(str, production)]
    compiled = execute("compile", compile_command)
    if compiled.returncode:
        report["status"] = "BLOCKED"
        report["blocker"] = "Java compilation failed; not a behavioral failure"
        (out / "RESULTS.json").write_text(json.dumps(report, indent=2)+"\n")
        print(compiled.stderr, file=sys.stderr)
        return 2
    print("COMPILE_OK production hook + DexSearchEngine + DexCacheManager + DexNegativeLookupCache", flush=True)
    cases = ["A_cold"] if args.negative_control == "swallowed-boundary-error" else ["B_warm_disk"] if (
        args.preinitialize_control or args.negative_control == "forced-disk-cache-miss") else [
        "A_cold", "B_warm_disk", "C_invalid_disk", "D_init_fail", "D2_warm_init_fail",
        "E_disable", "F_constructor_error", "G_fps", "H_null_receiver"]
    fail_count = 0
    for name in cases:
        directory = out / name
        directory.mkdir()
        cache = directory / "preferences.properties"
        flags = []
        if args.negative_control == "swallowed-boundary-error":
            flags.append("-Dwaex.probe.unexpectedMetadata=true")
        if args.negative_control == "forced-disk-cache-miss":
            flags.append("-Dwaex.probe.forceDiskMiss=true")
        prefix = [args.java, *flags, "-cp", str(classes), "fixture.MediaWarmCacheProbe"]
        if name in {"B_warm_disk", "C_invalid_disk", "D2_warm_init_fail"}:
            seed = execute(name+".seed", prefix+["seed", str(cache), "false"])
            if seed.returncode or "RESULT scenario=seed status=PASS" not in seed.stdout:
                report["cases"].append({"scenario": name, "status": "BLOCKED", "reason": "seed contract failed"})
                fail_count += 1
                print(seed.stdout+seed.stderr, flush=True)
                continue
            if name == "C_invalid_disk":
                corrupt = execute(name+".corrupt", prefix+["corrupt", str(cache), "false"])
                if corrupt.returncode or "SETUP_OK" not in corrupt.stdout:
                    report["cases"].append({"scenario": name, "status": "BLOCKED", "reason": "corruption setup failed"})
                    fail_count += 1
                    continue
            snapshot = directory / "cache-input.properties"
            shutil.copy2(cache, snapshot)
            report.setdefault("disk_inputs", {})[name] = {
                "path": str(snapshot), "sha256": sha256(snapshot), "live_store": str(cache)}
        result = execute(name, prefix+[name, str(cache), str(args.preinitialize_control).lower()])
        marker = "RESULT scenario="+name+" status="
        complete = marker in result.stdout
        status = "PASS" if result.returncode == 0 and marker+"PASS" in result.stdout else "FAIL" if complete else "BLOCKED"
        report["cases"].append({"scenario": name, "status": status,
                                "result": next((line for line in result.stdout.splitlines() if line.startswith("RESULT ")), None),
                                "assertion_failures": [line for line in result.stdout.splitlines() if line.startswith("ASSERT_FAIL ")]})
        fail_count += status != "PASS"
        if cache.exists():
            report.setdefault("disk_outputs", {})[name] = {"path": str(cache), "sha256": sha256(cache)}
        print(result.stdout, end="", flush=True)
        if result.stderr:
            print(result.stderr, file=sys.stderr)
    final_identities = {str(p.relative_to(root)): sha256(p) for p in [*production, template]}
    report["source_unchanged_during_run"] = identities == final_identities
    if identities != final_identities:
        fail_count += 1
        report["source_after_sha256"] = final_identities
    report["status"] = "PASS" if fail_count == 0 else "FAIL"
    report["unique_scenarios"] = len(cases)
    report["failed_or_blocked_scenarios"] = fail_count
    (out / "RESULTS.json").write_text(json.dumps(report, indent=2)+"\n")
    print("EVIDENCE "+str(out/"RESULTS.json"), flush=True)
    return 0 if fail_count == 0 else 1


if __name__ == "__main__":
    raise SystemExit(main())
