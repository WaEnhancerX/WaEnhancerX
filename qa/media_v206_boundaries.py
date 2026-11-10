"""Explicit standalone boundaries for complete public registry/media compilation.

HookingEngine, CustomAbPropsRuntime and unrelated private feature bodies are not
available public implementations. These test boundaries select STARTUP for the
public target factories only; they are not shipping code or licensing substitutes.
Native Xposed/DexKit behavior is outside this JVM fixture.
"""

def update_stubs(stubs, prefix):
    s=dict(stubs)
    s['android/app/Activity.java']='package android.app; public class Activity extends android.content.Context { public void invalidateOptionsMenu(){} }'
    s['android/content/Context.java']='package android.content; public class Context { public Context getApplicationContext(){return this;} public String getPackageName(){return "com.whatsapp";} public ContentResolver getContentResolver(){return new ContentResolver();} }'
    s['android/content/SharedPreferences.java']='''package android.content;
public interface SharedPreferences {boolean getBoolean(String key,boolean fallback);
 default String getString(String key,String fallback){return key.equals("hooking_mode")?"Maximum":fallback;}
 default boolean contains(String key){return false;}
 default java.util.Set<String> getStringSet(String key,java.util.Set<String> fallback){return fallback;}
}'''
    s['org/json/JSONObject.java']='''package org.json;
public class JSONObject {public JSONObject(){} public JSONObject(String source){if(!"{}".equals(source))throw new AssertionError("Custom modes outside fixture");}
 public java.util.Iterator<String> keys(){return java.util.Collections.emptyIterator();}
 public String optString(String key,String fallback){return fallback;}
 public JSONObject put(String key,Object value){return this;}}
'''
    s['com/waenhancer/config/HookingModePolicy.java']='''package com.waenhancer.config;
public final class HookingModePolicy {public static final String KEY="hooking_mode",BALANCED="Balanced",LIGHTWEIGHT="Lightweight",CUSTOMIZED="Customized",CUSTOMIZATIONS_KEY="hook_customizations";
 public static String normalize(String value,boolean entitled){if(!"Maximum".equals(value))throw new AssertionError("Only explicit Maximum fixture supported");return value;}}
'''
    s['com/waenhancer/hooking/HookingEngine.java']='''package com.waenhancer.hooking;
public final class HookingEngine {
 public enum Installation {STARTUP,LAZY_HOME,LAZY_CONVERSATION,LAZY_MEDIA,LAZY_STATUS}
 public interface PreHook {void apply(android.content.SharedPreferences prefs);}
 public static final class FeaturePolicy {public PreHook preHook;}
 public static FeaturePolicy policyFor(String name){return new FeaturePolicy();}
 public static Installation effectiveInstallation(FeaturePolicy policy,String mode){if(!"Maximum".equals(mode))throw new AssertionError("Private mode policy outside fixture");return Installation.STARTUP;}
 public static LazyHookCoordinator.Screen screenFor(Installation mode){throw new AssertionError("Deferred scheduling outside fixture");}
}
'''
    s['com/waenhancer/hooking/LazyHookCoordinator.java']='''package com.waenhancer.hooking;
import android.app.Activity;
public final class LazyHookCoordinator {public enum Screen {HOME,CONVERSATION,MEDIA,STATUS}
 public void register(Screen s,Runnable action){throw new AssertionError("Deferred scheduling outside fixture");}
 public void registerDisposer(Screen s,java.util.function.Consumer<Activity> action){throw new AssertionError("Deferred scheduling outside fixture");}
 public void onActivityCreated(Activity a){throw new AssertionError("Activity dispatch outside fixture");}
 public void onActivityResumed(Activity a){throw new AssertionError("Activity dispatch outside fixture");}
 public void onActivityDestroyed(Activity a){throw new AssertionError("Activity dispatch outside fixture");}}
'''
    s['com/waenhancer/hooking/ScreenScopedFeature.java']='package com.waenhancer.hooking; public interface ScreenScopedFeature {void onScreenDisposed(android.app.Activity a);}'
    s['com/waenhancer/licensing/customprops/CustomAbPropsRuntime.java']='''package com.waenhancer.licensing.customprops;
public final class CustomAbPropsRuntime {public static int calls;
 public static void loadInto(android.content.SharedPreferences prefs,java.util.Map<Integer,Boolean> b,java.util.Map<Integer,Integer> i,java.util.Map<Integer,Float> f,java.util.Map<Integer,String> s){calls++;}
}
'''
    s[prefix+'utils/ActivityTracker.java']='''package com.waenhancer.xposed.utils;
public final class ActivityTracker {
 public interface ActivityListener {default void onActivityCreated(android.app.Activity a){} default void onActivityResumed(android.app.Activity a){} default void onActivityStopped(android.app.Activity a){} default void onActivityDestroyed(android.app.Activity a){}}
 public static void addListener(ActivityListener listener){} public static android.app.Activity getCurrentActivity(){return null;}}
'''
    base=s[prefix+'core/BaseFeature.java']
    base=base.replace(' public abstract void hook() throws Throwable;', ' public void beginHookTracking(){} public void endHookTracking(){} public void unhookAll(){}\n public abstract void hook() throws Throwable;')
    s[prefix+'core/BaseFeature.java']=base
    dex=s[prefix+'core/devkit/DexSearchEngine.java']
    dex=dex.replace(' public DexKitBridge getBridge(){return bridge;}', ' public DexKitBridge getBridge(){return bridge;}\n public void acquireLease(){} public void releaseLease(){} public void close(){}')
    s[prefix+'core/devkit/DexSearchEngine.java']=dex
    props=s[prefix+'features/customization/PropsOverrideHook.java']
    props=props.replace('public final class PropsOverrideHook {','''public final class PropsOverrideHook extends com.waenhancer.xposed.core.BaseFeature {
 public PropsOverrideHook(android.content.Context c,ClassLoader l,android.content.SharedPreferences p){super(c,l,p);}
 public void hook(){throw new AssertionError("Property transport outside this fixture");}
 public String getName(){return "Props Override";}
 public static boolean isHookInstalled(){return false;}
''')
    s[prefix+'features/customization/PropsOverrideHook.java']=props
    dexmeta=s['org/luckypray/dexkit/DexKitBridge.java']
    dexmeta=dexmeta.replace('   try{Class<?> i=Class.forName(q.matcher.iface);if(i.isAssignableFrom(data.type))out.add(data);}', '''   try{
    if(q.matcher.iface!=null){Class<?> i=Class.forName(q.matcher.iface);if(i.isAssignableFrom(data.type))out.add(data);}
    else if(q.matcher.anchor!=null){
     boolean found=false;for(MethodData method:methods){if(method.method.getDeclaringClass()!=data.type)continue;
      for(String text:method.anchors){found|=q.matcher.match==StringMatchType.StartsWith?text.startsWith(q.matcher.anchor):text.equals(q.matcher.anchor);}}
     if(found)out.add(data);
    }
   }''')
    s['org/luckypray/dexkit/DexKitBridge.java']=dexmeta
    s['android/os/BaseBundle.java']='''package android.os;
public class BaseBundle {public final java.util.Map<String,Boolean> bools=new java.util.HashMap<>();
 public boolean getBoolean(String key,boolean fallback){return bools.getOrDefault(key,fallback);}}
'''
    s['android/os/Bundle.java']='package android.os; public class Bundle extends BaseBundle {public void putString(String k,String v){} public void putLong(String k,long v){}}'
    s['android/content/Intent.java']='''package android.content;
public class Intent {public final java.util.Map<String,Boolean> bools=new java.util.HashMap<>();
 public boolean hasExtra(String key){return bools.containsKey(key);}
 public boolean getBooleanExtra(String key,boolean fallback){return bools.getOrDefault(key,fallback);}}
'''
    s['android/app/Activity.java']='package android.app; public class Activity extends android.content.Context {public android.content.Intent intent; public android.content.Intent getIntent(){return intent;} public void invalidateOptionsMenu(){}}'
    s['androidx/fragment/app/Fragment.java']='package androidx.fragment.app; public class Fragment {public android.os.Bundle arguments; public android.app.Activity activity; public android.app.Activity getActivity(){return activity;}}'
    s['android/content/pm/ApplicationInfo.java']='package android.content.pm; public class ApplicationInfo {public String sourceDir;}'
    s['android/content/Context.java']=s['android/content/Context.java'].replace('public Context getApplicationContext()', 'public android.content.pm.ApplicationInfo getApplicationInfo(){throw new AssertionError("APK identity checked by separate native test");} public Context getApplicationContext()')
    s['com/waenhancer/xposed/features/media/MediaHostFingerprint.java']='''package com.waenhancer.xposed.features.media;
public final class MediaHostFingerprint {public static boolean accepted=true;public static boolean accepts(android.content.Context context){return accepted;}}
'''
    return s
