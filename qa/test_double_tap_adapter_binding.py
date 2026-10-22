#!/usr/bin/env python3
"""Compile complete production DoubleTapReactionHook; exercise adapter/getView/touch callbacks.

The Android, Xposed, DexKit and reaction sender endpoints are explicit JVM doubles.
No real reaction or network request is sent. Selectors/native hook order on a real
WhatsApp installation remain a separate device acceptance requirement.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
HOOK = Path("app/src/main/java/com/waenhancer/xposed/features/conversation/DoubleTapReactionHook.java")
STUBS = {}
def stub(path, text):
    STUBS[path] = text

for n in ("NonNull", "Nullable"):
    stub("androidx/annotation/"+n+".java", "package androidx.annotation; public @interface "+n+" {}")
stub("android/content/SharedPreferences.java", """package android.content;
public interface SharedPreferences {boolean getBoolean(String k,boolean d);String getString(String k,String d);}""")
stub("android/content/Context.java", """package android.content;
public class Context {public android.content.res.Resources getResources(){return new android.content.res.Resources();}public String getPackageName(){return "com.whatsapp";}}""")
stub("android/content/res/Resources.java", """package android.content.res;
public class Resources {public int getIdentifier(String name,String type,String pkg){return 0;}}""")
stub("android/app/Activity.java", "package android.app; public class Activity extends android.content.Context {}")
stub("android/R.java", "package android; public class R {public static class id {public static int list=1;}}")
stub("android/os/SystemClock.java", """package android.os;
public class SystemClock {public static long now=10000;public static long uptimeMillis(){return now;}}""")
stub("android/view/View.java", """package android.view;
import android.content.Context;
public class View {
 public static final int VISIBLE=0; public int id=1; public Context context=new Context();
 public ListenerInfo mListenerInfo=new ListenerInfo();
 public int getId(){return id;}public Context getContext(){return context;}public int getVisibility(){return VISIBLE;}
 public <T extends View>T findViewById(int id){return null;}
 public void setOnTouchListener(OnTouchListener l){mListenerInfo.mOnTouchListener=l;}
 public boolean touch(MotionEvent e){return mListenerInfo.mOnTouchListener!=null&&mListenerInfo.mOnTouchListener.onTouch(this,e);}
 public interface OnTouchListener{boolean onTouch(View v,MotionEvent e);}
 public static class ListenerInfo{public OnTouchListener mOnTouchListener;}
}""")
stub("android/view/ViewGroup.java", """package android.view;
public class ViewGroup extends View {public int getChildCount(){return 0;}public View getChildAt(int i){throw new IndexOutOfBoundsException();}}""")
stub("android/view/MotionEvent.java", """package android.view;
public class MotionEvent {public static final int ACTION_DOWN=0,ACTION_UP=1,ACTION_MOVE=2,ACTION_CANCEL=3;
private final int action;public MotionEvent(int a){action=a;}public int getActionMasked(){return action;}
public float getX(){return 1;}public float getY(){return 1;}}""")
stub("android/view/ViewConfiguration.java", """package android.view;
public class ViewConfiguration {public static ViewConfiguration get(android.content.Context c){return new ViewConfiguration();}
public int getScaledTouchSlop(){return 8;}public static int getLongPressTimeout(){return 500;}public static int getDoubleTapTimeout(){return 300;}}""")
stub("android/widget/ListAdapter.java", """package android.widget;
import android.view.*; public interface ListAdapter {Object getItem(int pos);View getView(int pos,View recycle,ViewGroup parent);}""")
stub("android/widget/HeaderViewListAdapter.java", """package android.widget;
import android.view.*; public class HeaderViewListAdapter implements ListAdapter {
public ListAdapter wrapped;public ListAdapter getWrappedAdapter(){return wrapped;}public Object getItem(int p){return wrapped.getItem(p);}
public View getView(int p,View r,ViewGroup v){return wrapped.getView(p,r,v);}}""")
stub("android/widget/ListView.java", """package android.widget;
public class ListView extends android.view.ViewGroup {public void setAdapter(ListAdapter a){}}""")
stub("android/widget/TextView.java", """package android.widget;
public class TextView extends android.view.View {public CharSequence getText(){return "";}}""")
stub("com/waenhancer/xposed/core/BaseFeature.java", """package com.waenhancer.xposed.core;
import android.content.*;
public abstract class BaseFeature {protected final Context context;protected final SharedPreferences prefs;protected final ClassLoader classLoader;
public BaseFeature(Context c,ClassLoader l,SharedPreferences p){context=c;classLoader=l;prefs=p;}
protected boolean isEnabled(String k,boolean d){return prefs.getBoolean(k,d);}
public abstract void hook()throws Throwable;public abstract String getName();}""")
stub("com/waenhancer/xposed/utils/ActivityTracker.java", """package com.waenhancer.xposed.utils;
public class ActivityTracker {public static android.app.Activity current;public static android.app.Activity getCurrentActivity(){return current;}}""")
stub("de/robv/android/xposed/XC_MethodHook.java", """package de.robv.android.xposed;
public class XC_MethodHook {protected void beforeHookedMethod(MethodHookParam p)throws Throwable{}protected void afterHookedMethod(MethodHookParam p)throws Throwable{}
public void before(MethodHookParam p)throws Throwable{beforeHookedMethod(p);}public void after(MethodHookParam p)throws Throwable{afterHookedMethod(p);}
public static class MethodHookParam {public Object thisObject;public Object[] args;public java.lang.reflect.Method method;private Object result;
 public Object getResult(){return result;}public void setResult(Object r){result=r;}}
public class Unhook {private final Runnable action;public Unhook(Runnable r){action=r;}public void unhook(){action.run();}}}""")
stub("de/robv/android/xposed/XposedBridge.java", """package de.robv.android.xposed;
import java.lang.reflect.*;import java.util.*;
public class XposedBridge {
public static final Map<Method,List<XC_MethodHook>> hooks=new LinkedHashMap<>();
public static final List<String> logs=new ArrayList<>();
public static boolean failGetViewOnce;
public static XC_MethodHook.Unhook hookMethod(Method m,XC_MethodHook h){if(failGetViewOnce&&m.getName().equals("getView")){failGetViewOnce=false;throw new IllegalStateException("synthetic hook failure");}hooks.computeIfAbsent(m,k->new ArrayList<>()).add(h);return h.new Unhook(()->hooks.get(m).remove(h));}
public static void hookAllConstructors(Class<?> c,XC_MethodHook h){}public static void log(String s){logs.add(s);}
}""")
stub("de/robv/android/xposed/XposedHelpers.java", """package de.robv.android.xposed;
import java.lang.reflect.*;import java.util.*;
public class XposedHelpers {
public static final Map<Object,Map<String,Object>> fields=new IdentityHashMap<>();
public static Object getAdditionalInstanceField(Object o,String k){return fields.getOrDefault(o,Collections.emptyMap()).get(k);}
public static Object setAdditionalInstanceField(Object o,String k,Object v){return fields.computeIfAbsent(o,a->new HashMap<>()).put(k,v);}
public static Object removeAdditionalInstanceField(Object o,String k){Map<String,Object> f=fields.get(o);return f==null?null:f.remove(k);}
public static Object getObjectField(Object o,String n){try {return o.getClass().getField(n).get(o);}catch(Exception e){throw new IllegalStateException(e);}}
public static Method findMethodExactIfExists(Class<?> c,String n,Class<?>...types){try{return c.getMethod(n,types);}catch(Exception e){return null;}}
public static Method findMethodBestMatch(Class<?> c,String n,Class<?>...types){return findMethodExactIfExists(c,n,types);}
public static void findAndHookMethod(Class<?> c,String n,Object...args){Class<?>[] t=new Class<?>[args.length-1];for(int i=0;i<t.length;i++)t[i]=(Class<?>)args[i];
Method m=findMethodExactIfExists(c,n,t);if(m==null)throw new IllegalStateException("fixture method not found");XposedBridge.hookMethod(m,(XC_MethodHook)args[args.length-1]);}
}""")
stub("org/luckypray/dexkit/query/enums/StringMatchType.java", "package org.luckypray.dexkit.query.enums;public enum StringMatchType {Contains}")
stub("org/luckypray/dexkit/query/FindMethod.java", """package org.luckypray.dexkit.query;
public class FindMethod {public static FindMethod create(){return new FindMethod();}public FindMethod matcher(Object o){return this;}}""")
stub("org/luckypray/dexkit/query/matchers/MethodMatcher.java", """package org.luckypray.dexkit.query.matchers;
public class MethodMatcher {public static MethodMatcher create(){return new MethodMatcher();}
public MethodMatcher addUsingString(Object...o){return this;}public MethodMatcher paramTypes(Object...o){return this;}
public MethodMatcher modifiers(int i){return this;}public MethodMatcher returnType(Object o){return this;}}""")
stub("org/luckypray/dexkit/result/MethodData.java", """package org.luckypray.dexkit.result;
public class MethodData {public java.lang.reflect.Method getMethodInstance(ClassLoader l){return null;}}""")
stub("com/waenhancer/xposed/core/devkit/DexSearchEngine.java", """package com.waenhancer.xposed.core.devkit;
import android.content.Context;import org.luckypray.dexkit.result.MethodData;
public class DexSearchEngine {public static DexSearchEngine getInstance(){return new DexSearchEngine();}
public interface Finder<T>{T find(Bridge b,ClassLoader l)throws Throwable;}
public Class<?> findClassWithCache(Context c,ClassLoader l,String key,Finder<Class<?>> f){return null;}
public static class Results<T> extends java.util.ArrayList<T>{public T firstOrNull(){return isEmpty()?null:get(0);}}
public static class Bridge {public Results<MethodData> findMethod(Object o){return new Results<>();}}}""")

TEST = r'''package fixture;
import android.content.*;import android.view.*;import android.widget.*;import android.os.SystemClock;
import com.waenhancer.xposed.features.conversation.DoubleTapReactionHook;
import com.waenhancer.xposed.utils.ActivityTracker;import de.robv.android.xposed.*;
import java.lang.reflect.*;import java.util.*;
public class DoubleTapChecks {
 static final String BOUND="waex_doubletap_fmessage";
 public static class ConversationFixture extends android.app.Activity{}
 public static class Message {final String id;Message(String i){id=i;}}
 public static class Prefs implements SharedPreferences {boolean enabled=true;
 public boolean getBoolean(String k,boolean d){return enabled;}public String getString(String k,String d){return d;}}
 public static class Adapter implements ListAdapter {
  Object item;boolean fail;int reads;Adapter(Object m){item=m;}
  public Object getItem(int p){reads++;if(fail)throw new IllegalStateException("synthetic");return item;}
  public View getView(int p,View r,ViewGroup g){return r==null?new ViewGroup():r;}
 }
 public static class Action {final List<Message> sent=new ArrayList<>();
 public boolean send(Message m,String emoji,boolean add){sent.add(m);return true;}}
 static class Env {
  final Prefs prefs=new Prefs();final Action action=new Action();final DoubleTapReactionHook hook;
  final ListView list=new ListView();
  Env()throws Throwable{
   XposedBridge.hooks.clear();XposedBridge.logs.clear();XposedBridge.failGetViewOnce=false;XposedHelpers.fields.clear();SystemClock.now=10000;
   ActivityTracker.current=new ConversationFixture();
   hook=new DoubleTapReactionHook(new Context(),DoubleTapChecks.class.getClassLoader(),prefs);hook.hook();
   field("fMessageClass",Message.class);field("actionUserClass",Action.class);
   field("reactionSenderMethod",Action.class.getMethod("send",Message.class,String.class,boolean.class));field("cachedActionUserInstance",action);
  }
  void field(String n,Object value)throws Exception{Field f=DoubleTapReactionHook.class.getDeclaredField(n);f.setAccessible(true);f.set(hook,value);}
  void install(Adapter adapter)throws Throwable{
   Method m=ListView.class.getMethod("setAdapter",ListAdapter.class);XC_MethodHook.MethodHookParam p=new XC_MethodHook.MethodHookParam();
   p.thisObject=list;p.method=m;p.args=new Object[]{adapter};
   for(XC_MethodHook h:new ArrayList<>(XposedBridge.hooks.get(m)))h.before(p);
  }
  void bind(Adapter adapter,ViewGroup row)throws Throwable{
   Method m=Adapter.class.getMethod("getView",int.class,View.class,ViewGroup.class);
   XC_MethodHook.MethodHookParam p=new XC_MethodHook.MethodHookParam();p.thisObject=adapter;p.method=m;p.args=new Object[]{0,row,list};p.setResult(row);
   List<XC_MethodHook> callbacks=new ArrayList<>(XposedBridge.hooks.get(m));
   // Xposed after-callbacks unwind registrations at equal priority.
   for(int i=callbacks.size()-1;i>=0;i--)callbacks.get(i).after(p);
   check(p.getResult()==row&&p.thisObject==adapter,"host row/receiver changed");
  }
 }
 interface Check{void run()throws Throwable;}static int tests,failed;
 static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
 static void test(String name,Check c){tests++;try{c.run();System.out.println("PASS|"+name);}catch(Throwable t){failed++;System.out.println("FAIL|"+name+"|"+t.getClass().getSimpleName()+"|"+t.getMessage());}}
 static void doubleTap(View row){for(int i=0;i<2;i++){SystemClock.now+=30;row.touch(new MotionEvent(MotionEvent.ACTION_DOWN));SystemClock.now+=30;row.touch(new MotionEvent(MotionEvent.ACTION_UP));}}
 public static void main(String[]args)throws Throwable{
  test("first adapter reaction targets its current message",()->{Env e=new Env();Message a=new Message("first");Adapter x=new Adapter(a);ViewGroup r=new ViewGroup();e.install(x);e.bind(x,r);doubleTap(r);check(e.action.sent.size()==1&&e.action.sent.get(0)==a,"first reaction identity");});
  test("class hook binds the invoking second adapter",()->{Env e=new Env();Message a=new Message("first"),b=new Message("second");Adapter x=new Adapter(a),y=new Adapter(b);ViewGroup r=new ViewGroup();e.install(x);e.bind(y,r);doubleTap(r);check(e.action.sent.size()==1&&e.action.sent.get(0)==b,"reaction used captured first adapter");check(x.reads==0&&y.reads==1,"wrong receiver queried");});
  test("reinstalling same getView method does not duplicate callback",()->{Env e=new Env();Adapter x=new Adapter(new Message("first")),y=new Adapter(new Message("second"));e.install(x);e.install(y);Method m=Adapter.class.getMethod("getView",int.class,View.class,ViewGroup.class);check(XposedBridge.hooks.get(m).size()==1,"duplicate getView hooks");});
  test("second chat after two installations reacts to second message",()->{Env e=new Env();Message a=new Message("first"),b=new Message("second");Adapter x=new Adapter(a),y=new Adapter(b);ViewGroup r=new ViewGroup();e.install(x);e.install(y);e.bind(y,r);doubleTap(r);check(e.action.sent.size()==1&&e.action.sent.get(0)==b,"later callback overwritten by stale adapter");});
  test("recycled null item cannot react to its predecessor",()->{Env e=new Env();Adapter x=new Adapter(new Message("first"));ViewGroup r=new ViewGroup();e.install(x);e.bind(x,r);x.item=null;e.bind(x,r);doubleTap(r);check(e.action.sent.isEmpty()&&XposedHelpers.getAdditionalInstanceField(r,BOUND)==null,"null item retained old reaction target");});
  test("failed recycled item cannot react to its predecessor",()->{Env e=new Env();Adapter x=new Adapter(new Message("first"));ViewGroup r=new ViewGroup();e.install(x);e.bind(x,r);x.fail=true;e.bind(x,r);doubleTap(r);check(e.action.sent.isEmpty(),"failed item retained old reaction target");});
  test("disabled-time recycle invalidates old identity before reenabling",()->{Env e=new Env();Adapter x=new Adapter(new Message("first"));ViewGroup r=new ViewGroup();e.install(x);e.bind(x,r);e.prefs.enabled=false;x.item=new Message("second");e.bind(x,r);e.prefs.enabled=true;doubleTap(r);check(e.action.sent.isEmpty(),"disabled recycle retained old target");});
  test("original touch handler keeps event and return behavior",()->{Env e=new Env();Adapter x=new Adapter(new Message("first"));ViewGroup r=new ViewGroup();final int[] seen={0};r.setOnTouchListener((v,event)->{seen[0]++;return true;});e.install(x);e.bind(x,r);check(r.touch(new MotionEvent(MotionEvent.ACTION_DOWN))&&seen[0]==1,"original touch callback changed");});
  test("registration failure permits a later adapter retry",()->{Env e=new Env();Adapter x=new Adapter(new Message("first"));Message b=new Message("second");Adapter y=new Adapter(b);ViewGroup r=new ViewGroup();XposedBridge.failGetViewOnce=true;e.install(x);e.install(y);e.bind(y,r);doubleTap(r);check(e.action.sent.size()==1&&e.action.sent.get(0)==b,"failed registration retained reservation");});
  test("unsupported recycled item clears reaction target",()->{Env e=new Env();Adapter x=new Adapter(new Message("first"));ViewGroup r=new ViewGroup();e.install(x);e.bind(x,r);x.item=new Object();e.bind(x,r);doubleTap(r);check(e.action.sent.isEmpty(),"unsupported item retained predecessor");});
  test("a tap before row recycling cannot complete a later reaction",()->{Env e=new Env();Adapter x=new Adapter(new Message("first"));ViewGroup r=new ViewGroup();e.install(x);e.bind(x,r);SystemClock.now+=30;r.touch(new MotionEvent(MotionEvent.ACTION_DOWN));SystemClock.now+=30;r.touch(new MotionEvent(MotionEvent.ACTION_UP));x.item=new Message("second");e.bind(x,r);SystemClock.now+=30;r.touch(new MotionEvent(MotionEvent.ACTION_DOWN));SystemClock.now+=30;r.touch(new MotionEvent(MotionEvent.ACTION_UP));check(e.action.sent.isEmpty(),"gesture crossed recycled messages");});
  System.out.println("RESULT|"+tests+"|"+failed);check(tests==11,"missing scenarios");check(failed==0,"reaction binding regressions");
 }
}'''

def run(source, out, allow_failure):
    out.mkdir(parents=True, exist_ok=True)
    src=out/"fixtures";src.mkdir()
    classes=out/"classes";classes.mkdir()
    for path,text in {**STUBS,"fixture/DoubleTapChecks.java":TEST}.items():
        p=src/path;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(text)
    result=subprocess.run(["javac","--release","17","-d",str(classes),str(source),*map(str,src.rglob("*.java"))],capture_output=True,text=True,timeout=60)
    (out/"compile.log").write_text(result.stdout+result.stderr)
    if result.returncode:raise AssertionError("Complete production compile failed: "+str(out/"compile.log"))
    result=subprocess.run(["java","-cp",str(classes),"fixture.DoubleTapChecks"],capture_output=True,text=True,timeout=15)
    log=result.stdout+result.stderr;(out/"tests.log").write_text(log);print(log)
    summary=re.search(r"RESULT\|(\d+)\|(\d+)",result.stdout)
    assert summary and int(summary[1])==11,"fixture incomplete"
    record={"source":str(source),"source_sha256":hashlib.sha256(source.read_bytes()).hexdigest(),"fixture_sha256":hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),"complete_production_compiled":True,"checks":int(summary[1]),"failed":int(summary[2]),"test_exit":result.returncode,"limits":"Android/Xposed/DexKit/host reaction sender are explicit doubles. No external message or reaction sent."}
    (out/"result.json").write_text(json.dumps(record,indent=2)+"\n")
    if not allow_failure:assert result.returncode==0,"Double-tap binding regression"

if __name__=="__main__":
    p=argparse.ArgumentParser(description=__doc__);p.add_argument("--source",type=Path,default=ROOT/HOOK);p.add_argument("--out",type=Path);p.add_argument("--allow-test-failure",action="store_true");a=p.parse_args()
    if a.out:run(a.source.resolve(),a.out.resolve(),a.allow_test_failure)
    else:
        with tempfile.TemporaryDirectory(prefix="waex-double-tap-") as temp:run(a.source.resolve(),Path(temp),a.allow_test_failure)
