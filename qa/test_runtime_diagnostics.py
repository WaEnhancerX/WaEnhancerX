#!/usr/bin/env python3
"""Compile production decision/evidence/inspector/presentation code with explicit Android doubles.

This does not execute root on Android, Compose, Binder, HMA or LSPosed. The current on-demand RootProbe shell is exercised with fake ps and real grep.
Historical heartbeat-specific tests are replaced by the current boot/PID and
explicit-observation contract; actual Android/ART/Compose are not executed.
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
    from test_target_hook_sync import run_checks
    run_checks(ROOT)
    for tool in ('kotlinc', 'java', 'javac', 'sh', 'grep'):
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
        manager = (JAVA/'utils/TargetAppManager.kt').read_text()
        root_probe = manager[manager.index('internal data class RootProbeResult'):manager.index('/**\n * Universal source of truth')]
        put('RootProbe.kt', 'package com.waenhancer.utils\nimport android.content.Context\nimport android.os.SystemClock\nimport com.waenhancer.xposed.utils.AppRestartHelper\n' + root_probe)
        put('Scope.kt', """package com.waenhancer.utils
import android.content.Context
data class LsposedScopeSnapshot(val isLive:Boolean=false,val packages:Set<String> = emptySet()) {
 fun contains(p:String)=packages.contains(p)
}
object LsposedScopeReader {
 const val STAGED_OK_MARKER="SYNTHETIC_SCOPE_OK"
 fun stageCommand(c:Context)=":"
 fun parseStaged(c:Context,ok:Boolean)=LsposedScopeSnapshot(ok)
}
""")
        for rel in ('utils/TargetAppManager.kt','utils/WhatsAppPackageDetector.kt','utils/DeclaredWhatsAppTargets.java','xposed/core/VersionGuard.kt'):
            path=JAVA/rel
            hashes[str(path.relative_to(ROOT))]=hashlib.sha256(path.read_bytes()).hexdigest()
        classes=out/'classes';classes.mkdir()
        subprocess.run(['javac','--release','8','-d',str(classes),str(JAVA/'utils/DeclaredWhatsAppTargets.java')],check=True,timeout=30)
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
        args = [shutil.which('kotlinc'), '-nowarn', '-language-version', '2.0', '-classpath', str(classes), *map(str, sorted(src.glob('*.kt'))), '-include-runtime', '-d', str(out/'checks.jar')]
        subprocess.run(args, check=True, timeout=90)
        print('COMPILE_OK: production runtime source',flush=True)
        subprocess.run(['java','-cp',os.pathsep.join((str(out/'checks.jar'),str(classes))),'com.waenhancer.utils.ChecksKt',str(out/'command.sh')],check=True,timeout=30)
        command=(out/'command.sh').read_text()
        assert command.startswith(":\necho WAEX_PS_BEGIN\n"), "Unexpected root staging adapter"
        # Only the synthetic ps and a known grep executable are reachable.
        bin_dir=out/'fake-bin';bin_dir.mkdir()
        ps=bin_dir/'ps'
        ps.write_text('#!/bin/sh\nif [ "$#" -gt 0 ]; then printf "%s" "$PRIMARY_OUT"; exit "$PRIMARY_CODE"; else printf "%s" "$FALLBACK_OUT"; exit "$FALLBACK_CODE"; fi\n')
        ps.chmod(0o700)
        (bin_dir/'grep').symlink_to(shutil.which('grep'))
        cases=(
            (0,"1 com.waenhancer\n123 com.whatsapp\n456 com.whatsapp.w4b\n999 com.example.other\n",9,"",True,["1 com.waenhancer","123 com.whatsapp","456 com.whatsapp.w4b"]),
            (2,"1 com.waenhancer\n123 com.whatsapp\n",0,"u0_a1 1 0 12 34 S com.waenhancer\nu0_a2 456 1 12 34 S com.whatsapp.w4b\n",True,["u0_a1 1 0 12 34 S com.waenhancer","u0_a2 456 1 12 34 S com.whatsapp.w4b"]),
            (2,"1 com.waenhancer\n",3,"123 com.whatsapp\n",False,[]),
            (2,"",127,"",False,[]),
            (0,"PID NAME\n999 com.example.other\n",3,"",False,[]),
            (0,"PID NAME\n 1 com.waenhancer\n",9,"",True,[" 1 com.waenhancer"]),
            (0,"1 com.waenhancer\n123 com.whatsapp.extra\n124 com.whatsapp:worker\n125 com.whatsapp\n",9,"",True,["1 com.waenhancer","125 com.whatsapp"]),
            (2,"123 com.whatsapp\n",0,"1 com.waenhancer\n",True,["1 com.waenhancer"]),
            (0,"",0,"1 com.waenhancer\n",False,[]),
            (0,"1\tcom.waenhancer\n123\tcom.whatsapp\n",9,"",True,["1\tcom.waenhancer","123\tcom.whatsapp"]),
        )
        for pc,po,fc,fo,end,rows in cases:
            env={'PATH':str(bin_dir),'PRIMARY_CODE':str(pc),'PRIMARY_OUT':po,'FALLBACK_CODE':str(fc),'FALLBACK_OUT':fo,'LANG':'C'}
            result=subprocess.run(['/bin/sh','-c',command],text=True,capture_output=True,env=env,timeout=5)
            expected=['WAEX_PS_BEGIN']+rows+(['WAEX_PS_END'] if end else [])
            assert result.returncode==0 and result.stdout.splitlines()==expected,(pc,fc,result)
        print(f'PASS: {len(cases)} exact emitted-shell controls, synthetic ps only')
    # Guard actual call sites: production decisions, not a replacement demo UI.
    dash=(JAVA/'ui/screens/dashboard/MainDashboardScreen.kt').read_text()
    health=(JAVA/'ui/screens/settings/SystemHealthScreen.kt').read_text()
    versions=(JAVA/'ui/screens/settings/SupportedVersionsScreen.kt').read_text()
    for ui in (dash,health,versions):
        assert 'TargetAppManager.detectedApps.collectAsState()' in ui
    assert 'TargetRuntimePresentation.card(' in dash and 'TargetRuntimePresentation.summarize(' in dash
    assert 'TargetRuntimePresentation.bridge(app)' in health and 'TargetRuntimePresentation.initialization(app)' in health
    assert 'app.injectionEvidence.label' in health
    assert 'UniversalVersionValidator.evaluate(' in versions and 'Version check unavailable' in versions
    assert 'if (isAllSupported)' not in versions
    assert 'val isSupported = decision == UniversalVersionValidator.Decision.BUILTIN_RULE\n' in versions
    assert 'val isCustomRuleYellow = isYellow && allInjected && installedTargets.any {' in dash
    assert 'decisions[it.packageName] == UniversalVersionValidator.Decision.CUSTOM_RULE' in dash
    assert 'isYellow && allInjected -> stringResource(R.string.waex_runtime_status_rules_accepted_runtime_unconfirmed)' in dash
    labels=(JAVA/'utils/InternalLabels.java').read_text()
    assert 'case "Injected • Custom version allowance; unverified":' in labels
    assert 'case "Custom version allowance; compatibility unverified":' in labels
    assert 'runtimeSnapshot?.pidsFor(' in det and 'classifyInjection(' in det
    assert 'runtimeSnapshot?.runningPids?.get(pkg).orEmpty()' not in det
    mainhook=(JAVA/'xposed/MainHook.java').read_text();provider=(JAVA/'xposed/bridge/HookProvider.java').read_text()
    assert 'VersionGuard.runtimeStatus()' in mainhook and '"COMPATIBILITY_UNAVAILABLE"' in provider
    print('PASS: current on-demand UI/transport source guards; no Android/LSPosed or Compose execution.')
    print('PRODUCTION_SHA256='+json.dumps(hashes,sort_keys=True))

if __name__ == '__main__': main()
