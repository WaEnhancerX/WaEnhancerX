#!/usr/bin/env python3
"""Execute exact CallsHub state/effect/toggle fragments on Kotlin/JVM.
Compose scheduling and the preference manager are explicit in-memory boundaries;
this does not compile or render the complete Android screen.
"""
from pathlib import Path
import argparse,subprocess,hashlib,json,os,shutil
P=argparse.ArgumentParser();P.add_argument('--source-root',type=Path,default=Path(__file__).resolve().parents[1]);P.add_argument('--out',type=Path,required=True)
P.add_argument('--java',default='java');P.add_argument('--kotlin-home',type=Path)
a=P.parse_args()
a.out.mkdir(parents=True,exist_ok=False)
path=a.source_root/'app/src/main/java/com/waenhancer/ui/screens/calls/CallsHubScreen.kt'
s=path.read_text(encoding='utf-8')
start=s.index('    // Call Blocker & Privacy State');end=s.index('    var callRejectType',start);initial=s[start:end]
def braces(pos):
 start=s.index('{',pos);depth=1;i=start+1;quoted=False;escaped=False
 while i<len(s):
  c=s[i]
  if quoted:
   if escaped:escaped=False
   elif c=='\\':escaped=True
   elif c=='"':quoted=False
  elif c=='"':quoted=True
  elif c=='{':depth+=1
  elif c=='}':
   depth-=1
   if depth==0:return s[start+1:i]
  i+=1
 raise RuntimeError('Unbalanced exact source fragment')
tile=s.index('// ── SWITCH TILE: ENABLE CALL BLOCKING')
row=braces(s.index('.clickable',tile));switch=braces(s.index('onCheckedChange =',tile));switch=switch[switch.index('next ->')+len('next ->'):]
code=r'''
import kotlin.reflect.KProperty
class State<T>(var value:T) { operator fun getValue(a:Any?,p:KProperty<*>)=value;operator fun setValue(a:Any?,p:KProperty<*>,v:T){value=v} }
fun <T> mutableStateOf(v:T)=State(v)
object Composition { var slot=0; val slots=mutableMapOf<Int,Pair<List<Any?>,Any?>>(); fun begin(fresh:Boolean){slot=0;if(fresh)slots.clear()} }
@Suppress("UNCHECKED_CAST") fun <T> remember(vararg keys:Any?,calculation:()->State<T>):State<T> {
 val id=Composition.slot++;val k=keys.toList();val old=Composition.slots[id]
 if(old!=null && old.first==k)return old.second as State<T>
 val v=calculation();Composition.slots[id]=k to v;return v
}
fun LaunchedEffect(vararg keys:Any?,block:()->Unit){
 val id=Composition.slot++;val k=keys.toList();val old=Composition.slots[id]
 if(old==null || old.first!=k){block();Composition.slots[id]=k to true}
}
class Prefs { var targetEpoch=0;var target="personal";val stores=mutableMapOf<String,MutableMap<String,Any?>>();var writes=0
 fun data()=stores.getOrPut(target){mutableMapOf()}
 fun getString(k:String,d:String?):String?=if(data().containsKey(k))data()[k] as String? else d
 fun getBoolean(k:String,d:Boolean):Boolean=if(data().containsKey(k))data()[k] as Boolean else d
 fun putBoolean(k:String,v:Boolean){data()[k]=v;writes++}
 fun putString(k:String,v:String){data()[k]=v;writes++}
 fun switchTarget(v:String){target=v;targetEpoch++}
}
class Frame(val mode:()->String,val enabled:()->Boolean,val row:()->Unit,val toggle:(Boolean)->Unit)
fun render(prefManager:Prefs,fresh:Boolean=true):Frame {
 Composition.begin(fresh)
__INIT__
 return Frame({callPrivacyMode},{callPrivacyEnabled},{__ROW__},{next->__SWITCH__})
}
var cases=0;var checks=0;val failed=mutableListOf<String>()
fun verify(value:Boolean,label:String){checks++;if(!value)throw AssertionError(label)}
fun test(name:String,block:()->Unit){cases++;try{block();println("PASS "+name)}catch(e:AssertionError){failed.add(name+": "+e.message);println("FAIL "+name+": "+e.message)}}
fun main(){
 for(mode in listOf("0","1","2","3","4")) for(flag in listOf<Boolean?>(null,false,true)) {
  test("read-only mode="+mode+" flag="+flag){
   val p=Prefs();p.data()["call_privacy"]=mode;if(flag!=null)p.data()["call_privacy_enabled"]=flag
   val before=p.data().toMap();val f=render(p)
   val expected=flag ?: (mode!="0")
   verify(f.enabled()==expected,"explicit flag must override retained mode")
   verify(f.mode()==mode,"must preserve selected legacy mode")
   verify(p.data()==before && p.writes==0,"opening must never write preferences")
   val second=render(p);verify(second.enabled()==expected,"reopen must preserve opt-out")
   verify(p.data()==before && p.writes==0,"reopen must be read-only")
  }
 }
 test("empty configuration stays disabled across three visits"){
  val p=Prefs();repeat(3){val f=render(p);verify(!f.enabled(),"fresh account enabled without consent");verify(p.data().isEmpty(),"visit changed absent preferences")}
 }
 for(mode in listOf("1","2","3","4"))for(which in listOf("row","switch")){
  test("disable remains off mode="+mode+" via="+which){
   val p=Prefs();p.data()["call_privacy"]=mode;p.data()["call_privacy_enabled"]=true
   val f=render(p);if(which=="row")f.row() else f.toggle(false)
   verify(p.data()["call_privacy_enabled"]==false,"disable not stored")
   val again=render(p);verify(!again.enabled(),"screen silently re-enabled blocking")
   verify(p.data()["call_privacy"]==mode,"disable/reopen changed chosen policy")
  }
 }
 for(which in listOf("row","switch"))test("explicit enable initializes default via="+which){
  val p=Prefs();val f=render(p);if(which=="row")f.row() else f.toggle(true)
  verify(p.data()["call_privacy_enabled"]==true,"enable must be explicit")
  verify(p.data()["call_privacy"]=="1","first opt-in must initialize a valid blocking mode")
  f.toggle(false);verify(!render(p).enabled(),"explicit off must survive reopen")
 }
 for(mode in listOf("3","4"))for(which in listOf("row","switch"))test("explicit re-enable preserves custom mode="+mode+" via="+which){
  val p=Prefs();p.data()["call_privacy"]=mode;p.data()["call_privacy_enabled"]=false
  val f=render(p);if(which=="row")f.row() else f.toggle(true)
  verify(p.data()["call_privacy_enabled"]==true,"explicit enable not stored")
  verify(p.data()["call_privacy"]==mode && f.mode()==mode,"existing contact policy replaced by block all")
 }
 test("target switch refreshes mode and explicit opt-out without writes"){
  val p=Prefs();p.data()["call_privacy"]="2";p.data()["call_privacy_enabled"]=true;render(p)
  p.switchTarget("business");p.data()["call_privacy"]="1";p.data()["call_privacy_enabled"]=false
  val f=render(p,false);verify(f.mode()=="1","mode stale after target switch");verify(!f.enabled(),"target explicit off ignored")
  verify(p.writes==0,"target change caused writes")
 }
 for(which in listOf("row","switch"))test("stale callback cannot modify another target via="+which){
  val p=Prefs();p.data()["call_privacy"]="0";p.data()["call_privacy_enabled"]=false;val old=render(p)
  p.switchTarget("business");p.data()["call_privacy"]="4";p.data()["call_privacy_enabled"]=false
  val before=p.data().toMap();if(which=="row")old.row() else old.toggle(true)
  verify(p.data()==before && p.writes==0,"stale target callback wrote a different account")
 }
 for(mode in listOf("invalid","99",""))test("invalid retained mode is not activation consent: "+mode){
  val p=Prefs();p.data()["call_privacy"]=mode;val before=p.data().toMap();val f=render(p)
  verify(!f.enabled() && f.mode()=="0","invalid mode enabled blocking")
  verify(p.data()==before && p.writes==0,"invalid preference silently rewritten")
 }
 println("SUMMARY cases="+cases+" checks="+checks+" failures="+failed.size)
 if(failed.isNotEmpty())throw AssertionError("REGRESSION_CASES="+failed.size)
}
'''.replace('__INIT__',initial).replace('__ROW__',row).replace('__SWITCH__',switch)
kt=a.out/'CallsOptOutFixture.kt';kt.write_text(code,encoding='utf-8')
java=shutil.which(a.java) or (str(Path(a.java).resolve()) if Path(a.java).is_file() else None)
if not java:raise RuntimeError('Java runtime unavailable; provide --java or PATH')
kh=a.kotlin_home
if kh is None:
 compiler=shutil.which('kotlinc.bat' if os.name=='nt' else 'kotlinc')
 if not compiler:raise RuntimeError('Kotlin distribution unavailable; provide --kotlin-home')
 kh=Path(compiler).resolve().parent.parent
kh=kh.resolve()
if not (kh/'lib/kotlin-compiler.jar').is_file():raise RuntimeError('Not a complete Kotlin compiler distribution: '+str(kh))
jar=a.out/'calls-test.jar'
command=[str(java),'-cp',str(kh/'lib/*'),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-kotlin-home',str(kh),'-jvm-target','17','-include-runtime','-d',str(jar),str(kt)]
c=subprocess.run(command,capture_output=True,timeout=90);(a.out/'compile.log').write_bytes(c.stdout+c.stderr)
if c.returncode:raise RuntimeError('COMPILE_FAILED '+(c.stdout+c.stderr).decode(errors='replace')[-3000:])
r=subprocess.run([str(java),'-jar',str(jar)],capture_output=True,timeout=45);(a.out/'run.log').write_bytes(r.stdout+r.stderr)
text=r.stdout.decode();import re
m=re.search(r'SUMMARY cases=(\d+) checks=(\d+) failures=(\d+)',text)
if not m:raise RuntimeError('NO_COMPLETED_SUMMARY')
result={'source':str(path),'source_sha256':hashlib.sha256(path.read_bytes()).hexdigest(),'cases':int(m[1]),'checks':int(m[2]),'failures':int(m[3]),'exit':r.returncode,'compiled':True,'scope':'exact production Kotlin state/effect/toggle fragments; explicit Compose and preferences doubles; not full screen/device'}
(a.out/'result.json').write_text(json.dumps(result,indent=2)+'\n');print(text);print(json.dumps(result));raise SystemExit(r.returncode)
