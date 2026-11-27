package com.waenhancer.utils

import android.content.pm.PackageManager
import android.app.Activity
import de.robv.android.xposed.XposedBridge

import android.content.*
import android.content.res.Resources
import android.os.SystemClock
import com.waenhancer.app.R
import com.waenhancer.utils.UniversalVersionValidator.Decision as D
import com.waenhancer.utils.TargetRuntimePresentation.Severity as S
import com.waenhancer.xposed.utils.AppRestartHelper as Root
import com.waenhancer.xposed.core.VersionGuard
import com.waenhancer.xposed.core.components.WaexBottomSheet
import java.io.File

class Prefs:SharedPreferences,SharedPreferences.Editor {
    val values=mutableMapOf<String,Any>();var fail=false
    override fun getBoolean(k:String,d:Boolean):Boolean{if(fail)throw ClassCastException("private value");return values[k] as? Boolean?:d}
    override fun getString(k:String,d:String?)=values[k] as? String?:d
    override fun getStringSet(k:String,d:Set<String>?)=values[k] as? Set<String>?:d
    override fun edit():SharedPreferences.Editor=this
    override fun putBoolean(k:String,v:Boolean)=apply{values[k]=v}
    override fun putString(k:String,v:String?)=apply{if(v!=null)values[k]=v}
    override fun putStringSet(k:String,v:Set<String>)=apply{values[k]=v}
    override fun apply(){}
}
var checks=0
var failed=0
fun ck(label:String,a:Any?,b:Any?){
    checks++
    if(a!=b){failed++;println("ASSERTION|"+label)} else println("PASS|"+label)
}
fun main(args:Array<String>){
    val names=mapOf("universal_supported_versions" to R.array.universal_supported_versions)
    val sourceRules=arrayOf(/*BUILTIN_RULES*/)
    val rules=arrayOf("2.26.40.xx","2.26.42.xx","2.26.46.xx")
    fun module(rules:Array<String>, name:String?=null, namesArg:Map<String,Int> = names)=Context("com.waenhancer",Resources("com.waenhancer",namesArg,mapOf(R.array.universal_supported_versions to rules),name))
    val mod=module(rules);val prefs=Prefs()
    val actualModule=module(sourceRules)
    ck("actual resource array returned intact",UniversalVersionValidator.getBuiltinSupportedVersions(actualModule),sourceRules.toList())
    for(rule in sourceRules){
        val example=if(rule.endsWith(".xx",true))rule.dropLast(2)+"82"
            else if(rule.endsWith(".*"))rule.dropLast(1)+"82" else rule
        ck("actual source rule admitted $rule",UniversalVersionValidator.evaluate(actualModule,example,prefs),D.BUILTIN_RULE)
    }
    val host=Context("com.whatsapp",Resources("com.whatsapp",emptyMap(),emptyMap()),mod)
    for(v in listOf("2.26.42.9","2.26.40.76","2.26.46.82","2.26.46.83"," v2.26.40.76 ")){
        ck("module admits $v",UniversalVersionValidator.evaluate(mod,v,prefs),D.BUILTIN_RULE)
        ck("host uses own module resources $v",UniversalVersionValidator.evaluate(host,v,prefs),D.BUILTIN_RULE)
        ck("Boolean API compatible $v",UniversalVersionValidator.isSupported(host,v,prefs),true)
    }
    ck("host table not consulted",host.resources.reads.size,0)
    ck("module context requested by exact package",host.contextRequests.isNotEmpty() && host.contextRequests.all{it.first=="com.waenhancer"},true)
    ck("resource access never requests application code",host.contextRequests.all{it.second and Context.CONTEXT_INCLUDE_CODE==0},true)
    val hidden=Context("com.whatsapp",Resources("com.whatsapp",emptyMap(),mapOf(R.array.universal_supported_versions to arrayOf("9.99.1"))))
    ck("hidden module has no version rules",UniversalVersionValidator.evaluate(hidden,"2.26.40.76",prefs),D.RULES_UNAVAILABLE)
    ck("reject foreign matching numeric table",UniversalVersionValidator.isSupported(hidden,"9.99.1",prefs),false)
    ck("never read foreign resource ID",hidden.resources.reads.size,0)
    ck("null context has no built-in rules",UniversalVersionValidator.evaluate(null,"2.26.40.76",prefs),D.RULES_UNAVAILABLE)
    ck("no-context bool cannot infer support",UniversalVersionValidator.isSupported("2.26.40.76",prefs),false)
    ck("no-context built-in list is empty",UniversalVersionValidator.getBuiltinSupportedVersions(null),emptyList<String>())
    ck("no-context rejects unknown versions",UniversalVersionValidator.isSupported(null,"9.99.1",prefs),false)
    ck("wrong context has no module rules",UniversalVersionValidator.evaluate(Context("com.whatsapp",hidden.resources,hidden),"2.26.40.76",prefs),D.RULES_UNAVAILABLE)
    val foreignMatching=Context("com.whatsapp",Resources("com.whatsapp",names,mapOf(R.array.universal_supported_versions to rules)))
    ck("wrong context cannot supply module rules",UniversalVersionValidator.evaluate(Context("com.whatsapp",hidden.resources,foreignMatching),"2.26.40.76",prefs),D.RULES_UNAVAILABLE)
    ck("wrong context rejected before its array read",foreignMatching.resources.reads.size,0)
    val misplacedModuleResources=Resources("com.waenhancer",names,mapOf(R.array.universal_supported_versions to rules))
    val wrongPackageContext=Context("com.whatsapp",misplacedModuleResources)
    ck("wrong package cannot supply module rules",UniversalVersionValidator.evaluate(Context("com.whatsapp",hidden.resources,wrongPackageContext),"2.26.40.76",prefs),D.RULES_UNAVAILABLE)
    ck("unexpected context is rejected before reading even matching arrays",misplacedModuleResources.reads.size,0)
    ck("foreign owner is unavailable",UniversalVersionValidator.evaluate(module(rules,"com.whatsapp:array/universal_supported_versions"),"2.26.40.76",prefs),D.RULES_UNAVAILABLE)
    ck("foreign array name is unavailable",UniversalVersionValidator.evaluate(module(rules,"com.waenhancer:array/another_array"),"2.26.40.76",prefs),D.RULES_UNAVAILABLE)
    ck("own numeric fallback verified by full name",UniversalVersionValidator.evaluate(module(rules,"com.waenhancer:array/universal_supported_versions",emptyMap()),"2.26.40.76",prefs),D.BUILTIN_RULE)
    for(failure in listOf(SecurityException("blocked"),PackageManager.NameNotFoundException(),NoClassDefFoundError("unavailable"))){
        val inaccessible=Context("com.whatsapp",hidden.resources,mod).apply{contextFailure=failure}
        ck("resource access failure has no rules ${failure.javaClass.simpleName}",UniversalVersionValidator.evaluate(inaccessible,"2.26.40.76",prefs),D.RULES_UNAVAILABLE)
    }
    for(failure in listOf(AssertionError("controlled assertion"),OutOfMemoryError("controlled VM failure"),ThreadDeath())){
        val broken=Context("com.whatsapp",hidden.resources,mod).apply{contextFailure=failure}
        var caught:Throwable?=null
        try { UniversalVersionValidator.evaluate(broken,"2.26.40.76",prefs) } catch(t:Throwable){caught=t}
        ck("fatal or assertion error is not a rules result ${failure.javaClass.simpleName}",caught===failure,true)
    }
    for(list in listOf(emptyArray<String>(),arrayOf("bad"),arrayOf(""),arrayOf("2.26.40.xx",""),arrayOf("*.xx"))){
        ck("invalid array is unavailable",UniversalVersionValidator.evaluate(module(list),"2.26.40.76",prefs),D.RULES_UNAVAILABLE)
    }
    // A future 2.26.x branch can become supported as arrays.xml changes. Keep the
    // synthetic major-zero version outside that range and test 2.26.42 above.
    for(v in listOf("2.26.35.90","2.26.39.74","2.26.39.72","0.0.0.0","9.99.1"))ck("true rejected version $v",UniversalVersionValidator.evaluate(mod,v,prefs),D.UNSUPPORTED)
    for(v in listOf(null,""," ","v"))ck("version unavailable",UniversalVersionValidator.evaluate(mod,v,prefs),D.VERSION_UNAVAILABLE)
    val custom=Prefs().apply{values["customize_supported_versions"]=true;values["custom_supported_versions"]=setOf("9.99.1")}
    ck("intentional custom permission preserved",UniversalVersionValidator.evaluate(hidden,"9.99.1",custom),D.CUSTOM_RULE)
    ck("custom does not invent unavailable built-ins",UniversalVersionValidator.evaluate(hidden,"9.99.2",custom),D.RULES_UNAVAILABLE)
    ck("built-in rule retains precedence",UniversalVersionValidator.evaluate(host,"2.26.40.76",custom),D.BUILTIN_RULE)
    custom.values["customize_supported_versions"]=false
    ck("disabled custom does not replace missing rules",UniversalVersionValidator.evaluate(hidden,"9.99.1",custom),D.RULES_UNAVAILABLE)
    val bypass=Prefs().apply{values["bypass_version_check"]=true}
    ck("explicit existing bypass preserved",UniversalVersionValidator.evaluate(hidden,"99.8.7",bypass),D.EXPLICIT_BYPASS)
    ck("even bypass needs version",UniversalVersionValidator.evaluate(hidden,"",bypass),D.VERSION_UNAVAILABLE)
    ck("unreadable preferences named honestly",UniversalVersionValidator.evaluate(mod,"2.26.40.76",Prefs().apply{fail=true}),D.PREFERENCES_UNAVAILABLE)
    ck("evaluating did not change preferences",prefs.values.size,0)
    val loader=VersionGuard.javaClass.classLoader
    ck("guard accepts only array-declared rules",VersionGuard.verifyAndGuard(host,loader,prefs),true)
    val unavailablePrefs=Prefs().apply{fail=true}
    ck("guard pauses on unreadable settings",VersionGuard.verifyAndGuard(hidden,loader,unavailablePrefs),false)
    ck("guard forwards unavailable decision",VersionGuard.runtimeStatus(),"COMPATIBILITY_UNAVAILABLE")
    VersionGuard.onTargetActivityResumed(Activity())
    ck("settings access error notice",WaexBottomSheet.lastMessage.contains("compatibility settings"),true)
    ck("dialog shown once",WaexBottomSheet.shows,1)
    VersionGuard.onTargetActivityResumed(Activity());ck("no duplicate dialog",WaexBottomSheet.shows,1)
    ck("guard recovery after fresh verification",VersionGuard.verifyAndGuard(host,loader,prefs),true)
    VersionGuard.onTargetActivityResumed(Activity());ck("successful guard clears pending warning",WaexBottomSheet.shows,1)
    host.packageManager.version="9.99.1"
    ck("guard truly unsupported",VersionGuard.verifyAndGuard(host,loader,prefs),false)
    ck("precise unsupported status",VersionGuard.runtimeStatus(),"UNSUPPORTED_VERSION")
    host.packageManager.fail=true
    ck("version lookup failure pauses",VersionGuard.verifyAndGuard(host,loader,prefs),false)
    ck("lookup error doesn't reuse earlier rejection",VersionGuard.runtimeStatus(),"COMPATIBILITY_UNAVAILABLE")
    host.packageManager.fail=false;host.packageManager.version="2.26.40.76"
    ck("settings failure pauses",VersionGuard.verifyAndGuard(host,loader,Prefs().apply{fail=true}),false)
    ck("failure notice reason",VersionGuard.noticeMessage("v",D.PREFERENCES_UNAVAILABLE).contains("compatibility settings"),true)
    ck("no missing-resource mislabel",VersionGuard.noticeMessage("2.26.40.76",D.RULES_UNAVAILABLE).contains("not match"),false)
    ck("guard logs exclude raw preferences",XposedBridge.logs.any{it.contains("private value")},false)
    VersionGuard.verifyAndGuard(hidden,loader,unavailablePrefs)
    WaexBottomSheet.fail=true;VersionGuard.onTargetActivityResumed(Activity())
    WaexBottomSheet.fail=false;VersionGuard.onTargetActivityResumed(Activity())
    ck("warning can recover after failed show",WaexBottomSheet.shows,2)

    fun evidence(pids:Set<Int>?=setOf(123),t:Long=10000,boot:Int=7,recorded:Int=7,pid:Int=123,active:Boolean=true)=classifyInjection(active,recorded,boot,pid,pids,t)
    ck("current",evidence(),InjectionEvidence.CURRENT)
    ck("missing root observation",evidence(null),InjectionEvidence.PROCESS_OBSERVATION_UNAVAILABLE)
    ck("completed empty result",evidence(emptySet()),InjectionEvidence.PROCESS_STOPPED)
    ck("changed PID",evidence(setOf(456)),InjectionEvidence.PROCESS_CHANGED)
    ck("unreadable boot",evidence(boot=-1),InjectionEvidence.BOOT_UNKNOWN)
    ck("other negative boot value",evidence(boot=-2,recorded=-2),InjectionEvidence.BOOT_UNKNOWN)
    ck("other boot",evidence(recorded=6),InjectionEvidence.PREVIOUS_BOOT)
    ck("missing report",evidence(pid=0),InjectionEvidence.NO_REPORT)
    ck("inactive",evidence(active=false),InjectionEvidence.MODULE_INACTIVE)
    ck("zero timestamp",evidence(t=0),InjectionEvidence.NO_REPORT)
    ck("negative timestamp",evidence(t=Long.MIN_VALUE),InjectionEvidence.NO_REPORT)
    ck("large positive timestamp remains a report",evidence(t=Long.MAX_VALUE),InjectionEvidence.CURRENT)
    ck("compat helper matches classifier",hasCurrentInjection(true,7,7,123,setOf(123),10000),true)
    ck("compat helper rejects different PID",hasCurrentInjection(true,7,7,123,setOf(456),10000),false)
    val manager="1 com.waenhancer"
    val personal="123 com.whatsapp"
    val business="456 com.whatsapp.w4b"
    fun frame(s:String)="prefix\nWAEX_PS_BEGIN\n"+s+"\nWAEX_PS_END\n"
    fun parse(s:String,at:Long=1000)=RootProbe.parseRuntime(s,at)
    val snap=parse(frame("$manager\n$personal\n$business"))
    ck("valid exact identities",snap?.pidsFor("com.whatsapp"),setOf(123))
    ck("both targets independent",snap?.pidsFor("com.whatsapp.w4b"),setOf(456))
    ck("completed manager-only list proves target absent",parse(frame(manager))?.pidsFor("com.whatsapp"),emptySet<Int>())
    ck("timestamp at observation start",snap?.observedAtElapsed,1000L)
    ck("unobserved package is unknown",snap?.pidsFor("com.example.other"),null)
    ck("filtered list is not exhaustive for the system",snap?.exhaustive,false)
    ck("declared but absent package is observed",snap?.pidsFor("com.whatsapp.clone"),emptySet<Int>())
    ck("two processes for one target",parse(frame("$manager\n$personal\n124 com.whatsapp"))?.pidsFor("com.whatsapp"),setOf(123,124))
    ck("legacy layout",parse(frame("u0_a1 1 0 100 10 S com.waenhancer\nu0_a2 123 1 100 10 S com.whatsapp"))?.pidsFor("com.whatsapp"),setOf(123))
    ck("header and CRLF",parse(frame("PID NAME\n$manager\n$personal").replace("\n","\r\n"))?.pidsFor("com.whatsapp"),setOf(123))
    for((label,text) in listOf(
        "missing manager" to frame(personal),
        "missing end" to "WAEX_PS_BEGIN\n$manager\n$personal",
        "embedded start marker" to "xWAEX_PS_BEGIN\n$manager\nWAEX_PS_END",
        "embedded end marker" to "WAEX_PS_BEGIN\n$manager\nxWAEX_PS_END",
        "duplicate start" to "WAEX_PS_BEGIN\nWAEX_PS_BEGIN\n$manager\nWAEX_PS_END",
        "duplicate end" to frame(manager)+"WAEX_PS_END\n",
        "reversed markers" to "WAEX_PS_END\n$manager\nWAEX_PS_BEGIN",
        "empty framed result" to frame(""),
        "malformed target PID" to frame("$manager\nabc com.whatsapp"),
        "zero PID" to frame("$manager\n0 com.whatsapp"),
        "negative PID" to frame("$manager\n-3 com.whatsapp"),
        "overflow PID" to frame("$manager\n2147483648 com.whatsapp"),
        "duplicate PID same package" to frame("$manager\n$personal\n$personal"),
        "duplicate PID cross package" to frame("$manager\n$personal\n123 com.whatsapp.w4b"),
        "foreign name" to frame("$manager\n2 com.example.other"),
        "suffix is not exact package" to frame("$manager\n2 com.whatsapp.extra"),
        "malformed package" to frame("$manager\n2 com.whatsapp;bad"),
        "malformed row" to frame("$manager\nunexpected"),
        "malformed legacy ppid" to frame("$manager\nuser 2 bad com.whatsapp")
    ))ck(label,parse(text),null)
    ck("negative observation time",parse(frame(manager),-1),null)
    ck("incomplete snapshot does not answer absence",XposedRuntimeSnapshot(emptyMap(),complete=false,exhaustive=true).pidsFor("com.whatsapp"),null)
    ck("nonexhaustive absence unknown",XposedRuntimeSnapshot(emptyMap()).pidsFor("com.whatsapp"),null)
    ck("malformed list unavailable",parseProcessList("$manager\nbad").complete,false)
    var immutable=false
    try{(snap!!.runningPids as MutableMap).clear()}catch(_:UnsupportedOperationException){immutable=true}
    ck("observations immutable",immutable,true)
    Root.output=frame("$manager\n$personal");Root.fail=false;Root.advance=4000;SystemClock.time=1000
    val probed=RootProbe.run(mod)
    ck("root observation started before command",probed.runtime?.observedAtElapsed,1000L)
    File(args.single()).writeText(Root.lastCommand)
    Root.output=null;ck("root unavailable",RootProbe.run(mod).runtime,null)
    Root.fail=true;ck("root exception unavailable",RootProbe.run(mod).runtime,null);Root.fail=false
    val app=HookedAppInfo("com.whatsapp","WhatsApp","2.26.40.76",true,true,lastActiveTime=10000,runtimeStatus="READY",bridgeStatus="PROVIDER",processObservedAtElapsed=1000)
    fun card(a:HookedAppInfo,d:D=D.BUILTIN_RULE)=TargetRuntimePresentation.card(a,true,d)
    fun sum(apps:List<HookedAppInfo>,d:D=D.BUILTIN_RULE)=TargetRuntimePresentation.summarize(apps,apps.associate{it.packageName to d})
    ck("current complete",card(app).severity,S.OK)
    for(state in listOf("UNSUPPORTED_VERSION","COMPATIBILITY_UNAVAILABLE","INITIALIZATION_FAILED","READY_WITH_ERRORS")){
        for(d in listOf(D.BUILTIN_RULE,D.CUSTOM_RULE,D.EXPLICIT_BYPASS))ck("target state wins $state $d",card(app.copy(runtimeStatus=state),d).severity,S.REVIEW)
    }
    for(state in listOf("INJECTED","INITIALIZING","future-state"))ck("not ready $state",card(app.copy(runtimeStatus=state)).severity,S.UNKNOWN)
    ck("unknown bridge isn't ready",card(app.copy(bridgeStatus="PENDING")).severity,S.UNKNOWN)
    ck("failed bridge",card(app.copy(bridgeStatus="FAILED")).severity,S.REVIEW)
    ck("fallback bridge",card(app.copy(bridgeStatus="DISK_FALLBACK")).severity,S.REVIEW)
    ck("bypass not certification",card(app,D.EXPLICIT_BYPASS).severity,S.REVIEW)
    ck("custom not certification",card(app,D.CUSTOM_RULE).severity,S.REVIEW)
    ck("custom label uses existing translation key",card(app,D.CUSTOM_RULE).text,"Injected • Custom version allowance; unverified")
    ck("custom summary unverified",sum(listOf(app),D.CUSTOM_RULE).healthy,null)
    val stopped=app.copy(isHooked=false,injectionEvidence=InjectionEvidence.PROCESS_STOPPED)
    ck("bridge historical",TargetRuntimePresentation.bridge(stopped),"Last report (not current): Connected directly")
    ck("init historical",TargetRuntimePresentation.initialization(stopped),"Last report (not current): Completed")
    ck("current bridge",TargetRuntimePresentation.bridge(app),"Connected directly")
    ck("empty targets",sum(emptyList()).healthy,null)
    ck("uninstalled",sum(listOf(app.copy(isInstalled=false))).healthy,null)
    ck("stopped no false supported",sum(listOf(stopped)).healthy,null)
    ck("unsupported without injection",sum(listOf(stopped),D.UNSUPPORTED).healthy,false)
    ck("target pause",sum(listOf(app.copy(runtimeStatus="UNSUPPORTED_VERSION"))).healthy,false)
    ck("bypass aggregate",sum(listOf(app),D.EXPLICIT_BYPASS).healthy,false)
    ck("current ready",sum(listOf(app)).healthy,true)
    ck("one stopped neutral",sum(listOf(app,stopped.copy(packageName="com.whatsapp.w4b"))).healthy,true)
    for(e in listOf(InjectionEvidence.PROCESS_OBSERVATION_UNAVAILABLE,InjectionEvidence.BOOT_UNKNOWN,InjectionEvidence.PROCESS_CHANGED,InjectionEvidence.PREVIOUS_BOOT,InjectionEvidence.MODULE_INACTIVE,InjectionEvidence.NO_REPORT)){
        ck("unknown second target cannot certify $e",sum(listOf(app,stopped.copy(packageName="com.whatsapp.w4b",injectionEvidence=e))).healthy,null)
    }
    ck("summary limited to initialization",sum(listOf(app)).text,"Rules accepted; initialization reported")
    val delayed=Activity();delayed.defer=true
    prefs.values.clear();prefs.fail=false;host.packageManager.fail=false;host.packageManager.version="99.99.99.99"
    ck("queue scenario paused",VersionGuard.verifyAndGuard(host,host.javaClass.classLoader,prefs),false)
    VersionGuard.onTargetActivityResumed(delayed)
    val before=WaexBottomSheet.shows
    host.packageManager.version="2.26.40.76"
    ck("queue scenario subsequently admitted",VersionGuard.verifyAndGuard(host,host.javaClass.classLoader,prefs),true)
    delayed.flush()
    ck("obsolete pause callback does not render",WaexBottomSheet.shows,before)
    host.packageManager.version="99.99.99.99"
    VersionGuard.verifyAndGuard(host,host.javaClass.classLoader,prefs);VersionGuard.onTargetActivityResumed(delayed)
    host.packageManager.version=null
    VersionGuard.verifyAndGuard(host,host.javaClass.classLoader,prefs);VersionGuard.onTargetActivityResumed(delayed)
    delayed.flush()
    ck("only latest failed decision renders",WaexBottomSheet.shows,before+1)
    ck("latest message uses latest decision",WaexBottomSheet.lastMessage.contains("determine the installed WhatsApp version"),true)


    println("RESULT|runtime|$checks|$failed")
    if(failed!=0)throw AssertionError("behavioral assertions failed")
}
