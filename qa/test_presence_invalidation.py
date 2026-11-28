#!/usr/bin/env python3
"""Exercise production presence callbacks with separately queued lookup/UI work.
The Android/Xposed scheduler classes here are explicit JVM doubles, not ART.
"""
import argparse
import ast
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]

def extract(source, marker):
    start=source.index(marker);i=source.index('{',start);depth=0;state='code'
    while i<len(source):
        c,pair=source[i],source[i:i+2]
        if state=='line':
            if c=='\n':state='code'
        elif state=='comment':
            if pair=='*/':state='code';i+=1
        elif state in ('str','char'):
            if c=='\\':i+=1
            elif c==('"' if state=='str' else "'"):state='code'
        elif pair=='//':state='line';i+=1
        elif pair=='/*':state='comment';i+=1
        elif c=='"':state='str'
        elif c=="'":state='char'
        elif c=='{':depth+=1
        elif c=='}':
            depth-=1
            if depth==0:return source[start:i+1]
        i+=1
    raise ValueError(marker)

ERROR_STUBS = {
    'androidx/annotation/NonNull.java': 'package androidx.annotation; public @interface NonNull {}',
    'androidx/annotation/VisibleForTesting.java': 'package androidx.annotation; public @interface VisibleForTesting {}',
    'de/robv/android/xposed/XposedBridge.java': '''package de.robv.android.xposed;
import java.util.ArrayList; import java.util.List;
public final class XposedBridge {
 public static final List<String> messages = new ArrayList<>();
 public static void log(String message) { messages.add(message); }
}''',
}

CASES=r'''
 interface Scenario { void run() throws Exception; }
 static int scenarios, failures;
 static boolean enabled=true;
 static Queue<Runnable> lookupQueue=new ArrayDeque<>(), uiQueue=new ArrayDeque<>();
 final Handler mainHandler=new Handler();
 static class Toast {
  static final int LENGTH_SHORT=0;static boolean fail;String text;
  static Toast makeText(Object c,String text,int d){Toast t=new Toast();t.text=text;return t;}
  void show(){if(fail)throw new IllegalStateException("private toast detail");toasts.add(text);}
 }
 static void reset() throws Exception {
  for(String n:new String[]{"STATES","LISTENERS"}){
   Field f=PresenceStateStore.class.getDeclaredField(n);f.setAccessible(true);Object o=f.get(null);
   if(o instanceof Map)((Map<?,?>)o).clear();else ((Set<?>)o).clear();
  }
  lookupQueue.clear();uiQueue.clear();toasts.clear();enabled=true;Toast.fail=false;
  DexSearchEngine.cached=null;Bridge.candidates=Arrays.asList(TestHandler.class.getDeclaredMethod("handleMessage",Message.class));
  new TestPresenceEventContract().hookIncomingPresence();
 }
 static final String A="910001@s.whatsapp.net",B="910002@s.whatsapp.net";
 static void lookup(){while(!lookupQueue.isEmpty())lookupQueue.remove().run();}
 static void ui(){while(!uiQueue.isEmpty())uiQueue.remove().run();}
 static void pump(){lookup();ui();}
 static void online(){event(message(5,5,A,null),false);}
 static void offline(){event(message(5,64,A,null),false);}
 static void deny(){event(message(5,64,A,"deny"),false);}
 static boolean known(String jid)throws Exception {
  PresenceStateStore.State s=PresenceStateStore.get(jid);if(s==null)return false;
  try{return s.getClass().getField("known").getBoolean(s);}catch(NoSuchFieldException oldSource){return true;}
 }
 static void require(boolean b,String m){if(!b)throw new AssertionError(m);}
 static void scenario(String name,Scenario test)throws Exception {
  reset();try{test.run();System.out.println("PASS: "+name);}catch(AssertionError failure){failures++;System.out.println("FAIL: "+name+": "+failure.getMessage());}scenarios++;
 }
 public static void main(String[]args)throws Exception {
  scenario("current online toast",()->{online();pump();require(toasts.size()==1,"current event lost");});
  scenario("duplicate observation",()->{online();online();pump();require(toasts.size()==1,"duplicate toast");});
  scenario("offline before lookup",()->{online();offline();pump();require(toasts.isEmpty(),"stale online toast");});
  scenario("offline between lookup and UI",()->{online();lookup();offline();ui();require(toasts.isEmpty(),"stale online toast");});
  scenario("denied before lookup",()->{online();deny();pump();require(!known(A)&&toasts.isEmpty(),"denied presence retained");});
  scenario("denied between lookup and UI",()->{online();lookup();deny();ui();require(!known(A)&&toasts.isEmpty(),"denied presence announced");});
  scenario("denial has no fabricated timestamp",()->{online();deny();require(!known(A)&&PresenceStateStore.get(A).changedAtMillis==0L,"denial is not last-seen");});
  scenario("connecting clears old transport",()->{online();event(message(1,0,null,null),false);pump();require(PresenceStateStore.get(A)==null&&toasts.isEmpty(),"old transport retained");});
  scenario("disconnect clears queued UI",()->{online();lookup();event(message(4,1,null,null),false);ui();require(PresenceStateStore.get(A)==null&&toasts.isEmpty(),"disconnected state retained");});
  scenario("settings change before UI",()->{online();lookup();enabled=false;ui();require(toasts.isEmpty(),"disabled toast shown");});
  scenario("old transition cannot replace new transition",()->{online();lookup();offline();online();lookup();ui();require(toasts.size()==1,"old transition also emitted");});
  scenario("failed lifecycle callback preserves state",()->{online();event(message(4,0,null,null),true);pump();require(known(A)&&toasts.size()==1,"failed original invalidated state");});
  scenario("unrelated family is not disconnect",()->{online();event(message(999,4,null,null),false);pump();require(known(A)&&toasts.size()==1,"guessed lifecycle");});
  scenario("unknown presence payload",()->{online();event(message(5,64,A,"later"),false);pump();require(known(A)&&toasts.size()==1,"unknown payload invalidated");});
  scenario("invalid JID denial",()->{online();event(message(5,64,"910001@example.org","deny"),false);pump();require(known(A)&&toasts.size()==1,"unrelated identifier invalidated");});
  scenario("denied contact does not clear another",()->{online();event(message(5,64,B,"deny"),false);pump();require(known(A)&&!known(B)&&toasts.size()==1,"cross-contact invalidation");});
  scenario("available deny is not reviewed denial envelope",()->{online();event(message(5,5,A,"deny"),false);pump();require(known(A)&&toasts.size()==1,"accepted unreviewed envelope");});
  scenario("repeated observation retains transition eligibility",()->{online();lookup();online();ui();require(toasts.size()==1,"duplicate observation wrongly cancelled toast");});
  scenario("reset does not manufacture contact state",()->{event(message(4,0,B,null),false);require(PresenceStateStore.get(B)==null,"invented contact offline");});
  scenario("new transport accepts new observations",()->{online();event(message(4,0,null,null),false);online();pump();require(known(A)&&toasts.size()==1,"new observation lost or old duplicated");});
  scenario("reentrant observation cannot steal the newer transition",()->{
   final boolean[] nested={false};
   PresenceStateStore.addListener((jid,state)->{if(A.equals(jid)&&state.online&&!nested[0]){
    nested[0]=true;offline();online();
   }});
   online();pump();require(toasts.size()==1,"reread newer transition and queued it twice");
  });
  scenario("toast failure uses real error recorder without stopping UI",()->{
   int before=HookErrors.count("PresenceToastsHook",IllegalStateException.class);
   int logged=de.robv.android.xposed.XposedBridge.messages.size();
   Toast.fail=true;online();pump();
   require(toasts.isEmpty()&&HookErrors.count("PresenceToastsHook",IllegalStateException.class)==before+1,"toast error lost");
   require(de.robv.android.xposed.XposedBridge.messages.size()==logged+1,"Xposed diagnostic not observed");
   require(de.robv.android.xposed.XposedBridge.messages.stream().noneMatch(s->s.contains("private toast detail")),"private diagnostic payload");
   Toast.fail=false;offline();online();pump();require(toasts.size()==1,"later toast lost after logging failure");
  });
  System.out.println("SCENARIOS="+scenarios+"; FAILURES="+failures+"; queued JVM doubles, not Android/LSPosed");
  if(failures>0)throw new AssertionError("Presence lifecycle regressions: "+failures);
 }
}
'''

def main():
    ap=argparse.ArgumentParser();ap.add_argument('--source-root',type=Path,default=ROOT);args=ap.parse_args()
    src=args.source_root/'app/src/main/java/com/waenhancer/xposed/features/automation'
    text=(src/'PresenceToastsHook.java').read_text(encoding='utf-8')
    values={'install':extract(text,'    private void hookIncomingPresence()'),
            'shape':extract(text,'    private static boolean isPresenceHandler('),
            'decode':extract(text,'    private static PresenceEventPolicy.Event decodePresenceEvent(')}
    tree=ast.parse((args.source_root/'qa/test_presence_event_contract.py').read_text(encoding='utf-8'))
    expr=next(n.value for n in tree.body if isinstance(n,ast.Assign) and any(isinstance(t,ast.Name) and t.id=='body' for t in n.targets))
    if any(not isinstance(n,(ast.BinOp,ast.Add,ast.Constant,ast.Name,ast.Load)) for n in ast.walk(expr)):
        raise ValueError('Unexpected fixture template expression')
    body=eval(compile(ast.Expression(expr),'<fixture-template>','eval'),{'__builtins__':{}},values)
    body=body[:body.index(' public static void main(')]
    body=body.replace('Executor asyncExecutor=Runnable::run;', 'Executor asyncExecutor=lookupQueue::add;')
    start=body.index(' boolean isOnlineToastEnabled()');end=body.index(' static class ContactNameResolver',start)
    toast=extract(text,'    private void showToastSafely(String message)')
    if '    private void showToastSafely(String message,' in text:
        toast+='\n'+extract(text,'    private void showToastSafely(String message,')
    body=body[:start]+' boolean isOnlineToastEnabled(){return enabled;}\n'+toast+'\n'+body[end:]
    body=body.replace('static class Handler {public void handleMessage(Message m){}}',
                      'static class Handler {public void handleMessage(Message m){} boolean post(Runnable r){uiQueue.add(r);return true;}}')
    body='import com.waenhancer.xposed.utils.HookErrors;\n'+body+CASES
    with tempfile.TemporaryDirectory(prefix='waex_presence_lifecycle_') as tmp:
        p=Path(tmp)/'TestPresenceEventContract.java';p.write_text(body,encoding='utf-8')
        support=[]
        for name, content in ERROR_STUBS.items():
            file=Path(tmp)/name;file.parent.mkdir(parents=True,exist_ok=True)
            file.write_text(content,encoding='utf-8');support.append(str(file))
        errors=args.source_root/'app/src/main/java/com/waenhancer/xposed/utils/HookErrors.java'
        subprocess.run(['javac','--release','17','-d',tmp,str(src/'PresenceStateStore.java'),str(src/'PresenceEventPolicy.java'),str(errors),*support,str(p)],check=True,timeout=60)
        subprocess.run(['java','-cp',tmp,'TestPresenceEventContract'],check=True,timeout=60)
if __name__=='__main__':main()
