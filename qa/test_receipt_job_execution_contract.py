#!/usr/bin/env python3
"""Validate exact receipt installation/resolution with JVM doubles, not LSPosed."""
from pathlib import Path
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]

def extract(source, marker):
    """Copy one actual Java declaration, preserving its body byte for byte."""
    start = source.index(marker)
    pos = source.index('{', start)
    depth = 0
    state = 'code'
    i = pos
    while i < len(source):
        c, pair = source[i], source[i:i + 2]
        if state == 'line':
            if c == '\n': state = 'code'
        elif state == 'comment':
            if pair == '*/': state = 'code'; i += 1
        elif state in ('string', 'char'):
            if c == '\\': i += 1
            elif c == ('"' if state == 'string' else "'"): state = 'code'
        elif pair == '//': state = 'line'; i += 1
        elif pair == '/*': state = 'comment'; i += 1
        elif c == '"': state = 'string'
        elif c == "'": state = 'char'
        elif c == '{': depth += 1
        elif c == '}':
            depth -= 1
            if depth == 0: return source[start:i + 1]
        i += 1
    raise ValueError('Unclosed Java declaration: ' + marker)

def run_java(name, body, production):
    for tool in ('javac', 'java'):
        if not shutil.which(tool): raise SystemExit('Required tool missing: ' + tool)
    with tempfile.TemporaryDirectory(prefix='waex_contract_') as directory:
        test = Path(directory) / (name + '.java')
        test.write_text(body, encoding='utf-8')
        subprocess.run(['javac', '--release', '17', '-d', directory,
                        *map(str, production), str(test)], check=True, timeout=60)
        print('COMPILE_OK: receipt installer',flush=True)
        subprocess.run(['java', '-cp', directory, 'com.waenhancer.xposed.features.privacy.' + name], check=True, timeout=60)

src = ROOT / 'app/src/main/java/com/waenhancer/xposed/features/privacy'
text = (src / 'HideReceiptsHook.java').read_text(encoding='utf-8')
install = extract(text, '    private void hookSendReadReceiptJob()')
resolve = extract(text, '    private Method resolveReadReceiptExecution(')
assert 'getDeclaredMethods()' not in install, 'Do not blanket-hook receipt job methods'
assert 'matches.size() != 1' in resolve, 'Ambiguous DEX matches must be rejected'
assert 'waex_send_read_receipt_execution_v1' in resolve

body = r"""
package com.waenhancer.xposed.features.privacy;
import java.lang.reflect.*;
import java.util.*;
import com.waenhancer.xposed.features.privacy.ReceiptJobExecutionPolicy;
import com.waenhancer.xposed.features.privacy.ReceiptJidPolicy;
public class TestReceiptExecutionContract {
 static int checks;
 static void check(boolean value, String label) {
  checks++; if (!value) throw new AssertionError(label);
  System.out.println("PASS: " + label);
 }
 static final String TAG="test";
 Object context=new Object(); ClassLoader classLoader=getClass().getClassLoader();
 static class Context {}
 static class Job {
  String jid="123@s.whatsapp.net", participant;
  int ran, retried, initialized, added, cancelled, restored;
  public void body(){ran++;}
  public void onAdded(){added++;}
  public void onCancel(){cancelled++;}
  public boolean retry(Exception failure){retried++;return true;}
  public void initialize(Context context){initialized++;}
  private String diagnostic(){return "original";}
  private void readObject(java.io.ObjectInputStream stream){restored++;}
  public static void staticBody(){}
  public native void nativeBody();
 }
 static abstract class AbstractJob { public abstract void body(); }
 static class ChildJob extends Job {}
 interface Generic<T> {T value();}
 static class BridgeJob implements Generic<String> {public String value(){return "v";}}
 static class XC_MethodHook {
  static class MethodHookParam {
   Object thisObject; Member method; Object[] args; Object result; boolean cancelled;
   void setResult(Object result){this.result=result;cancelled=true;}
  }
  protected void beforeHookedMethod(MethodHookParam p){}
  protected void afterHookedMethod(MethodHookParam p){}
 }
 static class XposedBridge {
  static final Map<Method,XC_MethodHook> hooks=new LinkedHashMap<>();
  static int constructors;static XC_MethodHook constructorHook;
  static void hookAllConstructors(Class<?> c,XC_MethodHook h){constructors++;constructorHook=h;}
  static void hookMethod(Method m,XC_MethodHook h){hooks.put(m,h);}
  static void log(String s){}
 }
 static class XposedHelpers {
  static final Map<Object,Object> marks=new IdentityHashMap<>();
  static Class<?> findClassIfExists(String n,ClassLoader l){return Job.class;}
  static Object getAdditionalInstanceField(Object j,String k){return marks.get(j);}
  static void setAdditionalInstanceField(Object j,String k,Object v){marks.put(j,v);}
 }
 static class BlueOnReplyHook {static final List<String> captured=new ArrayList<>();static void captureSuppressedReadJob(String j,Constructor<?> c,Object[] a){captured.add(j);}static boolean isManualStatusSeenEnabled(Object prefs){return false;}static void captureSuppressedStatusReadJob(Constructor<?> c,Object[] a){}static boolean isManualStatusReceipt(Object job){return false;}}
 Object prefs=new Object();
 boolean blueEnabled;
 boolean isEnabled(String key,boolean value){return blueEnabled||value;}
 static String canonicalJid(String value){return ReceiptJidPolicy.canonical(value);}
 boolean isManualSeenTickEnabled(){return false;}
 static String allowedRecipient;
 static boolean isExplicitReceiptAllowed(String r,String id){return Objects.equals(allowedRecipient,r)&&"fixture-id".equals(id);}
 String findJidInObject(Object o){return null;}


 String extractJidString(Object obj,String key){Job j=(Job)obj;return key.equals("jid")?j.jid:j.participant;}
 boolean isStatusTarget(String a,String b){return "status@broadcast".equals(a)||"status@broadcast".equals(b);}
 static boolean hide=true, hideDelivery;
 boolean shouldHideStatusView(String target){return hide;}
 boolean shouldHideDeliveryReceipt(String target){return hideDelivery;}
 enum StringMatchType {Equals,EndsWith}
 static class ClassMatcher {static ClassMatcher create(){return new ClassMatcher();} ClassMatcher className(String n,StringMatchType t){return this;}}
 static class FindClass {static FindClass create(){return new FindClass();} FindClass matcher(ClassMatcher m){return this;}}
 static class ClassData {Class<?> getInstance(ClassLoader l){return Job.class;}}
 static class ClassDataList {ClassData firstOrNull(){return new ClassData();}}
 static class MethodMatcher {
  String owner, returns; int count=-1; Map<String,StringMatchType> anchors=new LinkedHashMap<>();
  static MethodMatcher create(){return new MethodMatcher();}
  MethodMatcher declaredClass(String n){owner=n;return this;}
  MethodMatcher returnType(String n){returns=n;return this;}
  MethodMatcher paramCount(int n){count=n;return this;}
  MethodMatcher addUsingString(String s,StringMatchType type){anchors.put(s,type);return this;}
 }
 static class FindMethod {MethodMatcher matcher;static FindMethod create(){return new FindMethod();} FindMethod matcher(MethodMatcher m){matcher=m;return this;}}
 static class MethodData {Method value;MethodData(Method m){value=m;}Method getMethodInstance(ClassLoader l){return value;}}
 static class MethodDataList extends ArrayList<MethodData> {}
 static class Bridge {
  static List<Method> candidates=new ArrayList<>();
  ClassDataList findClass(FindClass f){return new ClassDataList();}
  MethodDataList findMethod(FindMethod query){
   MethodMatcher m=query.matcher;
   check(Job.class.getName().equals(m.owner),"DEX query restricted to resolved job class");
   check(m.count==0&&"void".equals(m.returns),"DEX query requires no arguments and void");
   check(m.anchors.size()==2&&m.anchors.get("receipt")==StringMatchType.Equals
       &&m.anchors.get("status")==StringMatchType.Equals,"Both exact semantic literals required");
   MethodDataList out=new MethodDataList();for(Method method:candidates)out.add(new MethodData(method));return out;
  }
 }
 interface MethodFinder {Method find(Bridge b,ClassLoader l)throws Throwable;}
 interface ClassFinder {Class<?> find(Bridge b,ClassLoader l)throws Throwable;}
 static class DexSearchEngine {
  static final DexSearchEngine INSTANCE=new DexSearchEngine(); static Method cached;
  static DexSearchEngine getInstance(){return INSTANCE;}
  Method findMethodWithCache(Object c,ClassLoader l,String key,MethodFinder f){
   if(cached!=null)return cached;try{return f.find(new Bridge(),l);}catch(Throwable t){throw new AssertionError(t);}
  }
  Class<?> findClassWithCache(Object c,ClassLoader l,String k,ClassFinder f){return Job.class;}
 }
""" + install + '\n' + resolve + '\n' + extract(text, '    private static String findJidInArguments(') + '\n' + extract(text, '    private static String resolveConversationTarget(') + '\n' + extract(text, '    private static boolean hasExplicitReceiptIds(') + r"""
 static Method method(String name,Class<?>...types)throws Exception{return Job.class.getDeclaredMethod(name,types);}
 static Object invoke(Job job,Method method,Object...args)throws Throwable{
  XC_MethodHook h=XposedBridge.hooks.get(method);
  XC_MethodHook.MethodHookParam p=new XC_MethodHook.MethodHookParam();p.thisObject=job;p.method=method;p.args=args;
  if(h!=null)h.beforeHookedMethod(p);
  if(p.cancelled)return p.result;
  method.setAccessible(true);return method.invoke(job,args);
 }
 static void setup(Method... candidates){
  Bridge.candidates=Arrays.asList(candidates);DexSearchEngine.cached=null;
  XposedBridge.hooks.clear();XposedBridge.constructors=0;XposedHelpers.marks.clear();hide=true;hideDelivery=false;
 }
 public static void main(String[] args)throws Throwable{
  TestReceiptExecutionContract hook=new TestReceiptExecutionContract();Method body=method("body");
  setup(body);hook.hookSendReadReceiptJob();
  check(XposedBridge.hooks.size()==1&&XposedBridge.hooks.containsKey(body),"Only execution body is hooked");
  check(XposedBridge.constructors==1,"Constructor capture preserved");
  Job job=new Job();XposedHelpers.marks.put(job,true);
  invoke(job,body);check(job.ran==0,"Tagged status job execution suppressed");
  check(Boolean.TRUE.equals(invoke(job,method("retry",Exception.class),new Exception())),"Retry boolean retained, never null");
  check(job.retried==1,"Retry original executed");
  check("original".equals(invoke(job,method("diagnostic"))),"Diagnostic return retained");
  invoke(job,method("initialize",Context.class),new Context());check(job.initialized==1,"Dependency initialization retained");
  invoke(job,method("onAdded"));invoke(job,method("onCancel"));
  check(job.added==1&&job.cancelled==1,"Other void lifecycle methods retained");
  invoke(job,method("readObject",java.io.ObjectInputStream.class),(Object)null);
  check(job.restored==1,"Deserialization is not suppressed");
  XposedHelpers.marks.clear();invoke(job,body);check(job.ran==1,"Ordinary untagged chat execution retained");
  job.jid="status@broadcast";job.participant="123@s.whatsapp.net";invoke(job,body);
  check(job.ran==1,"Dynamic status preference suppressed at execution");
  hide=false;invoke(job,body);check(job.ran==2,"Disabled status preference permits ordinary execution");
  XposedHelpers.marks.put(job,"notBoolean");job.jid="123@s.whatsapp.net";invoke(job,body);
  check(job.ran==3,"Unexpected mark type does not cause a cast failure");
  hideDelivery=true;invoke(job,body);
  check(job.ran==3,"Delivery-hidden chat job is cancelled at verified execution");
  check(!ReceiptJobExecutionPolicy.isExecutionCall(Job.class,body,method("onAdded"),job,new Object[0]),"Callback identity must match selected body");
  check(!ReceiptJobExecutionPolicy.isExecutionCall(Job.class,body,body,new Object(),new Object[0]),"Wrong receiver rejected");
  check(!ReceiptJobExecutionPolicy.isExecutionCall(Job.class,body,body,null,new Object[0]),"Null receiver rejected");
  check(!ReceiptJobExecutionPolicy.isExecutionCall(Job.class,body,body,job,new Object[]{1}),"Unexpected callback arguments rejected");
  check(ReceiptJobExecutionPolicy.isExecutionCall(Job.class,body,body,job,null),"Null zero-argument array supported");
  check(!ReceiptJobExecutionPolicy.isExecutionMethod(null,body)&&!ReceiptJobExecutionPolicy.isExecutionMethod(Job.class,null),"Missing method/class rejected");
  for(Method m:new Method[]{method("retry",Exception.class),method("diagnostic"),method("readObject",java.io.ObjectInputStream.class),method("initialize",Context.class),method("staticBody"),method("nativeBody")})
   check(!ReceiptJobExecutionPolicy.isExecutionMethod(Job.class,m),"Incompatible shape rejected: "+m.getName());
  check(!ReceiptJobExecutionPolicy.isExecutionMethod(ChildJob.class,body),"Wrong declaring class rejected");
  check(!ReceiptJobExecutionPolicy.isExecutionMethod(AbstractJob.class,AbstractJob.class.getDeclaredMethod("body")),"Abstract body rejected");
  for(Method m:BridgeJob.class.getDeclaredMethods())check(!ReceiptJobExecutionPolicy.isExecutionMethod(BridgeJob.class,m),"Synthetic/bridge or non-void method rejected");
  setup();hook.hookSendReadReceiptJob();check(XposedBridge.hooks.isEmpty(),"Missing DEX body does not install broad fallback");
  setup(body,method("onAdded"));hook.hookSendReadReceiptJob();check(XposedBridge.hooks.isEmpty(),"Ambiguous DEX bodies rejected");
  setup(method("retry",Exception.class));hook.hookSendReadReceiptJob();check(XposedBridge.hooks.isEmpty(),"Malformed resolver candidate rejected");
  setup(body);DexSearchEngine.cached=method("retry",Exception.class);hook.hookSendReadReceiptJob();
  check(XposedBridge.hooks.isEmpty(),"Cached non-void method revalidated and rejected");
  setup(body);hook.hookSendReadReceiptJob();hook.blueEnabled=true;
  XC_MethodHook.MethodHookParam cp=new XC_MethodHook.MethodHookParam();
  Job captureJob=new Job();cp.thisObject=captureJob;cp.method=Job.class.getDeclaredConstructor();cp.args=new Object[]{"456@s.whatsapp.net",new String[]{"fixture-id"}};
  captureJob.jid="123@s.whatsapp.net";captureJob.participant="999@s.whatsapp.net";
  BlueOnReplyHook.captured.clear();XposedBridge.constructorHook.afterHookedMethod(cp);
  check(BlueOnReplyHook.captured.equals(Arrays.asList("123@s.whatsapp.net")),"Constructor captures only its serialized recipient");
  captureJob.jid=null;XposedBridge.constructorHook.afterHookedMethod(cp);
  check(BlueOnReplyHook.captured.size()==1,"Missing job recipient never falls back to participant or argument");
  captureJob.jid="not-a-jid";XposedBridge.constructorHook.afterHookedMethod(cp);
  check(BlueOnReplyHook.captured.size()==1,"Invalid job recipient never captures");
  captureJob.jid="123@g.us";XposedBridge.constructorHook.afterHookedMethod(cp);
  check(BlueOnReplyHook.captured.equals(Arrays.asList("123@s.whatsapp.net","123@g.us")),"Group job retains group recipient, not participant");
  captureJob.jid="status@broadcast";XposedBridge.constructorHook.afterHookedMethod(cp);
  check(BlueOnReplyHook.captured.size()==2,"Status constructor is not queued as a conversation read");
  BlueOnReplyHook.captured.clear();captureJob.jid="123@s.whatsapp.net";captureJob.participant="999@g.us";allowedRecipient="999@g.us";
  XposedBridge.constructorHook.afterHookedMethod(cp);
  check(BlueOnReplyHook.captured.equals(Arrays.asList("123@s.whatsapp.net")),"Participant allowance never exempts serialized recipient");
  allowedRecipient="123@s.whatsapp.net";XposedBridge.constructorHook.afterHookedMethod(cp);
  check(BlueOnReplyHook.captured.size()==1,"Exact serialized recipient allowance avoids recapture");
  allowedRecipient=null;
  System.out.println("CHECKS="+checks+"; extracted production resolver/installer + real policy; not Android/LSPosed");
 }
}
"""
run_java('TestReceiptExecutionContract', body, [src / 'ReceiptJobExecutionPolicy.java', src / 'ReceiptJidPolicy.java'])
