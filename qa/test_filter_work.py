#!/usr/bin/env python3
"""Production traversal/reload/scheduler with counted View/Handler substitutes. Not Android frames."""
from pathlib import Path
import argparse,subprocess,tempfile,re
import shutil
ROOT=Path(__file__).resolve().parents[1]
def block(s,m):
 a=s.index(m);b=s.index('{',a);d=0
 for i in range(b,len(s)):
  d+=(s[i]=='{')-(s[i]=='}')
  if not d:return s[a:i+1]
 raise ValueError(m)
PROGRAM=r'''import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
object Stats{var ids=0L;var children=0L;var parsed=0}
open class View(val raw:Int){val id:Int get(){Stats.ids++;return raw}}
class ViewGroup(raw:Int,val nodes:List<View>):View(raw){val childCount:Int get()=nodes.size;fun getChildAt(i:Int):View{Stats.children++;return nodes[i]}}
class Activity
object ActivityTracker{fun getCurrentActivity():Activity?=null}
class Prefs {var text="";fun getString(k:String,d:String?)=text}
class Handler {val jobs=mutableListOf<Runnable>();var posts=0;var accept=true;@Synchronized fun post(r:Runnable):Boolean{posts++;if(!accept)return false;jobs.add(r);return true};fun post(f:()->Unit)=post(Runnable(f));@Synchronized fun removeCallbacks(r:Runnable){jobs.removeAll{it===r}};fun flush(){while(jobs.isNotEmpty()){val r=synchronized(this){jobs.removeAt(0)};r.run()}}}
class Fixture{
 data class FilterItem(val id:String)
 @Volatile var targetMap:Map<Int,FilterItem> = emptyMap()
 val prefs=Prefs();val mainHandler=Handler();val reloadQueued=java.util.concurrent.atomic.AtomicBoolean(false);val reloadTask=Runnable{reloadQueued.set(false);reloadFilters()};var enabled=true
 val applied=mutableListOf<Int>();var duringApply:(()->Unit)?=null;var duringParse:(()->Unit)?=null
 fun isEnabled(k:String,d:Boolean)=enabled
 fun normalizeResourceIdName(n:String)=n
 fun getResourceId(n:String)=n.toIntOrNull()?:0
 fun parseFilters(raw:String):List<FilterItem>{Stats.parsed++;duringParse?.invoke();return raw.split(',').filter{it.isNotEmpty()}.map{FilterItem(it)}}
 fun applyFilterToView(v:View,f:FilterItem){applied.add(v.raw);duringApply?.invoke()}
 fun applyFiltersToActivity(a:Activity){}
 BODY
 fun apply(v:View)=applyFiltersRecursively(v)
 fun reload()=reloadFilters()
 fun signal()=scheduleReload()
}
fun tree(depth:Int,id:Int=1):View=if(depth==0)View(id)else ViewGroup(id,listOf(tree(depth-1,id*2),tree(depth-1,id*2+1)))
fun main(){
 val f=Fixture();val root=tree(10);repeat(1000){f.apply(root)};check(Stats.ids==0L&&Stats.children==0L){"empty rules traversed views: ${Stats.ids}"}
 println("MEASURE 1000 layouts x 2047 nodes: empty rules id/child visits ${Stats.ids}/${Stats.children}")
 f.targetMap=mapOf(1 to Fixture.FilterItem("1"),2 to Fixture.FilterItem("2"));f.duringApply={f.targetMap=mapOf(3 to Fixture.FilterItem("3"))};f.apply(root);check(f.applied==listOf(1,2)){"mixed snapshot during traversal"};f.duringApply=null
 f.targetMap=mapOf(8 to Fixture.FilterItem("8"));f.prefs.text="4,5";f.duringParse={check(f.targetMap.containsKey(8)){"published partial rules"}};f.reload();check(f.targetMap.keys==setOf(4,5));f.duringParse=null
 try{(f.targetMap as MutableMap)[6]=Fixture.FilterItem("6");error("mutable published rules")}catch(_:UnsupportedOperationException){}
 f.mainHandler.flush();f.mainHandler.posts=0;Stats.parsed=0;repeat(1000){f.signal()};check(f.mainHandler.jobs.size==1);f.mainHandler.flush();check(Stats.parsed==1);check(f.mainHandler.posts==2) // one reload + one repaint
 Stats.parsed=0;val threads=List(10){Thread{repeat(100){f.signal()}}};threads.forEach{it.start()};threads.forEach{it.join()};check(f.mainHandler.jobs.size==1);f.mainHandler.flush();check(Stats.parsed==1)
 f.mainHandler.accept=false;f.signal();check(!f.reloadQueued.get());f.mainHandler.accept=true;f.signal();f.mainHandler.flush()
 f.enabled=false;f.reload();check(f.targetMap.isEmpty());f.prefs.text="";f.enabled=true;f.reload();check(f.targetMap.isEmpty())
 println("PASS: empty fast path, one traversal snapshot, immutable atomic reload, serial/concurrent coalescing, disable/empty clearing. Real method bodies, explicit View/Handler/parser doubles.")
}
'''
def main():
 p=argparse.ArgumentParser();p.add_argument('--root',type=Path,default=ROOT);a=p.parse_args()
 path=a.root/'app/src/main/java/com/waenhancer/xposed/features/customization/FilterItemsHook.kt';s=path.read_text()
 methods=[block(s,m) for m in ['private fun scheduleReload()', 'private fun reloadFilters()', 'private fun applyFiltersRecursively(view: View)', 'private fun applyFiltersRecursively(view: View, rules:']]
 assert 'scheduleReload()' in s[s.index('prefs.registerOnSharedPreferenceChangeListener'):s.index('    private fun reloadFilters()')]
 assert '@Volatile private var targetMap:' in s
 assert 'if (targetMap.isEmpty() || activity.isFinishing' in s
 # No change to Pro-only behavior while reducing traversal/reload work.
 assert re.search(r"FilterItemsProFeature\.applyProFilter\(\s*target,\s*item\.behavior,\s*item\.color,\s*item\.opacity,\s*item\.scale,\s*prefs\s*\)", s)
 assert block(s,'private fun parseFilters(').count('JSONArray(trimmed)') == 1
 assert 'FilterItem.BEHAVIOR_GONE == item.behavior' in block(s,'private fun applyFilterToView(')
 with tempfile.TemporaryDirectory(prefix='waex-filter-') as d:
  out=Path(d);file=out/'Checks.kt';file.write_text(PROGRAM.replace('BODY','\n'.join(methods)))
  subprocess.run([shutil.which('kotlinc') or 'kotlinc','-nowarn','-language-version','2.0',str(file),'-include-runtime','-d',str(out/'checks.jar')],check=True,timeout=90)
  print('COMPILE_OK',flush=True);subprocess.run(['java','-jar',str(out/'checks.jar')],check=True,timeout=30)
if __name__=='__main__':main()
