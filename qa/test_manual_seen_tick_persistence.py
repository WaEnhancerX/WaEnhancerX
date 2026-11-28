#!/usr/bin/env python3
"""Execute the real Styles update lambda and preference manager, with explicit JVM doubles.
No Compose rendering, Android disk I/O, provider delivery or LSPosed claim.
"""
from pathlib import Path
import ast
import os
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / 'app/src/main/java/com/waenhancer'

def block(text, marker):
    start = text.index(marker)
    opening = text.index('{', start)
    depth = 0
    # Source marker contains no braces in strings; walk lexically nonetheless.
    state = 'code'; i = opening
    while i < len(text):
        c = text[i]; pair = text[i:i+2]
        if state == 'line':
            if c == '\n': state = 'code'
        elif state == 'comment':
            if pair == '*/': state = 'code'; i += 1
        elif state in ('str', 'char'):
            if c == '\\': i += 1
            elif c == ('"' if state == 'str' else "'"): state = 'code'
        elif pair == '//': state = 'line'; i += 1
        elif pair == '/*': state = 'comment'; i += 1
        elif c == '"': state = 'str'
        elif c == "'": state = 'char'
        elif c == '{': depth += 1
        elif c == '}':
            depth -= 1
            if depth == 0: return text[start:i+1]
        i += 1
    raise ValueError('Unclosed production declaration')

FAKE = r'''
package fixture;
import android.content.*;
import android.content.pm.ApplicationInfo;
import java.util.*;
public final class FakeContext extends Context {
 public int notifications; public final Prefs prefs=new Prefs();
 public SharedPreferences getSharedPreferences(String n,int m){
  if(!"waex_prefs".equals(n)||m!=MODE_PRIVATE)throw new AssertionError("Unexpected preference scope");return prefs;}
 public java.io.File getFilesDir(){throw new AssertionError("Unexpected file access");}
 public ApplicationInfo getApplicationInfo(){return new ApplicationInfo();}
 public ContentResolver getContentResolver(){return new ContentResolver(){
  public void notifyChange(android.net.Uri u,Object o){notifications++;}
 };}
 public static final class Prefs implements SharedPreferences {
  public final Map<String,Object> memory=new HashMap<>(),disk=new HashMap<>();
  public int mode,commits,edits,readsAfterCommit;
  public void seed(String v){memory.clear();disk.clear();if(v!=null){memory.put("seentick",v);disk.put("seentick",v);}}
  public String getString(String k,String d){if(commits>0)readsAfterCommit++;return (String)memory.getOrDefault(k,d);}
  public boolean getBoolean(String k,boolean d){return (Boolean)memory.getOrDefault(k,d);}
  public int getInt(String k,int d){return (Integer)memory.getOrDefault(k,d);}
  public float getFloat(String k,float d){return (Float)memory.getOrDefault(k,d);}
  @SuppressWarnings("unchecked") public Set<String> getStringSet(String k,Set<String>d){return (Set<String>)memory.getOrDefault(k,d);}
  public boolean contains(String k){return memory.containsKey(k);}
  public Map<String,?>getAll(){return new HashMap<>(memory);}
  public Editor edit(){edits++;if(mode==3)throw new IllegalStateException("editor fixture");return new Editor(){
   final Map<String,Object> pending=new HashMap<>();
   public Editor putString(String k,String v){pending.put(k,v);return this;}
   public Editor putBoolean(String k,boolean v){pending.put(k,v);return this;}
   public Editor putInt(String k,int v){pending.put(k,v);return this;}
   public Editor putFloat(String k,float v){pending.put(k,v);return this;}
   public Editor putStringSet(String k,Set<String>v){pending.put(k,v);return this;}
   public Editor putLong(String k,long v){throw new AssertionError("Unexpected long preference");}
   public Editor remove(String k){throw new AssertionError("Unexpected preference removal");}
   public void apply(){throw new AssertionError("Manual setting requires synchronous commit");}
   public boolean commit(){commits++;if(mode==2)throw new IllegalStateException("commit fixture");
    if(mode==0||mode==4)memory.putAll(pending);
    if(mode!=0)return false;disk.putAll(pending);return true;}
  };}
 }
}
'''
TEST = r'''
import fixture.FakeContext
import android.widget.Toast
import com.waenhancer.core.preferences.WaexPreferenceManager
import com.waenhancer.utils.DiagnosticsRecorder
import com.waenhancer.ui.screens.settings.*
class Ui(val context: FakeContext) {
 val preferenceManager = WaexPreferenceManager(context)
 val settingsState = mutableMapOf("seen_tick_customization" to
     ManualSeenTickPreferencePolicy.isEnabled(preferenceManager.getString("seentick", "0")))
__PRODUCTION__
}
fun main() {
 var scenarios=0
 for (initial in listOf<String?>(null,"0","1","2","true","invalid")) {
  for (value in listOf(false,true)) for (mode in 0..4) {
   val c=FakeContext();c.prefs.seed(initial);c.prefs.mode=mode;Toast.shown=0;DiagnosticsRecorder.changes=0
   val u=Ui(c);val before=u.settingsState["seen_tick_customization"]
   val diskBefore=HashMap(c.prefs.disk)
   val target=ManualSeenTickPreferencePolicy.valueAfterExplicitToggle(value,initial?:"0")
   u.updatePreference("seen_tick_customization",value)
   if(mode==0){
    check(c.prefs.disk["seentick"]==target);check(c.notifications==1);check(Toast.shown==0)
    check(DiagnosticsRecorder.changes==1){"successful write not journaled once"}
    check(u.settingsState["seen_tick_customization"]==ManualSeenTickPreferencePolicy.isEnabled(target))
   } else {
    check(c.prefs.disk==diskBefore){"failed write changed durable fixture"}
    check(c.notifications==0){"failure notified as success"};check(Toast.shown==1){"failure not surfaced"}
    check(DiagnosticsRecorder.changes==0){"failed write journaled as success"}
    check(u.settingsState["seen_tick_customization"]==before){"UI accepted failed write mode=$mode initial=$initial"}
    check(c.prefs.readsAfterCommit==0){"reread failed in-memory write as successful"}
    if(mode==4)check(c.prefs.memory["seentick"]==target){"memory-first failure must be exercised"}
    c.prefs.mode=0;Toast.shown=0;u.updatePreference("seen_tick_customization",value)
    check(c.notifications==1&&Toast.shown==0&&c.prefs.disk["seentick"]==target){"retry failed"}
    check(DiagnosticsRecorder.changes==1){"retry not journaled once"}
   }
   check(!c.prefs.memory.containsKey("seen_tick_customization")){"legacy Boolean alias written"}
   scenarios++
  }
 }
 println("PASS: $scenarios manual-setting scenarios, including failed commits, memory-first failures and retries; production lambda/manager with JVM doubles")
}
'''

def main():
    tools = {tool: shutil.which(tool) for tool in ('javac','java','kotlinc')}
    for tool, path in tools.items():
        if path is None: raise RuntimeError('Required tool missing: '+tool)
    text=(SRC/'ui/screens/settings/StylesSettingsScreen.kt').read_text(encoding='utf-8')
    update=block(text,'val updatePreference: (String, Boolean) -> Unit = update@')
    stub_tree=ast.parse((ROOT/'qa/test_media_preference_sync.py').read_text(encoding='utf-8'))
    stubs=next(ast.literal_eval(n.value) for n in stub_tree.body if isinstance(n,ast.Assign)
               and any(isinstance(t,ast.Name) and t.id=='STUBS' for t in n.targets))
    # Own this fixture boundary instead of depending on another QA proposal.
    stubs['androidx/annotation/NonNull.java']='package androidx.annotation; public @interface NonNull {}'
    stubs['androidx/annotation/Nullable.java']='package androidx.annotation; public @interface Nullable {}'
    stubs['com/waenhancer/utils/DiagnosticsRecorder.java']=r"""
package com.waenhancer.utils;
import android.content.Context;
public final class DiagnosticsRecorder {
 public static int changes;
 public static void recordPreferenceChange(Context context,String key,boolean scoped,String operation) {
  if(context==null||!"seentick".equals(key)||scoped||!"PUT".equals(operation))
   throw new AssertionError("Unexpected manual-setting diagnostic contract");
  changes++;
 }
 public static void recordSettingsTransfer(Context context,String kind,int count) {
  throw new AssertionError("Unexpected settings transfer");
 }
}
"""
    # Older standalone branches have fewer methods in their Android double.
    prefs=stubs['android/content/SharedPreferences.java']
    if 'getStringSet' not in prefs:
        prefs=prefs.replace(' Editor edit();',' java.util.Set<String> getStringSet(String k, java.util.Set<String> d);\n Editor edit();')
        prefs=prefs.replace('  boolean commit();','  Editor putStringSet(String k, java.util.Set<String> v);\n  boolean commit();')
    stubs['android/content/SharedPreferences.java']=prefs
    stubs['android/widget/Toast.java']='''package android.widget; import android.content.Context;
public final class Toast {public static int shown;public static final int LENGTH_SHORT=0;
public static Toast makeText(Context c,String s,int l){if(!"Unable to save setting".equals(s))throw new AssertionError(s);return new Toast();}
public void show(){shown++;}}'''
    stubs['fixture/FakeContext.java']=FAKE
    with tempfile.TemporaryDirectory(prefix='waex_manual_persist_') as td:
        out=Path(td);sources=[]
        for name,content in stubs.items():
            p=out/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(content,encoding='utf-8');sources.append(str(p))
        for rel in ('config/PreferenceSchema.java','config/PreferenceStores.java',
                    'config/ExclusivePreferences.java',
                    'core/preferences/WaexPreferenceManager.java',
                    'ui/screens/settings/ManualSeenTickPreferencePolicy.java',
                    'ui/screens/settings/StyleBooleanAliasPolicy.java',
                    'ui/screens/settings/StyleNonBooleanPreferencePolicy.java'):
            sources.append(str(SRC/rel))
        subprocess.run([tools['javac'],'--release','17','-d',td,*sources],check=True,timeout=60)
        # Production callback uses the module's localized, presentation-only warning.
        # Keep its exact body but provide explicit resource adapters for this JVM probe.
        import re
        ids=sorted(set(re.findall(r'R\.string\.([A-Za-z_][A-Za-z_0-9]*)',update)))
        r_stub='object R {object string {'+ ''.join(f'const val {name} = {0x7f060100+i};' for i,name in enumerate(ids))+'}}\n'
        ui_stub='object UiText {fun get(id:Int, fallback:String, vararg args:Any?):String = if(args.isEmpty()) fallback else String.format(java.util.Locale.US, fallback, *args)}\n'
        kt=out/'TestManual.kt';kt.write_text(TEST.replace('__PRODUCTION__',update).replace('class Ui(',r_stub+ui_stub+'class Ui('),encoding='utf-8')
        jar=out/'test.jar'
        subprocess.run([tools['kotlinc'],str(kt),str(SRC/'ui/screens/settings/FloatingNavigationExclusivity.kt'),'-classpath',td,'-jvm-target','17','-include-runtime','-d',str(jar)],check=True,timeout=90)
        subprocess.run([tools['java'],'-cp',str(jar)+os.pathsep+td,'TestManualKt'],check=True,timeout=60)
if __name__=='__main__':main()
