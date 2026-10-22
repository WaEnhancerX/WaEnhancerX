#!/usr/bin/env python3
"""Compile the complete recorder hook; execute its installed callbacks with explicit JVM doubles.

The scheduler, host, Android media/IPC and Xposed dispatcher are controlled doubles.
The DexKit declarations are checked by a separate optional real-API compile below.
No phone, microphone, native DexKit or WhatsApp call is executed.
"""
from pathlib import Path
import argparse, hashlib, json, os, subprocess, tempfile

ROOT = Path(__file__).resolve().parents[1]
STUBS = {
    'androidx/annotation/NonNull.java': 'package androidx.annotation; public @interface NonNull {}',
    'android/net/Uri.java': 'package android.net; public class Uri {public static Uri parse(String value){return new Uri();}}',
    'android/os/Build.java': '''package android.os; public final class Build {
      public static class VERSION {public static int SDK_INT=34;}
      public static class VERSION_CODES {public static final int Q=29,S=31;}
    }''',
    'android/os/Bundle.java': '''package android.os; import java.util.*; public class Bundle {
      public final Map<String,Object> values=new HashMap<>();
      public void putString(String k,String v){values.put(k,v);} public String getString(String k){return (String)values.get(k);}
      @SuppressWarnings("unchecked") public <T>T getParcelable(String k){return (T)values.get(k);}
      public <T>T getParcelable(String k,Class<T> type){return type.cast(values.get(k));}
    }''',
    'android/os/ParcelFileDescriptor.java': '''package android.os; import java.io.*;
    public class ParcelFileDescriptor {public int closes; public final FileDescriptor descriptor=new FileDescriptor();
      public FileDescriptor getFileDescriptor(){return descriptor;} public void close(){closes++;}
    }''',
    'android/content/SharedPreferences.java': '''package android.content; public interface SharedPreferences {
      boolean getBoolean(String k,boolean fallback); String getString(String k,String fallback);
      interface OnSharedPreferenceChangeListener {void onSharedPreferenceChanged(SharedPreferences p,String key);}
      void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener);
      void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener);
    }''',
    'android/content/Context.java': '''package android.content; import android.os.*; import android.net.Uri; import java.util.*;
    public class Context {public final ContentResolver resolver=new ContentResolver();
      public ContentResolver getContentResolver(){return resolver;} public Context getApplicationContext(){return this;}
      public static class ContentResolver {public final List<ParcelFileDescriptor> files=new ArrayList<>(); public int deletes,creates; public boolean fail;
        public Bundle call(Uri uri,String method,String argument,Bundle request){
          if(method.equals("create_call_recording")){creates++;if(fail)return null;
            Bundle result=new Bundle(); ParcelFileDescriptor fd=new ParcelFileDescriptor();files.add(fd);
            result.values.put("descriptor",fd);result.putString("path","private/"+request.getString("name"));return result;}
          if(method.equals("delete_call_recording")){deletes++;return new Bundle();}
          throw new AssertionError("Unexpected IPC operation: "+method);
        }
      }
    }''',
    'android/media/MediaRecorder.java': '''package android.media; import android.content.Context; import java.io.*; import java.util.*; import java.util.concurrent.*;
    public class MediaRecorder {
      public static final List<MediaRecorder> instances=new CopyOnWriteArrayList<>();
      public static final Set<Integer> rejectedSources=ConcurrentHashMap.newKeySet();
      public static volatile CountDownLatch prepareEntered,prepareRelease;public static boolean stopFailure,releaseFailure;
      public int source,starts,stops,releases,prepares; public FileDescriptor output;
      public MediaRecorder(){instances.add(this);} public MediaRecorder(Context c){this();}
      public void setAudioSource(int v){source=v;if(rejectedSources.contains(v))throw new IllegalStateException("private audio detail");}
      public void setOutputFormat(int v){} public void setAudioEncoder(int v){} public void setAudioSamplingRate(int v){}
      public void setAudioEncodingBitRate(int v){} public void setOutputFile(FileDescriptor f){output=f;}
      public void prepare() throws Exception {prepares++;CountDownLatch entered=prepareEntered,release=prepareRelease;
        if(entered!=null){entered.countDown();if(!release.await(5,TimeUnit.SECONDS))throw new AssertionError("prepare barrier timeout");}}
      public void start(){starts++;} public void stop(){stops++;if(stopFailure)throw new IllegalStateException("private stop detail");}
      public void reset(){} public void release(){releases++;if(releaseFailure)throw new IllegalStateException("private release detail");}
      public static void resetFixtures(){instances.clear();rejectedSources.clear();prepareEntered=null;prepareRelease=null;stopFailure=false;releaseFailure=false;}
      public static final class AudioSource {public static final int MIC=1,VOICE_CALL=4,VOICE_COMMUNICATION=7;}
      public static final class OutputFormat {public static final int MPEG_4=2,OGG=11;}
      public static final class AudioEncoder {public static final int AAC=3,OPUS=7;}
    }''',
    'com/waenhancer/xposed/core/BaseFeature.java': '''package com.waenhancer.xposed.core; import android.content.*;
    public abstract class BaseFeature {protected final Context context;protected final ClassLoader classLoader;protected final SharedPreferences prefs;
      protected BaseFeature(Context c,ClassLoader l,SharedPreferences p){context=c;classLoader=l;prefs=p;}
      protected boolean isEnabled(String k,boolean fallback){return prefs.getBoolean(k,fallback);}
      public abstract void hook() throws Throwable;public abstract String getName();}
    ''',
    'com/waenhancer/xposed/bridge/HookProvider.java': 'package com.waenhancer.xposed.bridge; public class HookProvider {public static final String AUTHORITY="com.waenhancer.hook";}',
    'com/waenhancer/xposed/core/devkit/DexSearchEngine.java': '''package com.waenhancer.xposed.core.devkit; import android.content.Context; import org.luckypray.dexkit.DexKitBridge;
    public final class DexSearchEngine {public interface Resolver {Class<?> find(DexKitBridge b,ClassLoader l) throws ReflectiveOperationException;}
      public static DexSearchEngine getInstance(){return new DexSearchEngine();}
      public Class<?> findClassWithCache(Context c,ClassLoader l,String key,Resolver r){return null;}}
    ''',
    'org/luckypray/dexkit/DexKitBridge.java': '''package org.luckypray.dexkit; import org.luckypray.dexkit.query.FindClass; import org.luckypray.dexkit.result.ClassData;
    public class DexKitBridge {public Results findClass(FindClass query){throw new AssertionError("Native search must not run in this fixture");}
      public static class Results {public ClassData firstOrNull(){return null;}}}''',
    'org/luckypray/dexkit/query/FindClass.java': '''package org.luckypray.dexkit.query; import org.luckypray.dexkit.query.matchers.ClassMatcher;
    public class FindClass {public static FindClass create(){return new FindClass();}public FindClass matcher(ClassMatcher m){return this;}}''',
    'org/luckypray/dexkit/query/enums/StringMatchType.java': 'package org.luckypray.dexkit.query.enums; public enum StringMatchType {EndsWith}',
    'org/luckypray/dexkit/query/matchers/ClassMatcher.java': '''package org.luckypray.dexkit.query.matchers; import org.luckypray.dexkit.query.enums.StringMatchType;
    public class ClassMatcher {public static ClassMatcher create(){return new ClassMatcher();}public ClassMatcher className(String n,StringMatchType t){return this;}}''',
    'org/luckypray/dexkit/result/ClassData.java': 'package org.luckypray.dexkit.result; public class ClassData {public Class<?> getInstance(ClassLoader l){return null;}}',
    'de/robv/android/xposed/XC_MethodHook.java': '''package de.robv.android.xposed; import java.lang.reflect.Member; import java.util.*;
    public abstract class XC_MethodHook {
      protected void beforeHookedMethod(MethodHookParam p) throws Throwable{} protected void afterHookedMethod(MethodHookParam p) throws Throwable{}
      public static class MethodHookParam {public Object thisObject;public Object[] args;public Member method;public Throwable error;public Object result;
        public final Map<String,Object> extras=new HashMap<>(); public boolean hasThrowable(){return error!=null;}
        public void setObjectExtra(String key,Object value){extras.put(key,value);}public Object getObjectExtra(String key){return extras.get(key);}
        public Object getResult(){return result;}public void setResult(Object v){result=v;}
      }
      public class Unhook {private final Runnable remove;public Unhook(Runnable r){remove=r;}public void unhook(){remove.run();}}
    }''',
    'de/robv/android/xposed/XposedBridge.java': '''package de.robv.android.xposed; import java.lang.reflect.*; import java.util.*;
    public final class XposedBridge {
      public static final Map<Method,List<XC_MethodHook>> hooks=new LinkedHashMap<>();public static final List<String> logs=new ArrayList<>();
      public static int registrations,failRegistration; public static boolean failUnhook; public static Runnable afterRegistration;
      public static void reset(){hooks.clear();logs.clear();registrations=0;failRegistration=0;failUnhook=false;afterRegistration=null;}
      public static void log(String s){logs.add(s);}
      public static XC_MethodHook.Unhook hookMethod(Member member,XC_MethodHook hook){Method m=(Method)member;m.setAccessible(true);
        if(++registrations==failRegistration)throw new IllegalStateException("private hook failure detail");
        hooks.computeIfAbsent(m,k->new ArrayList<>()).add(hook);if(afterRegistration!=null)afterRegistration.run();
        return hook.new Unhook(()->{if(failUnhook)throw new IllegalStateException("private unhook detail");hooks.get(m).remove(hook);});}
      public static Set<XC_MethodHook.Unhook> hookAllMethods(Class<?> type,String name,XC_MethodHook hook){Set<XC_MethodHook.Unhook> out=new HashSet<>();
        for(Method m:type.getDeclaredMethods())if(m.getName().equals(name))out.add(hookMethod(m,hook));return out;}
      public static void fire(Object owner,String name,Class<?>[] types,Object...args) throws Throwable {
        Method m=owner.getClass().getDeclaredMethod(name,types);m.setAccessible(true);XC_MethodHook.MethodHookParam p=new XC_MethodHook.MethodHookParam();
        p.thisObject=owner;p.args=args;p.method=m;Object[] original=args.clone();List<XC_MethodHook> list=List.copyOf(hooks.getOrDefault(m,List.of()));
        for(XC_MethodHook hook:list)hook.beforeHookedMethod(p);
        try{p.result=m.invoke(owner,p.args);}catch(InvocationTargetException e){p.error=e.getCause();}
        for(int i=list.size()-1;i>=0;i--)list.get(i).afterHookedMethod(p);
        if(!Arrays.equals(original,p.args)||p.result!=null)throw new AssertionError("Recorder altered host arguments/result");
        if(p.error!=null)throw p.error;
      }
    }''',
    'de/robv/android/xposed/XposedHelpers.java': '''package de.robv.android.xposed; public class XposedHelpers {
      public static Class<?> findClassIfExists(String name,ClassLoader loader){try{return Class.forName(name,false,loader);}catch(ClassNotFoundException e){return null;}}
    }''',
    'com/whatsapp/calling/infra/voipcalling/CallState.java': '''package com.whatsapp.calling.infra.voipcalling;
    public enum CallState {NONE,CALLING,ACTIVE,ENDING,CONNECTED_LONELY,RECEIVED_CALL,REJOINING,ACTIVE_ELSEWHERE}''',
    'com/whatsapp/calling/infra/voipcalling/CallInfo.java': '''package com.whatsapp.calling.infra.voipcalling;
    public final class CallInfo {public final String callId;public final CallState callState;
      public CallInfo(String id,CallState state){callId=id;callState=state;}}
    ''',
    'com/whatsapp/calling/infra/voipcalling/CallLogInfo.java': 'package com.whatsapp.calling.infra.voipcalling; public class CallLogInfo {}',
    'com/whatsapp/fieldstats/events/WamCall.java': 'package com.whatsapp.fieldstats.events; public class WamCall {}',
    'com/whatsapp/infra/core/jid/UserJid.java': 'package com.whatsapp.infra.core.jid; public class UserJid {}',
    'com/whatsapp/calling/service/VoiceServiceEventCallback.java': '''package com.whatsapp.calling.service;
    import com.whatsapp.calling.infra.voipcalling.*;import com.whatsapp.fieldstats.events.WamCall;import com.whatsapp.infra.core.jid.UserJid;
    public class VoiceServiceEventCallback {
      public CallInfo current;public int originalCalls;public boolean failNext,clearAtEnd=true;
      private CallInfo getCallInfo(){return current;}
      private void original(){originalCalls++;if(failNext){failNext=false;throw new IllegalStateException("host original failure");}}
      public void soundPortCreated(int engine){original();}
      public void callStateChanged(CallState oldState,CallInfo info){original();}
      public void callEnding(CallLogInfo info,int interval,String timeSeries,String log){original();if(clearAtEnd)current=null;}
      public void fieldstatsReady(WamCall info,UserJid user,boolean lastReport,boolean realtime){original();}
    }''',
    'com/whatsapp/voipcalling/VoipActivityV2.java': 'package com.whatsapp.voipcalling; public class VoipActivityV2 {public void onDestroy(){}}',
    'com/whatsapp/voipcalling/VoipActivity.java': 'package com.whatsapp.voipcalling; public class VoipActivity {public void onDestroy(){}}',
}


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--root',type=Path,default=ROOT)
    p.add_argument('--out',type=Path)
    p.add_argument('--scenario',default='all')
    p.add_argument('--dexkit-jar',type=Path)
    p.add_argument('--kotlin-stdlib',type=Path)
    p.add_argument('--host-variant',choices=['valid','typed-ending','mutable-info','sound-return','sound-static','info-return','missing-end'],default='valid')
    args=p.parse_args()
    if args.dexkit_jar and not args.kotlin_stdlib:
        p.error('--dexkit-jar requires --kotlin-stdlib for the real Kotlin API signatures')
    directory=args.out or Path(tempfile.mkdtemp(prefix='waex-call-recording-'))
    directory.mkdir(parents=True,exist_ok=True)
    sources=[]
    for path,content in STUBS.items():
        if args.dexkit_jar and path.startswith('org/luckypray/dexkit/'):continue
        if path.endswith('/CallInfo.java') and args.host_variant=='mutable-info':
            content=content.replace('public final String callId','public String callId')
        if path.endswith('/VoiceServiceEventCallback.java'):
            replacements={
                'typed-ending':('public void callEnding(CallLogInfo info,int interval,String timeSeries,String log)',
                                'public void callEnding(CallLogInfo info,long interval,boolean finalEvent)'),
                'sound-return':('public void soundPortCreated(int engine){original();}', 'public boolean soundPortCreated(int engine){original();return true;}'),
                'sound-static':('public void soundPortCreated(int engine){original();}', 'public static void soundPortCreated(int engine){}'),
                'info-return':('private CallInfo getCallInfo()', 'private Object getCallInfo()'),
                'missing-end':('public void callEnding(', 'public void removedCallEnding('),
            }
            if args.host_variant in replacements:
                before,after=replacements[args.host_variant];assert before in content;content=content.replace(before,after)
        target=directory/'stubs'/path;target.parent.mkdir(parents=True,exist_ok=True);target.write_text(content)
        sources.append(str(target))
    media=args.root/'app/src/main/java/com/waenhancer/xposed/features/media'
    production=[media/'CallRecordingHook.java']
    contract=media/'CallRecordingEventContract.java'
    if contract.exists():production.append(contract)
    sources += [str(s) for s in production]
    sources.append(str(ROOT/'qa/fixtures/CallRecordingLifecycleChecks.java'))
    classpath=str(directory/'classes')
    compile_cmd=['javac','--release','17','-d',classpath]
    if args.dexkit_jar:compile_cmd += ['-cp',os.pathsep.join(map(str,[args.dexkit_jar,args.kotlin_stdlib]))]
    with (directory/'compile.log').open('w') as f:
        result=subprocess.run([*compile_cmd,*sources],stdout=f,stderr=subprocess.STDOUT,timeout=60)
    if result.returncode:
        print((directory/'compile.log').read_text());result.check_returncode()
    print('COMPILE_OK: complete production recorder hook',flush=True)
    (directory/'source-identity.json').write_text(json.dumps({
        'production':{str(s.relative_to(args.root)):hashlib.sha256(s.read_bytes()).hexdigest() for s in production},
        'scenario':args.scenario,'host_variant':args.host_variant,
        'real_dexkit_jar_sha256':hashlib.sha256(args.dexkit_jar.read_bytes()).hexdigest() if args.dexkit_jar else None,
        'real_kotlin_stdlib_sha256':hashlib.sha256(args.kotlin_stdlib.read_bytes()).hexdigest() if args.kotlin_stdlib else None,
        'limitations':'Complete production hook; explicit Android, Xposed, host, scheduler and storage doubles. No microphone, native audio, native DexKit, LSPosed or device call.',
    },indent=2))
    if args.dexkit_jar:
        print('PASS: complete recorder hook compiles against the real DexKit API; native matching is not executed')
    else:
        with (directory/'behavior.log').open('w') as f:
            result=subprocess.run(['java','-Xmx64m','-cp',classpath,'CallRecordingLifecycleChecks',args.scenario],stdout=f,stderr=subprocess.STDOUT,timeout=60)
        print((directory/'behavior.log').read_text());result.check_returncode()

if __name__=='__main__':main()
