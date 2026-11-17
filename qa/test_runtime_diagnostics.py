#!/usr/bin/env python3
"""Compile production decision/evidence/inspector/presentation code with explicit Android doubles.

This does not execute root on Android, Compose, Binder, HMA or LSPosed. The shell
command is additionally exercised using a fake pidof executable on the local OS.
"""
from pathlib import Path
import hashlib
import json
import os
import shutil
import subprocess
import tempfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / 'app/src/main/java/com/waenhancer'

def main():
    for tool in ('kotlinc', 'java', 'sh'):
        if not shutil.which(tool):
            raise SystemExit('Required tool unavailable: ' + tool)
    with tempfile.TemporaryDirectory(prefix='waex-runtime-') as temp:
        out = Path(temp); src = out / 'src'; src.mkdir()
        hashes = {}
        def put(name, text):
            (src / name).write_text(text)
        for name in ('UniversalVersionValidator', 'InjectionEvidence', 'TargetRuntimePresentation', 'XposedRuntimeInspector'):
            path = JAVA / ('utils/' + name + '.kt')
            put(name + '.kt', path.read_text())
            hashes[str(path.relative_to(ROOT))] = hashlib.sha256(path.read_bytes()).hexdigest()
        det = (JAVA / 'utils/WhatsAppPackageDetector.kt').read_text()
        model = det[det.index('data class InjectedHookItem('):det.index('object WhatsAppPackageDetector')]
        put('Model.kt', 'package com.waenhancer.utils\n' + model)
        # A scope adapter is required by the detector's unchanged classifyScope
        # function. Its public isLive/contains answers are explicit inputs, not
        # a simulated LSPosed database or assumed permissions.
        put('Scope.kt', '''package com.waenhancer.utils
data class LsposedScopeSnapshot(val packages:Set<String>, val isLive:Boolean) {
 fun contains(packageName:String):Boolean = packageName in packages
}
''')
        # Full production guard is compiled too; the visual builder is an explicit double.
        guard_source = (JAVA/'xposed/core/VersionGuard.kt').read_text()
        put('VersionGuard.kt', guard_source)
        # Compile the real guard against explicit presentation-only adapters.
        # Runtime policy decisions and English fallbacks remain executable.
        import re
        guard_ids = sorted(set(re.findall(r'R\.string\.([A-Za-z_][A-Za-z_0-9]*)', guard_source)))
        put('UiText.kt', '''package com.waenhancer.utils
object UiText {
 @JvmStatic fun get(id:Int, fallback:String, vararg args:Any?):String =
   if(args.isEmpty()) fallback else String.format(java.util.Locale.US, fallback, *args)
}
''')
        put('Resources.kt', '''package android.content.res
class Resources(val owner:String,val names:Map<String,Int>,val arrays:Map<Int,Array<String>>,val overrideName:String?=null) {
 class NotFoundException:RuntimeException()
 val reads=mutableListOf<Int>()
 fun getIdentifier(name:String,type:String,pkg:String):Int=if(pkg==owner&&type=="array") names[name]?:0 else 0
 fun getResourceName(id:Int):String=overrideName?:names.entries.firstOrNull{it.value==id}?.let{"$owner:array/${it.key}"}?:throw NotFoundException()
 fun getStringArray(id:Int):Array<String>{reads.add(id);return arrays[id]?.clone()?:throw NotFoundException()}
}
''')
        put('Context.kt', '''package android.content
import android.content.res.Resources
import android.content.pm.PackageManager
open class Context(val packageName:String,val resources:Resources,val module:Context?=null) {
 val packageManager=PackageManager()
 val contextRequests=mutableListOf<Pair<String,Int>>()
 var contextFailure:Throwable?=null
 open fun createPackageContext(name:String,flags:Int):Context {
  contextRequests.add(name to flags)
  contextFailure?.let{throw it}
  return module?:throw PackageManager.NameNotFoundException()
 }
 companion object {const val CONTEXT_INCLUDE_CODE=1;const val CONTEXT_IGNORE_SECURITY=2}
}
class Intent {fun setClassName(a:String,b:String){};fun putExtra(a:String,b:String){};fun addFlags(f:Int){}
 companion object {const val FLAG_ACTIVITY_NEW_TASK=1;const val FLAG_ACTIVITY_CLEAR_TOP=2;const val FLAG_ACTIVITY_SINGLE_TOP=4}}
interface SharedPreferences {fun getBoolean(k:String,d:Boolean):Boolean;fun getString(k:String,d:String?):String?;fun getStringSet(k:String,d:Set<String>?):Set<String>?;fun edit():Editor
 interface Editor{fun putBoolean(k:String,v:Boolean):Editor;fun putString(k:String,v:String?):Editor;fun putStringSet(k:String,v:Set<String>):Editor;fun apply()}}
''')
        put('PM.kt', '''package android.content.pm
import android.content.Intent
class PackageInfo(val versionName:String?)
class PackageManager {class NameNotFoundException:Exception();var version:String?="2.26.40.76";var fail=false
 fun getPackageInfo(p:String,f:Int):PackageInfo=if(fail)throw NameNotFoundException() else PackageInfo(version)
 fun getLaunchIntentForPackage(p:String):Intent?=Intent()}
''')
        put('Activity.kt', '''package android.app
import android.content.*
import android.content.res.Resources
class Activity:Context("com.whatsapp",Resources("com.whatsapp",emptyMap(),emptyMap())) {
 var isFinishing=false;var isDestroyed=false;var defer=false;val pending=mutableListOf<()->Unit>()
 fun runOnUiThread(r:()->Unit){if(defer)pending.add(r) else r()};fun startActivity(i:Intent){}
 fun flush(){val jobs=pending.toList();pending.clear();jobs.forEach{it()}}
}
''')
        put('Sheet.kt', '''package com.waenhancer.xposed.core.components
import android.app.Activity
class Dialog{fun dismiss(){}}
class WaexBottomSheet(a:Activity) {
 fun setTitle(s:String)=this;fun setMessage(s:String)=apply{lastMessage=s}
 fun setPositiveButton(s:String,f:(Dialog,Int)->Unit)=this
 fun show(){if(fail)throw IllegalStateException();shows++}
 companion object{var fail=false;var shows=0;var lastMessage=""}}
''')
        put('Log.kt', '''package de.robv.android.xposed
object XposedBridge{val logs=mutableListOf<String>();fun log(s:String){logs.add(s)}}
''')
        put('Clock.kt', '''package android.os
object SystemClock{var time=1000L;fun elapsedRealtime()=time}
''')
        put('R.kt', 'package com.waenhancer.app\nobject R { object array { const val universal_supported_versions = 0x7f030000 }; object string {\n' + '\n'.join('const val '+name+' = '+str(0x7f050100+i) for i,name in enumerate(guard_ids)) + '\n} }\n')
        put('Root.kt', '''package com.waenhancer.xposed.utils
object AppRestartHelper {var output:String?=null;var lastCommand="";var fail=false;var calls=0;var advance=0L
 fun runRootCommand(command:String):String?{calls++;lastCommand=command;android.os.SystemClock.time+=advance;if(fail)throw SecurityException("secret diagnostic");return output}}
''')
        fixture = (ROOT/'qa/fixtures/RuntimeDiagnosticsChecks.kt').read_text()
        rules=[i.text for i in ET.parse(ROOT/'app/src/main/res/values/arrays.xml').getroot().find("string-array[@name='universal_supported_versions']")]
        put('Checks.kt',fixture.replace('/*BUILTIN_RULES*/',','.join(json.dumps(s) for s in rules)))
        args = [shutil.which('kotlinc'), '-nowarn', '-language-version', '2.0', *map(str, sorted(src.glob('*.kt'))), '-include-runtime', '-d', str(out/'checks.jar')]
        subprocess.run(args, check=True, timeout=90)
        print('COMPILE_OK: production runtime source',flush=True)
        subprocess.run(['java','-jar',str(out/'checks.jar')], check=True, timeout=30)
        # The old pidof/sentinel command was removed in favor of a single ps
        # listing staged with LSPosed scope. A controlled shell probe exercises
        # the production command's selection shape; RootProbe.parseRuntime and
        # root permissions still require an Android integration test.
        manager=(JAVA/'utils/TargetAppManager.kt').read_text()
        assert '(ps -A -o PID,NAME 2>/dev/null || ps 2>/dev/null)' in manager
        assert 'grep -E \' ($names)[[:space:]]*$\'' in manager
        assert 'return snapshot.takeIf { it.runningPids.isNotEmpty() }' in manager
        declared=(JAVA/'utils/DeclaredWhatsAppTargets.java').read_text()
        import re
        packages=re.findall(r'"(com\.[\w.]+)"', declared)
        assert 'com.whatsapp' in packages and 'com.whatsapp.w4b' in packages
        accepted=packages+['com.waenhancer']
        names='|'.join(re.escape(package) for package in accepted)
        command=f"(ps -A -o PID,NAME 2>/dev/null || ps 2>/dev/null) | grep -E ' ({names})[[:space:]]*$'"
        fake=out/'ps'
        fake.write_text('#!/bin/sh\nprintf "%s\\n" "$FAKE_PS"\n');fake.chmod(0o700)
        env=os.environ|{'PATH':str(out)+os.pathsep+os.environ['PATH'],
                        'FAKE_PS':'PID NAME\n10 com.whatsapp\n20 com.whatsapp.w4b\n30 com.waenhancer\n40 com.fakeapp'}
        shell=shutil.which('sh')
        assert shell, 'Expected a POSIX shell on the test host'
        result=subprocess.run([shell,'-c',command],text=True,capture_output=True,env=env,timeout=5)
        assert result.returncode==0 and result.stdout.splitlines()==[
            '10 com.whatsapp','20 com.whatsapp.w4b','30 com.waenhancer'],result
        env['FAKE_PS']='PID NAME\n99 com.fakeapp'
        result=subprocess.run([shell,'-c',command],text=True,capture_output=True,env=env,timeout=5)
        assert result.returncode==1 and result.stdout=='',result
    # Guard actual call sites: production decisions, not a replacement demo UI.
    dash=(JAVA/'ui/screens/dashboard/MainDashboardScreen.kt').read_text()
    health=(JAVA/'ui/screens/settings/SystemHealthScreen.kt').read_text()
    versions=(JAVA/'ui/screens/settings/SupportedVersionsScreen.kt').read_text()
    # The periodic Compose observation clock was removed when runtime evidence
    # became PID/boot-bound and target refreshes became event-driven. Guard the
    # real producer -> lifecycle -> StateFlow -> UI path instead of a deleted API.
    navigation=(JAVA/'ui/navigation/Navigation.kt').read_text()
    manager=(JAVA/'utils/TargetAppManager.kt').read_text()
    assert 'Lifecycle.Event.ON_RESUME -> TargetAppManager.attach(context)' in navigation
    assert 'Lifecycle.Event.ON_PAUSE -> TargetAppManager.detach(context)' in navigation
    assert 'TargetAppManager.detach(context)' in navigation
    assert 'appContext.registerReceiver(targetReceiver, filter' in manager
    assert 'unregisterReceiver(targetReceiver)' in manager
    assert 'if (context != null) refresh(context.applicationContext)' in manager
    assert 'refresh(appContext)' in manager and 'syncHooks(appContext)' in manager
    assert '_refreshEpoch.value = System.currentTimeMillis()' in manager
    assert 'rerunRequested.getAndSet(false)' in manager
    assert 'val reportedApps by TargetAppManager.detectedApps.collectAsState()' in dash
    assert 'val refreshEpoch by TargetAppManager.refreshEpoch.collectAsState()' in dash
    assert 'val activeTargets by TargetAppManager.detectedApps.collectAsState()' in health
    assert 'if (event == Lifecycle.Event.ON_RESUME) refreshTrigger++' in health
    assert 'LaunchedEffect(refreshTrigger)' in health and 'TargetAppManager.refresh(context)' in health
    assert 'TargetRuntimePresentation.card(' in dash and 'TargetRuntimePresentation.summarize(' in dash
    assert 'TargetRuntimePresentation.bridge(app)' in health and 'TargetRuntimePresentation.initialization(app)' in health
    assert 'app.injectionEvidence.label' in health
    assert 'UniversalVersionValidator.evaluate(' in versions and 'Version check unavailable' in versions
    assert 'if (isAllSupported)' not in versions
    assert 'decision == UniversalVersionValidator.Decision.CUSTOM_RULE' in versions
    assert 'UiText.get(R.string.waex_l10n_custom_unverified, "Custom")' in versions
    assert 'val isSupported = decision == UniversalVersionValidator.Decision.BUILTIN_RULE ||' in versions
    assert 'sendBroadcast(intent)' in det
    assert 'com.waenhancer.ACTION_TARGET_APP_ACTIVE' in det
    evidence=(JAVA/'utils/InjectionEvidence.kt').read_text()
    assert 'recordedBoot != currentBoot' in evidence
    assert 'reportedPid !in runningPids' in evidence
    assert 'lastActiveTime <= 0L' in evidence
    assert 'isRecentTimestamp(' not in evidence
    assert 'runtimeSnapshot?.pidsFor(' in det and 'classifyInjection(' in det
    assert 'runtimeSnapshot?.runningPids?.get(pkg).orEmpty()' not in det
    mainhook=(JAVA/'xposed/MainHook.java').read_text();provider=(JAVA/'xposed/bridge/HookProvider.java').read_text()
    assert 'VersionGuard.runtimeStatus()' in mainhook and '"COMPATIBILITY_UNAVAILABLE"' in provider
    print('PASS: controlled ps selection and event-driven lifecycle/StateFlow/transport source guards; no Android/LSPosed test.')
    print('PRODUCTION_SHA256='+json.dumps(hashes,sort_keys=True))

if __name__ == '__main__': main()
