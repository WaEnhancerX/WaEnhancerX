#!/usr/bin/env python3
"""Compile real Kotlin SettingsBackupCodec and round-trip every exportable preference.

The tests use small Android and org.json compile stubs; no native APK, SDK,
private licensing code, network, or actual Android device is involved.
Requires JDK17+, kotlinc and the Kotlin runner on PATH.
"""
import subprocess
import tempfile
import os
import shutil
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / 'app/src/main/java/com/waenhancer'
STUBS = {
    'android/content/Context.java': '''package android.content;
public abstract class Context {
 public static final int MODE_PRIVATE = 0;
 public abstract SharedPreferences getSharedPreferences(String name, int mode);
 // Keep external Android filesystem APIs explicit. The backup fixture never
 // needs them; fail if production starts using these paths during a test.
 public android.content.pm.ApplicationInfo getApplicationInfo(){
  throw new UnsupportedOperationException("Unexpected Context.getApplicationInfo");
 }
 public java.io.File getFilesDir(){
  throw new UnsupportedOperationException("Unexpected Context.getFilesDir");
 }
}''',
    'android/content/pm/ApplicationInfo.java': '''package android.content.pm;
public final class ApplicationInfo { public String dataDir; }''',
    'android/content/SharedPreferences.java': '''package android.content;
import java.util.Map; import java.util.Set;
public interface SharedPreferences {
 Map<String, ?> getAll(); Editor edit();
 interface Editor {
  Editor putBoolean(String key, boolean value);
  Editor putString(String key, String value);
  Editor putStringSet(String key, Set<String> value);
  Editor putInt(String key, int value);
  Editor putLong(String key, long value);
  Editor putFloat(String key, float value);
  Editor remove(String key);
  boolean commit();
 }
}''',
    'android/content/TestContext.java': '''package android.content;
import java.util.*;
public final class TestContext extends Context {
 private final Map<String,Prefs> stores = new HashMap<>();
 public TestContext(){stores.put("waex_prefs",new Prefs());stores.put("private_config",new Prefs());}
 @Override public SharedPreferences getSharedPreferences(String name,int mode){return stores.computeIfAbsent(name,k->new Prefs());}
 public void seed(String store,String key,Object v){stores.get(store).data.put(key,v);}
 public Object get(String store,String key){return stores.get(store).data.get(key);}
 public int count(){return stores.get("waex_prefs").data.size()+stores.get("private_config").data.size();}
 public void failCommits(String store,int count,boolean writeBeforeFailure){
  Prefs prefs=stores.get(store);
  if(prefs==null) throw new IllegalArgumentException("Unknown store");
  prefs.failCount=count; prefs.partialFailure=writeBeforeFailure;
 }
 static final class Prefs implements SharedPreferences {
  final Map<String,Object> data = new HashMap<>();
  int failCount; boolean partialFailure;
  @Override public Map<String,?> getAll(){return new HashMap<>(data);}
  @Override public Editor edit(){return new E();}
  private final class E implements Editor {
   final Map<String,Object> edits = new HashMap<>();
   final Set<String> removed = new HashSet<>();
   public Editor putBoolean(String k,boolean v){removed.remove(k);edits.put(k,v);return this;}
   public Editor putString(String k,String v){removed.remove(k);edits.put(k,v);return this;}
   public Editor putStringSet(String k,Set<String> v){removed.remove(k);edits.put(k,new HashSet<>(v));return this;}
   public Editor putInt(String k,int v){removed.remove(k);edits.put(k,v);return this;}
   public Editor putLong(String k,long v){removed.remove(k);edits.put(k,v);return this;}
   public Editor putFloat(String k,float v){removed.remove(k);edits.put(k,v);return this;}
   public Editor remove(String k){edits.remove(k);removed.add(k);return this;}
   public boolean commit(){
    boolean failed=failCount>0;
    if(failed) failCount--;
    if(!failed || partialFailure){
      for(String k:removed)data.remove(k);
      data.putAll(edits);
    }
    return !failed;
   }
  }
 }
}''',
    'org/json/JSONTokener.java': '''package org.json;
public final class JSONTokener {
 private final String src;
 public JSONTokener(String src){this.src=src;}
 public Object nextValue(){return new JSONObject(src);}
 public char nextClean(){return 0;}
}''',
    'org/json/JSONException.java': '''package org.json;
public final class JSONException extends RuntimeException {
 public JSONException(String text){super(text);}
}''',
    'org/json/JSONArray.java': '''package org.json;
public final class JSONArray {
 public int length(){return 0;}
 public Object opt(int i){return null;}
 public String optString(int i){return "";}
}''',
    'org/json/JSONObject.java': '''package org.json;
import java.util.*;
public final class JSONObject {
 public static final Object NULL = new Object();
 public int length(){return data.size();}
 // Deliberately a miniature ordered fixture, NOT a general JSON parser.
 // It only tests key reconciliation inside the real Kotlin codec.
 private final Map<String,Object> data = new LinkedHashMap<>();
 public JSONObject(String src){
  if(!src.startsWith("LEGACY_TEST:")) throw new JSONException("Unsupported JSON fixture");
  String body=src.substring("LEGACY_TEST:".length());
  if(body.isEmpty()) return;
  for(String part:body.split(";")){
   int eq=part.indexOf('=');
   if(eq<1) throw new JSONException("Malformed fixture entry");
   String value=part.substring(eq+1);
   data.put(part.substring(0,eq),"true".equals(value) ? Boolean.TRUE :
      "false".equals(value) ? Boolean.FALSE : value);
  }
 }
 public boolean has(String key){return data.containsKey(key);}
 public JSONObject optJSONObject(String key){return null;}
 public Object opt(String key){return data.get(key);}
 public String optString(String key,String fallback){return data.containsKey(key)?String.valueOf(data.get(key)):fallback;}
 public Iterator<String> keys(){return data.keySet().iterator();}
}''',
}
KOTLIN_TEST = '''package com.waenhancer.core.preferences
import android.content.TestContext
import com.waenhancer.config.PreferenceSchema
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

private fun store(e: PreferenceSchema.Entry) = if(e.store == PreferenceSchema.Store.PRIVATE) "private_config" else "waex_prefs"
private fun sample(e: PreferenceSchema.Entry,i:Int): Any = when(e.type){
 PreferenceSchema.Type.BOOLEAN -> i%2==0
 PreferenceSchema.Type.STRING -> "text_"+i
 PreferenceSchema.Type.STRING_SET -> setOf("key_"+i,"second_"+i)
 PreferenceSchema.Type.INT -> 1000+i
 PreferenceSchema.Type.LONG -> 5000000000L+i
 PreferenceSchema.Type.FLOAT -> i+0.25f
}
fun main(){
 // Reflect the actual private codec resolver rather than emulating migrations.
 val resolveMethod=SettingsBackupCodec::class.java.declaredMethods.single {
   it.name == "resolveKey" && it.parameterCount == 2
 }
 resolveMethod.isAccessible = true
 fun resolve(raw:String,legacy:Boolean):String? =
   resolveMethod.invoke(SettingsBackupCodec,raw,legacy) as String?
 val currentKeys=listOf(
   "typing_online_toasts", "typing_privacy", "hide_seen_receipts", "hidereceipt",
   "hide_delivery_receipts", "hide_read_receipts", "stealth_status_view",
   "separate_groups_tabs", "bubble_colors", "channels_enhancements", "anti_revoke"
 )
 for(key in currentKeys) {
   check(PreferenceSchema.entry(key)!=null){"Missing current schema key: $key"}
   check(resolve(key,true)==key){"Legacy JSON redirected a live preference: $key"}
   check(resolve(key,false)==key){"Versioned/binary backup redirected a live key: $key"}
 }
 val trueLegacy=mapOf(
   "custom_theme" to "custom_css",
   "custom_time_format" to "segundos",
   "custom_view_dpi" to "change_dpi",
   "hide_ui_tabs" to "hidetabs",
   "chat_filters" to "chatfilter",
   "quick_home_menu" to "show_home_menu"
 )
 for((raw,mapped) in trueLegacy) {
   check(PreferenceSchema.entry(raw)==null){"Expected historical unknown alias: $raw"}
   check(resolve(raw,true)==mapped){"Old JSON alias not migrated: $raw"}
   check(resolve(raw,false)==null){"Unknown key accepted by versioned/binary backup: $raw"}
 }
 check(resolve("a_totally_unknown_setting",true)==null)
 check(resolve("a_totally_unknown_setting",false)==null)
 println("PASS: live Boolean privacy settings retained in legacy JSON; unknown old aliases migrated safely")
 val initial=TestContext()
 val expected=LinkedHashMap<String,Any>()
 PreferenceSchema.exportableKeys().sorted().forEachIndexed { i,key ->
  val schema=PreferenceSchema.entry(key)!!
  val value=sample(schema,i)
  initial.seed(store(schema),key,value)
  expected[key]=value
 }
 val bytes=ByteArrayOutputStream()
 val written=SettingsBackupCodec.encode(initial,bytes)
 check(written==expected.size){"not all settings exported"}
 val restored=TestContext()
 val count=SettingsBackupCodec.decode(restored, ByteArrayInputStream(bytes.toByteArray()))
 check(count==written){"current binary keys silently rewritten/dropped: $count/$written"}
 expected.forEach { (key,value) ->
  val schema=PreferenceSchema.entry(key)!!
  check(restored.get(store(schema),key)==value){"wrong restored key/type/value: $key"}
 }
 check(restored.count()==expected.size){"colliding aliases altered restored key count"}
 // Older WAEX Privacy UI stored this INT preference as #RRGGBB / #AARRGGBB.
 val legacyRgb=TestContext()
 legacyRgb.seed("waex_prefs", "deleted_message_color", "#335577")
 val legacyBytes=ByteArrayOutputStream()
 check(SettingsBackupCodec.encode(legacyRgb,legacyBytes)==1){"RGB color lost from binary backup"}
 val migratedRgb=TestContext()
 check(SettingsBackupCodec.decode(migratedRgb,ByteArrayInputStream(legacyBytes.toByteArray()))==1)
 check(migratedRgb.get("waex_prefs","deleted_message_color")==0xFF335577.toInt()){"RGB not converted to opaque ARGB"}
 val legacyArgb=TestContext()
 legacyArgb.seed("waex_prefs", "deleted_message_color", "#80223344")
 val alphaBytes=ByteArrayOutputStream()
 check(SettingsBackupCodec.encode(legacyArgb,alphaBytes)==1){"ARGB color lost from backup"}
 val migratedArgb=TestContext()
 check(SettingsBackupCodec.decode(migratedArgb,ByteArrayInputStream(alphaBytes.toByteArray()))==1)
 check(migratedArgb.get("waex_prefs","deleted_message_color")==0x80223344.toInt()){"ARGB transparency lost"}
 val invalidColor=TestContext()
 invalidColor.seed("waex_prefs","deleted_message_color","#XYZXYZ")
 check(SettingsBackupCodec.encode(invalidColor,ByteArrayOutputStream())==0){"Malformed color unexpectedly exported"}
 // Minimal deterministic JSON fixture: canonical keys win regardless of
 // iteration order. Android's real org.json parser is NOT under test here.
 for (payload in listOf(
   "LEGACY_TEST:custom_theme=obsolete;custom_css=current",
   "LEGACY_TEST:custom_css=current;custom_theme=obsolete"
 )) {
   val target=TestContext()
   check(SettingsBackupCodec.decode(target, ByteArrayInputStream(payload.toByteArray()))==1)
   check(target.get("waex_prefs","custom_css")=="current"){"Alias overwrote canonical JSON value"}
 }
 val oldAlias=TestContext()
 check(SettingsBackupCodec.decode(oldAlias,
     ByteArrayInputStream("LEGACY_TEST:custom_theme=legacy_css".toByteArray()))==1)
 check(oldAlias.get("waex_prefs","custom_css")=="legacy_css"){"Unknown legacy alias not migrated"}
 for (fixture in listOf(
   "LEGACY_TEST:typing_online_toasts=true;typing_privacy=false",
   "LEGACY_TEST:typing_privacy=false;typing_online_toasts=true"
 )) {
   val target=TestContext()
   check(SettingsBackupCodec.decode(target, ByteArrayInputStream(fixture.toByteArray()))==2)
   check(target.get("waex_prefs","typing_online_toasts")==true){"Online toast unexpectedly lost"}
   check(target.get("waex_prefs","typing_privacy")==false){"Typing suppression unexpectedly enabled"}
 }
 for (fixture in listOf(
   "LEGACY_TEST:hide_seen_receipts=true;hidereceipt=false",
   "LEGACY_TEST:hidereceipt=false;hide_seen_receipts=true"
 )) {
   val target=TestContext()
   check(SettingsBackupCodec.decode(target, ByteArrayInputStream(fixture.toByteArray()))==2)
   check(target.get("waex_prefs","hide_seen_receipts")==true){"Audio read privacy lost"}
   check(target.get("waex_prefs","hidereceipt")==false){"Delivery suppression unexpectedly enabled"}
 }
 // Simulated SharedPreferences.commit() failures in either file, including
 // writes that happen before commit reports false. Compensation must restore
 // previous values, preserve unrelated keys and original runtime value types.
 val mixed=TestContext()
 mixed.seed("private_config","downgrades_enabled",true)
 mixed.seed("waex_prefs","always_online",true)
 val mixedOut=ByteArrayOutputStream()
 check(SettingsBackupCodec.encode(mixed,mixedOut)==2){"Missing mixed private/public export fixture"}
 val mixedBytes=mixedOut.toByteArray()
 for(store in listOf("private_config","waex_prefs")) {
   for(partial in listOf(false,true)) {
     val target=TestContext()
     target.seed("private_config","downgrades_enabled",false)
     target.seed("waex_prefs","always_online",false)
     target.seed("waex_prefs","other_unrelated_key","keep_me")
     target.failCommits(store,1,partial)
     val error=runCatching {
       SettingsBackupCodec.decode(target,ByteArrayInputStream(mixedBytes))
     }.exceptionOrNull()
     check(error!=null){"Failed commit was reported successful"}
     check(target.get("private_config","downgrades_enabled")==false){"Private settings remained partially imported"}
     check(target.get("waex_prefs","always_online")==false){"Public settings remained partially imported"}
     check(target.get("waex_prefs","other_unrelated_key")=="keep_me"){"Unrelated setting overwritten by rollback"}
   }
 }
 val untouched=TestContext()
 untouched.failCommits("waex_prefs",1,true)
 check(runCatching {
   SettingsBackupCodec.decode(untouched,ByteArrayInputStream(mixedBytes))
 }.isFailure)
 check(untouched.get("private_config","downgrades_enabled")==null){"Private key was not removed by rollback"}
 check(untouched.get("waex_prefs","always_online")==null){"Public key was not removed by rollback"}
 val legacyTyped=TestContext()
 legacyTyped.seed("private_config","downgrades_enabled",false)
 legacyTyped.seed("waex_prefs","always_online","legacy_bool_string")
 legacyTyped.failCommits("waex_prefs",1,true)
 check(runCatching {
   SettingsBackupCodec.decode(legacyTyped,ByteArrayInputStream(mixedBytes))
 }.isFailure)
 check(legacyTyped.get("waex_prefs","always_online")=="legacy_bool_string"){"Legacy original type was not restored"}
 val clean=TestContext()
 check(SettingsBackupCodec.decode(clean,ByteArrayInputStream(mixedBytes))==2)
 check(clean.get("private_config","downgrades_enabled")==true)
 check(clean.get("waex_prefs","always_online")==true)
 val cannotRollback=TestContext()
 cannotRollback.failCommits("private_config",2,true)
 val incomplete=runCatching {
   SettingsBackupCodec.decode(cannotRollback,ByteArrayInputStream(mixedBytes))
 }.exceptionOrNull()
 check(incomplete!=null && incomplete.message?.contains("rollback could not")==true){
   "Incomplete compensation was not surfaced explicitly"
 }
 // The import must reject more than 8 MiB BEFORE materializing the
 // entire untrusted InputStream. This synthetic stream advertises >9 MiB but
 // must not be read past the first 8 MiB + one detection byte.
 val excessive = object : java.io.InputStream() {
   var served = 0
   var closed = false
   private val total = 9 * 1024 * 1024
   override fun read(): Int {
     if (served >= total) return -1
     served++
     return 65
   }
   override fun read(b:ByteArray, off:Int, len:Int): Int {
     if (served >= total) return -1
     val n=minOf(len,total-served)
     java.util.Arrays.fill(b,off,off+n,65.toByte())
     served+=n
     return n
   }
   override fun close() { closed=true }
 }
 val overError=runCatching {
   SettingsBackupCodec.decode(TestContext(),excessive)
 }.exceptionOrNull()
 check(overError is IllegalArgumentException &&
       overError.message?.contains("size limit")==true){"Oversized backup was not blocked"}
 check(excessive.served==8*1024*1024+1){"Read past first overflow byte"}
 check(excessive.closed){"Oversized input was not closed"}
 // Exactly 8 MiB is permitted by the length preflight. Parsing invalid
 // contents can still fail, but *not* for exceeding the input-size limit.
 val atLimit=ByteArray(8*1024*1024)
 val limitErr=runCatching {
   SettingsBackupCodec.decode(TestContext(),ByteArrayInputStream(atLimit))
 }.exceptionOrNull()
 check(limitErr!=null && limitErr.message?.contains("size limit")!=true){
   "Exactly MAX_BYTES was rejected for the wrong reason"
 }
 // read(byte[], ...) may legally return zero; progress via read() must work.
 val slow=object : java.io.InputStream() {
   var first=true
   private val delegate=ByteArrayInputStream("LEGACY_TEST:always_online=true".toByteArray())
   override fun read():Int=delegate.read()
   override fun read(b:ByteArray,off:Int,len:Int):Int {
     if(first){ first=false; return 0 }
     return delegate.read(b,off,len)
   }
   override fun close(){delegate.close()}
 }
 val slowTarget=TestContext()
 check(SettingsBackupCodec.decode(slowTarget,slow)==1)
 check(slowTarget.get("waex_prefs","always_online")==true)
 println("PASS: streaming 8 MiB import bound, first overflow byte, close on failure and zero-progress reads")
 println("PASS: mixed-store import, four failure permutations, typed rollback and incomplete-rollback reporting")
 println("PASS: legacy JSON canonical precedence, alias fallback, typing/delivery privacy independence")
 println("PASS: legacy RGB/ARGB colors restored using the INT preference schema")
 println("PASS: $written current settings preserved by key/type/value in binary round-trip")
}
'''

with tempfile.TemporaryDirectory(prefix='waex_backup_binary_') as workspace:
    # Windows Python CreateProcess does not resolve a bare `kotlinc` to the
    # installed .bat wrapper; use the actual executable path on every host.
    commands = {tool: shutil.which(tool) for tool in ('javac', 'kotlinc', 'java')}
    if not all(commands.values()):
        raise FileNotFoundError(f'Missing JVM tools: {[k for k, v in commands.items() if not v]}')
    kotlin_lib = Path(commands['kotlinc']).parent.parent / 'lib'
    stdlib = kotlin_lib / 'kotlin-stdlib.jar'
    if not stdlib.is_file():
        raise FileNotFoundError(f'Missing compiler-matched Kotlin standard library: {stdlib}')
    root = Path(workspace)
    files = []
    for name, code in STUBS.items():
        path = root / 'sources' / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(code, encoding='utf-8')
        files.append(str(path))
    out_java, out_kotlin = root / 'java', root / 'kotlin'
    subprocess.run([commands['javac'],'--release','17','-d',str(out_java),*files,
                    str(SRC/'config/PreferenceSchema.java'),
                    str(SRC/'config/PreferenceStores.java')],check=True)
    test_kotlin=root/'TestBinaryRoundTrip.kt'
    test_kotlin.write_text(KOTLIN_TEST,encoding='utf-8')
    subprocess.run([commands['kotlinc'],'-jvm-target','17','-cp',str(out_java),'-d',str(out_kotlin),
                    str(SRC/'core/preferences/SettingsBackupCodec.kt'),str(test_kotlin)],check=True)
    subprocess.run([commands['java'],'-cp',os.pathsep.join((str(out_java),str(out_kotlin),str(stdlib))),
                    'com.waenhancer.core.preferences.TestBinaryRoundTripKt'],check=True)
