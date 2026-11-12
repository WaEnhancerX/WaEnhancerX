#!/usr/bin/env python3
"""Production presence-store replay/order regression, plus renderer wiring guards.

Host JVM only. This does not exercise Android drawing or a WhatsApp connection;
run android_presence_row_test.py separately for framework/Skia row tests.
"""
from pathlib import Path
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
STORE = ROOT / 'app/src/main/java/com/waenhancer/xposed/features/automation/PresenceStateStore.java'
RENDERER = ROOT / 'app/src/main/java/com/waenhancer/xposed/features/customization/OnlinePresenceIndicatorsHook.kt'
TEST = r'''
package com.waenhancer.xposed.features.automation;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
public final class TestPresenceReplay {
 static int checks;
 static void check(boolean value, String name) {
  if(!value)throw new AssertionError(name);checks++;System.out.println("PASS: "+name);
 }
 static void reset() throws Exception {
  for(String name:new String[]{"STATES","LISTENERS"}){
   java.lang.reflect.Field f=PresenceStateStore.class.getDeclaredField(name);f.setAccessible(true);
   Object v=f.get(null);if(v instanceof Map)((Map<?,?>)v).clear();else ((Set<?>)v).clear();
  }
 }
 public static void main(String[] args)throws Exception {
  reset();String a="1001@s.whatsapp.net", lid="9001@lid";
  check(PresenceStateStore.getLatest(null)==null,"Missing candidate set has no inferred presence");
  check(PresenceStateStore.getLatest(Collections.emptyList())==null,"Empty candidate set has no presence");
  check(PresenceStateStore.getLatest(Arrays.asList(null,a))==null,"Missing identity observations ignored");
  AtomicInteger notifications=new AtomicInteger();
  PresenceStateStore.Listener counter=(j,s)->notifications.incrementAndGet();
  PresenceStateStore.addListener(counter);PresenceStateStore.addListener(counter);
  check(PresenceStateStore.update(a,true),"First available observation is a transition");
  PresenceStateStore.State first=PresenceStateStore.get(a);
  check(!PresenceStateStore.update(a,true),"Repeated online does not trigger duplicate toast transition");
  PresenceStateStore.State repeated=PresenceStateStore.get(a);
  check(repeated.revision>first.revision,"Every observation advances revision");
  check(repeated.changedAtMillis==first.changedAtMillis,"Repeated observation preserves transition wall time");
  check(notifications.get()==2,"Deduplicated listener receives both observations");
  check(!PresenceStateStore.update(null,true)&&!PresenceStateStore.update(" ",true),"Empty identities ignored");
  check(!PresenceStateStore.update("12-34@g.us",true),"Group identity ignored");
  check(PresenceStateStore.getLatest(Arrays.asList(lid))==null,"Namespace similarity is not an alias");
  PresenceStateStore.update(lid,false);PresenceStateStore.State newer=PresenceStateStore.get(lid);
  check(PresenceStateStore.getLatest(Arrays.asList(a,lid))==newer,"Latest explicit identity wins");
  check(PresenceStateStore.getLatest(Arrays.asList(lid,a))==newer,"Candidate order cannot override observation order");
  check(PresenceStateStore.getLatest(Arrays.asList(a,a,null))==repeated,"Duplicates and null candidates do not fabricate data");
  PresenceStateStore.update(a,true);
  check(PresenceStateStore.getLatest(Arrays.asList(a,lid)).online,"Repeat of earlier namespace becomes latest observation");
  PresenceStateStore.removeListener(counter);int n=notifications.get();PresenceStateStore.update(a,false);
  check(notifications.get()==n,"Listener removal is respected");
  PresenceStateStore.addListener((j,s)->{throw new IllegalStateException("fixture");});
  PresenceStateStore.addListener((j,s)->{throw new NoClassDefFoundError("fixture");});
  PresenceStateStore.addListener(counter);PresenceStateStore.update(a,true);
  check(notifications.get()==n+1,"Runtime and linkage failures isolate listener delivery");
  reset();for(int i=0;i<3000;i++)PresenceStateStore.update(i+"@lid",true);
  check(PresenceStateStore.stateCount()==2048,"State remains bounded at 2048");
  check(PresenceStateStore.get("0@lid")==null,"Evicted identity is unknown, not offline");
  PresenceStateStore.get("952@lid");PresenceStateStore.update("new@lid",true);
  check(PresenceStateStore.get("952@lid")==null&&PresenceStateStore.get("953@lid")!=null,"Reads cannot extend an old observation past a newer tombstone");
  reset();int workers=8,loops=4000;
  Set<Long> revisions=ConcurrentHashMap.newKeySet();AtomicInteger delivered=new AtomicInteger();
  PresenceStateStore.addListener((j,s)->{if(!revisions.add(s.revision))throw new AssertionError("Duplicate revision");delivered.incrementAndGet();});
  ExecutorService pool=Executors.newFixedThreadPool(workers);List<Future<?>> futures=new ArrayList<>();
  for(int w=0;w<workers;w++){final int id=w;futures.add(pool.submit(()->{
   for(int i=0;i<loops;i++){
    PresenceStateStore.update((id*100+i%100)+"@lid",(i&1)==0);
    PresenceStateStore.getLatest(Arrays.asList((id*100+i%100)+"@lid","unseen@lid"));
   }
  }));}
  pool.shutdown();for(Future<?> f:futures)f.get(30,TimeUnit.SECONDS);
  check(pool.awaitTermination(30,TimeUnit.SECONDS),"Concurrent workers terminate");
  check(delivered.get()==workers*loops&&revisions.size()==workers*loops,"32000 concurrent observations have unique revisions and delivery");
  check(PresenceStateStore.stateCount()<=2048,"Concurrent read/write bound preserved");
  PresenceStateStore.State latest=PresenceStateStore.getLatest(Arrays.asList("0@lid","100@lid","200@lid","300@lid"));
  check(latest!=null,"Concurrent state remains readable");
  System.out.println("CHECKS="+checks+"; 32000 observations (not independent features); no Android/LSPosed");
 }
}
'''

def main():
    for tool in ('javac','java'):
        if not shutil.which(tool): raise RuntimeError('Required compiler/runtime missing: '+tool)
    source=RENDERER.read_text(encoding='utf-8')
    guards={
        'visible rows use weak keys': 'Collections.synchronizedMap(WeakHashMap<View, String>())',
        'pending rows use weak keys': 'Collections.synchronizedMap(WeakHashMap<View, Any>())',
        'cached aliases use ordered state': 'PresenceStateStore.getLatest(keys)',
        'new binds restore cached state': 'render(row, stateForRow(jid))',
        'late observations read current state': 'render(row, stateForRow(boundJid))',
        'bind generation rejects obsolete callbacks': 'pendingBindings[current] !== token',
        'binding tag verified': 'row.getTag(TAG_BOUND_JID) == boundJid',
        'failed bind clears previous contact': 'queueBindRow(row, null)',
        'invalidated rows clear tag': 'row.setTag(TAG_BOUND_JID, null)',
        'refresh burst coalescing': 'refreshPending.compareAndSet(false, true)',
        'dot participates in real parent layout': 'targetGroup.addView(dot, params)',
        'dot uses relative avatar-end gravity': 'Gravity.BOTTOM or Gravity.END',
        'text participates in real parent layout': 'group.addView(text, params)',
        'ambiguous alias tombstone': 'if (previous == null || previous == jid) jid else ""',
    }
    for name,required in guards.items():
        assert required in source, name
    assert 'row.post { bindRow' not in source,'No unconditional delayed old binding'
    assert 'render(row, state)' not in source,'Do not repaint captured stale event data'
    with tempfile.TemporaryDirectory(prefix='waex_presence_replay_') as td:
        test=Path(td)/'TestPresenceReplay.java';test.write_text(TEST)
        subprocess.run(['javac','--release','17','-d',td,str(STORE),str(test)],check=True,timeout=60)
        subprocess.run(['java','-cp',td,'com.waenhancer.xposed.features.automation.TestPresenceReplay'],check=True,timeout=60)
    print('PASS: '+str(len(guards)+2)+' renderer source guards; run Android/Robolectric tests separately')

if __name__=='__main__':
    main()
