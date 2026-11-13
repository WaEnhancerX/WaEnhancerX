#!/usr/bin/env python3
"""Build an isolated Android APK that exercises the real recording provider/store.

Build-only is the default. No private licensing implementation, WhatsApp account,
Xposed installation or production signing key is required or simulated. The APK
has its own package, authority, data, local signing key and provider process.
Unrelated service contracts fail explicitly if reached. It does NOT test the
recording hook, microphone, real audio, licensing, or the production APK.

Use --execute-device --serial EXACT --user N to install/run only this probe.
Existing probe installs require --replace-probe and a compatible local key.
The runner never uninstalls, clears data, changes users, or grants permissions.
"""

from pathlib import Path
import argparse
import hashlib
import json
import os
import platform
import re
import shutil
import subprocess
import sys
import time
import uuid
import zipfile

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = "io.github.tkmbrain.waex.callrecordingprobe"
AUTHORITY = PACKAGE + ".provider"
RUN_RECORDS = []
PKG = Path("app/src/main/java/com/waenhancer")
PRODUCTION = [
    PKG / "xposed/bridge/HookProvider.java",
    PKG / "xposed/bridge/CallerScopePolicy.java",
    PKG / "xposed/bridge/ProviderDerivedKeyPolicy.java",
    PKG / "xposed/bridge/PreferenceWriteTypePolicy.java",
    PKG / "calls/RecordingOutputFiles.java",
    PKG / "calls/CallRecordingStore.kt",
    PKG / "config/PreferenceSchema.java",
    PKG / "utils/DeclaredWhatsAppTargets.java",
    PKG / "utils/ContactIdentityPolicy.java",
    PKG / "xposed/core/db/PreservedMessageStore.kt",
]

ACTIVITY = r'''package io.github.tkmbrain.waex.callrecordingprobe;
import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.widget.TextView;
import com.waenhancer.calls.RecordingNameFixture;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

public final class ProbeActivity extends Activity {
  private final JSONArray cases = new JSONArray();
  private String token;
  private File directory;
  private Uri endpoint;
  private int assertions;
  interface Checked { void run() throws Exception; }

  @Override public void onCreate(Bundle state) {
    super.onCreate(state);
    TextView text = new TextView(this); text.setText("WAEX isolated recording-provider probe");
    setContentView(text);
    String supplied = getIntent().getStringExtra("run_id");
    if (supplied == null || !supplied.matches("[a-f0-9]{32}")) { finish(); return; }
    token = supplied;
    new Thread(() -> {
      JSONObject report = new JSONObject();
      long start = android.os.SystemClock.elapsedRealtime();
      try {
        int expectedUser = getIntent().getIntExtra("expected_user", -1);
        check(getPackageName().equals("io.github.tkmbrain.waex.callrecordingprobe"), "wrong package");
        check(android.os.Process.myUid() / 100000 == expectedUser, "wrong Android user");
        endpoint = Uri.parse("content://io.github.tkmbrain.waex.callrecordingprobe.provider");
        File base = getExternalFilesDir(null); if (base == null) base = getFilesDir();
        directory = new File(base, "CallRecordings");
        runCases();
        report.put("status", "PASS");
      } catch (Throwable failure) {
        try { report.put("status", "FAIL").put("error_type", failure.getClass().getName())
          .put("error", String.valueOf(failure.getMessage())); } catch (JSONException ignored) {}
      }
      try {
        report.put("run_id", token).put("package", getPackageName())
          .put("uid", android.os.Process.myUid()).put("user", android.os.Process.myUid()/100000)
          .put("api", android.os.Build.VERSION.SDK_INT).put("cases", cases)
          .put("assertions", assertions).put("duration_ms", android.os.SystemClock.elapsedRealtime()-start)
          .put("limits", "Real Binder/PFD/SQLite in isolated app; no WhatsApp, Xposed, audio capture or private licensing");
        File result = new File(getFilesDir(), "probe-"+token+".json");
        try (FileOutputStream output = new FileOutputStream(result)) {
          output.write(report.toString(2).getBytes(StandardCharsets.UTF_8)); output.getFD().sync();
        }
        final String status = report.optString("status");
        runOnUiThread(() -> text.setText("WAEX provider probe: "+status+" (synthetic data only)"));
      } catch (Throwable failedReport) {
        android.util.Log.e("WaexRecordingProbe", "Could not write synthetic probe result", failedReport);
      }
    }, "WAEX-RecordingProviderProbe").start();
  }

  private void check(boolean result, String message) {
    assertions++; if (!result) throw new AssertionError(message);
  }
  private void test(String name, Checked body) throws Exception {
    long started = android.os.SystemClock.elapsedRealtime();
    try { body.run(); cases.put(new JSONObject().put("name",name).put("status","PASS")
      .put("duration_ms",android.os.SystemClock.elapsedRealtime()-started)); }
    catch (Throwable failure) {
      cases.put(new JSONObject().put("name",name).put("status","FAIL")
        .put("error_type",failure.getClass().getName()).put("error",String.valueOf(failure.getMessage())));
      if(failure instanceof Exception)throw (Exception)failure;
      throw new AssertionError(name+": "+failure.getMessage(),failure);
    }
  }
  private Bundle create(String name, String id) {
    Bundle extras=new Bundle(); extras.putString("name",name);
    if(id!=null){extras.putString("call_id",id);extras.putString("contact_name","Synthetic fixture")
      ;extras.putString("direction","unknown");extras.putString("target_package",getPackageName());
      extras.putLong("started_at",1730000000000L);}
    return getContentResolver().call(endpoint,"create_call_recording",null,extras);
  }
  @SuppressWarnings("deprecation") private ParcelFileDescriptor descriptor(Bundle result) {
    return result==null?null:(ParcelFileDescriptor)result.getParcelable("descriptor");
  }
  private void write(Bundle result, byte[] bytes) throws Exception {
    check(result!=null,"missing provider response"); ParcelFileDescriptor fd=descriptor(result);
    check(fd!=null,"missing file descriptor");
    try(OutputStream output=new ParcelFileDescriptor.AutoCloseOutputStream(fd)){output.write(bytes);output.flush();}
  }
  private byte[] read(File file) throws Exception {
    try(InputStream input=new FileInputStream(file);ByteArrayOutputStream output=new ByteArrayOutputStream()){
      byte[] block=new byte[1024];int count;while((count=input.read(block))!=-1)output.write(block,0,count);return output.toByteArray();}
  }
  private SQLiteDatabase database(boolean write) {
    return SQLiteDatabase.openDatabase(getDatabasePath("call_recordings.db").getAbsolutePath(),null,
      write?SQLiteDatabase.OPEN_READWRITE:SQLiteDatabase.OPEN_READONLY);
  }
  private int rows(String id) {
    try(SQLiteDatabase db=database(false);Cursor c=db.rawQuery("SELECT COUNT(*) FROM call_recordings WHERE call_id=?",new String[]{id})){
      check(c.moveToFirst(),"missing count");return c.getInt(0);}
  }
  private void finishRecording(String id,boolean successful){Bundle b=new Bundle();b.putString("call_id",id);
    b.putLong("ended_at",1730000000999L);b.putBoolean("successful",successful);
    Bundle r=getContentResolver().call(endpoint,"finish_call_recording",null,b);
    check(r!=null&&r.getBoolean("success"),"finish rejected");}
  private int providerPid(){
    ActivityManager manager=(ActivityManager)getSystemService(Context.ACTIVITY_SERVICE);
    List<ActivityManager.RunningAppProcessInfo> processes=manager.getRunningAppProcesses();
    if(processes==null)return -1;
    for(ActivityManager.RunningAppProcessInfo p:processes)if((getPackageName()+":provider").equals(p.processName))return p.pid;
    return -1;
  }
  private int providerFds(){int pid=providerPid();if(pid<0)return -1;
    String[] names=new File("/proc/"+pid+"/fd").list();return names==null?-1:names.length;}
  private void runCases() throws Exception {
    final String first=token+"-first.m4a",firstId=token+"-a",secondId=token+"-b";
    final byte[] original="synthetic previous recording bytes".getBytes(StandardCharsets.UTF_8);
    test("shell_rejection_has_no_file",()->check(!new File(directory,"shell-denied-"+token+".m4a").exists(),"shell created file"));
    test("create_write_finish",()->{
      Bundle result=create(first,firstId);write(result,original);
      check(new File(result.getString("path")).getCanonicalFile().getParentFile().equals(directory.getCanonicalFile()),"wrong output directory");
      check(Arrays.equals(original,read(new File(directory,first))),"first bytes changed");
      check(rows(firstId)==1,"first metadata missing");finishRecording(firstId,true);
    });
    test("provider_uses_separate_process",()->{
      int pid=providerPid();check(pid>0&&pid!=android.os.Process.myPid(),"provider process identity not verified");
    });
    test("exact_duplicate_preserves_audio_and_metadata",()->{
      Bundle duplicate=create(first,secondId);
      if(duplicate!=null){ParcelFileDescriptor fd=descriptor(duplicate);if(fd!=null)fd.close();}
      check(duplicate==null,"duplicate name accepted");check(Arrays.equals(original,read(new File(directory,first))),"duplicate truncated original");
      check(rows(firstId)==1&&rows(secondId)==0,"duplicate changed recording metadata");
    });
    test("same_second_distinct_names",()->{
      String[] names=RecordingNameFixture.names(token);check(!names[0].equals(names[1]),"same-second name reused");
      Bundle a=create(names[0],token+"-same-a"),b=create(names[1],token+"-same-b");
      write(a,new byte[]{11,12,13});write(b,new byte[]{21,22,23});
      check(!a.getString("path").equals(b.getString("path")),"distinct sessions share path");
      check(Arrays.equals(new byte[]{11,12,13},read(new File(a.getString("path")))),"first same-second file changed");
      check(Arrays.equals(new byte[]{21,22,23},read(new File(b.getString("path")))),"second same-second file changed");
      check(rows(token+"-same-a")==1&&rows(token+"-same-b")==1,"same-second records missing");
    });
    test("null_name_uses_default_without_overwriting",()->{
      // Bundle.getString(key, default) uses the default for both absent and null values.
      File defaultFile=new File(directory,"Call.m4a");
      if(defaultFile.exists()){
        // Reset only this runner's recognizable synthetic fixture, never unknown data.
        check(Arrays.equals(new byte[]{27,28},read(defaultFile)),"unknown default file; refusing fixture reset");
        try(SQLiteDatabase db=database(false);Cursor c=db.rawQuery("SELECT call_id,target_package FROM call_recordings WHERE recording_path=?",new String[]{defaultFile.getAbsolutePath()})){
          check(c.moveToFirst()&&c.getString(0).matches("[a-f0-9]{32}-default")&&getPackageName().equals(c.getString(1)),"unknown default metadata; refusing fixture reset");
        }
        Bundle remove=new Bundle();remove.putString("name","Call.m4a");
        Bundle removed=getContentResolver().call(endpoint,"delete_call_recording",null,remove);
        check(removed!=null&&removed.getBoolean("deleted")&&!defaultFile.exists(),"could not reset owned default fixture");
      }
      Bundle r=create(null,token+"-default");write(r,new byte[]{27,28});
      check(defaultFile.getAbsolutePath().equals(r.getString("path")),"null name did not use documented default");
      check(rows(token+"-default")==1,"default filename metadata missing");
      Bundle duplicate=create(null,token+"-default-duplicate");
      if(duplicate!=null&&descriptor(duplicate)!=null)descriptor(duplicate).close();
      check(duplicate==null,"existing default destination accepted");
      check(Arrays.equals(new byte[]{27,28},read(defaultFile)),"default collision changed prior bytes");
      check(rows(token+"-default-duplicate")==0,"default collision indexed");
    });
    test("invalid_names",()->{for(String invalid:new String[]{"",".",".."}){
      Bundle r=create(invalid,token+"-invalid");if(r!=null&&descriptor(r)!=null)descriptor(r).close();check(r==null,"invalid filename accepted");}
      check(rows(token+"-invalid")==0,"invalid name indexed");});
    test("normalized_path_stays_inside_directory",()->{
      Bundle r=create("../"+token+"-outside.m4a",token+"-path");write(r,new byte[]{31});
      check(new File(r.getString("path")).getCanonicalFile().getParentFile().equals(directory.getCanonicalFile()),"path escaped storage");
    });
    test("concurrent_exact_name_has_one_winner",()->{
      int workers=12;ExecutorService pool=Executors.newFixedThreadPool(workers);
      CountDownLatch ready=new CountDownLatch(workers),start=new CountDownLatch(1);
      List<Future<Bundle>> jobs=new ArrayList<>();try{
        for(int i=0;i<workers;i++)jobs.add(pool.submit(()->{ready.countDown();if(!start.await(10,TimeUnit.SECONDS))throw new IllegalStateException("start timeout");return create(token+"-race.m4a",null);}));
        check(ready.await(10,TimeUnit.SECONDS),"workers not ready");start.countDown();int winners=0;
        for(Future<Bundle> job:jobs){Bundle r=job.get(15,TimeUnit.SECONDS);if(r!=null){winners++;write(r,new byte[]{41,42});}}
        check(winners==1,"parallel reservation winner count: "+winners);
      }finally{start.countDown();pool.shutdownNow();}
    });
    test("sqlite_failure_closes_and_removes_reserved_file",()->{
      int before=providerFds();
      try(SQLiteDatabase db=database(true)){db.execSQL("CREATE TRIGGER probe_abort_"+token+" BEFORE INSERT ON call_recordings WHEN NEW.call_id LIKE '"+token+"-dbfail-%' BEGIN SELECT RAISE(ABORT, 'synthetic probe rejection'); END");}
      try{for(int i=0;i<8;i++){
        String name=token+"-dbfail-"+i+".m4a",id=token+"-dbfail-"+i;
        Bundle result=create(name,id);if(result!=null&&descriptor(result)!=null)descriptor(result).close();
        check(result==null,"failed SQL insertion acknowledged");check(!new File(directory,name).exists(),"failed SQL insertion left reserved file");check(rows(id)==0,"failed SQL insertion produced row");
      }}finally{try(SQLiteDatabase db=database(true)){db.execSQL("DROP TRIGGER IF EXISTS probe_abort_"+token);}}
      int after=providerFds();
      if(before>=0&&after>=0)check(after<=before+2,"provider descriptors grew during failed inserts");
      cases.put(new JSONObject().put("name","provider_fd_count_observation").put("status",before>=0&&after>=0?"PASS":"NOT_VERIFIED").put("before",before).put("after",after));
    });
    test("metadata_persists_after_database_reopen",()->{
      try(SQLiteDatabase db=database(false);Cursor c=db.rawQuery("SELECT contact_name,direction,target_package,started_at,ended_at,recording_path,status FROM call_recordings WHERE call_id=?",new String[]{firstId})){
        check(c.moveToFirst(),"persisted row missing");check("Synthetic fixture".equals(c.getString(0)),"contact label changed");
        check("unknown".equals(c.getString(1)),"direction changed");check(getPackageName().equals(c.getString(2)),"target changed");
        check(c.getLong(3)==1730000000000L&&c.getLong(4)==1730000000999L,"timestamps changed");
        check(new File(directory,first).getAbsolutePath().equals(c.getString(5)),"persisted path changed");check("completed".equals(c.getString(6)),"completion status changed");}
    });
    test("delete_only_selected_synthetic_record",()->{
      String name=token+"-delete.m4a",id=token+"-delete";write(create(name,id),new byte[]{51});
      Bundle b=new Bundle();b.putString("name",name);Bundle r=getContentResolver().call(endpoint,"delete_call_recording",null,b);
      check(r!=null&&r.getBoolean("deleted"),"synthetic delete failed");check(!new File(directory,name).exists()&&rows(id)==0,"delete left file or row");
      check(Arrays.equals(original,read(new File(directory,first)))&&rows(firstId)==1,"delete changed original");
    });
    test("unrelated_private_contracts_fail_explicitly",()->{
      boolean license=false,entitlement=false;
      try{com.waenhancer.licensing.LicenseManager.getProStatus(this);}catch(AssertionError expected){license=true;}
      try{com.waenhancer.licensing.ProFeatureGate.isEntitled(this,"outside-fixture");}catch(AssertionError expected){entitlement=true;}
      check(license&&entitlement,"private service double was permissive");
    });
  }
}
'''

NAME_FIXTURE = '''package com.waenhancer.calls;
public final class RecordingNameFixture {
 public static String[] names(String run) {
  java.util.Date sameSecond=new java.util.Date(1730000000000L);
  java.util.UUID seed=java.util.UUID.nameUUIDFromBytes(run.getBytes(java.nio.charset.StandardCharsets.UTF_8));
  return new String[]{RecordingOutputFiles.formatName(sameSecond,seed,".m4a"),
   RecordingOutputFiles.formatName(sameSecond,new java.util.UUID(seed.getMostSignificantBits(),seed.getLeastSignificantBits()^1L),".m4a")};
 }
}'''


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def validate_sources(root):
    missing = [str(p) for p in PRODUCTION if not (root / p).is_file()]
    if missing:
        raise RuntimeError("Missing production input: " + ", ".join(missing))
    provider = (root / PKG / "xposed/bridge/HookProvider.java").read_text(encoding="utf-8")
    if provider.index("isTrustedCaller(context, callerUid)") > provider.index("Binder.clearCallingIdentity()"):
        raise RuntimeError("Provider authorization order changed")
    contracts = {
        "config/PreferenceRestartTracker.java": [r'NEED_RESTART\s*=\s*"need_restart"', r'PENDING_CHANGES\s*=\s*"pending_restart_changes"'],
        "utils/WhatsAppPackageDetector.kt": [r'DeclaredWhatsAppTargets\.packages\(\)', r'DeclaredWhatsAppTargets\.contains\(packageName\)'],
        "core/transcription/TranscriptionRequestHandler.java": [r'METHOD\s*=\s*"transcribe_audio"', r'METHOD_READY\s*=\s*"transcription_ready"', r'METHOD_LOG\s*=\s*"transcription_log"'],
    }
    contract_hashes = {}
    for relative, patterns in contracts.items():
        path = root / PKG / relative
        text = path.read_text(encoding="utf-8")
        if not all(re.search(pattern, text) for pattern in patterns):
            raise RuntimeError("Unrelated-service contract changed: " + relative)
        contract_hashes[str(PKG / relative)] = sha(path)
    return provider, contract_hashes


def service_contracts(provider):
    fixtures = {
        "androidx/annotation/NonNull.java": "package androidx.annotation;public @interface NonNull{}",
        "androidx/annotation/Nullable.java": "package androidx.annotation;public @interface Nullable{}",
        "com/waenhancer/utils/WhatsAppPackageDetector.java": """package com.waenhancer.utils;
public final class WhatsAppPackageDetector {
 public static java.util.Set<String> getSupportedPackages(android.content.Context context){return DeclaredWhatsAppTargets.packages();}
 public static boolean isWhatsAppPackageName(String name){return DeclaredWhatsAppTargets.contains(name);}
 public static boolean isWhatsAppPackageName(android.content.Context context,String name){return isWhatsAppPackageName(name);}
}""",
        "com/waenhancer/config/PreferenceRestartTracker.java": """package com.waenhancer.config;
public final class PreferenceRestartTracker {public static final String NEED_RESTART="need_restart";public static final String PENDING_CHANGES="pending_restart_changes";}""",
        "com/waenhancer/licensing/LicenseManager.java": """package com.waenhancer.licensing;
public class LicenseManager {public static String getProStatus(android.content.Context c){throw new AssertionError("Private licensing is outside this probe");}}""",
        "com/waenhancer/xposed/utils/AppRestartHelper.java": """package com.waenhancer.xposed.utils;
public class AppRestartHelper {public static void restartPackage(android.content.Context c,String p,String n,boolean b){throw new AssertionError("App restart is outside this probe");}}""",
        "com/waenhancer/update/UpdateCheckCoordinator.java": """package com.waenhancer.update;
public class UpdateCheckCoordinator {public static final String TRIGGER_WHATSAPP="outside-probe";public static void request(android.content.Context c,String s,boolean b){throw new AssertionError("Update service is outside this probe");}}""",
        "com/waenhancer/core/transcription/TranscriptionRequestHandler.java": """package com.waenhancer.core.transcription;
public class TranscriptionRequestHandler {
 public static final String METHOD="transcribe_audio",METHOD_READY="transcription_ready",METHOD_LOG="transcription_log";
 public static android.os.Bundle handle(android.content.Context c,android.os.Bundle b){throw new AssertionError("Transcription outside probe");}
 public static android.os.Bundle ready(android.content.Context c){throw new AssertionError("Transcription outside probe");}
 public static android.os.Bundle log(android.content.Context c,android.os.Bundle b){throw new AssertionError("Transcription outside probe");}
}""",
    }
    for type_, package in [("ProFeatureGate", "com.waenhancer.licensing"), ("BootloaderSpooferFeature", "com.waenhancer.licensing.features")]:
        names = sorted(set(re.findall(type_ + r"\.([A-Z][A-Z_]+)", provider)))
        fields = "".join('public static final String ' + name + '="' + name + '";' for name in names)
        method = 'public static boolean isEntitled(android.content.Context c,String s){throw new AssertionError("Private entitlement is outside this probe");}' if type_ == "ProFeatureGate" else ""
        fixtures[package.replace(".", "/") + "/" + type_ + ".java"] = "package " + package + ";public class " + type_ + "{" + fields + method + "}"
    return fixtures


def run(command, log, timeout=120, env=None, allow_failure=False):
    started = time.monotonic()
    result = subprocess.run([str(x) for x in command], capture_output=True, text=True,
                            encoding="utf-8", errors="replace", timeout=timeout, env=env)
    log.write_text(result.stdout + result.stderr, encoding="utf-8")
    RUN_RECORDS.append({"command": [str(x) for x in command], "exit_code": result.returncode,
                        "duration_seconds": round(time.monotonic() - started, 3), "log": log.name})
    print(json.dumps({"step": log.stem, "exit_code": result.returncode,
                      "duration_seconds": round(time.monotonic() - started, 3)}), flush=True)
    if result.returncode and not allow_failure:
        raise RuntimeError(log.stem + " failed; see " + str(log))
    return result


def binary(directory, name):
    path = directory / (name + (".exe" if os.name == "nt" else ""))
    if not path.is_file():
        raise RuntimeError("Missing executable: " + str(path))
    return path


def build(args, out, sources, fixtures, report):
    for arg in ("sdk", "jdk_home", "kotlin_home"):
        if getattr(args, arg) is None:
            raise RuntimeError("Build requires --" + arg.replace("_", "-"))
    sdk, jdk, kotlin = args.sdk.resolve(), args.jdk_home.resolve(), args.kotlin_home.resolve()
    android = sdk / "platforms" / ("android-" + str(args.compile_sdk)) / "android.jar"
    bt = sdk / "build-tools" / args.build_tools
    if not android.is_file():
        raise RuntimeError("Missing SDK platform: " + str(android))
    java, javac, keytool = [binary(jdk / "bin", name) for name in ("java", "javac", "keytool")]
    run([java, "-version"], out / "java-version.log")
    run([javac, "-version"], out / "javac-version.log")
    libs = kotlin / "lib"
    stdlib = libs / "kotlin-stdlib.jar"
    if not stdlib.is_file() or not (libs / "kotlin-compiler.jar").is_file():
        raise RuntimeError("Kotlin distribution needs lib/kotlin-compiler.jar and kotlin-stdlib.jar")
    classes = out / "classes"; classes.mkdir()
    classpath = os.pathsep.join(map(str, (classes, android, stdlib)))
    support = [p for p in sources if p.suffix == ".java" and p.name != "HookProvider.java"] + fixtures
    run([javac, "--release", "17", "-cp", classpath, "-d", classes, *support], out / "javac-support.log")
    compiler_cp = os.pathsep.join(str(p) for p in sorted(libs.glob("*.jar")))
    run([java, "-Dkotlin.home=" + str(kotlin), "-cp", compiler_cp, "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-version"], out / "kotlin-version.log")
    run([java, "-Dkotlin.home=" + str(kotlin), "-cp", compiler_cp, "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-jvm-target", "17",
         "-kotlin-home", kotlin, "-no-stdlib", "-no-reflect", "-classpath", classpath, "-d", classes,
         *[p for p in sources if p.suffix == ".kt"]], out / "kotlinc.log", timeout=180)
    generated = out / "generated"
    run([javac, "--release", "17", "-cp", classpath, "-d", classes,
         *[p for p in sources if p.name == "HookProvider.java"],
         generated / "RecordingNameFixture.java", generated / "ProbeActivity.java"], out / "javac-probe.log")
    jar = out / "production-and-probe.jar"
    with zipfile.ZipFile(jar, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        for path in sorted(classes.rglob("*.class")):
            archive.write(path, path.relative_to(classes).as_posix())
    dex = out / "dex"; dex.mkdir()
    d8 = bt / "lib" / "d8.jar"
    if not d8.is_file():
        raise RuntimeError("Missing D8: " + str(d8))
    run([java, "-cp", d8, "com.android.tools.r8.D8", "--min-api", "24", "--lib", android,
         "--output", dex, jar, stdlib], out / "d8.log", timeout=180)
    unsigned = out / "probe-unsigned.apk"
    run([binary(bt, "aapt2"), "link", "-I", android, "--manifest", generated / "AndroidManifest.xml",
         "--min-sdk-version", "24", "--target-sdk-version", str(args.compile_sdk), "-o", unsigned], out / "aapt2.log")
    with zipfile.ZipFile(unsigned, "a", compression=zipfile.ZIP_DEFLATED) as archive:
        for path in sorted(dex.glob("classes*.dex")):
            archive.write(path, path.name)
    aligned = out / "probe-aligned.apk"
    run([binary(bt, "zipalign"), "-f", "4", unsigned, aligned], out / "zipalign.log")
    env = dict(os.environ)
    key = args.keystore.resolve() if args.keystore else out / "probe-only-signing.p12"
    # This is a disposable local test identity, never a production signing key.
    password = os.environ.get(args.keystore_password_env, "android" if args.keystore is None else "")
    if not password:
        raise RuntimeError("Existing keystore requires environment variable " + args.keystore_password_env)
    env["WAEX_PROBE_SIGN_PASSWORD"] = password
    if args.keystore is None:
        run([keytool, "-genkeypair", "-keystore", key, "-storetype", "PKCS12", "-alias", args.key_alias,
             "-storepass:env", "WAEX_PROBE_SIGN_PASSWORD", "-keypass:env", "WAEX_PROBE_SIGN_PASSWORD",
             "-keyalg", "RSA", "-keysize", "2048", "-validity", "3650", "-dname", "CN=WAEX isolated provider probe"],
            out / "keytool.log", env=env)
    elif not key.is_file():
        raise RuntimeError("Provided local keystore does not exist")
    apk = out / "waex-callrecording-probe.apk"
    signer = bt / "lib" / "apksigner.jar"
    run([java, "-jar", signer, "sign", "--ks", key, "--ks-key-alias", args.key_alias,
         "--ks-pass", "env:WAEX_PROBE_SIGN_PASSWORD", "--key-pass", "env:WAEX_PROBE_SIGN_PASSWORD",
         "--out", apk, aligned], out / "sign.log", env=env)
    run([java, "-jar", signer, "verify", "--verbose", "--print-certs", apk], out / "signature-verification.log")
    report.update({"build_status": "PASS", "apk": str(apk), "apk_sha256": sha(apk),
                   "tool_inputs_sha256": {"android.jar": sha(android), "kotlin-stdlib.jar": sha(stdlib), "d8.jar": sha(d8)},
                   "keystore": str(key), "signature_report": str(out / "signature-verification.log")})
    return apk


def device(args, out, apk, report):
    if not args.serial or args.user is None:
        raise RuntimeError("--execute-device requires --serial and --user")
    if not re.fullmatch(r"[A-Za-z0-9_.:-]+", args.serial) or args.user < 0:
        raise RuntimeError("Invalid explicit device selector")
    adb = binary(args.sdk.resolve() / "platform-tools", "adb")
    prefix = [adb, "-s", args.serial]
    def command(label, arguments, **options):
        return run(prefix + arguments, out / (label + ".log"), **options)
    state = command("device-state", ["get-state"])
    if state.stdout.strip() != "device":
        raise RuntimeError("Selected serial is not online/authorized")
    users = command("device-users", ["shell", "pm", "list", "users"])
    if not re.search(r"UserInfo\{" + str(args.user) + r":", users.stdout):
        raise RuntimeError("Selected Android user does not exist")
    api = command("device-api", ["shell", "getprop", "ro.build.version.sdk"])
    if not api.stdout.strip().isdigit() or int(api.stdout.strip()) < 24:
        raise RuntimeError("Probe requires Android API24 or later")
    installed = command("probe-existing", ["shell", "pm", "list", "packages", "--user", str(args.user), PACKAGE])
    if ("package:" + PACKAGE) in installed.stdout.splitlines() and not args.replace_probe:
        raise RuntimeError("Probe already installed; pass --replace-probe with its compatible local keystore")
    install_args = ["install", "-t", "--user", str(args.user)]
    if args.replace_probe:
        install_args.append("-r")
    installation = command("install-probe", [*install_args, apk], timeout=180)
    if "Success" not in installation.stdout:
        raise RuntimeError("Probe installation was not confirmed")
    def verify_installed_apk(label):
        located = command(label + "-path", ["shell", "pm", "path", "--user", str(args.user), PACKAGE])
        paths = [line[len("package:"):].strip() for line in located.stdout.splitlines() if line.startswith("package:")]
        if len(paths) != 1 or not paths[0].startswith("/data/app/") or not paths[0].endswith("/base.apk"):
            raise RuntimeError("Expected one installed probe base APK, not split or unknown paths")
        checked = command(label + "-sha256", ["shell", "sha256sum", paths[0]])
        installed_sha = checked.stdout.strip().split()[0] if checked.stdout.strip() else ""
        if installed_sha != sha(apk):
            raise RuntimeError("Installed probe APK differs from the signed local artifact")
        return {"path": paths[0], "sha256": installed_sha, "signature_identity": "same bytes as locally verified signed APK"}
    report["installed_apk_before"] = verify_installed_apk("installed-before")
    token = uuid.uuid4().hex
    denial = command("shell-caller-negative", ["shell", "content", "call", "--user", str(args.user),
        "--uri", "content://" + AUTHORITY, "--method", "create_call_recording", "--extra", "name:s:shell-denied-" + token + ".m4a"], allow_failure=True)
    denial_text = denial.stdout + denial.stderr
    denied = "SecurityException" in denial_text and "WAEX provider caller is outside the configured hook scope" in denial_text
    report["shell_caller_negative"] = {"status": "PASS" if denied else "FAIL", "exit_code": denial.returncode,
                                        "expected": "production caller-scope SecurityException"}
    if not denied:
        raise RuntimeError("Foreign shell caller was not rejected by the production scope check")
    launch = command("launch-probe", ["shell", "am", "start", "--user", str(args.user), "-W", "-n", PACKAGE + "/.ProbeActivity",
        "--es", "run_id", token, "--ei", "expected_user", str(args.user)], timeout=45)
    if "Error:" in launch.stdout + launch.stderr:
        raise RuntimeError("Probe activity launch failed")
    expires = time.monotonic() + 75
    result = None
    poll_started = time.monotonic()
    poll_attempts = 0
    read_command = [str(x) for x in prefix + ["shell", "run-as", PACKAGE, "--user", str(args.user),
                                            "cat", "files/probe-" + token + ".json"]]
    while time.monotonic() < expires:
        poll_attempts += 1
        read = subprocess.run(read_command, capture_output=True, text=True,
                              encoding="utf-8", errors="replace", timeout=10)
        if read.returncode == 0:
            try:
                candidate = json.loads(read.stdout)
                if candidate.get("run_id") == token:
                    result = candidate
                    break
            except json.JSONDecodeError:
                pass
        time.sleep(0.5)
    (out / "result-poll.log").write_text(read.stdout + read.stderr, encoding="utf-8")
    RUN_RECORDS.append({"command": read_command, "exit_code": read.returncode, "attempts": poll_attempts,
                        "duration_seconds": round(time.monotonic() - poll_started, 3), "log": "result-poll.log"})
    if result is None:
        raise RuntimeError("No completed result from exact probe run within 75 seconds")
    (out / "device-result.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    report["device"] = {"serial": args.serial, "user": args.user, "api": int(api.stdout.strip()), "run_id": token,
                        "result": result, "status": result.get("status")}
    report["installed_apk_after"] = verify_installed_apk("installed-after")
    if result.get("user") != args.user or result.get("package") != PACKAGE or result.get("status") != "PASS":
        raise RuntimeError("Real-device provider acceptance failed; see device-result.json")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-root", type=Path, default=ROOT)
    parser.add_argument("--out", type=Path)
    parser.add_argument("--sdk", type=Path)
    parser.add_argument("--jdk-home", type=Path)
    parser.add_argument("--kotlin-home", type=Path)
    parser.add_argument("--compile-sdk", type=int, default=36)
    parser.add_argument("--build-tools", default="36.0.0")
    parser.add_argument("--keystore", type=Path)
    parser.add_argument("--key-alias", default="waex-recording-probe")
    parser.add_argument("--keystore-password-env", default="WAEX_PROBE_KEYSTORE_PASSWORD")
    parser.add_argument("--validate-sources", action="store_true")
    parser.add_argument("--generate-only", action="store_true")
    parser.add_argument("--execute-device", action="store_true")
    parser.add_argument("--serial")
    parser.add_argument("--user", type=int)
    parser.add_argument("--replace-probe", action="store_true")
    args = parser.parse_args()
    if args.compile_sdk < 24 or not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+(?:[-.A-Za-z0-9]*)", args.build_tools):
        parser.error("Use an installed SDK >=24 and a build-tools version directory")
    if args.validate_sources and (args.execute_device or args.generate_only):
        parser.error("--validate-sources cannot be combined with generation/device execution")
    if args.execute_device and args.generate_only:
        parser.error("--generate-only cannot execute a device")
    if args.execute_device and (not args.serial or args.user is None):
        parser.error("--execute-device requires exact --serial and --user")
    root = args.source_root.resolve()
    provider, contracts = validate_sources(root)
    if args.validate_sources:
        print(json.dumps({"status": "PASS", "production_files": len(PRODUCTION), "scope": "source prerequisites only"}))
        return
    if args.out is None:
        parser.error("--out is required unless --validate-sources is used")
    out = args.out.resolve()
    out.mkdir(parents=True, exist_ok=False)
    report = {"status": "IN_PROGRESS", "package": PACKAGE, "authority": AUTHORITY,
              "production_sha256": {}, "compile_only_contract_sha256": contracts,
              "runner_sha256": sha(Path(__file__)), "host_os": platform.system(),
              "host_arch": platform.machine(), "python": platform.python_version(),
              "limits": "Isolated Android provider/storage test, not WAEX APK/WhatsApp/audio/licensing acceptance"}
    try:
        sources = []
        for relative in PRODUCTION:
            original = root / relative
            destination = out / "production" / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(original, destination)
            if sha(original) != sha(destination):
                raise RuntimeError("Production copy identity mismatch")
            report["production_sha256"][str(relative)] = sha(original)
            sources.append(destination)
        fixtures = []
        report["service_fixture_sha256"] = {}
        for relative, text in service_contracts(provider).items():
            path = out / "contracts" / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(text, encoding="utf-8")
            fixtures.append(path)
            report["service_fixture_sha256"][relative] = sha(path)
        generated = out / "generated"; generated.mkdir()
        (generated / "ProbeActivity.java").write_text(ACTIVITY, encoding="utf-8")
        (generated / "RecordingNameFixture.java").write_text(NAME_FIXTURE, encoding="utf-8")
        manifest = f'''<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="{PACKAGE}" android:versionCode="1" android:versionName="lab-only">
<uses-sdk android:minSdkVersion="24" android:targetSdkVersion="{args.compile_sdk}"/>
<application android:debuggable="true" android:testOnly="true" android:allowBackup="false" android:label="WAEX recording provider probe" android:theme="@android:style/Theme.Material.Light.NoActionBar">
<provider android:name="com.waenhancer.xposed.bridge.HookProvider" android:authorities="{AUTHORITY}" android:exported="true" android:process=":provider"/>
<activity android:name="{PACKAGE}.ProbeActivity" android:exported="true"/>
</application></manifest>'''
        (generated / "AndroidManifest.xml").write_text(manifest, encoding="utf-8")
        report["generated_sha256"] = {p.name: sha(p) for p in sorted(generated.iterdir())}
        if args.generate_only:
            report["status"] = "GENERATED_NOT_BUILT"
        else:
            apk = build(args, out, sources, fixtures, report)
            if args.execute_device:
                device(args, out, apk, report)
                report["status"] = "PASS"
            else:
                report["status"] = "BUILD_PASS_DEVICE_NOT_EXECUTED"
    except Exception as failure:
        report["status"] = "FAIL"
        report["error_type"] = type(failure).__name__
        report["error"] = str(failure)
        raise
    finally:
        report["commands"] = RUN_RECORDS
        (out / "result.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
        print(json.dumps({"status": report["status"], "evidence": str(out / "result.json")}), flush=True)


if __name__ == "__main__":
    main()
