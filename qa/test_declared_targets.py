#!/usr/bin/env python3
"""Exact target selection before/after Context, with current scope/report candidates.
Runs production Java and extracted Kotlin bodies with explicit Android doubles.
Does not authenticate installed APKs or exercise Xposed/Binder.
"""
from pathlib import Path
import hashlib, json, shutil, subprocess, tempfile, xml.etree.ElementTree as ET
import os
ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / 'app/src/main/java/com/waenhancer'

def body(text, marker):
    start = text.index(marker)
    p = text.index('{', start); depth = 0
    for i in range(p, len(text)):
        depth += (text[i] == '{') - (text[i] == '}')
        if depth == 0: return text[start:i+1]
    raise AssertionError('Unterminated ' + marker)

def main():
    for tool in ('javac', 'java', 'kotlinc'):
        if not shutil.which(tool): raise SystemExit('Required tool missing: ' + tool)
    scope = [x.text for x in ET.parse(ROOT/'app/src/main/res/values/arrays.xml').find("string-array[@name='xposed_scope']") if x.tag == 'item' and x.text != 'com.waenhancer']
    assert len(scope) == len(set(scope)), 'Duplicate declared scope'
    detector = (SRC/'utils/WhatsAppPackageDetector.kt').read_text()
    first = detector.index('    /** Declared targets')
    last = detector.index('    /**\n     * Registers a hooked', first)
    selection = detector[first:last]
    reported_start = detector.index('    private fun reportedPackages(')
    reported = detector[reported_start:detector.index('\n\n    /**', reported_start)]
    candidates = body(detector, '    fun candidatePackages(')
    select_start = detector.index('    internal fun selectCandidates(')
    select_end = detector.index('\n    /**', select_start)
    select = detector[select_start:select_end]
    assert 'WhatsAppPackageDetector.isWhatsAppPackageName(packageName)' in (SRC/'xposed/MainHook.java').read_text()
    with tempfile.TemporaryDirectory(prefix='waex-targets-') as folder:
        out=Path(folder); classes=out/'classes'; classes.mkdir()
        policy=SRC/'utils/DeclaredWhatsAppTargets.java'
        subprocess.run(['javac','--release','17','-d',str(classes),str(policy)],check=True)
        (out/'Context.kt').write_text('''package android.content
class Context { val packageManager=PackageManager() }
class PackageManager {
 var installed:Set<String> = emptySet()
 val queries=mutableListOf<String>()
 fun getPackageInfo(name:String,flags:Int):Any {
  queries.add(name)
  if(name !in installed)throw IllegalArgumentException("Not installed")
  return Any()
 }
}
''')
        (out/'Selection.kt').write_text('''package com.waenhancer.utils
import android.content.Context
data class LsposedScopeSnapshot(val packages:Set<String>)
object PreferenceStores { var reports:Set<String>?=null; fun publicStore(context:Context)=this
fun getStringSet(key:String,default:Set<String>?):Set<String>? { check(key=="hooked_whatsapp_packages"); return reports?:default } }
object WhatsAppPackageDetector {
private const val MODULE_PACKAGE="com.waenhancer"
'''+selection+'\n'+reported+'\n'+candidates+'\n'+select+'\n}\n')
        tests='''package com.waenhancer.utils
import android.content.Context
fun main() {
 var n=0
 fun ck(label:String,value:Boolean) {check(value){"ASSERTION|$label"};n++}
 val declared=listOf(SCOPE)
 val context=Context();context.packageManager.installed=declared.toSet()
 ck("scope exact",DeclaredWhatsAppTargets.packages().toList()==declared)
 for (pkg in declared) {
  ck("context $pkg",WhatsAppPackageDetector.isWhatsAppPackageName(context,pkg))
  ck("no context $pkg",WhatsAppPackageDetector.isWhatsAppPackageName(pkg))
  ck("case $pkg",!WhatsAppPackageDetector.isWhatsAppPackageName(pkg.uppercase()))
  ck("suffix $pkg",!WhatsAppPackageDetector.isWhatsAppPackageName(pkg+".scanner.fixture"))
 }
 for (pkg in listOf<String?>(null,"","com.waenhancer","android.whatsapp","com.android.whatsapp","org.example.delight.reader","com.whatsapp.web.dual.app.scanner","whatsapprecovery.deletedphotos.recover.fixture","whatsapp.web.whatscan.fixture","com.whatsapp:worker"," com.whatsapp","com.whatsapp ","com.whatsаpp","com.whatsapp\\n")) {
  ck("reject $pkg",!WhatsAppPackageDetector.isWhatsAppPackageName(pkg))
  ck("reject with context $pkg",!WhatsAppPackageDetector.isWhatsAppPackageName(context,pkg))
 }
 ck("null context retains declared scope",WhatsAppPackageDetector.getSupportedPackages(null).toList()==declared)
 ck("context agrees",WhatsAppPackageDetector.getSupportedPackages(context)==DeclaredWhatsAppTargets.packages())
 try { (DeclaredWhatsAppTargets.packages() as MutableSet<String>).clear(); error("mutable allowlist") } catch (expected:UnsupportedOperationException) {n++}
 ck("immutable after caller attempt",DeclaredWhatsAppTargets.packages().size==declared.size)
 PreferenceStores.reports=setOf("com.whatsapp.web.dual.app.scanner","com.waenhancer","com.aero","org.example.injected")
 ck("historical reports never authorize undeclared targets",WhatsAppPackageDetector.candidatePackages(context,null)==listOf("com.aero"))
 ck("candidate selector rejects undeclared scope",WhatsAppPackageDetector.selectCandidates(setOf("org.example.injected","com.waenhancer"),emptySet()).isEmpty())
 ck("scope and reports deduplicate targets",WhatsAppPackageDetector.selectCandidates(setOf("com.aero"),setOf("com.aero"))==listOf("com.aero"))
 PreferenceStores.reports=null
 ck("scope with absent reports preserves declared targets",WhatsAppPackageDetector.candidatePackages(context,LsposedScopeSnapshot(declared.toSet()))==declared)
 ck("missing scope and reports do not invent active targets",WhatsAppPackageDetector.candidatePackages(context,null).isEmpty())
 context.packageManager.installed=setOf(declared.first())
 ck("candidate list requires installed targets",WhatsAppPackageDetector.candidatePackages(context,LsposedScopeSnapshot(declared.toSet()))==listOf(declared.first()))
 ck("package manager sees only declared identities",context.packageManager.queries.all{it in declared})
 println("PASS: $n declared-target behavior checks; ${declared.size} exact scope entries, no Context/substring authorization")
}
'''.replace('SCOPE',','.join(json.dumps(x) for x in scope))
        (out/'Test.kt').write_text(tests)
        cmd=[shutil.which('kotlinc') or 'kotlinc','-language-version','2.0','-jvm-target','17','-cp',str(classes),*[str(p) for p in sorted(out.glob('*.kt'))],'-include-runtime','-d',str(out/'test.jar')]
        subprocess.run(cmd,check=True,timeout=120)
        print("COMPILE_OK: production target policy",flush=True)
        subprocess.run(['java','-cp',str(out/'test.jar')+os.pathsep+str(classes),'com.waenhancer.utils.TestKt'],check=True,timeout=60)
    print('PASS: exact pre-Context allowlist; current candidate selection intersects scope/reports with declared installed targets; no installed-name scan')
if __name__=='__main__':main()
