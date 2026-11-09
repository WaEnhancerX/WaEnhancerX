#!/usr/bin/env python3
"""Compile exact FeatureRegistry installation/reporting methods on the JVM.
Android IPC, JSON transport, Dex leases, feature bodies and lifecycle are explicit
counting fixtures. This is an operation-count contract, not a device speed test.
"""
from pathlib import Path
import argparse,hashlib,json,re,subprocess,shutil
P=argparse.ArgumentParser(description=__doc__)
P.add_argument('--source-root',type=Path,default=Path(__file__).resolve().parents[1]);P.add_argument('--out',type=Path,required=True)
a=P.parse_args();a.out.mkdir(parents=True,exist_ok=False)
p=a.source_root/'app/src/main/java/com/waenhancer/xposed/core/FeatureRegistry.java';source=p.read_text(encoding='utf-8')
main=(a.source_root/'app/src/main/java/com/waenhancer/xposed/MainHook.java').read_text(encoding='utf-8')
if not re.search(r'registry\.initializeAll\(\);\s*registry\.reportInjectedHooks\(appContext, packageName\);',main):raise RuntimeError('MainHook no longer owns the terminal startup snapshot; review this optimization')
def extract(anchor):
 start=source.index(anchor);brace=source.index('{',start);depth=0;state='code';i=brace
 while i<len(source):
  c=source[i];n=source[i:i+2]
  if state=='line':
   if c=='\n':state='code'
  elif state=='comment':
   if n=='*/':state='code';i+=1
  elif state in ('string','char'):
   if c=='\\':i+=1
   elif c==('"' if state=='string' else "'"):state='code'
  elif n=='//':state='line';i+=1
  elif n=='/*':state='comment';i+=1
  elif c=='"':state='string'
  elif c=="'":state='char'
  elif c=='{':depth+=1
  elif c=='}':
   depth-=1
   if not depth:return source[start:i+1]
  i+=1
 raise RuntimeError('Unterminated source block: '+anchor)
anchors=['public static final class FeatureEntry','public void initializeAll()','private void install(FeatureEntry entry)','private void installLocked(FeatureEntry entry)','private static boolean isEntryEnabled(FeatureEntry entry)','private void markDisabled(FeatureEntry entry)','public boolean hasFailures()','public void reportInjectedHooks(']
parts=[extract(x) for x in anchors]
fixtures=r'''
import java.util.*;import java.util.concurrent.atomic.AtomicBoolean;
public class StartupReportChecks {
 @interface NonNull {}
 enum Installation { STARTUP, LAZY_HOME }
 interface FeatureFactory { BaseFeature create() throws Throwable; }
 interface FeatureEnabled { boolean isEnabled(); }
 static class FeaturePolicy {}
 static class Activity {}
 static class BaseFeature {
  String name;boolean fail;int begun,ended,unhooked;
  BaseFeature(String n,boolean f){name=n;fail=f;}
  String getName(){return name;}
  void beginHookTracking(){begun++;}void endHookTracking(){ended++;}void unhookAll(){unhooked++;}
  void hook(){if(fail)throw new IllegalStateException("deliberate fixture failure");}
 }
 static class DexSearchEngine {
  static final DexSearchEngine INSTANCE=new DexSearchEngine();int acquisitions,releases,closes;
  static DexSearchEngine getInstance(){return INSTANCE;}
  void acquireLease(){acquisitions++;}void releaseLease(){releases++;}void close(){closes++;}
 }
 static class XposedBridge {static void log(Object o){} }
 static class ActivityTracker {
  interface ActivityListener {void onActivityCreated(Activity a);void onActivityResumed(Activity a);void onActivityStopped(Activity a);void onActivityDestroyed(Activity a);}
  static void addListener(ActivityListener l){} static Activity getCurrentActivity(){return null;}
 }
 static class LazyHookCoordinator {
  void onActivityCreated(Activity a){}void onActivityResumed(Activity a){}void onActivityDestroyed(Activity a){}
 }
 static class Uri {static Uri parse(String s){return new Uri();}}
 static class Bundle extends HashMap<String,Object> {
  void putString(String k,String v){put(k,v);}void putLong(String k,long v){put(k,v);}
 }
 static class JSONObject extends LinkedHashMap<String,Object> {}
 static class JSONArray {
  static List<Map<String,Object>> serialized; final List<Map<String,Object>> rows=new ArrayList<>();
  void put(JSONObject j){rows.add(new LinkedHashMap<>(j));}
  public String toString(){serialized=new ArrayList<>(rows);return "EXPLICIT_JSON_TRANSPORT_FIXTURE";}
 }
 static class Context {
  int reports,rowsSerialized;boolean fail;List<Map<String,Object>> last;
  Context getContentResolver(){return this;}
  Object call(Uri u,String method,String arg,Bundle extras){
   if(!"register_injected_hooks".equals(method))throw new AssertionError("Unexpected endpoint");
   if(!"fixture.whatsapp".equals(arg)||!arg.equals(extras.get("package")))throw new AssertionError("Target mismatch");
   if(!extras.containsKey("timestamp")||!extras.containsKey("hooking_mode"))throw new AssertionError("Missing protocol metadata");
   reports++;rowsSerialized+=JSONArray.serialized.size();last=new ArrayList<>(JSONArray.serialized);
   if(fail)throw new IllegalStateException("provider unavailable");return null;
  }
 }
 final List<FeatureEntry> features=new ArrayList<>();final LazyHookCoordinator lazyHooks=new LazyHookCoordinator();
 final Context reportingContext=new Context();final String reportingPackage="fixture.whatsapp",hookingMode="fixture";
 void applyUiPreHooks(){}
 void disposeLazyEntry(FeatureEntry entry,Activity a){throw new AssertionError("Disposal is outside this focused fixture");}
 __PRODUCTION__
 static int cases,checks,failures;
 static void ok(boolean b,String why){checks++;if(!b)throw new AssertionError(why);}
 static void test(String name,Runnable r){cases++;try{r.run();System.out.println("PASS "+name);}catch(AssertionError e){failures++;System.out.println("FAIL "+name+": "+e.getMessage());}}
 FeatureEntry add(String name,boolean enabled,boolean fail,Installation where){
  FeatureEntry e=new FeatureEntry(name,"Fixture",where,null,()->enabled,()->new BaseFeature(name,fail));features.add(e);return e;
 }
 void completeStartup(){initializeAll();reportInjectedHooks(reportingContext,reportingPackage);}
 public static void main(String[] args){
  for(int count:new int[]{1,8,28,65})test("terminal startup batch "+count,()->{
   StartupReportChecks r=new StartupReportChecks();for(int i=0;i<count;i++)r.add("F"+i,true,false,Installation.STARTUP);
   r.completeStartup();ok(r.reportingContext.reports==1,"expected 1 snapshot, got "+r.reportingContext.reports);
   ok(r.reportingContext.rowsSerialized==count,"must serialize only final catalogue once");
   ok(r.reportingContext.last.size()==count,"complete final snapshot required");
   for(FeatureEntry e:r.features){ok("ACTIVE".equals(e.status),"lost ACTIVE");ok(e.feature.begun==1&&e.feature.ended==1,"tracking contract changed");}
  });
  test("failed feature remains visible and does not stop siblings",()->{
   StartupReportChecks r=new StartupReportChecks();r.add("ok",true,false,Installation.STARTUP);r.add("bad",true,true,Installation.STARTUP);r.add("after",true,false,Installation.STARTUP);
   r.completeStartup();ok(r.hasFailures(),"failure suppressed");ok("ACTIVE".equals(r.features.get(2).status),"later hook skipped");
   ok("FAILED".equals(r.reportingContext.last.get(1).get("status")),"failure not reported");ok(r.reportingContext.last.get(1).containsKey("error"),"error missing");ok(r.reportingContext.reports==1,"redundant startup reports");
  });
  test("disabled and waiting states remain truthful",()->{
   StartupReportChecks r=new StartupReportChecks();r.add("off",false,false,Installation.STARTUP);r.add("lazy",true,false,Installation.LAZY_HOME);r.completeStartup();
   ok("DISABLED".equals(r.reportingContext.last.get(0).get("status")),"disabled changed");ok("WAITING".equals(r.reportingContext.last.get(1).get("status")),"lazy installed eagerly");ok(r.reportingContext.reports==1,"extra report");
  });
  test("late lazy install still publishes immediately and releases leases",()->{
   StartupReportChecks r=new StartupReportChecks();FeatureEntry e=r.add("lazy",true,false,Installation.LAZY_HOME);r.completeStartup();int before=DexSearchEngine.INSTANCE.closes;r.install(e);
   ok(r.reportingContext.reports==2,"lazy report lost");ok("ACTIVE".equals(r.reportingContext.last.get(0).get("status")),"stale lazy snapshot");ok(DexSearchEngine.INSTANCE.closes==before+1,"lazy Dex index not closed");r.install(e);ok(r.reportingContext.reports==2,"duplicate install re-reported");
  });
  test("lazy failure still publishes",()->{
   StartupReportChecks r=new StartupReportChecks();FeatureEntry e=r.add("lazybad",true,true,Installation.LAZY_HOME);r.completeStartup();r.install(e);
   ok(r.reportingContext.reports==2,"lazy failure report lost");ok("FAILED".equals(r.reportingContext.last.get(0).get("status")),"lazy failure concealed");
  });
  test("provider failure cannot block successful initialization",()->{
   StartupReportChecks r=new StartupReportChecks();r.reportingContext.fail=true;r.add("a",true,false,Installation.STARTUP);r.completeStartup();ok("ACTIVE".equals(r.features.get(0).status),"transport broke hooks");ok(r.reportingContext.reports==1,"provider failure retried per startup hook");
  });
  test("leases balanced",()->ok(DexSearchEngine.INSTANCE.acquisitions==DexSearchEngine.INSTANCE.releases,"Dex lease leak"));
  System.out.println("RESULT cases="+cases+" checks="+checks+" failures="+failures);
  if(failures!=0)throw new AssertionError("REGRESSION "+failures);
 }
}
'''.replace('__PRODUCTION__','\n'.join(parts))
f=a.out/'StartupReportChecks.java';f.write_text(fixtures,encoding='utf-8');classes=a.out/'classes';classes.mkdir()
for tool in ['javac','java']:
 if not shutil.which(tool):raise RuntimeError(tool+' unavailable')
c=subprocess.run(['javac','--release','17','-encoding','UTF-8','-d',str(classes),str(f)],capture_output=True,timeout=60);(a.out/'compile.log').write_bytes(c.stdout+c.stderr)
if c.returncode:raise RuntimeError('COMPILE_FAILED '+c.stderr.decode(errors='replace')[-2000:])
r=subprocess.run(['java','-cp',str(classes),'StartupReportChecks'],capture_output=True,timeout=30);(a.out/'behavior.log').write_bytes(r.stdout+r.stderr)
s=r.stdout.decode(errors='replace');m=re.search(r'RESULT cases=(\d+) checks=(\d+) failures=(\d+)',s)
if not m:raise RuntimeError('No completed result')
result={'source_sha256':hashlib.sha256(p.read_bytes()).hexdigest(),'source':str(p),'extracted_production_blocks':len(parts),'cases':int(m[1]),'checks':int(m[2]),'failures':int(m[3]),'compile_exit':c.returncode,'behavior_exit':r.returncode,'scope':'exact production methods; explicit IPC/JSON/feature/Dex/lifecycle counting boundaries; not complete registry nor runtime benchmark'}
(a.out/'result.json').write_text(json.dumps(result,indent=2)+'\n',encoding='utf-8');print(s);print(json.dumps(result));raise SystemExit(r.returncode)
