#!/usr/bin/env python3
"""Execute complete production media/view-once hooks against explicit JVM boundary doubles.

The DexKit double filters independently declared anchors and reflection signatures;
it does not parse DEX. The Xposed double calls the real fixture host method between
before/after callbacks. The complete FeatureRegistry executes the two real hook
factories; unrelated hooks and private installation entry points are explicit doubles.
This is not WhatsApp, Android, codec, or LSPosed validation.
Use --source-root with an unchanged checkout to compare the same assertions.
"""
from pathlib import Path
import argparse
import re
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
P = "com/waenhancer/xposed/"

STUBS = {
    "android/content/Context.java": "package android.content; public class Context {public ContentResolver getContentResolver(){return new ContentResolver();}}",
    "android/content/ContentResolver.java": """package android.content;
public class ContentResolver {public android.os.Bundle call(android.net.Uri u,String m,String a,android.os.Bundle b){throw new UnsupportedOperationException("Fixture does not deliver runtime reports");}}
""",
    "android/net/Uri.java": "package android.net; public class Uri {public static Uri parse(String s){return new Uri();}}",
    "android/os/Bundle.java": "package android.os; public class Bundle {public void putString(String k,String v){}public void putLong(String k,long v){}}",
    "org/json/JSONObject.java": "package org.json; public class JSONObject {public JSONObject put(String k,Object v){return this;}}",
    "org/json/JSONArray.java": "package org.json; public class JSONArray {public JSONArray put(Object v){return this;}}",
    "android/content/SharedPreferences.java": """package android.content;
public interface SharedPreferences { boolean getBoolean(String key, boolean fallback); }
""",
    "androidx/annotation/NonNull.java": "package androidx.annotation; public @interface NonNull {}",
    P + "core/BaseFeature.java": """package com.waenhancer.xposed.core;
import android.content.*;
public abstract class BaseFeature {
 protected final Context context; protected final ClassLoader classLoader; protected final SharedPreferences prefs;
 protected BaseFeature(Context c, ClassLoader l, SharedPreferences p) { context=c;classLoader=l;prefs=p; }
 protected boolean isEnabled(String k, boolean d) { return prefs.getBoolean(k,d); }
 public abstract void hook() throws Throwable;
 public abstract String getName();
}
""",
    P + "core/devkit/DexSearchEngine.java": """package com.waenhancer.xposed.core.devkit;
import android.content.Context; import java.lang.reflect.Method; import org.luckypray.dexkit.DexKitBridge;
public final class DexSearchEngine {
 private static final DexSearchEngine INSTANCE=new DexSearchEngine();
 public static DexKitBridge bridge; public static int initializes; public static RuntimeException failure;
 public static DexSearchEngine getInstance(){return INSTANCE;}
 public void initialize(Context c){initializes++;if(failure!=null)throw failure;}
 public DexKitBridge getBridge(){return bridge;}
 public Method findMethodWithCache(Context c, ClassLoader l, String k, Query q) {
  if(failure!=null)throw failure;
  try { return bridge == null ? null : q.run(bridge,l); }
  catch (RuntimeException | Error e) { throw e; }
  catch (Throwable e) { throw new IllegalStateException(e); }
 }
 public interface Query { Method run(DexKitBridge b, ClassLoader l) throws Throwable; }
}
""",
    "de/robv/android/xposed/XC_MethodHook.java": """package de.robv.android.xposed;
import java.lang.reflect.Method;
public abstract class XC_MethodHook {
 protected void beforeHookedMethod(MethodHookParam p) throws Throwable {}
 protected void afterHookedMethod(MethodHookParam p) throws Throwable {}
 public final void before(MethodHookParam p) throws Throwable {beforeHookedMethod(p);}
 public final void after(MethodHookParam p) throws Throwable {afterHookedMethod(p);}
 public static final class MethodHookParam {
  public Object thisObject; public Object[] args; public Method method;
  private Object result; private Throwable throwable;
  public Object getResult(){return result;}
  public void setResult(Object v){result=v;throwable=null;}
  public Throwable getThrowable(){return throwable;}
  public boolean hasThrowable(){return throwable!=null;}
  public void setThrowable(Throwable v){throwable=v;result=null;}
 }
}
""",
    "de/robv/android/xposed/XposedBridge.java": """package de.robv.android.xposed;
import java.lang.reflect.*; import java.util.*;
public final class XposedBridge {
 public static final Map<Method,List<XC_MethodHook>> hooks=new LinkedHashMap<>();
 public static final List<String> logs=new ArrayList<>();
 public static final Set<Method> rejected=new HashSet<>();
 public static void log(String s){logs.add(s);}
 public static void log(Throwable t){logs.add(t.toString());}
 public static void hookMethod(Method m,XC_MethodHook h){
  if(rejected.contains(m))throw new IllegalStateException("fixture-private-registration-value");
  hooks.computeIfAbsent(m,k->new ArrayList<>()).add(h);
 }
 public static XC_MethodHook.MethodHookParam invoke(Method m,Object receiver,Object... args) throws Throwable {
  XC_MethodHook.MethodHookParam p=new XC_MethodHook.MethodHookParam();p.method=m;p.thisObject=receiver;p.args=args;
  List<XC_MethodHook> list=hooks.getOrDefault(m,Collections.emptyList());
  for(XC_MethodHook h:list)h.before(p);
  try {m.setAccessible(true);p.setResult(m.invoke(receiver,p.args));}
  catch(InvocationTargetException e){p.setThrowable(e.getCause());}
  for(int i=list.size()-1;i>=0;i--)list.get(i).after(p);
  return p;
 }
 public static int registrations(){return hooks.values().stream().mapToInt(List::size).sum();}
}
""",
    "org/luckypray/dexkit/query/enums/StringMatchType.java": """package org.luckypray.dexkit.query.enums;
public enum StringMatchType {Contains,Equals,StartsWith,EndsWith}
""",
    "org/luckypray/dexkit/query/matchers/MethodMatcher.java": """package org.luckypray.dexkit.query.matchers;
import java.util.*; import org.luckypray.dexkit.query.enums.StringMatchType;
public final class MethodMatcher {
 public final Map<String,StringMatchType> strings=new LinkedHashMap<>();
 public String returns; public String[] params; public Integer count,flags;
 public static MethodMatcher create(){return new MethodMatcher();}
 public MethodMatcher addUsingString(String s,StringMatchType t){strings.put(s,t);return this;}
 public MethodMatcher usingStrings(String... ss){for(String s:ss)strings.put(s,StringMatchType.Contains);return this;}
 public MethodMatcher paramTypes(Class<?>... a){params=Arrays.stream(a).map(Class::getName).toArray(String[]::new);return this;}
 public MethodMatcher paramTypes(String... a){params=a;return this;}
 public MethodMatcher paramCount(int n){count=n;return this;}
 public MethodMatcher returnType(Class<?> c){returns=c.getName();return this;}
 public MethodMatcher returnType(String s){returns=s;return this;}
 public MethodMatcher modifiers(int f){flags=f;return this;}
}
""",
    "org/luckypray/dexkit/query/matchers/ClassMatcher.java": """package org.luckypray.dexkit.query.matchers;
public final class ClassMatcher {
 public String iface;
 public static ClassMatcher create(){return new ClassMatcher();}
 public ClassMatcher addInterface(String s){iface=s;return this;}
}
""",
    "org/luckypray/dexkit/query/FindMethod.java": """package org.luckypray.dexkit.query;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
public final class FindMethod {
 public MethodMatcher matcher;
 public static FindMethod create(){return new FindMethod();}
 public FindMethod matcher(MethodMatcher m){matcher=m;return this;}
}
""",
    "org/luckypray/dexkit/query/FindClass.java": """package org.luckypray.dexkit.query;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
public final class FindClass {
 public ClassMatcher matcher;
 public static FindClass create(){return new FindClass();}
 public FindClass matcher(ClassMatcher m){matcher=m;return this;}
}
""",
    "org/luckypray/dexkit/result/MethodDataList.java": """package org.luckypray.dexkit.result;
public class MethodDataList extends java.util.ArrayList<MethodData> {
 public MethodData firstOrNull(){return isEmpty()?null:get(0);}
}
""",
    "org/luckypray/dexkit/result/ClassDataList.java": """package org.luckypray.dexkit.result;
public class ClassDataList extends java.util.ArrayList<ClassData> {}
""",
    "org/luckypray/dexkit/result/FieldData.java": """package org.luckypray.dexkit.result;
import java.lang.reflect.Field;
public class FieldData { private final Field f; public FieldData(Field f){this.f=f;}
 public Field getFieldInstance(ClassLoader l) throws ReflectiveOperationException {return f;}}
""",
    "org/luckypray/dexkit/result/UsingFieldData.java": """package org.luckypray.dexkit.result;
import java.lang.reflect.Field;
public class UsingFieldData { private final FieldData f; public UsingFieldData(Field f){this.f=new FieldData(f);}
 public FieldData getField(){return f;}}
""",
    "org/luckypray/dexkit/result/MethodData.java": """package org.luckypray.dexkit.result;
import java.lang.reflect.*; import java.util.*;
public class MethodData {
 public final Method method; public final List<String> anchors= new ArrayList<>();
 public final MethodDataList invokes=new MethodDataList();
 public final List<UsingFieldData> fields=new ArrayList<>();
 public MethodData(Method m,String... s){method=m;anchors.addAll(Arrays.asList(s));}
 public Method getMethodInstance(ClassLoader l) throws ReflectiveOperationException {return method;}
 public boolean isMethod(){return true;}
 public MethodDataList getInvokes(){return invokes;}
 public List<UsingFieldData> getUsingFields(){return fields;}
}
""",
    "org/luckypray/dexkit/result/ClassData.java": """package org.luckypray.dexkit.result;
public class ClassData { public final Class<?> type; public ClassData(Class<?> c){type=c;}
 public Class<?> getInstance(ClassLoader l) throws ReflectiveOperationException {return type;}}
""",
    "org/luckypray/dexkit/DexKitBridge.java": """package org.luckypray.dexkit;
import java.lang.reflect.*; import java.util.*;
import org.luckypray.dexkit.query.*; import org.luckypray.dexkit.query.matchers.*;
import org.luckypray.dexkit.query.enums.*; import org.luckypray.dexkit.result.*;
public final class DexKitBridge {
 public final MethodDataList methods=new MethodDataList(); public final ClassDataList classes=new ClassDataList();
 public int queries;
 public MethodDataList findMethod(FindMethod q){
  queries++; MethodDataList result=new MethodDataList();MethodMatcher m=q.matcher;
  for(MethodData data:methods){
   Method method=data.method;String[] params=Arrays.stream(method.getParameterTypes()).map(Class::getName).toArray(String[]::new);
   if(m.returns!=null&&!m.returns.equals(method.getReturnType().getName()))continue;
   if(m.params!=null&&!Arrays.equals(m.params,params))continue;
   if(m.count!=null&&m.count!=params.length)continue;
   if(m.flags!=null&&(method.getModifiers()&m.flags)!=m.flags)continue;
   boolean matched=true;
   for(Map.Entry<String,StringMatchType> a:m.strings.entrySet()){
    boolean any=false;for(String value:data.anchors){
     switch(a.getValue()){
      case Equals:any|=value.equals(a.getKey());break;
      case Contains:any|=value.contains(a.getKey());break;
      case StartsWith:any|=value.startsWith(a.getKey());break;
      case EndsWith:any|=value.endsWith(a.getKey());break;
     }
    }if(!any){matched=false;break;}
   }
   if(matched)result.add(data);
  }return result;
 }
 public ClassDataList findClass(FindClass q){
  queries++;ClassDataList out=new ClassDataList();
  for(ClassData data:classes){
   try{Class<?> i=Class.forName(q.matcher.iface);if(i.isAssignableFrom(data.type))out.add(data);}
   catch(ClassNotFoundException e){throw new IllegalStateException(e);}
  }return out;
 }
}
""",
    "com/whatsapp/infra/core/jid/Jid.java": """package com.whatsapp.infra.core.jid;
public class Jid {public String getRawString(){return "fixture@s.whatsapp.net";}}
""",
    "com/whatsapp/infra/core/jid/UserJid.java": """package com.whatsapp.infra.core.jid;
public class UserJid extends Jid {}
""",
}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-root", type=Path, default=ROOT)
    parser.add_argument("--suite", choices=("all", "media", "viewonce", "status"), default="all")
    parser.add_argument("--jid-family", choices=("modern", "legacy"), default="modern",
                        help="Select the independently generated fixture JID namespace; production is unchanged")
    args = parser.parse_args()
    source = args.source_root / "app/src/main/java"
    production = [source / (P + "core/devkit/HookReturnTypeGuard.java"),
                  source / (P + "features/privacy/AntiViewOnceHook.java"),
                  source / (P + "features/media/MediaQualityBypassHook.java")]
    contract = source / (P + "features/privacy/ViewOnceMessageContract.java")
    if contract.is_file():
        production.append(contract)
    registry = source / (P + "core/FeatureRegistry.java")
    if not registry.is_file():
        # Focused historical/mutant snapshots may contain only the media production group.
        registry = ROOT / "app/src/main/java" / (P + "core/FeatureRegistry.java")
    production.append(registry)
    stubs = dict(STUBS)
    stubs[P + "core/components/NativeWhatsAppDialog.java"] = """package com.waenhancer.xposed.core.components;
public class NativeWhatsAppDialog {public static class Companion {public static void initialize(android.content.Context c,ClassLoader l){}}}
"""
    # Compile the complete registry. Only its two media factories are exercised; unrelated
    # hooks and private installation entry points are explicit dependency substitutes.
    for qualified in re.findall(r"^import (com\.waenhancer\.(?:xposed|licensing)\.features\.[\w.]+);", registry.read_text(), re.M):
        package, name = qualified.rsplit(".", 1)
        if name in {"AntiViewOnceHook", "MediaQualityBypassHook"}:
            continue
        if ".licensing." in qualified:
            install = ("public static boolean install(ClassLoader l,android.content.SharedPreferences p){return false;}"
                       if name == "NewChatFeature" else
                       "public static void install(android.content.Context c,ClassLoader l,android.content.SharedPreferences p){}")
            content = f"package {package}; public class {name} {{{install}}}"
        else:
            content = f"""package {package};
public class {name} extends com.waenhancer.xposed.core.BaseFeature {{
 public {name}(android.content.Context c,ClassLoader l,android.content.SharedPreferences p){{super(c,l,p);}}
 public void hook(){{throw new UnsupportedOperationException("Unrelated hook is outside the media fixture");}}
 public String getName(){{return "{name}";}}
}}
"""
        stubs[qualified.replace(".", "/") + ".java"] = content
    with tempfile.TemporaryDirectory(prefix="waex_media_hooks_") as directory:
        dest = Path(directory)
        paths = []
        for relative, content in stubs.items():
            if args.jid_family == "legacy" and relative.startswith("com/whatsapp/infra/core/jid/"):
                relative = relative.replace("com/whatsapp/infra/core/jid/", "com/whatsapp/jid/")
                content = content.replace("com.whatsapp.infra.core.jid", "com.whatsapp.jid")
            target = dest / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(content)
            paths.append(target)
        original_harness = ROOT / "qa/fixtures/media_hooks/MediaHookContractTest.java"
        harness = dest / "MediaHookContractTest.java"
        fixture_text = original_harness.read_text()
        if args.jid_family == "legacy":
            fixture_text = fixture_text.replace("com.whatsapp.infra.core.jid", "com.whatsapp.jid")
        harness.write_text(fixture_text)
        subprocess.run(["javac", "--release", "17", "-d", str(dest),
                        *map(str, paths), *map(str, production), str(harness)], check=True, timeout=60)
        print("FIXTURE_JID_FAMILY=" + args.jid_family, flush=True)
        subprocess.run(["java", "-cp", str(dest), "MediaHookContractTest", args.suite], check=True, timeout=60)


if __name__ == "__main__":
    main()
