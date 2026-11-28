#!/usr/bin/env python3
"""Execute the production presence decoder/installer with explicit JVM fixtures."""
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
        subprocess.run(['java', '-cp', directory, name], check=True, timeout=60)

src = ROOT / 'app/src/main/java/com/waenhancer/xposed/features/automation'
text = (src / 'PresenceToastsHook.java').read_text(encoding='utf-8')
install = extract(text, '    private void hookIncomingPresence()')
shape = extract(text, '    private static boolean isPresenceHandler(')
decode = extract(text, '    private static PresenceEventPolicy.Event decodePresenceEvent(')
assert 'findCapturedJid' not in text and 'findCapturedMessage' not in text
assert 'afterHookedMethod' in install
assert 'setResult(' not in install and 'setThrowable(' not in install
assert 'hookStatusViewedReceipt();' in text and 'hookOnInsertReceipt();' in text
body = r"""
import java.lang.reflect.*;import java.util.*;import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import com.waenhancer.xposed.features.automation.PresenceEventPolicy;
import com.waenhancer.xposed.features.automation.PresenceStateStore;
public class TestPresenceEventContract {
 static class R {static class string {static final int waex_ui_is_online=1;}}
 static class UiText {static String get(int id, String fallback){return fallback;}}
 static int checks;
 static void check(boolean value,String label){checks++;if(!value)throw new AssertionError(label);System.out.println("PASS: "+label);}
 static String TAG="test";Object context=new Object();ClassLoader classLoader=getClass().getClassLoader();
 static List<String> toasts=new ArrayList<>();Executor asyncExecutor=Runnable::run;
 boolean isOnlineToastEnabled(){return true;}void showToastSafely(String s){toasts.add(s);}
 void showToastSafely(String s,java.util.function.BooleanSupplier valid){if(valid.getAsBoolean())toasts.add(s);}
 static class ContactNameResolver {static ContactNameResolver INSTANCE=new ContactNameResolver();String resolveName(Object a,String b,Object c,Object d){return "test contact";}}
 static class BaseBundle {Map<String,Object> values=new HashMap<>();Object get(String key){return values.get(key);}}
 static class ThrowingBundle extends BaseBundle {Object get(String key){throw new IllegalStateException("synthetic");}}
 static class Message {int what,arg1;Object obj;}
 static class Handler {public void handleMessage(Message m){}}
 static class TestHandler extends Handler {public void handleMessage(Message m){}}
 static class RunnableReceiver {public void run(){} public void handleMessage(Message m){}}
 static class WrongArgumentHandler extends Handler {public void handleMessage(Object m){}}
 static class NativeHandler extends Handler {public native void handleMessage(Message m);}
 static abstract class AbstractHandler extends Handler {public abstract void handleMessage(Message m);}
 static class Trap {public String toString(){throw new AssertionError("Must not stringify arbitrary objects");}}
 static class XC_MethodHook {
  static class MethodHookParam {Object[] args;boolean failed;boolean hasThrowable(){return failed;}}
  protected void afterHookedMethod(MethodHookParam p){}
 }
 static class XposedBridge {static XC_MethodHook hook;static Method selected;static void hookMethod(Method m,XC_MethodHook h){selected=m;hook=h;}static void log(String s){}}
 enum StringMatchType {Equals}
 static class MethodMatcher {
  String name,returns;String[] params;MethodMatcher invokes;int count=-1;Map<String,StringMatchType> anchors=new HashMap<>();
  static MethodMatcher create(){return new MethodMatcher();}
  MethodMatcher name(String n){name=n;return this;}MethodMatcher returnType(String n){returns=n;return this;}
  MethodMatcher addInvoke(MethodMatcher m){invokes=m;return this;} MethodMatcher paramCount(int n){count=n;return this;}
  MethodMatcher paramTypes(String...p){params=p;return this;}
  MethodMatcher addUsingString(String s,StringMatchType t){anchors.put(s,t);return this;}
 }
 static class FindMethod {MethodMatcher matcher;static FindMethod create(){return new FindMethod();}FindMethod matcher(MethodMatcher m){matcher=m;return this;}}
 static class MethodData {Method method;MethodData(Method m){method=m;}Method getMethodInstance(ClassLoader l){return method;}}
 static class MethodDataList extends ArrayList<MethodData> {}
 static class Bridge {
  static List<Method> candidates=new ArrayList<>();
  MethodDataList findMethod(FindMethod query){
   MethodMatcher m=query.matcher;
   check("handleMessage".equals(m.name)&&"void".equals(m.returns)
      &&Arrays.equals(m.params,new String[]{"android.os.Message"}),"Resolver requests exact Handler signature");
   check(m.anchors.size()==3&&m.anchors.containsKey("MessageHandler/handleConnectionThreadReady connectionready")
      &&m.anchors.containsKey("lastSeen")&&m.anchors.containsKey("presence"),"Resolver requires all three handler anchors");
   for(StringMatchType match:m.anchors.values())check(match==StringMatchType.Equals,"Resolver literal is exact");
   check(m.invokes!=null&&m.invokes.count==3&&"void".equals(m.invokes.returns)
      &&m.invokes.anchors.get("MessageHandler/handleDisconnected error=")==StringMatchType.Equals,"Resolver verifies lifecycle call edge");
   MethodDataList out=new MethodDataList();for(Method method:candidates)out.add(new MethodData(method));return out;
  }
 }
 interface Finder {Method find(Bridge b,ClassLoader l)throws Throwable;}
 static class DexSearchEngine {
  static final DexSearchEngine INSTANCE=new DexSearchEngine();static Method cached;
  static DexSearchEngine getInstance(){return INSTANCE;}
  Method findMethodWithCache(Object c,ClassLoader l,String key,Finder f){if(cached!=null)return cached;try{return f.find(new Bridge(),l);}catch(Throwable t){throw new AssertionError(t);}}
 }
""" + install + '\n' + shape + '\n' + decode + r"""
 static Message message(int what,int code,Object jid,Object presence){Message m=new Message();m.what=what;m.arg1=code;BaseBundle b=new BaseBundle();b.values.put("jid",jid);b.values.put("presence",presence);m.obj=b;return m;}
 static PresenceEventPolicy.Event decode(Message m){return decodePresenceEvent(new Object[]{m});}
 static void event(Message m,boolean failed){XC_MethodHook.MethodHookParam p=new XC_MethodHook.MethodHookParam();p.args=new Object[]{m};p.failed=failed;XposedBridge.hook.afterHookedMethod(p);}
 public static void main(String[] args)throws Exception{
  String jid="15550000001@s.whatsapp.net";
  for(String good:new String[]{jid,"15550000001@lid","15550000001:3@s.whatsapp.net","15550000001@whatsapp.net"}){
   PresenceEventPolicy.Event e=decode(message(5,5,good,null));check(e!=null&&e.online&&e.jid.equals(good),"Available explicit contact: "+good);
   e=decode(message(5,64,good,null));check(e!=null&&!e.online&&e.jid.equals(good),"Unavailable explicit contact: "+good);
  }
  for(Object bad:new Object[]{null,"", " 123@s.whatsapp.net","123@s.whatsapp.net ","id=123@s.whatsapp.net","123@s.whatsapp.net.evil","123@example.invalid","1-2@g.us","status@broadcast","a@lid","+123@s.whatsapp.net","123@s.whatsapp.net/resource","123@lid\n", "1".repeat(257)+"@lid",new Trap(),123,new StringBuilder(jid)})
   check(decode(message(5,5,bad,null))==null,"Invalid/non-string/ambiguous identity ignored");
  check(decodePresenceEvent(null)==null&&decodePresenceEvent(new Object[0])==null,"Missing message ignored");
  check(decodePresenceEvent(new Object[]{new Trap()})==null,"Unrelated object never stringified");
  check(decodePresenceEvent(new Object[]{message(5,5,jid,null),message(5,5,"2@lid",null)})==null,"Multiple Message candidates rejected");
  Message badPayload=message(5,5,jid,null);badPayload.obj=new Trap();check(decode(badPayload)==null,"Payload must be an actual bundle");
  badPayload.obj=new ThrowingBundle();check(decode(badPayload)==null,"Unreadable bundle ignored without callback failure");
  BaseBundle alias=new BaseBundle();alias.values.put("from",jid);badPayload.obj=alias;
  check(decode(badPayload)==null,"Unverified alias keys do not supply identity");
  for(Object presence:new Object[]{"available","unavailable","",new Trap()}){
   check(decode(message(5,64,jid,presence))==null,"Denied/unknown presence is not an offline timestamp");
   check(decode(message(5,5,jid,presence))==null,"Unexpected available payload ignored");
  }
  check(!decode(message(5,64,jid,"deny")).known,"Verified deny is unknown, not offline");
  check(decode(message(5,5,jid,"deny"))==null,"Deny is not an available envelope");
  for(int code:new int[]{Integer.MIN_VALUE,-1,0,1,4,6,12,20,21,63,65,158,237,271,272,Integer.MAX_VALUE})
   check(decode(message(5,code,jid,null))==null,"Unknown code ignored: "+code);
  for(int family:new int[]{Integer.MIN_VALUE,-1,0,1,4,6,64,Integer.MAX_VALUE})
   check(decode(message(family,5,jid,null))==null,"Wrong event family ignored: "+family);
  check(PresenceEventPolicy.decode(0,5,jid,null)==null,"Policy independently rejects wrong family");
  check(PresenceEventPolicy.decode(5,999,jid,null)==null,"Policy independently rejects unknown code");
  check(PresenceEventPolicy.decode(5,5,jid,null).online,"Policy independently preserves available");
  check(!PresenceEventPolicy.decode(5,64,jid,null).online,"Policy independently preserves unavailable");
  Random random=new Random(20261014);
  for(int i=0;i<2000;i++){int code=random.nextInt();if(code==5||code==64)continue;if(decode(message(5,code,jid,null))!=null)throw new AssertionError("unknown code accepted");}
  check(true,"2000 seeded unknown-code probes ignored");
  Method valid=TestHandler.class.getDeclaredMethod("handleMessage",Message.class);
  check(isPresenceHandler(valid),"Handler signature accepted");
  check(!isPresenceHandler(null),"Missing handler rejected");
  check(!isPresenceHandler(RunnableReceiver.class.getDeclaredMethod("run")),"Shared Runnable is not a presence handler");
  check(!isPresenceHandler(RunnableReceiver.class.getDeclaredMethod("handleMessage",Message.class)),"Non-Handler receiver rejected");
  check(!isPresenceHandler(WrongArgumentHandler.class.getDeclaredMethod("handleMessage",Object.class)),"Wrong Handler argument rejected");
  check(!isPresenceHandler(NativeHandler.class.getDeclaredMethod("handleMessage",Message.class)),"Native handler rejected");
  check(!isPresenceHandler(AbstractHandler.class.getDeclaredMethod("handleMessage",Message.class)),"Abstract handler rejected");
  TestPresenceEventContract instance=new TestPresenceEventContract();Bridge.candidates=Arrays.asList(valid);instance.hookIncomingPresence();
  check(XposedBridge.hook!=null&&XposedBridge.selected.equals(valid),"Only verified Handler installed");
  Message online=message(5,5,jid,null);Object payload=online.obj;
  event(online,false);event(online,false);check(toasts.size()==1,"Repeated available event emits one toast");
  check(online.obj==payload&&online.what==5&&online.arg1==5,"Host Message preserved");
  event(message(5,999,jid,null),false);check(PresenceStateStore.get(jid).online,"Unknown event cannot set contact offline");
  event(message(5,64,jid,null),true);check(PresenceStateStore.get(jid).online,"Failed original method does not update state");
  event(message(5,64,jid,"deny"),false);check(!PresenceStateStore.get(jid).known && PresenceStateStore.get(jid).changedAtMillis==0L,"Denied presence invalidates without a fabricated timestamp");
  event(message(5,64,jid,null),false);check(!PresenceStateStore.get(jid).online,"Verified unavailable transition retained");
  event(online,false);check(toasts.size()==2,"Online-offline-online cycle emits second online toast");
  event(message(0,5,"2@lid",null),false);check(PresenceStateStore.get("2@lid")==null,"Connection-ready envelope cannot synthesize contact state");
  Bridge.candidates=Collections.emptyList();XposedBridge.hook=null;instance.hookIncomingPresence();check(XposedBridge.hook==null,"Missing match does not fall back to generic Runnable");
  Bridge.candidates=Arrays.asList(valid,valid);instance.hookIncomingPresence();check(XposedBridge.hook==null,"Ambiguous matches rejected");
  DexSearchEngine.cached=RunnableReceiver.class.getDeclaredMethod("run");instance.hookIncomingPresence();
  check(XposedBridge.hook==null,"Cached incompatible handler rejected");
  System.out.println("CHECKS="+checks+"; 2000 seeded probes; real production decoder/installer/policies with JVM doubles, not Android");
 }
}
"""
run_java('TestPresenceEventContract', body, [src / 'PresenceEventPolicy.java', src / 'PresenceStateStore.java'])
