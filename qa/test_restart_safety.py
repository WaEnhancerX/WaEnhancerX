#!/usr/bin/env python3
"""JDK-only regression for supported restart targets, root subprocess safety, and dashboard calls.

Compiles production helper classes with small Android/Xposed substitutes; requires
Python 3, JDK 17+, and a POSIX shell (sh, yes, head, sleep). It does not execute
su, restart WhatsApp, install an APK, or test a real Android/LSPosed device.
"""
import subprocess
import tempfile
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / 'app/src/main/java/com/waenhancer/xposed/utils'
QA = ROOT / 'qa'
SCOPE_XML = ROOT / 'app/src/main/res/values/arrays.xml'
# The real repo's declared Xposed scope drives tests, not a hardcoded fake list.
xml = ET.parse(SCOPE_XML).getroot()
scope = [item.text.strip() for arr in xml.findall('string-array')
         if arr.get('name') == 'xposed_scope'
         for item in arr.findall('item') if item.text and item.text.strip()]
assert len(scope) > 10 and 'com.whatsapp' in scope and 'com.whatsapp.w4b' in scope

with tempfile.TemporaryDirectory(prefix='waex_root_runner_') as output:
    sources = [ROOT/'app/src/main/java/com/waenhancer/utils/AndroidUserScope.java',
               SRC/'RestartTargetPolicy.java', SRC/'RootCommandRunner.java',
               QA/'TestRestartTargetPolicy.java', QA/'TestRootCommandRunner.java']
    subprocess.run(['javac', '--release', '17', '-d', output, *map(str, sources)], check=True)
    for name, argv in [('TestRestartTargetPolicy', scope), ('TestRootCommandRunner', [])]:
        subprocess.run(['java', '-cp', output,
                        'com.waenhancer.xposed.utils.'+name, *argv], check=True)

# This second compilation exercises production AppRestartHelper but substitutes only the
# Android framework, Kotlin package detector, and the external su process starter.
STUBS = {
 'android/app/AlarmManager.java': '''package android.app;
public class AlarmManager {
 public static final int ELAPSED_REALTIME=3;
 public void set(int type,long triggerAtMillis,PendingIntent operation) {}
}
''',
 'android/app/PendingIntent.java': '''package android.app;
import android.content.Context;
import android.content.Intent;
public class PendingIntent {
 public static final int FLAG_CANCEL_CURRENT=0x10000000, FLAG_IMMUTABLE=0x04000000;
 public static PendingIntent getActivity(Context context,int requestCode,Intent intent,int flags) {
  return new PendingIntent();
 }
}
''',
 'android/content/ComponentName.java': '''package android.content;
public class ComponentName {
 private final String packageName, cls;
 public ComponentName(String p, String c) { packageName=p; cls=c; }
 public String getPackageName() { return packageName; }
 public String getClassName() { return cls; }
}
''',
 'android/content/Intent.java': '''package android.content;
import java.util.HashMap;
public class Intent {
 public static final int FLAG_ACTIVITY_NEW_TASK=0x10000000, FLAG_ACTIVITY_CLEAR_TASK=0x00008000;
 private String action, pkg; private ComponentName component; private int flags;
 private HashMap<String,String> extras = new HashMap<>();
 public Intent(String action) { this.action=action; }
 public Intent(Intent other) { action=other.action; pkg=other.pkg; component=other.component;
   flags=other.flags; extras=new HashMap<>(other.extras); }
 public String getAction(){return action;}
 public Intent setPackage(String value) { pkg=value;return this; }
 public String getPackage(){return pkg;}
 public Intent setComponent(ComponentName value){component=value;return this;}
 public ComponentName getComponent(){return component;}
 public Intent putExtra(String name,String value){extras.put(name,value);return this;}
 public String getStringExtra(String name){return extras.get(name);}
 public Intent addFlags(int flags){this.flags|=flags;return this;}
 public int getFlags(){return flags;}
 public static Intent makeRestartActivityTask(ComponentName component) {
  return new Intent("android.intent.action.MAIN").setComponent(component)
    .addFlags(FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TASK);
 }
}
''',
 'android/content/pm/ApplicationInfo.java': '''package android.content.pm;
public class ApplicationInfo {public int uid = 12345; public String packageName;}
''',
 'android/content/pm/PackageManager.java': '''package android.content.pm;
import android.content.Intent;
public class PackageManager {
 public static class NameNotFoundException extends Exception {}
 public ApplicationInfo getApplicationInfo(String name,int flags) throws NameNotFoundException {ApplicationInfo info=new ApplicationInfo();info.packageName=name;return info;}
 public volatile Intent intent; public volatile int calls;
 public Intent getLaunchIntentForPackage(String name) { calls++; return intent; }
}
''',
 'android/content/Context.java': '''package android.content;
import android.content.pm.PackageManager;
public abstract class Context {
 public static final String ALARM_SERVICE="alarm";
 public abstract Context getApplicationContext();
 public abstract PackageManager getPackageManager();
 public abstract android.content.pm.ApplicationInfo getApplicationInfo();
 public abstract String getPackageName();
 public abstract Object getSystemService(String name);
 public abstract void sendBroadcast(Intent intent);
 public abstract void startActivity(Intent intent);
}
''',
 'android/os/Looper.java': '''package android.os;
public class Looper { public static Looper getMainLooper(){return new Looper();} }
''',
 'android/os/Handler.java': '''package android.os;
public class Handler {
 public static volatile boolean reject;
 public Handler(Looper loop) {}
 public boolean post(Runnable task) { if(reject)return false; task.run(); return true; }
 public boolean postDelayed(Runnable task,long delay){ if(reject)return false; task.run(); return true; }
}
''',
 'android/os/Process.java': '''package android.os;
public class Process {
 public static int myPid(){return 1234;}
 public static int myUid(){return 12345;}
 public static void killProcess(int pid) {}
}
''',
 'android/os/SystemClock.java': '''package android.os;
public class SystemClock { public static long elapsedRealtime(){return 1000L;} }
''',
 'android/widget/Toast.java': '''package android.widget;
import android.content.Context;
import java.util.concurrent.CopyOnWriteArrayList;
public class Toast {
 public static final int LENGTH_SHORT=0;
 public static final CopyOnWriteArrayList<String> MESSAGES = new CopyOnWriteArrayList<>();
 private String text;
 public static Toast makeText(Context c,String text,int duration){Toast t=new Toast(); t.text=text; return t;}
 public void show(){MESSAGES.add(text);}
}
''',
 'com/waenhancer/utils/WhatsAppPackageDetector.java': '''package com.waenhancer.utils;
import android.content.Context;
import java.util.Set;
import java.util.Collections;
public class WhatsAppPackageDetector {
 public static volatile Set<String> SCOPE = Collections.emptySet();
 public static Set<String> getSupportedPackages(Context c) {return SCOPE;}
}
''',
 'com/waenhancer/xposed/utils/RootCommandRunner.java': '''package com.waenhancer.xposed.utils;
import java.io.IOException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
public class RootCommandRunner {
 interface Starter {Process start(String command) throws IOException;}
 public static final CopyOnWriteArrayList<String> commands = new CopyOnWriteArrayList<>();
 public static volatile String mode = "denied";
 public static volatile CountDownLatch entered, release;
 static String run(String command,long timeout,Starter starter) {
  commands.add(command);
  if (mode.equals("blocked")) {
   entered.countDown();
   try {release.await(2,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}
   return null;
  }
  if (mode.equals("denied")) return null;
  if (command.equals("id")) return "uid=0(root) gid=0(root)";
  if (command.startsWith("am force-stop")) return "";
  if (command.startsWith("am start")) return mode.equals("root") ? "Status: ok\\nComplete" : null;
  return null;
 }
}
''',
}
with tempfile.TemporaryDirectory(prefix='waex_restart_integration_') as output:
    base = Path(output)
    stub_sources = []
    for name, code in STUBS.items():
        file = base / name
        file.parent.mkdir(parents=True, exist_ok=True)
        file.write_text(code)
        stub_sources.append(file)
    # Explicit English display adapter; actual resource routing has its own runtime test.
    from localization_support import write_display_adapter
    stub_sources.extend(write_display_adapter(ROOT, base))
    compiled = [*stub_sources, ROOT/'app/src/main/java/com/waenhancer/utils/AndroidUserScope.java',
                SRC/'RestartTargetPolicy.java',
                SRC/'AppRestartHelper.java', QA/'TestAppRestartHelper.java']
    subprocess.run(['javac','--release','17','-d',output,*map(str,compiled)],check=True)
    subprocess.run(['java','-cp',output,
                    'com.waenhancer.xposed.utils.TestAppRestartHelper',*scope],check=True)

# Check current-version security wiring and avoid regressions to fragile installation features.
app = (SRC/'AppRestartHelper.java').read_text()
hook = (ROOT/'app/src/main/java/com/waenhancer/xposed/MainHook.java').read_text()
manifest = (ROOT/'app/src/main/AndroidManifest.xml').read_text()
checks = {
    'signature protection in manifest': 'android:protectionLevel="signature"' in manifest,
    'module owns restart permission': 'android:name="com.waenhancer.permission.RESTART_TARGET"' in manifest,
    'receiver permission in hook': 'appContext.registerReceiver(receiver, filter, RESTART_PERMISSION' in hook,
    'exact target check in hook': 'packageName.equals(pkg)' in hook,
    'package-scoped sender': 'restart.setPackage(packageName)' in app,
    'exact package extra': 'restart.putExtra("PKG", packageName)' in app,
    'single active request guard': 'RESTART_PENDING.compareAndSet(false, true)' in app,
    'command generated by validated policy': 'RestartTargetPolicy.forceStopCommand(packageName, supported, userId)' in app,
    'root launch generated by validated policy': 'RestartTargetPolicy.launchCommand(packageName, supported,' in app,
    'root stop performed before module broadcast': app.index('boolean stoppedByRoot') < app.index('context.sendBroadcast(restart)'),
    'no broadcast when root succeeds': 'if (!stoppedByRoot) {' in app,
    'root output processed through bounded runner': 'RootCommandRunner.run(command, ROOT_TIMEOUT_MS' in app,
    'no modern process waitFor overload': 'waitFor(timeoutMillis, TimeUnit.MILLISECONDS)' not in (SRC/'RootCommandRunner.java').read_text(),
    'no unvalidated interpolation of package into shell': '"am force-stop " + packageName' not in app,
}
for label, valid in checks.items():
    assert valid, 'Restart safety integration regression: ' + label
print(f'PASS: {len(checks)} manifest, receiver and production restart wiring checks')
