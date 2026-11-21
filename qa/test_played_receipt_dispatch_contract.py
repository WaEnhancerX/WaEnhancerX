#!/usr/bin/env python3
"""Validate played-dispatch call-edge query, installation and callback contracts with JVM doubles."""
from pathlib import Path
import subprocess, tempfile
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

source=ROOT/'app/src/main/java/com/waenhancer/xposed/features/privacy'
text=(source/'HideReceiptsHook.java').read_text()
install=extract(text,'    private void hookSenderPlayed()')
resolve=extract(text,'    private Method resolvePlayedReceiptDispatch()')
classify=extract(text,'    private boolean shouldSuppressPlayedReceipt(')
body=r'''
import java.lang.reflect.*;import java.util.*;
import com.waenhancer.xposed.features.privacy.PlayedReceiptDispatchPolicy;
public class PlayedDispatchTest {
 static int checks;static boolean audio,once;
 static void check(boolean c,String label){if(!c)throw new AssertionError(label);checks++;System.out.println("PASS: "+label);}
 Object context=new Object();ClassLoader classLoader=getClass().getClassLoader();static String TAG="fixture";
 boolean isHideVoiceNotePlayedEnabled(){return audio;}boolean isHideViewOnceSeenEnabled(){return once;}
 static class Media {int kind=2;}
 static class Dispatcher {
  int error, played, ordinary;
  public void mediaError(Media value){error++;}
  public void playedBatch(Set<?> value,boolean playedSelfFromPeer){played++;}
  public void otherBatch(Set<?> value,boolean flag){ordinary++;}
  public boolean retry(Exception e){ordinary++;return true;}
  public void initialize(Object context){ordinary++;}
  public static void staticBatch(Set<?> value,boolean flag){}
  public native void nativeBatch(Set<?> value,boolean flag);
  private void privateBatch(Set<?> value,boolean flag){}
  public boolean booleanBatch(Set<?> value,boolean flag){return true;}
  public void wrongFlag(Set<?> value,Boolean flag){}
  public void wrongSet(List<?> value,boolean flag){}
  public void zero(){}
 }
 static abstract class AbstractDispatcher{public abstract void batch(Set<?> value,boolean flag);}
 static class XC_MethodHook {
  protected void beforeHookedMethod(MethodHookParam p){}
  static class MethodHookParam{Member method;Object thisObject;Object[]args;Object result;boolean cancelled;void setResult(Object o){result=o;cancelled=true;}}
 }
 static class HookErrors { static void swallowed(String site, Throwable failure) {} }
 static class HostFields { static Field[] declared(Class<?> type) { return type.getDeclaredFields(); } }
 static class XposedBridge {
  static Map<Method,XC_MethodHook> hooks=new LinkedHashMap<>();static String lastLog="";
  static void hookMethod(Method m,XC_MethodHook h){hooks.put(m,h);}static void log(String s){lastLog=s;}
 }
 enum StringMatchType{Equals}
 static class MethodMatcher {
  String returns,owner,name,literal;int count=-1;List<String>params=new ArrayList<>();MethodMatcher invoke;StringMatchType match;
  static MethodMatcher create(){return new MethodMatcher();}
  MethodMatcher returnType(String s){returns=s;return this;}MethodMatcher paramCount(int c){count=c;return this;}
  MethodMatcher addParamType(String s){params.add(s);return this;}MethodMatcher declaredClass(String s){owner=s;return this;}
  MethodMatcher name(String s){name=s;return this;}MethodMatcher addUsingString(String s,StringMatchType m){literal=s;match=m;return this;}
  MethodMatcher addInvoke(MethodMatcher m){invoke=m;return this;}
 }
 static class FindMethod{MethodMatcher m;static FindMethod create(){return new FindMethod();}FindMethod matcher(MethodMatcher v){m=v;return this;}}
 static class MethodData {Method method;MethodData(Method m){method=m;}Method getMethodInstance(ClassLoader loader){return method;}}
 static class MethodDataList extends ArrayList<MethodData>{}
 static class Bridge{
  static List<Method> candidates=new ArrayList<>();
  MethodDataList findMethod(FindMethod q){
   check("void".equals(q.m.returns)&&q.m.count==2,"Query requires void and exactly two arguments");
   check(q.m.params.equals(Arrays.asList("java.util.Set","boolean")),"Query preserves ordered Set/primitive-boolean signature");
   MethodMatcher call=q.m.invoke;
   check(call!=null&&"com.whatsapp.messaging.receipts.jobqueue.job.SendPlayedReceiptJobV2".equals(call.owner),"Query requires played job constructor call edge");
   check("<init>".equals(call.name)&&call.count==2&&"played-receipt-v2-".equals(call.literal)&&call.match==StringMatchType.Equals,"Constructor identity and literal both required");
   MethodDataList list=new MethodDataList();for(Method m:candidates)list.add(new MethodData(m));return list;
  }
 }
 interface Finder {Method find(Bridge b,ClassLoader l);}
 static class DexSearchEngine {
  static Method cached;static DexSearchEngine getInstance(){return new DexSearchEngine();}
  Method findMethodWithCache(Object c,ClassLoader l,String k,Finder f){return cached!=null?cached:f.find(new Bridge(),l);}
 }
'''+install+'\n'+resolve+'\n'+classify+r'''
 static Method m(String n,Class<?>...p)throws Exception{return Dispatcher.class.getDeclaredMethod(n,p);}
 static void setup(Method...c){Bridge.candidates=Arrays.asList(c);DexSearchEngine.cached=null;XposedBridge.hooks.clear();XposedBridge.lastLog="";audio=true;once=false;}
 static boolean invoke(Dispatcher d,Method m,Object...a)throws Exception{
  XC_MethodHook.MethodHookParam p=new XC_MethodHook.MethodHookParam();p.method=m;p.thisObject=d;p.args=a;
  XC_MethodHook hook=XposedBridge.hooks.get(m);if(hook!=null)hook.beforeHookedMethod(p);
  if(!p.cancelled)m.invoke(d,a);return p.cancelled;
 }
 public static void main(String[]args)throws Exception {
  PlayedDispatchTest h=new PlayedDispatchTest();Method selected=m("playedBatch",Set.class,boolean.class);
  setup(selected);h.hookSenderPlayed();
  check(XposedBridge.hooks.size()==1&&XposedBridge.hooks.containsKey(selected),"Only constructor-verified played dispatcher installed");
  Dispatcher d=new Dispatcher();Media media=new Media();Set<Media> input=new LinkedHashSet<>();input.add(media);
  check(!invoke(d,m("mediaError",Media.class),media)&&d.error==1,"Media-error dispatcher still executes with privacy active");
  check(!invoke(d,m("retry",Exception.class),new Exception())&&d.ordinary==1,"Unrelated boolean retry still executes");
  check(!invoke(d,m("initialize",Object.class),new Object())&&d.ordinary==2,"Unrelated initialization still executes");
  check(invoke(d,selected,input,false)&&d.played==0,"Existing played privacy still suppresses verified batch");
  check(input.size()==1&&input.iterator().next()==media,"Caller-owned batch is not mutated");
  audio=false;check(!invoke(d,selected,input,false)&&d.played==1,"Disabled privacy preserves played dispatch");
  once=true;check(invoke(d,selected,input,true)&&d.played==1,"Existing view-once/self flag behavior retained without reinterpreting flag");
  check(!invoke(d,selected,Collections.emptySet(),false)&&d.played==2,"Empty played batch retains original behavior");
  check(PlayedReceiptDispatchPolicy.isDispatchCall(selected,selected,d,new Object[]{input,false}),"Valid exact callback accepted");
  check(!PlayedReceiptDispatchPolicy.isDispatchCall(selected,m("otherBatch",Set.class,boolean.class),d,new Object[]{input,false}),"Wrong callback method rejected");
  check(!PlayedReceiptDispatchPolicy.isDispatchCall(selected,selected,new Object(),new Object[]{input,false}),"Wrong receiver rejected");
  for(Object[]a:new Object[][]{null,new Object[]{input},new Object[]{input,null},new Object[]{input,1},new Object[]{new ArrayList<>(),false},new Object[]{null,false},new Object[]{input,false,1}})
   check(!PlayedReceiptDispatchPolicy.isDispatchCall(selected,selected,d,a),"Malformed callback arguments rejected");
  check(!PlayedReceiptDispatchPolicy.isDispatchMethod(null),"Missing method rejected");
  for(Method method:Dispatcher.class.getDeclaredMethods())if(!method.getName().equals("playedBatch")&&!method.getName().equals("otherBatch"))
   check(!PlayedReceiptDispatchPolicy.isDispatchMethod(method),"Incompatible shape rejected: "+method.getName());
  check(!PlayedReceiptDispatchPolicy.isDispatchMethod(AbstractDispatcher.class.getDeclaredMethod("batch",Set.class,boolean.class)),"Abstract dispatcher rejected");
  setup();h.hookSenderPlayed();check(XposedBridge.hooks.isEmpty()&&XposedBridge.lastLog.contains("incomplete"),"Missing match is reported; no broad fallback");
  setup(selected,m("otherBatch",Set.class,boolean.class));h.hookSenderPlayed();check(XposedBridge.hooks.isEmpty(),"Ambiguous call edges rejected");
  setup(m("booleanBatch",Set.class,boolean.class));h.hookSenderPlayed();check(XposedBridge.hooks.isEmpty(),"Wrong resolver return type rejected");
  setup(selected);DexSearchEngine.cached=m("booleanBatch",Set.class,boolean.class);h.hookSenderPlayed();check(XposedBridge.hooks.isEmpty(),"Cached descriptor shape revalidated");
  System.out.println("CHECKS="+checks+"; exact production installer/resolver with explicit JVM doubles, not native DexKit/Android");
 }
}
'''
with tempfile.TemporaryDirectory(prefix='waex_played_dispatch_') as td:
    p=Path(td)/'PlayedDispatchTest.java';p.write_text(body)
    subprocess.run(['javac','--release','17','-d',td,str(source/'PlayedReceiptDispatchPolicy.java'),str(p)],check=True,timeout=60)
    subprocess.run(['java','-cp',td,'PlayedDispatchTest'],check=True,timeout=60)
