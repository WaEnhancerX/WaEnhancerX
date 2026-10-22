#!/usr/bin/env python3
"""Compile the complete CallPrivacyHook; execute profile/contact decisions and incoming callback.

The exact CustomPrivacyHook reader and existing dialog Save/Cancel callbacks are
also compiled in Kotlin and exercised with the complete call hook. JSON uses the
hash-pinned Android 14 implementation. Android, Xposed, DexKit, contact-provider,
notification and HTTP boundaries are explicit doubles. The production SQL executes
against file-backed Python SQLite. No Android Binder, native hook injection, live
VoIP or external message runs. The AOSP source bootstrap may fetch missing pinned
sources; --json-source-dir or WAEX_AOSP_JSON_SOURCE_DIR supports offline execution.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import sqlite3
import subprocess
import sys
import tempfile

from test_settings_backup_json import SHA256, android_doubles, json_sources

ROOT = Path(__file__).resolve().parents[1]
HOOK = Path("app/src/main/java/com/waenhancer/xposed/features/privacy/CallPrivacyHook.java")
IDENTITY = Path("app/src/main/java/com/waenhancer/utils/ContactIdentityPolicy.java")
CUSTOM = Path("app/src/main/java/com/waenhancer/xposed/features/privacy/CustomPrivacyHook.kt")
STUBS = {}


def stub(path, source):
    STUBS[path] = source


stub('android/annotation/SuppressLint.java', '''package android.annotation; public @interface SuppressLint { String[] value(); }''')
stub('androidx/annotation/NonNull.java', '''package androidx.annotation; public @interface NonNull {}''')
stub('android/content/Context.java', '''package android.content;
import java.io.File;
public class Context {
 public File root; public boolean nullFiles; public RuntimeException pathFailure;
 public final java.util.concurrent.CountDownLatch notificationDelivered=new java.util.concurrent.CountDownLatch(1);
 public final java.util.concurrent.atomic.AtomicInteger notificationCount=new java.util.concurrent.atomic.AtomicInteger();
 public Context(File r) {root=r;}
 public File getFilesDir() {return nullFiles ? null : new File(root,"files");}
 public File getDatabasePath(String name) {if(pathFailure!=null)throw pathFailure;return new File(root,"databases/"+name);}
}
''')
stub('android/content/SharedPreferences.java', '''package android.content;
public interface SharedPreferences {String getString(String key,String fallback); boolean getBoolean(String key,boolean fallback);
 Editor edit();interface Editor {Editor putString(String key,String value);void apply();}}
''')
stub('android/content/DialogInterface.java', '''package android.content;
public interface DialogInterface {void dismiss();interface OnClickListener {void onClick(DialogInterface dialog,int which);}}
''')
stub('android/widget/Toast.java', '''package android.widget;
import android.content.Context;public class Toast {public static final int LENGTH_SHORT=0;
 public static Toast makeText(Context c,String s,int length){return new Toast();}public void show(){}}
''')
stub('android/database/Cursor.java', '''package android.database;
public class Cursor implements AutoCloseable {private final boolean row; public Cursor(boolean value){row=value;}
 public boolean moveToFirst(){return row;} public void close(){} }
''')
stub('android/database/sqlite/SQLiteDatabase.java', '''package android.database.sqlite;
import android.database.Cursor;
import java.io.*; import java.nio.charset.StandardCharsets; import java.util.*;
public class SQLiteDatabase implements AutoCloseable {
 public static final int OPEN_READONLY=1;
 public static final List<String> paths=new ArrayList<>(); public static final List<String> queries=new ArrayList<>();
 private final String path; private SQLiteDatabase(String p){path=p;}
 public static SQLiteDatabase openDatabase(String p,Object factory,int flags){
  if(flags!=OPEN_READONLY)throw new AssertionError("Database must open read-only");
  paths.add(p);return new SQLiteDatabase(p);
 }
 public Cursor rawQuery(String query,String[] args){
  queries.add(query);
  List<String> cmd=new ArrayList<>(Arrays.asList(System.getProperty("fixture.python"),System.getProperty("fixture.sql"),path,encode(query)));
  for(String arg:args){if(arg==null)throw new AssertionError("Null SQLite selection argument");cmd.add(encode(arg));}
  try {
   Process p=new ProcessBuilder(cmd).redirectErrorStream(true).start();
   String output=new String(p.getInputStream().readAllBytes(),StandardCharsets.UTF_8).trim();
   if(!p.waitFor(5,java.util.concurrent.TimeUnit.SECONDS)){p.destroyForcibly();throw new AssertionError("SQLite fixture timeout");}
   if(p.exitValue()!=0)throw new IllegalStateException("Synthetic private SQL failure");
   if(!"0".equals(output)&&!"1".equals(output))throw new AssertionError("Unexpected SQLite result "+output);
   return new Cursor("1".equals(output));
  }catch(IOException|InterruptedException e){throw new AssertionError(e);}
 }
 private static String encode(String s){return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));}
 public void close(){}
}
''')
stub('android/os/Build.java', '''package android.os;public class Build {public static class VERSION {public static int SDK_INT=34;}public static class VERSION_CODES{public static final int O=26;}}''')
stub('android/os/Message.java', '''package android.os;public class Message {public Object obj;}''')
stub('android/text/TextUtils.java', '''package android.text;public class TextUtils {public static boolean isEmpty(CharSequence s){return s==null||s.length()==0;}}''')
stub('android/R.java', '''package android;public class R {public static class drawable{public static final int ic_dialog_info=1;}}''')
stub('android/app/NotificationChannel.java', '''package android.app;public class NotificationChannel {public NotificationChannel(String id,String name,int importance){}}''')
stub('android/app/NotificationManager.java', '''package android.app;public class NotificationManager {public static final int IMPORTANCE_HIGH=1;}''')
stub('androidx/core/app/NotificationCompat.java', '''package androidx.core.app;
import android.content.Context;public class NotificationCompat {public static final int PRIORITY_HIGH=1;
 public static class Builder {public Builder(Context c,String channel){} public Builder setSmallIcon(int i){return this;}
  public Builder setContentTitle(String s){return this;} public Builder setContentText(String s){return this;}
  public Builder setAutoCancel(boolean b){return this;}public Builder setPriority(int p){return this;}
  public Builder setStyle(BigTextStyle s){return this;} public Object build(){return new Object();}}
 public static class BigTextStyle {public BigTextStyle bigText(String s){return this;}}
}
''')
stub('androidx/core/app/NotificationManagerCompat.java', '''package androidx.core.app;
import android.content.Context;import android.app.NotificationChannel;
public class NotificationManagerCompat {private final Context context;private NotificationManagerCompat(Context c){context=c;}
 public static NotificationManagerCompat from(Context c){return new NotificationManagerCompat(c);}
 public void createNotificationChannel(NotificationChannel c){}
 public void notify(int id,Object notification){context.notificationCount.incrementAndGet();context.notificationDelivered.countDown();} }
''')
stub('com/waenhancer/utils/UiText.java', '''package com.waenhancer.utils;public class UiText {public static String get(int id,String fallback){return fallback;}}''')
stub('com/waenhancer/utils/ContactNameResolver.java', '''package com.waenhancer.utils;
import android.content.Context;import java.util.*;
public class ContactNameResolver {
 public static final ContactNameResolver INSTANCE=new ContactNameResolver();
 public final Map<String,String> mappings=new HashMap<>(), saved=new HashMap<>(), display=new HashMap<>();
 public int mappingCalls,systemCalls; public RuntimeException systemFailure; public Error fatalSystemFailure;
 public void reset(){mappings.clear();saved.clear();display.clear();mappingCalls=systemCalls=0;systemFailure=null;fatalSystemFailure=null;}
 public String resolveLidToPhone(Context c,String jid){mappingCalls++;return mappings.get(jid);}
 public String resolveName(Context c,String jid,String fallback,String hint){
  if(display.containsKey(jid))return display.get(jid);
  String phone=ContactIdentityPolicy.phoneUser(jid); if(phone==null)phone=mappings.get(jid);
  return phone==null ? "Unknown" : saved.getOrDefault(phone,"Unknown");
 }
 public String getSystemContactName(Context c,String phone){systemCalls++;if(fatalSystemFailure!=null)throw fatalSystemFailure;if(systemFailure!=null)throw systemFailure;return saved.get(phone);}
 public String cleanUserPart(String s){return s.split("@")[0].split(":")[0].split("\\\\.")[0];}
 public boolean isValidDisplayName(String s){return s!=null&&!s.isBlank()&&!"Unknown".equals(s)&&!s.startsWith("+");}
 public boolean isLidJid(String s){return ContactIdentityPolicy.isLid(s);}
}
''')
stub('com/waenhancer/xposed/core/BaseFeature.java', '''package com.waenhancer.xposed.core;
import android.content.*;public abstract class BaseFeature {protected final Context context;protected final ClassLoader classLoader;protected final SharedPreferences prefs;
 public BaseFeature(Context c,ClassLoader l,SharedPreferences p){context=c;classLoader=l;prefs=p;}
 public abstract void hook() throws Throwable; public abstract String getName();}
''')
stub('de/robv/android/xposed/XC_MethodHook.java', '''package de.robv.android.xposed;
public class XC_MethodHook {protected void beforeHookedMethod(MethodHookParam p)throws Throwable{} protected void afterHookedMethod(MethodHookParam p)throws Throwable{}
 public static class MethodHookParam {public Object thisObject;public Object[] args;private Object result;public boolean early;
  public void setResult(Object r){result=r;early=true;}public Object getResult(){return result;}}
 public void before(MethodHookParam p)throws Throwable{beforeHookedMethod(p);}}
''')
stub('de/robv/android/xposed/XposedBridge.java', '''package de.robv.android.xposed;
import java.lang.reflect.*;import java.util.*;public class XposedBridge {
 public static final Map<Method,XC_MethodHook> hooks=new HashMap<>();
 public static void hookMethod(Method m,XC_MethodHook h){hooks.put(m,h);}
 public static void hookAllConstructors(Class<?> c,XC_MethodHook h){} public static void hookAllMethods(Class<?> c,String name,XC_MethodHook h){}
 public static void log(String s){}
}
''')
stub('de/robv/android/xposed/XposedHelpers.java', '''package de.robv.android.xposed;
import java.lang.reflect.*;public class XposedHelpers {
 public static Class<?> findClassIfExists(String name,ClassLoader l){try{return Class.forName(name,false,l);}catch(ClassNotFoundException e){return null;}}
 public static Object callMethod(Object o,String name,Object...args){try{for(Method m:o.getClass().getMethods())if(m.getName().equals(name)&&m.getParameterTypes().length==args.length)return m.invoke(o,args);throw new NoSuchMethodException(name);}catch(Exception e){throw new IllegalStateException(e);}}
 public static Object getObjectField(Object o,String field){try{return o.getClass().getField(field).get(o);}catch(Exception e){throw new IllegalStateException(e);}}
}
''')
stub('org/luckypray/dexkit/query/enums/StringMatchType.java', '''package org.luckypray.dexkit.query.enums;public enum StringMatchType {Contains,EndsWith}''')
for name in ('Class', 'Method'):
    stub('org/luckypray/dexkit/query/Find'+name+'.java', 'package org.luckypray.dexkit.query;public class Find'+name+' {public static Find'+name+' create(){return new Find'+name+'();}public Find'+name+' matcher(Object m){return this;}}')
    stub('org/luckypray/dexkit/query/matchers/'+name+'Matcher.java', 'package org.luckypray.dexkit.query.matchers;public class '+name+'Matcher {public static '+name+'Matcher create(){return new '+name+'Matcher();}public '+name+'Matcher superClass(Object s){return this;}public '+name+'Matcher className(Object...s){return this;}public '+name+'Matcher addUsingString(Object...s){return this;}}')
stub('org/luckypray/dexkit/result/ClassData.java', '''package org.luckypray.dexkit.result;public class ClassData {public int getModifiers(){return 0;}public Class<?> getInstance(ClassLoader l){return null;}}''')
stub('org/luckypray/dexkit/result/MethodData.java', '''package org.luckypray.dexkit.result;public class MethodData {public java.lang.reflect.Method getMethodInstance(ClassLoader l){return null;}}''')
stub('com/waenhancer/xposed/core/devkit/DexSearchEngine.java', '''package com.waenhancer.xposed.core.devkit;
import android.content.Context;import java.lang.reflect.Method;import org.luckypray.dexkit.result.*;
public class DexSearchEngine {public static Method incoming;public static DexSearchEngine getInstance(){return new DexSearchEngine();}
 public interface Finder<T>{T find(Bridge b,ClassLoader l)throws Throwable;}
 public Method findMethodWithCache(Context c,ClassLoader l,String key,Finder<Method> f){return key.equals("wpp_voip_on_call_state_changed")?incoming:null;}
 public Class<?> findClassWithCache(Context c,ClassLoader l,String key,Finder<Class<?>> f){return null;}
 public static class Results<T> extends java.util.ArrayList<T>{public T firstOrNull(){return isEmpty()?null:get(0);}}
 public static class Bridge {public Results<ClassData> findClass(Object o){return new Results<>();}public Results<MethodData> findMethod(Object o){return new Results<>();}}
}
''')
stub('okhttp3/Request.java', '''package okhttp3;public class Request {public static class Builder {public Builder url(String u){return this;}public Request build(){return new Request();}}}''')
stub('okhttp3/Response.java', '''package okhttp3;public class Response implements AutoCloseable {public Body body(){return null;}public void close(){}public static class Body{public String string(){throw new AssertionError("HTTP must not execute");}}}''')
stub('okhttp3/OkHttpClient.java', '''package okhttp3;public class OkHttpClient {public static class Builder {public OkHttpClient build(){return new OkHttpClient();}}public Call newCall(Request r){throw new AssertionError("HTTP must not execute");}public static class Call{public Response execute(){throw new AssertionError("HTTP must not execute");}}}''')
stub('com/whatsapp/voipcalling/CallInfo.java', '''package com.whatsapp.voipcalling;
public class CallInfo {public final String jid;public final boolean outgoing;public CallInfo(String j,boolean b){jid=j;outgoing=b;}
 public boolean isCaller(){return outgoing;}public Object getPeerJid(){return new Peer(jid);}public String getCallId(){return "synthetic-call";}
 public static class Peer {final String jid;Peer(String j){jid=j;}public String getRawString(){return jid;}public String toString(){return jid;}}
}
''')

def source_block(source, start):
    """Extract a complete balanced production body, failing if its boundary changes."""
    opening = source.index('{', start)
    end, depth = opening + 1, 1
    while depth and end < len(source):
        depth += (source[end] == '{') - (source[end] == '}')
        end += 1
    if depth:
        raise ValueError('Unclosed production callback')
    return source[start:end]


def custom_privacy_fixture(source):
    reader = source_block(source, source.index('fun getContactPrivacy('))
    callbacks = []
    for button in ('.setPositiveButton(', '.setNegativeButton('):
        start = source.index('DialogInterface.OnClickListener {', source.index(button))
        callbacks.append(source_block(source, start))
    dialog_start = source.index('private fun showCustomPrivacyBottomSheet(')
    clean_number = re.search(r'val cleanNumber = [^\n]+', source[dialog_start:]).group()
    harness = r'''package com.waenhancer.xposed.features.privacy
import android.content.Context
import android.content.SharedPreferences
import android.content.DialogInterface
import android.widget.Toast
import com.waenhancer.app.R
import com.waenhancer.utils.UiText
import de.robv.android.xposed.XposedBridge
import org.json.JSONObject

/** Exact production reader and Save/Cancel callbacks; UI construction/name lookup are adapters. */
class CustomPrivacyHook {
 companion object {
  const val TAG = "[WAEX_CUSTOM_PRIVACY]"
  @JvmStatic
  __READER__
  private fun extractDisplayName(activity: Context, jid: String, cleanNumber: String) = "Synthetic contact"
  @JvmStatic
  fun saveDialogChoice(prefs: SharedPreferences, jid: String, choices: Map<String, Boolean>, cancel: Boolean) {
   val activity = Context(java.io.File("."))
   __CLEAN_NUMBER__
   val checkStateMap = choices.toMutableMap()
   val callback = if (cancel) __CANCEL__ else __SAVE__
   callback.onClick(object : DialogInterface { override fun dismiss() {} }, 0)
  }
 }
}
'''
    return (harness.replace('__READER__', reader).replace('__CLEAN_NUMBER__', clean_number)
            .replace('__SAVE__', callbacks[0]).replace('__CANCEL__', callbacks[1])), {
        'getContactPrivacy': hashlib.sha256(reader.encode()).hexdigest(),
        'dialogSave': hashlib.sha256(callbacks[0].encode()).hexdigest(),
        'dialogCancel': hashlib.sha256(callbacks[1].encode()).hexdigest(),
    }


TEST = r'''package com.waenhancer.xposed.features.privacy;
import android.content.*;
import android.database.sqlite.SQLiteDatabase;
import com.waenhancer.utils.ContactNameResolver;
import com.waenhancer.xposed.core.devkit.DexSearchEngine;
import com.whatsapp.voipcalling.CallInfo;
import de.robv.android.xposed.*;
import org.json.JSONObject;
import java.io.*;import java.lang.reflect.*;import java.util.*;

public class CallPrivacyContactsTest {
 static final String PN="56912345678@s.whatsapp.net", PHONE="56912345678", LID="123456789012345@lid";
 static final ContactNameResolver names=ContactNameResolver.INSTANCE;
 static File root;static Context context;static Prefs prefs;static CallPrivacyHook hook;static int checks,failures;
 interface Check {void run()throws Throwable;}
 static class Prefs implements SharedPreferences {final Map<String,Object> values=new HashMap<>();
  public String getString(String key,String fallback){return values.containsKey(key)?(String)values.get(key):fallback;}
  public boolean getBoolean(String key,boolean fallback){return values.containsKey(key)?(Boolean)values.get(key):fallback;}
  public Editor edit(){return new Editor(){final Map<String,String> pending=new HashMap<>();
   public Editor putString(String key,String value){pending.put(key,value);return this;}
   public void apply(){values.putAll(pending);}};}}
 static void reset(String fixture){names.reset();SQLiteDatabase.paths.clear();SQLiteDatabase.queries.clear();XposedBridge.hooks.clear();
  context=new Context(new File(root,fixture));prefs=new Prefs();hook=new CallPrivacyHook(context,CallPrivacyContactsTest.class.getClassLoader(),prefs);}
 static Object invoke(String name,Class<?>[] types,Object...args)throws Throwable {try{Method m=CallPrivacyHook.class.getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(hook,args);}catch(InvocationTargetException e){throw e.getCause();}}
 static boolean block(String jid)throws Throwable{return (Boolean)invoke("shouldBlockCall",new Class<?>[]{String.class},jid);}
 static void mode(String value){prefs.values.put("call_privacy",value);}
 static void rule(String jid,boolean value)throws Throwable{ruleMarker(jid,value,Boolean.TRUE);}
 static void ruleMarker(String jid,boolean value,Object marker)throws Throwable{
  JSONObject data=new JSONObject().put("BlockCall",value);if(marker!=null)data.put("BlockCallConfirmed",marker);
  prefs.values.put("per_contact_rules_"+jid,data.toString());
 }
 static void saveChoice(String jid,boolean value,boolean cancel){
  Map<String,Boolean> choices=new LinkedHashMap<>();choices.put("HideSeen",true);choices.put("BlockCall",value);
  CustomPrivacyHook.saveDialogChoice(prefs,jid,choices,cancel);
 }
 static void check(boolean value,String detail){if(!value)throw new AssertionError(detail);}
 static void test(String name,Check run){checks++;try{run.run();System.out.println("PASS|"+name);}catch(Throwable failure){failures++;System.out.println("FAIL|"+name+"|"+failure.getClass().getSimpleName()+"|"+failure.getMessage());}}
 public static class Incoming {public boolean onCall(CallInfo info){return false;}}
 static boolean incoming(String jid,boolean outgoing)throws Throwable{
  Method selected=Incoming.class.getDeclaredMethod("onCall",CallInfo.class);DexSearchEngine.incoming=selected;
  invoke("hookIncomingCall",new Class<?>[]{});
  XC_MethodHook callback=XposedBridge.hooks.get(selected);check(callback!=null,"Complete production hook must install callback");
  XC_MethodHook.MethodHookParam p=new XC_MethodHook.MethodHookParam();p.thisObject=new Incoming();p.args=new Object[]{new CallInfo(jid,outgoing)};
  callback.before(p);
  // The production callback schedules contact-name work on CompletableFuture's executor.
  // Wait for this Context's notification endpoint before another case clears shared doubles.
  if(p.early){
   check(context.notificationDelivered.await(5,java.util.concurrent.TimeUnit.SECONDS),"Blocked-call notification did not complete");
   check(context.notificationCount.get()==1,"Blocked call did not produce exactly one notification");
  }
  return p.early;
 }
 public static void main(String[] args)throws Throwable{
  root=new File(args[0]);
  test("default-global-mode-preserves-unconfigured-calls",()->{reset("empty");check(!incoming(PN,false),"Default changed normal call");});
  test("explicit-dialog-save-blocks-with-global-default",()->{reset("empty");saveChoice(PN,true,false);
   String saved=prefs.getString(PHONE+"_privacy",null);check(saved!=null,"Dialog did not write rules");
   JSONObject json=new JSONObject(saved);check(Boolean.TRUE.equals(json.opt("BlockCallConfirmed")),"Dialog did not explicitly confirm call choice");
   check(json.optBoolean("HideSeen",false)&&json.optBoolean("BlockCall",false),"Existing dialog choices changed");
   for(String key:new String[]{PN+"_privacy","per_contact_rules_"+PN})check(saved.equals(prefs.getString(key,null)),"Dialog aliases disagree");
   check(incoming(PN,false),"Saved confirmed contact rule skipped by global gate");});
  test("ambiguous-legacy-freeze-rule-does-not-activate-in-default-mode",()->{reset("empty");
   prefs.values.put("per_contact_rules_"+PN,"{\"FreezeLastSeen\":true,\"BlockCall\":true}");
   check(!incoming(PN,false),"Historical freeze alias unexpectedly began blocking calls");});
  test("legacy-block-rule-without-marker-needs-confirmation-in-default-mode",()->{reset("empty");ruleMarker(PN,true,null);
   check(!incoming(PN,false),"Unconfirmed historical rule activated without explicit choice");});
  test("false-confirmation-marker-does-not-activate-in-default-mode",()->{reset("empty");ruleMarker(PN,true,Boolean.FALSE);
   check(!incoming(PN,false),"False confirmation accepted");});
  test("nonboolean-confirmation-marker-is-not-explicit-confirmation",()->{reset("empty");ruleMarker(PN,true,"true");
   check(!incoming(PN,false),"String marker accepted as explicit boolean choice");});
  test("reading-a-legacy-rule-does-not-confirm-it",()->{reset("empty");ruleMarker(PN,true,null);
   Map<String,Object> before=new HashMap<>(prefs.values);check(CustomPrivacyHook.getContactPrivacy(prefs,PN)!=null,"Existing rule not readable");
   check(prefs.values.equals(before)&&!incoming(PN,false),"Rule read changed consent");});
  test("dialog-cancel-does-not-confirm-legacy-rule",()->{reset("empty");ruleMarker(PN,true,null);
   Map<String,Object> before=new HashMap<>(prefs.values);saveChoice(PN,true,true);
   check(prefs.values.equals(before)&&!incoming(PN,false),"Cancelling dialog confirmed call choice");});
  test("explicit-dialog-save-can-allow-calls",()->{reset("empty");rule(PN,true);saveChoice(PN,false,false);
   JSONObject data=new JSONObject(prefs.getString(PHONE+"_privacy",null));
   check(Boolean.TRUE.equals(data.opt("BlockCallConfirmed"))&&!data.optBoolean("BlockCall",true)&&!incoming(PN,false),"Explicit allow choice still blocks");});
  test("legacy-enable-flag-preserves-unconfirmed-block-rule",()->{reset("empty");prefs.values.put("call_privacy_enabled",true);ruleMarker(PN,true,null);
   check(incoming(PN,false),"Legacy enable-flag call rule changed");});
  test("legacy-enable-flag-preserves-false-marker-block-rule",()->{reset("empty");prefs.values.put("call_privacy_enabled",true);ruleMarker(PN,true,Boolean.FALSE);
   check(incoming(PN,false),"Confirmation was imposed on pre-existing enabled filter");});
  test("legacy-unknown-mode-preserves-saved-contact-block-rule",()->{reset("saved_phone");mode("2");ruleMarker(PN,true,null);
   check(incoming(PN,false),"Legacy explicit block no longer overrides known-contact membership");});
  test("legacy-block-all-ignores-confirmation-marker",()->{reset("saved_phone");mode("1");ruleMarker(PN,false,Boolean.FALSE);
   check(incoming(PN,false),"Confirmation weakened block-all mode");});
  test("disabled-custom-privacy-ignores-contact-rule",()->{reset("empty");prefs.values.put("custom_privacy",false);rule(PN,true);check(!incoming(PN,false),"Disabled contact rules still block");});
  test("outgoing-call-preserves-original-path",()->{reset("empty");mode("1");check(!incoming(PN,true),"Outgoing call intercepted");});
  test("global-block-all-skips-contact-lookups",()->{reset("empty");mode("1");check(block(PN),"Block All ignored");check(names.mappingCalls==0&&names.systemCalls==0&&SQLiteDatabase.paths.isEmpty(),"Block All performed unnecessary contact work");});
  test("legacy-enable-alone-does-not-block-everyone",()->{reset("empty");prefs.values.put("call_privacy_enabled",true);check(!incoming(PN,false),"Enable flag changed policy mode");});
  test("unsupported-identities-preserve-original-path",()->{reset("empty");mode("1");for(String jid:new String[]{"","12345",PN+".other",LID+"\n","<jid:"+PN+">"})check(!block(jid),"Unsupported identity accepted: "+jid);});
  test("mapped-phone-rule-keeps-qualified-namespace",()->{reset("empty");names.mappings.put(LID,PHONE);rule(PN,true);check(block(LID),"Mapped phone rule lookup lost namespace");});
  test("explicit-raw-rule-keeps-existing-precedence",()->{reset("empty");names.mappings.put(LID,PHONE);rule(LID,false);rule(PN,true);check(!block(LID),"Existing raw rule precedence changed");});
  test("unconfirmed-raw-rule-cannot-borrow-phone-confirmation",()->{reset("empty");names.mappings.put(LID,PHONE);ruleMarker(LID,true,null);rule(PN,true);
   check(!incoming(LID,false),"Mapped identity confirmed an ambiguous higher-priority raw rule");});
  test("global-unknown-mode-allows-exact-saved-phone-row",()->{reset("saved_phone");mode("2");check(!block(PN),"Exact saved phone blocked");});
  test("mapped-lid-can-use-qualified-saved-phone-row",()->{reset("saved_phone");mode("2");names.mappings.put(LID,PHONE);check(!block(LID),"Mapped LID did not match qualified phone jid");check(names.mappingCalls==1,"Mapping repeated during single decision");});
  test("unmapped-lid-never-matches-numeric-phone-collision",()->{reset("lid_collision");mode("2");check(block(LID),"LID user digits incorrectly used as phone");});
  test("unmapped-lid-never-matches-empty-database-key",()->{reset("blank_keys");mode("2");check(block(LID),"Absent mapping matched empty database key");});
  test("new-address-book-table-survives-missing-legacy-table",()->{reset("address_book_only");mode("2");check(!block(LID),"Missing wa_contacts skipped wa_address_book");});
  test("legacy-table-survives-missing-new-table",()->{reset("legacy_only");mode("2");check(!block(PN),"Missing wa_address_book invalidated legacy row");});
  test("non-whatsapp-address-book-row-does-not-authorize",()->{reset("address_book_nonuser");mode("2");check(block(LID),"Inactive address-book record accepted");});
  test("database-open-error-retains-system-contacts-fallback",()->{reset("corrupt");mode("2");names.saved.put(PHONE,"Saved contact");check(!block(PN),"Open error suppressed Contacts lookup");});
  test("database-path-error-retains-system-contacts-fallback",()->{reset("empty");mode("2");context.pathFailure=new SecurityException("Synthetic path denial");names.saved.put(PHONE,"Saved contact");check(!block(PN),"Path error suppressed Contacts lookup");});
  test("missing-database-allows-exact-system-contact",()->{reset("missing");mode("2");names.saved.put(PHONE,"Saved contact");check(!block(PN),"Exact system contact blocked");});
  test("unmapped-lid-never-probes-system-phone-contacts",()->{reset("empty");mode("2");names.saved.put("123456789012345","Different phone owner");check(block(LID)&&names.systemCalls==0,"Unmapped LID promoted to phone");});
  test("mapped-lid-can-use-exact-system-contact",()->{reset("empty");mode("2");names.mappings.put(LID,PHONE);names.saved.put(PHONE,"Saved contact");check(!block(LID),"Verified mapped phone cannot use Contacts");});
  test("business-push-or-cached-name-is-not-saved-contact",()->{reset("empty");mode("2");names.display.put(PN,"Unsaved business profile");check(block(PN),"Display name treated as saved-contact membership");});
  test("system-provider-denial-preserves-unknown-policy",()->{reset("empty");mode("2");names.systemFailure=new SecurityException("Synthetic contact denial");check(block(PN),"Unknown caller allowed on Contacts denial");});
  test("fatal-contact-error-is-not-converted-to-unknown",()->{reset("empty");mode("2");Error sentinel=new AssertionError("Synthetic fatal marker");names.fatalSystemFailure=sentinel;try{block(PN);throw new IllegalStateException("Fatal error swallowed");}catch(Error actual){check(actual==sentinel,"Fatal identity changed");}});
  test("current-context-database-path-wins-over-null-files-dir",()->{reset("empty");context.nullFiles=true;File actual=(File)invoke("findDatabase",new Class<?>[]{String.class},"wa.db");check(context.getDatabasePath("wa.db").equals(actual),"Lookup escaped to relative/other package database");mode("2");check(block(PN),"Foreign relative database authorized caller");});
  test("database-helper-rejects-unrelated-names",()->{reset("empty");check(invoke("findDatabase",new Class<?>[]{String.class},"../outside.db")==null,"Unrelated database path accepted");});
  test("same-phone-in-another-client-is-not-a-contact",()->{reset("empty");mode("2");check(block(PN),"Contact from other context accepted");check(SQLiteDatabase.paths.stream().allMatch(path->path.equals(context.getDatabasePath("wa.db").getAbsolutePath())),"Read left selected profile/client");});
  test("saved-contact-queries-are-bounded",()->{reset("empty");mode("2");check(block(PN),"Synthetic unknown allowed");check(!SQLiteDatabase.queries.isEmpty()&&SQLiteDatabase.queries.stream().allMatch(q->q.endsWith("LIMIT 1")),"Unbounded membership query");});
  System.out.println("RESULT|"+checks+"|"+failures);
  if(failures!=0)System.exit(1);
 }
}
'''


def prepare_databases(out):
    root = out / 'databases'
    schema = ('CREATE TABLE wa_contacts(jid TEXT, number TEXT, display_name TEXT, raw_contact_id INTEGER);'
              'CREATE TABLE wa_address_book(jid TEXT, is_whatsapp_user INTEGER);')
    phone = '56912345678@s.whatsapp.net'
    lid = '123456789012345@lid'
    for name in ('empty', 'saved_phone', 'lid_collision', 'blank_keys', 'address_book_only',
                 'legacy_only', 'address_book_nonuser', 'corrupt', 'missing'):
        folder = root / name / 'databases'
        folder.mkdir(parents=True)
        path = folder / 'wa.db'
        if name == 'missing':
            continue
        if name == 'corrupt':
            path.write_bytes(b'Synthetic invalid SQLite database')
            continue
        with sqlite3.connect(path) as db:
            db.executescript(schema)
            if name in ('saved_phone', 'legacy_only'):
                db.execute('INSERT INTO wa_contacts VALUES (?,NULL,?,1)', (phone, 'Saved contact'))
            elif name == 'lid_collision':
                db.execute('INSERT INTO wa_contacts VALUES (?, ?, ?, 1)',
                           ('99912345678@s.whatsapp.net', '123456789012345', 'Different phone owner'))
            elif name == 'blank_keys':
                db.execute("INSERT INTO wa_contacts VALUES ('','','Empty identity',1)")
            elif name in ('address_book_only', 'address_book_nonuser'):
                db.execute('INSERT INTO wa_address_book VALUES (?,?)', (lid, int(name == 'address_book_only')))
            if name in ('address_book_only', 'address_book_nonuser'):
                db.execute('DROP TABLE wa_contacts')
            elif name == 'legacy_only':
                db.execute('DROP TABLE wa_address_book')
    # A synthetic relative-path decoy makes the old null-files-dir fallback observable.
    with sqlite3.connect(root / 'wa.db') as db:
        db.executescript(schema)
        db.execute('INSERT INTO wa_contacts VALUES (?,NULL,?,1)', (phone, 'Other client contact'))
    return root


def run(root, out, allow_failure=False, json_source_dir=None):
    out.mkdir(parents=True, exist_ok=True)
    source = (root / HOOK).read_text()
    custom_source = (root / CUSTOM).read_text()
    sources = out / 'sources'
    classes = out / 'classes'
    classes.mkdir(exist_ok=True)
    cache = Path(json_source_dir or os.environ.get('WAEX_AOSP_JSON_SOURCE_DIR')
                 or Path.home() / '.cache/waex-qa/android-json-14').resolve()
    aosp_sources = json_sources(cache, allow_download=json_source_dir is None
                               and not os.environ.get('WAEX_AOSP_JSON_SOURCE_DIR'))
    for relative, annotation in android_doubles(ROOT).items():
        if not relative.startswith('android/content/'):
            stub(relative, annotation)
    ids = sorted(set(re.findall(r'R\.string\.([A-Za-z0-9_]+)', source + custom_source)))
    stub('com/waenhancer/app/R.java', 'package com.waenhancer.app;public class R {public static class string {'
         + ''.join('public static final int '+key+'='+str(index)+';' for index, key in enumerate(ids, 1)) + '}}')
    stub('com/waenhancer/xposed/features/privacy/CallPrivacyContactsTest.java', TEST)
    for relative, text in STUBS.items():
        path = sources / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text)
    kotlin_source, callback_hashes = custom_privacy_fixture(custom_source)
    kotlin = sources / 'CustomPrivacyHook.kt'
    kotlin.write_text(kotlin_source)
    custom_jar = out / 'custom_privacy_callbacks.jar'
    test_source = sources / 'com/waenhancer/xposed/features/privacy/CallPrivacyContactsTest.java'
    database_root = prepare_databases(out)
    sql_runner = out / 'query_sqlite.py'
    sql_runner.write_text('''import base64, sqlite3, sys
from pathlib import Path
decode=lambda s:base64.b64decode(s).decode('utf-8')
try:
 with sqlite3.connect(Path(sys.argv[1]).resolve().as_uri()+'?mode=ro',uri=True) as db:
  print(int(db.execute(decode(sys.argv[2]),[decode(a) for a in sys.argv[3:]]).fetchone() is not None))
except sqlite3.Error:
 print('SQL_ERROR')
 sys.exit(12)
''')
    commands = [
        ['javac', '--release', '17', '-d', str(classes),
         *map(str, sorted(p for p in sources.rglob('*.java') if p != test_source)),
         *map(str, aosp_sources), str(root / IDENTITY)],
        ['kotlinc', '-jvm-target', '17', '-classpath', str(classes),
         str(kotlin), '-include-runtime', '-d', str(custom_jar)],
        ['javac', '--release', '17', '-classpath', os.pathsep.join(map(str, (classes, custom_jar))),
         '-d', str(classes), str(root / HOOK), str(test_source)],
        ['java', '-Dfixture.python='+sys.executable, '-Dfixture.sql='+str(sql_runner),
         '-cp', os.pathsep.join(map(str, (classes, custom_jar))),
         'com.waenhancer.xposed.features.privacy.CallPrivacyContactsTest', str(database_root)],
    ]
    outcomes = []
    compile_output = []
    for index, command in enumerate(commands):
        result = subprocess.run(command, cwd=out, text=True, stdout=subprocess.PIPE,
                                stderr=subprocess.STDOUT, timeout=90)
        if index < len(commands) - 1:
            compile_output.append(result.stdout)
            (out / 'compile.log').write_text(''.join(compile_output))
        else:
            (out / 'tests.log').write_text(result.stdout)
        outcomes.append(result.returncode)
        print(result.stdout, end='')
        if index < len(commands) - 1 and result.returncode:
            raise RuntimeError('Production call hook/dialog callback compilation failed at stage '+str(index))
    match = re.search(r'^RESULT\|(\d+)\|(\d+)$', result.stdout, re.M)
    if not match:
        raise RuntimeError('No completed fixture result; a startup failure is not a negative control')
    count, failed = map(int, match.groups())
    expected = TEST.count('test("')
    report = {
        'source_root': str(root), 'complete_hook_compiled': True, 'checks': count,
        'failed': failed, 'expected_checks': expected, 'exit_codes': outcomes,
        'source_sha256': {str(p): hashlib.sha256((root / p).read_bytes()).hexdigest() for p in (HOOK, IDENTITY, CUSTOM)},
        'compiled_custom_privacy_fragments': callback_hashes,
        'async_isolation': 'Every intercepted incoming callback awaits its own Context notification endpoint with a bounded latch before another case can reset contact counters; the production executor and callback remain unchanged.',
        'aosp_json_android_14_source_sha256': SHA256,
        'fixture_sha256': hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
        'limits': ('Complete production CallPrivacyHook and ContactIdentityPolicy compiled and executed. '
                   'Exact CustomPrivacyHook reader and Save/Cancel callbacks compiled in Kotlin; '
                   'full native dialog construction is not compiled or rendered. JSON is hash-pinned AOSP Android 14. '
                   'SQLite predicates run read-only against real file-backed Python SQLite. Android Context, '
                   'Xposed registration, DexKit discovery, contact resolution, preference storage, dialog inputs, '
                   'notifications and HTTP are explicit doubles. No real profile Binder, ContactsProvider, '
                   'native DexKit, LSPosed, VoIP call, external message or signed APK build.'),
    }
    (out / 'result.json').write_text(json.dumps(report, indent=2)+'\n')
    print(json.dumps(report, indent=2))
    if count != expected or (not allow_failure and (failed or any(outcomes))):
        raise RuntimeError('Call privacy contact fixture failed')
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source-root', type=Path, default=ROOT)
    parser.add_argument('--out', type=Path)
    parser.add_argument('--json-source-dir', type=Path)
    parser.add_argument('--allow-test-failure', action='store_true')
    args = parser.parse_args()
    if args.out:
        run(args.source_root.resolve(), args.out.resolve(), args.allow_test_failure, args.json_source_dir)
    else:
        with tempfile.TemporaryDirectory(prefix='waex-call-contacts-') as directory:
            run(args.source_root.resolve(), Path(directory), args.allow_test_failure, args.json_source_dir)


if __name__ == '__main__':
    main()
