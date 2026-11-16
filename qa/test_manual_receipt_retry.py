#!/usr/bin/env python3
"""Compose real receipt callbacks, exact manual sender and real bounded store; no WhatsApp/network."""
from pathlib import Path
import argparse, ast, hashlib, importlib.util, json, os, re, shutil, subprocess
ROOT=Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser(description=__doc__);p.add_argument('--out',required=True,type=Path);p.add_argument('--source-root',type=Path,default=ROOT);p.add_argument('--kotlinc',default=os.environ.get('KOTLINC') or shutil.which('kotlinc'));a=p.parse_args();a.out.mkdir(parents=True,exist_ok=False)
spec=importlib.util.spec_from_file_location('receipt_boundary',ROOT/'qa/test_receipt_stanza_runtime.py');h=importlib.util.module_from_spec(spec);spec.loader.exec_module(h)
module=ast.parse((ROOT/'qa/test_receipt_job_execution_contract.py').read_text(encoding='utf-8'));fn=next(n for n in module.body if isinstance(n,ast.FunctionDef) and n.name=='extract');space={};exec(compile(ast.Module(body=[fn],type_ignores=[]),'<source-extractor>','exec'),space);extract=space['extract']
area=a.source_root/h.AREA;source=(area/'BlueOnReplyHook.java').read_text(encoding='utf-8')
markers=['    private static int sendPendingReadJobs(', '    private static Object[] copyArguments(', '    private static final class ReadJobRecipe', '    private static String canonicalJid(', '    public static void prepareSuppressedReadJob(', '    public static void recordSuppressedReadJob(', '    private static final class PendingChatReceipt', '    public static Set<String> readReceiptMessageIds(', '    public static boolean isManualChatReceipt(']
if '    private static final class ManualReadAttempt' in source:markers.append('    private static final class ManualReadAttempt')
blocks=[extract(source,m) for m in markers]
blue='''package com.waenhancer.xposed.features.privacy;
import java.util.*;import java.lang.reflect.*;
import de.robv.android.xposed.XposedHelpers;import de.robv.android.xposed.XposedBridge;
public final class BlueOnReplyHook {
 static final String TAG="fixture",PENDING_CHAT_RECEIPT="waex_pending_chat_receipt",MANUAL_CHAT_ATTEMPT="waex_manual_chat_attempt";
 static final PendingReadReceiptStore<ReadJobRecipe> PENDING_JOBS=new PendingReadReceiptStore<>(128,50);
 static Method enqueueJobMethod;static Object manager;
 static Object getOrFindJobManager(){return manager;}
 static void markEvictedHandled(String recipient,ReadJobRecipe evicted){if(evicted!=null)throw new AssertionError("Unexpected bounded-store eviction in small retry case");}
 public static boolean isManualStatusSeenEnabled(android.content.SharedPreferences p){return false;}
 public static boolean isManualStatusReceipt(Object job){return false;}
 public static void captureSuppressedStatusReadJob(Constructor<?> c,Object[] a){throw new AssertionError("Status outside chat retry fixture");}
 public static void recordObservedReceipt(String j,Object t,String i){throw new AssertionError("Observed inbox outside retry fixture");}
 public static int send(String recipient){return sendPendingReadJobs(recipient);}
 public static int pending(String recipient){return PENDING_JOBS.pendingCount(recipient);}
 public static void reset(){for(String recipient:new String[]{"1001@s.whatsapp.net","2002@s.whatsapp.net"})for(ReadJobRecipe r:PENDING_JOBS.snapshot(recipient))PENDING_JOBS.remove(recipient,r);}
 __PRODUCTION__
}'''.replace('__PRODUCTION__','\n'.join(blocks))
stubs=dict(h.STUBS);stubs['com/waenhancer/xposed/features/privacy/BlueOnReplyHook.java']=blue
stubs['android/os/SystemClock.java']='package android.os;public final class SystemClock { public static long now=1_000_000L;public static long elapsedRealtime(){return now;} }'
bridge='de/robv/android/xposed/XposedBridge.java'
# Explicit constructor instrumentation: reflection itself does not execute Xposed in a JVM.
# This invokes the actual registered after-constructor callback once for every synthetic job.
anchor=' public static Object construct(Constructor<?> constructor,Object... args)throws Throwable{'
assert anchor in stubs[bridge]
stubs[bridge]=stubs[bridge].replace(anchor,''' public static void constructed(Constructor<?> constructor,Object instance,Object[] args){
  XC_MethodHook.MethodHookParam p=new XC_MethodHook.MethodHookParam();p.method=constructor;p.thisObject=instance;p.args=args;p.result=instance;
  try{for(XC_MethodHook callback:new ArrayList<>(hooks.getOrDefault(constructor,List.of())))callback.afterHookedMethod(p);}
  catch(Throwable e){throw new AssertionError("Constructor callback failed",e);}
 }
'''+anchor)
java=r'''package com.waenhancer.xposed.features.privacy;
import java.lang.reflect.*;import java.util.*;
import android.content.*;import org.json.JSONObject;
import com.waenhancer.xposed.core.devkit.DexSearchEngine;
import de.robv.android.xposed.*;
public final class ManualReceiptRetryChecks {
 static final String PERSON="1001@s.whatsapp.net",OTHER="2002@s.whatsapp.net";
 static final Prefs prefs=new Prefs();static int checks,scenarios;static final List<String> failures=new ArrayList<>();
 static class Prefs implements SharedPreferences {
  final Map<String,Object> values=new HashMap<>();public boolean getBoolean(String k,boolean f){Object v=values.get(k);return v==null?f:(Boolean)v;}
  public boolean contains(String k){return values.containsKey(k);}public Map<String,?> getAll(){return values;}
 }
 public static final class Job {
  public String jid,participant;public String[] ids;public String[] diagnosticIds={"DIAGNOSTIC"};public int executions;
  public Job(String recipient,String[] ids){this.jid=recipient;this.ids=ids;
   try{XposedBridge.constructed(Job.class.getConstructor(String.class,String[].class),this,new Object[]{recipient,ids});}
   catch(ReflectiveOperationException e){throw new AssertionError(e);}
  }
  public void execute(){executions++;}
 }
 public static final class Manager {
  final List<Job> accepted=new ArrayList<>();boolean synchronous,throwAfterVeto,fail;boolean keepRead=true;boolean concurrent;Thread worker;final java.util.concurrent.atomic.AtomicReference<Throwable> workerFailure=new java.util.concurrent.atomic.AtomicReference<>();String policy;
  public void enqueue(Object value)throws Throwable{
   if(fail)throw new IllegalStateException("synthetic queue reject");Job j=(Job)value;accepted.add(j);
   if(concurrent){
    veto(policy,true);java.util.concurrent.CountDownLatch start=new java.util.concurrent.CountDownLatch(1);
    worker=new Thread(()->{try{start.await();execute(j);}catch(Throwable failure){workerFailure.set(failure);}},"manual-veto-fixture");
    worker.setDaemon(true);worker.start();start.countDown();Thread.yield();return;
   }
   if(synchronous){if(!keepRead){prefs.values.put("hide_read_receipts",false);prefs.values.put("hideread",false);}veto(policy,true);execute(j);if(throwAfterVeto)throw new IllegalStateException("synthetic ambiguous enqueue");}
  }
 }
 static void check(boolean condition,String label){checks++;if(!condition)throw new AssertionError(label);}
 static void veto(String name,boolean value){
  prefs.values.put("hide_delivery_receipts",false);prefs.values.put("ghostmode_active",false);CustomPrivacyHook.contacts.clear();
  if("delivery".equals(name))prefs.values.put("hide_delivery_receipts",value);
  else if("ghost".equals(name)){prefs.values.put("ghostmode_active",value);}
  else if("contact".equals(name))CustomPrivacyHook.contacts.put(PERSON,new JSONObject().put("HideReceipt",value));
 }
 static void execute(Job j)throws Throwable{XposedBridge.invoke(Job.class.getMethod("execute"),j);}
 static Manager setup()throws Throwable{
  android.os.SystemClock.now+=1_000_000L;BlueOnReplyHook.reset();prefs.values.clear();prefs.values.put("hide_read_receipts",true);prefs.values.put("hideread",true);prefs.values.put("blueonreply",true);
  veto("delivery",false);XposedBridge.hooks.clear();XposedBridge.logs.clear();DexSearchEngine.classes.clear();DexSearchEngine.methods.clear();
  HideReceiptsHook.revokeExplicitReceiptIds(PERSON,new Object[]{new String[]{"M1","DIAGNOSTIC","MUTATED"}});
  HideReceiptsHook.revokeExplicitReceiptIds(OTHER,new Object[]{new String[]{"M2"}});
  DexSearchEngine.classes.put("waex_send_read_receipt_job_class",Job.class);
  DexSearchEngine.methods.put("waex_send_read_receipt_execution_v2",Job.class.getMethod("execute"));
  HideReceiptsHook hook=new HideReceiptsHook(new Context(),ManualReceiptRetryChecks.class.getClassLoader(),prefs);
  Method install=HideReceiptsHook.class.getDeclaredMethod("hookSendReadReceiptJob");install.setAccessible(true);install.invoke(hook);
  check(XposedBridge.hooks.containsKey(Job.class.getMethod("execute")),"real execution callback not installed");
  Job initial=new Job(PERSON,new String[]{"M1"});execute(initial);check(initial.executions==0,"automatic read not hidden");
  check(BlueOnReplyHook.pending(PERSON)==1,"real constructor/execution did not stage initial recipe");
  Job other=new Job(OTHER,new String[]{"M2"});execute(other);check(BlueOnReplyHook.pending(OTHER)==1,"other recipient baseline missing");
  Manager m=new Manager();BlueOnReplyHook.manager=m;BlueOnReplyHook.enqueueJobMethod=Manager.class.getMethod("enqueue",Object.class);return m;
 }
 static void retry(String policy,boolean sync,boolean exception,boolean mutate)throws Throwable{retry(policy,sync,exception,mutate,true);}
 static void retry(String policy,boolean sync,boolean exception,boolean mutate,boolean keepRead)throws Throwable{
  Manager m=setup();m.policy=policy;m.synchronous=sync;m.throwAfterVeto=exception;m.keepRead=keepRead;
  int sent=BlueOnReplyHook.send(PERSON);check(sent==(exception?0:1),"enqueue return contract changed");check(m.accepted.size()==1,"first job missing");
  Job first=m.accepted.get(0);
  if(!sync){check(BlueOnReplyHook.pending(PERSON)==0,"accepted recipe retained before local veto");if(mutate)first.ids[0]="MUTATED";if(!keepRead){prefs.values.put("hide_read_receipts",false);prefs.values.put("hideread",false);}veto(policy,true);execute(first);}
  check(first.executions==0,"privacy veto executed host body");
  check(BlueOnReplyHook.pending(PERSON)==1,"manual veto lost or duplicated retry recipe");
  execute(first);check(BlueOnReplyHook.pending(PERSON)==1,"duplicate callback duplicated recovered recipe");
  check(BlueOnReplyHook.pending(OTHER)==1,"veto crossed recipient");
  veto(policy,false);m.synchronous=false;m.throwAfterVeto=false;
  check(m.accepted.size()==1,"disabling veto automatically enqueued a receipt");
  check(BlueOnReplyHook.send(PERSON)==1,"next explicit action could not retry");check(m.accepted.size()==2,"retry missing");
  Job second=m.accepted.get(1);check(Arrays.equals(second.ids,new String[]{"M1"}),"retry reconstructed unapproved host IDs");
  check(BlueOnReplyHook.pending(PERSON)==0,"second enqueue not acknowledged");
  veto(policy,true);execute(first);check(BlueOnReplyHook.pending(PERSON)==0,"late old callback resurrected consumed retry");veto(policy,false);execute(second);
  check(second.executions==1,"authorized retry did not execute exactly once");check(BlueOnReplyHook.pending(PERSON)==0,"successful retry not acknowledged");
  check(BlueOnReplyHook.pending(OTHER)==1,"retry removed other recipient");
 }
 interface Case{void run()throws Throwable;}
 static void test(String name,Case action){scenarios++;try{action.run();}catch(Throwable e){failures.add(name+": "+e.getClass().getSimpleName()+": "+e.getMessage());}}
 public static void main(String[] args)throws Throwable{
  for(String policy:new String[]{"delivery","ghost","contact"}){
   test("delayed-"+policy,()->retry(policy,false,false,false));test("synchronous-"+policy,()->retry(policy,true,false,false));
  }
  test("synchronous-veto-enqueue-exception",()->retry("delivery",true,true,false));
  test("delayed-veto-mutated-host-array",()->retry("delivery",false,false,true));
  test("delayed-delivery-with-read-disabled",()->retry("delivery",false,false,false,false));
  test("synchronous-delivery-with-read-disabled",()->retry("delivery",true,false,false,false));
  test("queue-rejected-retains-original",()->{Manager m=setup();m.fail=true;check(BlueOnReplyHook.send(PERSON)==0,"queue reject treated as success");check(BlueOnReplyHook.pending(PERSON)==1,"rejected queue lost original");m.fail=false;check(BlueOnReplyHook.send(PERSON)==1,"queue retry missing");execute(m.accepted.get(0));check(m.accepted.get(0).executions==1,"retry body missing");});
  test("ordinary-explicit-accepted",()->{Manager m=setup();check(BlueOnReplyHook.send(PERSON)==1,"ordinary enqueue failed");execute(m.accepted.get(0));check(m.accepted.get(0).executions==1&&BlueOnReplyHook.pending(PERSON)==0,"ordinary explicit receipt changed");});
  test("retry-grant-does-not-authorize-other-jobs",()->{
   Manager m=setup();check(BlueOnReplyHook.send(PERSON)==1,"initial enqueue");veto("delivery",true);execute(m.accepted.get(0));check(BlueOnReplyHook.pending(PERSON)==1,"retry source missing");
   veto("delivery",false);check(BlueOnReplyHook.send(PERSON)==1,"explicit retry");
   for(Job probe:new Job[]{new Job(PERSON,new String[]{"DIAGNOSTIC"}),new Job(PERSON,new String[]{"MUTATED"}),new Job(OTHER,new String[]{"M1"})}){
    execute(probe);check(probe.executions==0,"retry grant authorized an unrelated recipient or ID");
   }
   execute(m.accepted.get(1));check(m.accepted.get(1).executions==1,"approved retry was blocked");
  });
  test("enqueue-ack-races-actual-worker-veto",()->{
   for(int repeat=0;repeat<20;repeat++){
    Manager m=setup();m.concurrent=true;m.policy="delivery";check(BlueOnReplyHook.send(PERSON)==1,"concurrent enqueue rejected");
    m.worker.join(5000);check(!m.worker.isAlive(),"worker did not terminate");if(m.workerFailure.get()!=null)throw m.workerFailure.get();
    check(m.accepted.size()==1&&m.accepted.get(0).executions==0,"concurrent veto executed or re-enqueued host job");
    check(BlueOnReplyHook.pending(PERSON)==1,"concurrent acknowledgement/veto lost or duplicated recipe");
    check(BlueOnReplyHook.pending(OTHER)==1,"concurrent recovery crossed recipient");
   }
  });
  System.out.println("MANUAL_RETRY_MATRIX="+scenarios+";CHECKS="+checks+";FAILURES="+failures.size());for(String f:failures)System.out.println("FAIL: "+f);if(!failures.isEmpty())System.exit(1);
 }
}
'''
generated=a.out/'generated';classes=a.out/'classes';generated.mkdir();classes.mkdir();stub_paths=[];blue_path=None
for name,content in stubs.items():
 f=generated/name;f.parent.mkdir(parents=True,exist_ok=True);f.write_text(content,encoding='utf-8');
 if name.endswith('/BlueOnReplyHook.java'):blue_path=f
 else:stub_paths.append(f)
fixture=a.out/'ManualReceiptRetryChecks.java';fixture.write_text(java,encoding='utf-8')
kotlin=Path(a.kotlinc).resolve().parents[1];stdlib=kotlin/'lib/kotlin-stdlib.jar';assert stdlib.is_file(),'Complete Kotlin distribution required'
dexkit=Path(os.environ['WAEX_QA_DEXKIT_JAR']);assert hashlib.sha256(dexkit.read_bytes()).hexdigest()=='195979d9576ba6d68a1c60bdd3e38cd3aed068d4e0c5c6c99caa50bc25fcb9f1'
cp=os.pathsep.join(map(str,[classes,stdlib,dexkit]))
def command(args,name,negative=False):
 r=subprocess.run(list(map(str,args)),capture_output=True,timeout=90);(a.out/name).write_bytes(r.stdout+r.stderr)
 if r.returncode and not negative:raise RuntimeError('COMPILE_FAILED '+(r.stdout+r.stderr).decode('utf-8',errors='replace')[-9000:])
 return r
command(['javac','--release','17','-encoding','UTF-8','-cp',cp,'-d',classes,*stub_paths],'compile-boundaries.log')
base=a.source_root/'app/src/main/java/com/waenhancer/xposed/core/BaseFeature.kt';lifecycle=[base.parent/n for n in ('HookRegistrar.java','ViewHookDispatcher.java')]
command(['java','-cp',kotlin/'lib/*','org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-kotlin-home',kotlin,'-jvm-target','17','-classpath',cp,'-d',classes,base,*lifecycle],'compile-base.log')
production=[area/n for n in [*h.PRODUCTION,'PendingReadReceiptStore.java']]
command(['javac','--release','17','-encoding','UTF-8','-cp',cp,'-d',classes,*lifecycle,*production,blue_path,fixture],'compile-production.log')
r=command(['java','-ea','-cp',cp,'com.waenhancer.xposed.features.privacy.ManualReceiptRetryChecks'],'behavior.log',True);log=(r.stdout+r.stderr).decode('utf-8',errors='replace');print(log)
m=re.search(r'MANUAL_RETRY_MATRIX=(\d+);CHECKS=(\d+);FAILURES=(\d+)',log);complete=bool(m) and int(m[1])==14
result={'scenarios':int(m[1]) if m else 0,'checks':int(m[2]) if m else 0,'failures':int(m[3]) if m else None,'completed':complete,'exit':r.returncode,'source_sha256':{str(f.relative_to(a.source_root)):hashlib.sha256(f.read_bytes()).hexdigest() for f in [*production,area/'BlueOnReplyHook.java',base,*lifecycle]},'blue_extracted_blocks':len(blocks),'android_or_network':False,'constructor_callbacks_dispatched':True}
(a.out/'result.json').write_text(json.dumps(result,indent=2)+'\n',encoding='utf-8')
raise SystemExit(0 if complete and r.returncode==0 and int(m[3])==0 else 1)
