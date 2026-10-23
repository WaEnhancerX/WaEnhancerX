"""Explicit non-Android boundaries for complete MediaPreviewHook JVM execution.

Android Dialog/View/Handler/VideoView implementations run through Robolectric.
This fixture models HTTP transport, host DB discovery, Xposed registration and
the text/tint adapters. No WhatsApp database or external network is accessed.
"""
STUBS = {
'com/waenhancer/utils/UiText.java': '''package com.waenhancer.utils;
public final class UiText {
 public static String get(int id,String fallback,Object...args) {
  return args.length==0 ? fallback : String.format(java.util.Locale.US,fallback,args);
 }
}''',
'androidx/core/graphics/drawable/DrawableCompat.java': '''package androidx.core.graphics.drawable;
public final class DrawableCompat {
 public static void setTint(android.graphics.drawable.Drawable d,int c){d.setTint(c);}
}''',
'com/waenhancer/xposed/core/db/MessageStore.java': '''package com.waenhancer.xposed.core.db;
public final class MessageStore {
 private static final MessageStore INSTANCE=new MessageStore();
 public static MessageStore getInstance(android.content.Context c){return INSTANCE;}
 public android.database.sqlite.SQLiteDatabase getDatabase(){return null;}
 public long getIdFromKey(String key){return 0L;}
}''',
'com/waenhancer/xposed/core/devkit/DexSearchEngine.java': '''package com.waenhancer.xposed.core.devkit;
public final class DexSearchEngine {
 public interface ClassQuery {Class<?> find(org.luckypray.dexkit.DexKitBridge bridge,ClassLoader loader);}
 public static DexSearchEngine getInstance(){return new DexSearchEngine();}
 public Class<?> findClassWithCache(android.content.Context c,ClassLoader loader,String key,ClassQuery query){return null;}
}''',
'de/robv/android/xposed/XC_MethodHook.java': '''package de.robv.android.xposed;
public class XC_MethodHook {
 public static class MethodHookParam {public Object[] args=new Object[0];public Object thisObject;}
 protected void afterHookedMethod(MethodHookParam param) throws Throwable {}
}''',
'de/robv/android/xposed/XposedBridge.java': '''package de.robv.android.xposed;
public final class XposedBridge {
 public static void log(String message){}
 public static void hookAllConstructors(Class<?> type,XC_MethodHook callback){throw new AssertionError("No host injection in fixture");}
 public static void hookAllMethods(Class<?> type,String name,XC_MethodHook callback){throw new AssertionError("No host injection in fixture");}
}''',
'de/robv/android/xposed/XposedHelpers.java': '''package de.robv.android.xposed;
public final class XposedHelpers {
 public static Object callMethod(Object target,String name,Object...args){throw new AssertionError("No host message in fixture");}
}''',
'okhttp3/Call.java': '''package okhttp3;
public interface Call { Response execute() throws java.io.IOException; void cancel(); boolean isCanceled(); }
''',
'okhttp3/Request.java': '''package okhttp3;
public final class Request {
 public final String url;
 public Request(String url){this.url=url;}
 public Builder newBuilder(){return new Builder().url(url);}
 public static final class Builder {
  private String url;
  public Builder url(String value){url=value;return this;}
  public Builder addHeader(String name,String value){return this;}
  public Request build(){return new Request(url);}
 }
}''',
'okhttp3/Interceptor.java': '''package okhttp3;
public interface Interceptor {
 Response intercept(Chain chain) throws java.io.IOException;
 interface Chain {Request request();Response proceed(Request request) throws java.io.IOException;}
}''',
'okhttp3/Response.java': '''package okhttp3;
public final class Response implements java.io.Closeable {
 private final ResponseBody body;
 public Response(ResponseBody body){this.body=body;}
 public boolean isSuccessful(){return true;}
 public int getCode(){return 200;}
 public ResponseBody getBody(){return body;}
 public void close() throws java.io.IOException {body.byteStream().close();}
}''',
'okhttp3/ResponseBody.java': '''package okhttp3;
public final class ResponseBody {
 private final java.io.InputStream stream; private final long length;
 public ResponseBody(java.io.InputStream stream,long length){this.stream=stream;this.length=length;}
 public long contentLength(){return length;}
 public java.io.InputStream byteStream(){return stream;}
}''',
'okhttp3/OkHttpClient.java': '''package okhttp3;
public final class OkHttpClient {
 public Call newCall(Request request){return FixtureNetwork.create(request);}
 public static final class Builder {
  public Builder addInterceptor(Interceptor interceptor){return this;}
  public OkHttpClient build(){return new OkHttpClient();}
 }
}''',
'okhttp3/FixtureNetwork.java': '''package okhttp3;
import java.io.*;import java.util.*;import java.util.concurrent.*;
public final class FixtureNetwork {
 public static final Map<String,Scenario> scenarios=new ConcurrentHashMap<>();
 public static final List<FixtureCall> calls=new CopyOnWriteArrayList<>();
 public static final byte[] PNG=java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jZ9kAAAAASUVORK5CYII=");
 public static final class Scenario {
  public final CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1),created=new CountDownLatch(1),releaseCreation=new CountDownLatch(1);
  public volatile boolean block,fail,ignoreCancellation,blockCreation;
  public byte[] bytes=PNG;
  public Scenario(boolean block,boolean fail){this.block=block;this.fail=fail;}
 }
 public static final class FixtureCall implements Call {
  public final Request request;public final Scenario scenario;
  public volatile boolean cancelled,closed;
  FixtureCall(Request request,Scenario scenario){this.request=request;this.scenario=scenario;}
  public Response execute() throws IOException {
   scenario.entered.countDown();
   while(scenario.block && scenario.release.getCount()!=0){
    try{scenario.release.await(100,TimeUnit.MILLISECONDS);}catch(InterruptedException ignored){}
    if(cancelled && !scenario.ignoreCancellation)throw new IOException("fixture cancelled");
   }
   if(scenario.fail)throw new IOException("fixture delayed failure");
   if(cancelled && !scenario.ignoreCancellation)throw new IOException("fixture cancelled");
   InputStream stream=new ByteArrayInputStream(scenario.bytes){public void close()throws IOException{closed=true;super.close();}};
   return new Response(new ResponseBody(stream,scenario.bytes.length));
  }
  public void cancel(){cancelled=true;if(!scenario.ignoreCancellation)scenario.release.countDown();}
  public boolean isCanceled(){return cancelled;}
 }
 public static FixtureCall create(Request request){
  Scenario scenario=scenarios.get(request.url);
  if(scenario==null)throw new AssertionError("Unconfigured fixture URL");
  FixtureCall call=new FixtureCall(request,scenario);calls.add(call);scenario.created.countDown();
  while(scenario.blockCreation && scenario.releaseCreation.getCount()!=0){try{scenario.releaseCreation.await(100,TimeUnit.MILLISECONDS);}catch(InterruptedException ignored){}}
  return call;
 }
 public static void reset(){for(Scenario s:scenarios.values()){s.release.countDown();s.releaseCreation.countDown();}scenarios.clear();calls.clear();}
}
''',
}
