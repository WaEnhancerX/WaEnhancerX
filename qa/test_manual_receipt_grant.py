#!/usr/bin/env python3
"""Execute exact current BlueOnReply sender with real grant/store policies and explicit queue/Xposed seams."""
from pathlib import Path
import argparse, ast, hashlib, json, subprocess
R=Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser(description=__doc__);p.add_argument('--out',required=True,type=Path);p.add_argument('--source-root',default=R,type=Path);a=p.parse_args();a.out.mkdir(parents=True,exist_ok=False)
A=a.source_root/'app/src/main/java/com/waenhancer/xposed/features/privacy'
# Reuse only the source-extraction function, without executing the other runner.
module=ast.parse((R/'qa/test_receipt_job_execution_contract.py').read_text(encoding='utf-8'))
fn=next(n for n in module.body if isinstance(n,ast.FunctionDef) and n.name=='extract');space={};exec(compile(ast.Module(body=[fn],type_ignores=[]),'<source-extractor>','exec'),space);extract=space['extract']
source=(A/'BlueOnReplyHook.java').read_text(encoding='utf-8');anchors=['    private static int sendPendingReadJobs(', '    private static Object[] copyArguments(', '    private static final class ReadJobRecipe','    private static String canonicalJid('];
if '    private static final class ManualReadAttempt' in source:anchors.append('    private static final class ManualReadAttempt')
blocks=[extract(source,m) for m in anchors]
java=r'''
package com.waenhancer.xposed.features.privacy;
import java.util.*;import java.lang.reflect.*;
public final class ActualManualGrantChecks {
 static final String PERSON="1001@s.whatsapp.net",OTHER="2002@s.whatsapp.net",GROUP="3003@g.us",TAG="fixture";
 static final String MANUAL_CHAT_ATTEMPT="waex_manual_chat_attempt";
 static void markEvictedHandled(String recipient,ReadJobRecipe recipe){if(recipe!=null)throw new AssertionError("Unexpected eviction in small grant fixture");}
 static final PendingReadReceiptStore<ReadJobRecipe> PENDING_JOBS=new PendingReadReceiptStore<>(128,50);
 static final Manager manager=new Manager();static Method enqueueJobMethod;
 static RecipientReceiptAllowance grants=new RecipientReceiptAllowance(1000,4096);static long now;
 static Object getOrFindJobManager(){return manager;}
 public static class ManualJob {
  public final String jid;public final String[] ids;public final String[] diagnosticIds={"M2"};static boolean fail;
  public ManualJob(String jid,String[] ids){if(fail)throw new IllegalStateException("SYNTHETIC-PRIVATE-CONSTRUCTOR");this.jid=jid;this.ids=ids;}
 }
 public static class Manager {
  final List<Object> accepted=new ArrayList<>();boolean fail;
  public void enqueue(Object job){if(fail)throw new IllegalStateException("SYNTHETIC-PRIVATE-QUEUE");accepted.add(job);}
 }
 static class XposedHelpers {
  static final IdentityHashMap<Object,Map<String,Object>> fields=new IdentityHashMap<>();
  static void setAdditionalInstanceField(Object job,String key,Object value){fields.computeIfAbsent(job,x->new HashMap<>()).put(key,value);}
  static Object getAdditionalInstanceField(Object job,String key){return fields.getOrDefault(job,Collections.emptyMap()).get(key);}
 }
 static class XposedBridge {static final List<String> logs=new ArrayList<>();static void log(String text){logs.add(text);}}
 // Initial allowance transport is an explicit seam; the real bounded policy enforces its scope.
 static class HideReceiptsHook {
  static void allowExplicitReceiptIds(String key,Object[] args){for(Object arg:args)if(arg instanceof String[])for(String id:(String[])arg)grants.allow(key,id,now);}
  static void revokeExplicitReceiptIds(String key,Object[] args){for(Object arg:args)if(arg instanceof String[])for(String id:(String[])arg)grants.revoke(key,id);}
 }
 __PRODUCTION__
 static int checks;static void ok(boolean b,String label){checks++;if(!b)throw new AssertionError(label);}
 static void queue(String recipient,String id)throws Exception{PENDING_JOBS.add(recipient,new ReadJobRecipe(ManualJob.class.getConstructor(String.class,String[].class),new Object[]{recipient,new String[]{id}}));}
 static void reset(){
  for(String recipient:new String[]{PERSON,OTHER,GROUP})for(ReadJobRecipe r:PENDING_JOBS.snapshot(recipient))PENDING_JOBS.remove(recipient,r);
  manager.accepted.clear();manager.fail=false;ManualJob.fail=false;grants=new RecipientReceiptAllowance(1000,4096);now=0;XposedHelpers.fields.clear();XposedBridge.logs.clear();
 }
 static Object token(ManualJob job){return XposedHelpers.getAdditionalInstanceField(job,ExplicitReadReceiptPolicy.JOB_GRANT_KEY);}
 public static void main(String[] args)throws Exception{
  enqueueJobMethod=Manager.class.getMethod("enqueue",Object.class);
  reset();queue(PERSON,"M1");ok(sendPendingReadJobs(PERSON)==1,"real sender must enqueue one recipe");
  ManualJob job=(ManualJob)manager.accepted.get(0);Object token=token(job);ok(token!=null,"sender did not attach immutable action grant");
  ok(PENDING_JOBS.pendingCount(PERSON)==0,"accepted recipe not acknowledged");
  now=2000;ok(!grants.isAllowed(PERSON,"M1",now),"initial grant did not expire in fixture");
  ok(ExplicitReadReceiptPolicy.refreshJobGrant(token,job.jid,PERSON,false,grants,now),"original action grant failed after queue delay");
  ok(grants.isAllowed(PERSON,"M1",now),"original M1 permission not renewed");
  ok(!grants.isAllowed(PERSON,job.diagnosticIds[0],now),"unrelated host String[] granted M2");
  ok(!grants.isAllowed(OTHER,"M1",now),"read authorization crossed recipient");
  grants=new RecipientReceiptAllowance(1000,4096);
  ok(!ExplicitReadReceiptPolicy.refreshJobGrant(token,job.jid,GROUP,false,grants,now),"participant group replaced explicit action recipient");
  ok(!grants.isAllowed(GROUP,"M1",now)&&!grants.isAllowed(PERSON,"M1",now),"rejected participant refresh wrote a permission");
  ok(!ExplicitReadReceiptPolicy.refreshJobGrant(token,OTHER,PERSON,false,grants,now),"changed serialized recipient retained grant");
  ok(!ExplicitReadReceiptPolicy.refreshJobGrant(token,PERSON,PERSON,true,grants,now),"explicit action bypassed Ghost/delivery");
  ok(!ExplicitReadReceiptPolicy.refreshJobGrant(Boolean.TRUE,PERSON,PERSON,false,grants,now),"boolean/manual marker forged consent");
  job.ids[0]="M3";ok(ExplicitReadReceiptPolicy.refreshJobGrant(token,PERSON,PERSON,false,grants,now),"original grant disappeared after host array mutation");
  ok(grants.isAllowed(PERSON,"M1",now)&&!grants.isAllowed(PERSON,"M3",now),"mutable host array changed original authorization");
  reset();queue(PERSON,"M1");queue(OTHER,"M2");ok(sendPendingReadJobs("not-a-jid")==0,"unknown recipient dispatched jobs");
  ok(PENDING_JOBS.pendingCount(PERSON)==1&&PENDING_JOBS.pendingCount(OTHER)==1,"unknown recipient modified queues");
  manager.fail=true;ok(sendPendingReadJobs(PERSON)==0,"queue failure reported accepted");
  ok(PENDING_JOBS.pendingCount(PERSON)==1&&!grants.isAllowed(PERSON,"M1",now),"failed queue lost recipe or retained grant");
  manager.fail=false;ok(sendPendingReadJobs(PERSON)==1&&PENDING_JOBS.pendingCount(PERSON)==0,"retry did not acknowledge exact successful recipe");
  ok(PENDING_JOBS.pendingCount(OTHER)==1,"retry removed another recipient");
  reset();queue(PERSON,"M1");ManualJob.fail=true;ok(sendPendingReadJobs(PERSON)==0,"throwing constructor reported success");
  ok(PENDING_JOBS.pendingCount(PERSON)==1&&!grants.isAllowed(PERSON,"M1",now),"constructor failure lost retry scope or leaked allowance");
  ok(XposedBridge.logs.stream().noneMatch(s->s.contains("SYNTHETIC-PRIVATE")),"sender leaked private constructor/queue text");
  reset();queue(PERSON,"");ok(sendPendingReadJobs(PERSON)==0&&manager.accepted.isEmpty(),"no-ID recipe became blanket permission");
  ok(PENDING_JOBS.pendingCount(PERSON)==1,"unconfirmed empty recipe was removed");
  System.out.println("MANUAL_GRANT_CHECKS="+checks+"; exact production sender and immutable policy; explicit host queue/initial-allowance/Xposed boundaries");
 }
}
'''.replace('__PRODUCTION__','\n'.join(blocks))
f=a.out/'ActualManualGrantChecks.java';f.write_text(java,encoding='utf-8');classes=a.out/'classes';classes.mkdir()
production=[A/n for n in ('ExplicitReadReceiptPolicy.java','RecipientReceiptAllowance.java','BoundedReceiptAllowance.java','ReceiptJidPolicy.java','PendingReadReceiptStore.java')]
cmd=['javac','--release','17','-encoding','UTF-8','-d',str(classes),*map(str,production),str(f)]
r=subprocess.run(cmd,capture_output=True,timeout=60);(a.out/'compile.log').write_bytes(r.stdout+r.stderr)
if r.returncode:raise RuntimeError('COMPILE_FAILED '+r.stderr.decode('utf-8',errors='replace'))
r=subprocess.run(['java','-cp',str(classes),'com.waenhancer.xposed.features.privacy.ActualManualGrantChecks'],capture_output=True,timeout=30);(a.out/'behavior.log').write_bytes(r.stdout+r.stderr)
import re
log=(r.stdout+r.stderr).decode('utf-8',errors='replace');m=re.search(r'MANUAL_GRANT_CHECKS=(\d+)',log)
result={'compile_exit':0,'behavior_exit':r.returncode,'checks':int(m[1]) if m else 0,'completed':bool(m),'source_hashes':{str(p.relative_to(a.source_root)):hashlib.sha256(p.read_bytes()).hexdigest() for p in [*production,A/'BlueOnReplyHook.java']},'extracted_blocks':len(blocks),'scope':__doc__}
(a.out/'result.json').write_text(json.dumps(result,indent=2)+'\n',encoding='utf-8');print(log)
if r.returncode or not m:raise SystemExit(1)
