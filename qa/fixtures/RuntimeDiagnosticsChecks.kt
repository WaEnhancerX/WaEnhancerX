package com.waenhancer.utils

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
fun ck(label:String,a:Any?,b:Any?){check(a==b){"ASSERTION|$label actual=$a expected=$b"};checks++}
fun main(args:Array<String>){
    val names=mapOf("universal_supported_versions" to R.array.universal_supported_versions)
    val rules=arrayOf(/*BUILTIN_RULES*/)
    fun module(rules:Array<String>, name:String?=null, namesArg:Map<String,Int> = names)=Context("com.waenhancer",Resources("com.waenhancer",namesArg,mapOf(R.array.universal_supported_versions to rules),name))
    val mod=module(rules);val prefs=Prefs()
    val host=Context("com.whatsapp",Resources("com.whatsapp",emptyMap(),emptyMap()),mod)
    for(v in listOf("2.26.42.9","2.26.40.76","2.26.39.74","2.26.39.72"," v2.26.40.76 ")){
        ck("module admits $v",UniversalVersionValidator.evaluate(mod,v,prefs),D.BUILTIN_RULE)
        ck("host uses own module resources $v",UniversalVersionValidator.evaluate(host,v,prefs),D.BUILTIN_RULE)
        ck("Boolean API compatible $v",UniversalVersionValidator.isSupported(host,v,prefs),true)
    }
    ck("host table not consulted",host.resources.reads.size,0)
    ck("module context requested by exact package",host.contextRequests.isNotEmpty() && host.contextRequests.all{it.first=="com.waenhancer"},true)
    ck("resource access never requests application code",host.contextRequests.all{it.second and Context.CONTEXT_INCLUDE_CODE==0},true)
    val hidden=Context("com.whatsapp",Resources("com.whatsapp",emptyMap(),mapOf(R.array.universal_supported_versions to arrayOf("9.99.1"))))
    ck("hidden module uses static module rules",UniversalVersionValidator.evaluate(hidden,"2.26.40.76",prefs),D.BUILTIN_RULE)
    ck("reject foreign matching numeric table",UniversalVersionValidator.isSupported(hidden,"9.99.1",prefs),false)
    ck("never read foreign resource ID",hidden.resources.reads.size,0)
    ck("null context uses static module rules",UniversalVersionValidator.evaluate(null,"2.26.40.76",prefs),D.BUILTIN_RULE)
    ck("no-context bool uses verified module fallback",UniversalVersionValidator.isSupported("2.26.40.76",prefs),true)
    ck("static rules remain identical to module array",UniversalVersionValidator.getBuiltinSupportedVersions(null),rules.toList())
    ck("static fallback rejects unknown versions",UniversalVersionValidator.isSupported(null,"9.99.1",prefs),false)
    ck("wrong context uses static module rules",UniversalVersionValidator.evaluate(Context("com.whatsapp",hidden.resources,hidden),"2.26.40.76",prefs),D.BUILTIN_RULE)
    val foreignMatching=Context("com.whatsapp",Resources("com.whatsapp",names,mapOf(R.array.universal_supported_versions to rules)))
    ck("wrong context cannot replace the static module fallback",UniversalVersionValidator.evaluate(Context("com.whatsapp",hidden.resources,foreignMatching),"2.26.40.76",prefs),D.BUILTIN_RULE)
    ck("wrong context rejected before its array read",foreignMatching.resources.reads.size,0)
    val misplacedModuleResources=Resources("com.waenhancer",names,mapOf(R.array.universal_supported_versions to rules))
    val wrongPackageContext=Context("com.whatsapp",misplacedModuleResources)
    ck("wrong package cannot replace static module rules",UniversalVersionValidator.evaluate(Context("com.whatsapp",hidden.resources,wrongPackageContext),"2.26.40.76",prefs),D.BUILTIN_RULE)
    ck("unexpected context is rejected before reading even matching arrays",misplacedModuleResources.reads.size,0)
    ck("foreign owner uses static module rules",UniversalVersionValidator.evaluate(module(rules,"com.whatsapp:array/universal_supported_versions"),"2.26.40.76",prefs),D.BUILTIN_RULE)
    ck("foreign array name uses static module rules",UniversalVersionValidator.evaluate(module(rules,"com.waenhancer:array/another_array"),"2.26.40.76",prefs),D.BUILTIN_RULE)
    ck("own numeric fallback verified by full name",UniversalVersionValidator.evaluate(module(rules,"com.waenhancer:array/universal_supported_versions",emptyMap()),"2.26.40.76",prefs),D.BUILTIN_RULE)
    for(failure in listOf(SecurityException("blocked"),android.content.pm.PackageManager.NameNotFoundException(),NoClassDefFoundError("unavailable"))){
        val inaccessible=Context("com.whatsapp",hidden.resources,mod).apply{contextFailure=failure}
        ck("resource access failure uses static module rules ${failure.javaClass.simpleName}",UniversalVersionValidator.evaluate(inaccessible,"2.26.40.76",prefs),D.BUILTIN_RULE)
    }
    for(failure in listOf(AssertionError("controlled assertion"),OutOfMemoryError("controlled VM failure"),ThreadDeath())){
        val broken=Context("com.whatsapp",hidden.resources,mod).apply{contextFailure=failure}
        var caught:Throwable?=null
        try { UniversalVersionValidator.evaluate(broken,"2.26.40.76",prefs) } catch(t:Throwable){caught=t}
        ck("fatal or assertion error is not a rules result ${failure.javaClass.simpleName}",caught===failure,true)
    }
    for(list in listOf(emptyArray<String>(),arrayOf("bad"),arrayOf(""),arrayOf("2.26.40.xx",""),arrayOf("*.xx"))){
        ck("invalid array uses static module rules",UniversalVersionValidator.evaluate(module(list),"2.26.40.76",prefs),D.BUILTIN_RULE)
    }
    // A future 2.26.x branch can become supported as arrays.xml changes. Keep the
    // synthetic major-zero version outside that range and test 2.26.42 above.
    for(v in listOf("2.26.35.90","0.0.0.0","9.99.1"))ck("true rejected version $v",UniversalVersionValidator.evaluate(mod,v,prefs),D.UNSUPPORTED)
    for(v in listOf(null,""," ","v"))ck("version unavailable",UniversalVersionValidator.evaluate(mod,v,prefs),D.VERSION_UNAVAILABLE)
    val custom=Prefs().apply{values["customize_supported_versions"]=true;values["custom_supported_versions"]=setOf("9.99.1")}
    ck("intentional custom permission preserved",UniversalVersionValidator.evaluate(hidden,"9.99.1",custom),D.CUSTOM_RULE)
    ck("custom does not admit other versions",UniversalVersionValidator.evaluate(hidden,"9.99.2",custom),D.UNSUPPORTED)
    ck("built-in rule retains precedence",UniversalVersionValidator.evaluate(hidden,"2.26.40.76",custom),D.BUILTIN_RULE)
    custom.values["customize_supported_versions"]=false
    ck("disabled custom not auto-enabled",UniversalVersionValidator.evaluate(hidden,"9.99.1",custom),D.UNSUPPORTED)
    val bypass=Prefs().apply{values["bypass_version_check"]=true}
    ck("explicit existing bypass preserved",UniversalVersionValidator.evaluate(hidden,"99.8.7",bypass),D.EXPLICIT_BYPASS)
    ck("even bypass needs version",UniversalVersionValidator.evaluate(hidden,"",bypass),D.VERSION_UNAVAILABLE)
    ck("unreadable preferences named honestly",UniversalVersionValidator.evaluate(mod,"2.26.40.76",Prefs().apply{fail=true}),D.PREFERENCES_UNAVAILABLE)
    ck("evaluating did not change preferences",prefs.values.size,0)
    val loader=VersionGuard.javaClass.classLoader
    ck("guard accepts only declared fallback rules",VersionGuard.verifyAndGuard(hidden,loader,prefs),true)
    val unavailablePrefs=Prefs().apply{fail=true}
    ck("guard pauses on unreadable settings",VersionGuard.verifyAndGuard(hidden,loader,unavailablePrefs),false)
    ck("guard forwards unavailable decision",VersionGuard.runtimeStatus(),"COMPATIBILITY_UNAVAILABLE")
    VersionGuard.onTargetActivityResumed(android.app.Activity())
    ck("settings access error notice",WaexBottomSheet.lastMessage.contains("compatibility settings"),true)
    ck("dialog shown once",WaexBottomSheet.shows,1)
    VersionGuard.onTargetActivityResumed(android.app.Activity());ck("no duplicate dialog",WaexBottomSheet.shows,1)
    ck("guard recovery after fresh verification",VersionGuard.verifyAndGuard(host,loader,prefs),true)
    VersionGuard.onTargetActivityResumed(android.app.Activity());ck("successful guard clears pending warning",WaexBottomSheet.shows,1)
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
    ck("guard logs exclude raw preferences",de.robv.android.xposed.XposedBridge.logs.any{it.contains("private value")},false)
    VersionGuard.verifyAndGuard(hidden,loader,unavailablePrefs)
    WaexBottomSheet.fail=true;VersionGuard.onTargetActivityResumed(android.app.Activity())
    WaexBottomSheet.fail=false;VersionGuard.onTargetActivityResumed(android.app.Activity())
    ck("warning can recover after failed show",WaexBottomSheet.shows,2)
    fun evidence(pids:Set<Int>?=setOf(123),t:Long=10000,now:Long=20000,boot:Int=7,recorded:Int=7,pid:Int=123,active:Boolean=true)=classifyInjection(active,recorded,boot,pid,pids,t,now)
    ck("current",evidence(),InjectionEvidence.CURRENT)
    ck("missing root observation",evidence(null),InjectionEvidence.PROCESS_OBSERVATION_UNAVAILABLE)
    ck("completed empty result",evidence(emptySet()),InjectionEvidence.PROCESS_STOPPED)
    ck("changed PID",evidence(setOf(456)),InjectionEvidence.PROCESS_CHANGED)
    ck("unreadable boot",evidence(boot=-1),InjectionEvidence.BOOT_UNKNOWN)
    ck("other boot",evidence(recorded=6),InjectionEvidence.PREVIOUS_BOOT)
    ck("missing report",evidence(pid=0),InjectionEvidence.NO_REPORT)
    ck("inactive",evidence(active=false),InjectionEvidence.MODULE_INACTIVE)
    for(delta in listOf(-1L,0L,24999L,25000L,25001L,Long.MAX_VALUE-10000L)){
        ck("heartbeat boundary $delta",evidence(now=10000+delta),if(delta in 0L..25000L)InjectionEvidence.CURRENT else InjectionEvidence.REPORT_EXPIRED)
    }
    ck("zero timestamp",evidence(t=0,now=1),InjectionEvidence.NO_REPORT)
    ck("negative timestamp cannot overflow",isRecentTimestamp(-1,Long.MAX_VALUE),false)
    ck("clock rollback",isRecentTimestamp(3,2),false)
    ck("compat helper preserves strict proof",hasCurrentInjection(true,7,7,123,setOf(123),10000,35000),true)
    ck("compat helper expires",hasCurrentInjection(true,7,7,123,setOf(123),10000,35001),false)
    SystemClock.time=1000
    Root.output="PID|com.whatsapp|123 456\n\nEND|WAEX_PROCESS_SNAPSHOT"
    val snap=XposedRuntimeInspector.inspect(listOf("com.whatsapp"))!!
    File(args.single()).writeText(Root.lastCommand)
    ck("PID values",snap.pidsFor("com.whatsapp",1000),setOf(123,456))
    ck("missing package isn't stopped",snap.pidsFor("com.whatsapp.w4b",1000),null)
    ck("snapshot age inclusive",snap.pidsFor("com.whatsapp",26000),setOf(123,456))
    ck("snapshot age expired",snap.pidsFor("com.whatsapp",26001),null)
    ck("snapshot time rollback",snap.pidsFor("com.whatsapp",999),null)
    ck("fresh cache",XposedRuntimeInspector.cached()!=null,true)
    SystemClock.time=26001;ck("cache expires",XposedRuntimeInspector.cached(),null)
    SystemClock.time=1000
    for(text in listOf("", "bad", "PID|com.whatsapp|123\nPID|com.whatsapp|456", "PID|com.whatsapp|0", "PID|com.whatsapp|-1", "PID|com.whatsapp|abc", "PID|com.whatsapp|2147483648", "PID|com.whatsapp|123 other", "PID|com.whatsapp;bad|123", "PID|com.whatsapp.w4b|456")){
        Root.output=text+"\nEND|WAEX_PROCESS_SNAPSHOT\n"
        ck("incomplete or malformed probe rejected",XposedRuntimeInspector.inspect(listOf("com.whatsapp")),null)
        ck("failed probe clears old proof",XposedRuntimeInspector.cached(),null)
    }
    Root.output="PID|com.whatsapp|123\nEND|WAEX_PROCESS_SNAPSHOT";XposedRuntimeInspector.inspect(listOf("com.whatsapp"))
    Root.output=null;ck("root denied",XposedRuntimeInspector.inspect(listOf("com.whatsapp")),null)
    ck("denied invalidates cached",XposedRuntimeInspector.cached(),null)
    Root.output="PID|com.whatsapp|123\nEND|WAEX_PROCESS_SNAPSHOT";XposedRuntimeInspector.inspect(listOf("com.whatsapp"));Root.fail=true
    ck("root exception unavailable",XposedRuntimeInspector.inspect(listOf("com.whatsapp")),null);Root.fail=false
    ck("exception clears cache",XposedRuntimeInspector.cached(),null)
    Root.output="PID|com.whatsapp|\nEND|WAEX_PROCESS_SNAPSHOT"
    ck("true absence is distinct",XposedRuntimeInspector.inspect(listOf("com.whatsapp"))!!.pidsFor("com.whatsapp",1000),emptySet<Int>())
    Root.output="PID|com.whatsapp|123\nEND|WAEX_PROCESS_SNAPSHOT";val calls=Root.calls
    ck("invalid package refused",XposedRuntimeInspector.inspect(listOf("com.whatsapp;id")),null)
    ck("invalid input never reaches shell",Root.calls,calls)
    ck("partial report refuses multiple packages",XposedRuntimeInspector.inspect(listOf("com.whatsapp","com.whatsapp.w4b")),null)
    Root.output="PID|com.whatsapp|123\nPID|com.whatsapp.w4b|456\nEND|WAEX_PROCESS_SNAPSHOT"
    ck("both identities present",XposedRuntimeInspector.inspect(listOf("com.whatsapp","com.whatsapp.w4b"))!!.runningPids.size,2)
    var immutable=false
    try{(snap.runningPids as MutableMap).clear()}catch(_:UnsupportedOperationException){immutable=true}
    ck("observations immutable",immutable,true)
    ck("empty request neutral",XposedRuntimeInspector.inspect(emptyList())!!.runningPids.size,0)
    val app=HookedAppInfo("com.whatsapp","WhatsApp","2.26.40.76",true,true,lastActiveTime=10000,runtimeStatus="READY",bridgeStatus="PROVIDER",processObservedAtElapsed=1000)
    fun card(a:HookedAppInfo,d:D=D.BUILTIN_RULE)=TargetRuntimePresentation.card(a,true,d)
    ck("current complete",card(app).severity,S.OK)
    for(state in listOf("UNSUPPORTED_VERSION","COMPATIBILITY_UNAVAILABLE","INITIALIZATION_FAILED","READY_WITH_ERRORS")){
        for(d in listOf(D.BUILTIN_RULE,D.CUSTOM_RULE,D.EXPLICIT_BYPASS))ck("target state wins over local rule $state $d",card(app.copy(runtimeStatus=state),d).severity,S.REVIEW)
    }
    for(state in listOf("INJECTED","INITIALIZING","future-state"))ck("not ready",card(app.copy(runtimeStatus=state)).severity,S.UNKNOWN)
    ck("unknown bridge isn't ready",card(app.copy(bridgeStatus="PENDING")).severity,S.UNKNOWN)
    ck("failed bridge",card(app.copy(bridgeStatus="FAILED")).severity,S.REVIEW)
    ck("fallback bridge",card(app.copy(bridgeStatus="DISK_FALLBACK")).severity,S.REVIEW)
    ck("bypass not certification",card(app,D.EXPLICIT_BYPASS).severity,S.REVIEW)
    val expired=TargetRuntimePresentation.asOf(app,35001,1001)
    ck("display expires heartbeat",expired.isHooked,false)
    ck("expired list hidden",expired.injectedHooks.size,0)
    ck("bridge historical",TargetRuntimePresentation.bridge(expired),"Last report (not current): Connected directly")
    ck("init historical",TargetRuntimePresentation.initialization(expired),"Last report (not current): Completed")
    ck("current bridge no historical label",TargetRuntimePresentation.bridge(app),"Connected directly")
    ck("display invalidates process probe",TargetRuntimePresentation.asOf(app,10001,26001).injectionEvidence,InjectionEvidence.PROCESS_OBSERVATION_UNAVAILABLE)
    ck("display never promotes stale evidence",TargetRuntimePresentation.asOf(expired,10000,1000).isHooked,false)
    ck("still fresh inclusive",TargetRuntimePresentation.asOf(app,35000,26000).isHooked,true)
    fun sum(apps:List<HookedAppInfo>,d:D=D.BUILTIN_RULE)=TargetRuntimePresentation.summarize(apps,apps.associate{it.packageName to d})
    ck("empty targets not supported",sum(emptyList()).healthy,null)
    ck("uninstalled not supported",sum(listOf(app.copy(isInstalled=false))).healthy,null)
    ck("no injection no false supported",sum(listOf(expired)).healthy,null)
    ck("unsupported target matters without injection",sum(listOf(expired),D.UNSUPPORTED).healthy,false)
    ck("target pause aggregate",sum(listOf(app.copy(runtimeStatus="UNSUPPORTED_VERSION"))).healthy,false)
    ck("bypass aggregate warning",sum(listOf(app),D.EXPLICIT_BYPASS).healthy,false)
    ck("complete reported",sum(listOf(app)).healthy,true)
    ck("one unknown not all confirmed",sum(listOf(app,expired.copy(packageName="com.whatsapp.w4b"))).healthy,null)
    ck("summary says not all feature acceptance",sum(listOf(app)).text,"Rules accepted; initialization reported")

    // Complete-looking final PID tokens are not completion evidence without the sentinel.
    Root.fail=false;Root.advance=0;SystemClock.time=1000
    Root.output="PID|com.whatsapp|42\nEND|WAEX_PROCESS_SNAPSHOT\n"
    ck("finished probe accepted",XposedRuntimeInspector.inspect(listOf("com.whatsapp"))!=null,true)
    Root.output="PID|com.whatsapp|42"
    ck("missing terminal record is unknown",XposedRuntimeInspector.inspect(listOf("com.whatsapp")),null)
    ck("truncated new probe removes cached proof",XposedRuntimeInspector.cached(),null)
    Root.output="PID|com.whatsapp|42\nEND|WAEX_PROCESS_SNAPSHOT\n"
    Root.advance=4000
    val slow=XposedRuntimeInspector.inspect(listOf("com.whatsapp"))!!
    ck("probe age starts before command",slow.observedAtElapsed,1000L)
    SystemClock.time=26001
    ck("slow probe cannot extend validity",XposedRuntimeInspector.cached(),null)
    SystemClock.time=1000;Root.advance=25001
    ck("probe expiring during collection rejected",XposedRuntimeInspector.inspect(listOf("com.whatsapp")),null)
    SystemClock.time=1000;Root.advance=0
    ck("custom allowance card is not verified",TargetRuntimePresentation.card(app,true,D.CUSTOM_RULE).severity,S.REVIEW)
    ck("custom allowance summary not verified",sum(listOf(app),D.CUSTOM_RULE).healthy,false)
    ck("other negative boot value is unknown",classifyInjection(true,-2,-2,42,setOf(42),1000,1001),InjectionEvidence.BOOT_UNKNOWN)
    val delayed=android.app.Activity();delayed.defer=true
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

    println("PASS: $checks runtime compatibility/evidence/queue presentation checks. Explicit Android and root doubles.")
}
