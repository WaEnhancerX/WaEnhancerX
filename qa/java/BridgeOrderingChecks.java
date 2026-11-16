package test;
import android.content.*;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Bundle;
import com.waenhancer.xposed.bridge.client.PreferenceBridgeClient;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public final class BridgeOrderingChecks {
 static final AtomicInteger checks=new AtomicInteger();
 static void check(boolean b,String m){checks.incrementAndGet();if(!b)throw new AssertionError(m);}
 static void await(CountDownLatch l,String m){try{check(l.await(4,TimeUnit.SECONDS),m);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}}
 static final class Gate {
  final CountDownLatch entered=new CountDownLatch(1),released=new CountDownLatch(1);
  void enter(){entered.countDown();await(released,"gate release");}
 }
 static class Resolver extends ContentResolver {
  final Map<String,Object> db=new LinkedHashMap<>();
  final List<ContentObserver> observers=new CopyOnWriteArrayList<>();
  volatile Gate nextRead,nextWrite;
  volatile boolean deny;
  final AtomicInteger writes=new AtomicInteger();
  @Override public void registerContentObserver(Uri u,boolean d,ContentObserver o){observers.add(o);}
  void notifyChange(){for(ContentObserver o:observers)o.onChange(false);}
  @Override public Bundle call(Uri u,String method,String arg,Bundle extras){
   if("get_all_preferences".equals(method)){
    Bundle result=new Bundle(),v=new Bundle(),sets=new Bundle();
    synchronized(db){for(var e:db.entrySet()){
     Object x=e.getValue();String k=e.getKey();
     if(x instanceof String s)v.putString(k,s);else if(x instanceof Boolean b)v.putBoolean(k,b);
     else if(x instanceof Integer n)v.putInt(k,n);else if(x instanceof Long n)v.putLong(k,n);
     else if(x instanceof Set s)sets.putStringArrayList(k,new ArrayList<>(s));
    }}
    result.putBundle("values",v);result.putBundle("sets",sets);
    Gate g=nextRead;nextRead=null;if(g!=null)g.enter();
    return result;
   }
   if("apply_preferences".equals(method)){
    Gate g=nextWrite;nextWrite=null;if(g!=null)g.enter();
    Bundle result=new Bundle();if(deny){result.putBoolean("success",false);return result;}
    synchronized(db){
     if(extras.getBoolean("clear",false))db.clear();
     for(Bundle op:extras.getParcelableArrayList("operations")){
      String k=op.getString("key"),t=op.getString("type");
      switch(t){
       case "remove"->db.remove(k);case "string"->db.put(k,op.getString("value"));
       case "boolean"->db.put(k,op.getBoolean("value",false));case "int"->db.put(k,op.getInt("value"));
       case "long"->db.put(k,op.getLong("value"));case "string_set"->db.put(k,new HashSet<>(op.getStringArrayList("value")));
       default->throw new AssertionError(t);
      }
     }
    }
    writes.incrementAndGet();notifyChange();result.putBoolean("success",true);return result;
   }
   return null;
  }
 }
 static class Ctx extends Context{final Resolver r=new Resolver();public ContentResolver getContentResolver(){return r;}public String getPackageName(){return "com.whatsapp";}}
 static ExecutorService executor(PreferenceBridgeClient p)throws Exception{
  var f=p.getClass().getDeclaredField("ioExecutor");f.setAccessible(true);return (ExecutorService)f.get(p);
 }
 static void settle(PreferenceBridgeClient p)throws Exception{
  // Two barriers include a refresh queued by a preceding write-completion callback.
  executor(p).submit(()->{}).get(4,TimeUnit.SECONDS);executor(p).submit(()->{}).get(4,TimeUnit.SECONDS);
 }
 static void shutdown(PreferenceBridgeClient p)throws Exception{settle(p);executor(p).shutdownNow();}
 static void pendingRefreshCannotEraseApply()throws Exception{
  Ctx c=new Ctx();c.r.db.put("old",7L);PreferenceBridgeClient p=new PreferenceBridgeClient(c,null);
  Gate read=new Gate(),write=new Gate();c.r.nextRead=read;c.r.nextWrite=write;c.r.notifyChange();
  await(read.entered,"refresh captured old values");
  try{
   p.edit().clear().putString("new","yes").apply();
   check(!p.contains("old")&&"yes".equals(p.getString("new",null)),"immediate optimistic snapshot");
   read.released.countDown();await(write.entered,"write held after old refresh returns");
   check(!p.contains("old")&&"yes".equals(p.getString("new",null)),"stale refresh must not overwrite pending apply");
  }finally{read.released.countDown();write.released.countDown();shutdown(p);}
 }
 static void syncStartedAfterApplyCannotEraseIt()throws Exception{
  Ctx c=new Ctx();c.r.db.put("key","before");PreferenceBridgeClient p=new PreferenceBridgeClient(c,null);
  Gate write=new Gate();c.r.nextWrite=write;
  try{
   p.edit().putString("key","after").apply();await(write.entered,"write blocked");
   p.syncPreferences();
   check("after".equals(p.getString("key",null)),"read starting after apply still cannot use pre-persistence values");
  }finally{write.released.countDown();shutdown(p);}
 }
 static void olderCommitCannotEraseNewerApply()throws Exception{
  Ctx c=new Ctx();PreferenceBridgeClient p=new PreferenceBridgeClient(c,null);Gate write=new Gate();c.r.nextWrite=write;
  Gate laterWrite=new Gate();
  CompletableFuture<Boolean> committed=CompletableFuture.supplyAsync(()->p.edit().putString("key","commit").putInt("extra",9).commit());
  try{
   await(write.entered,"commit held");p.edit().putString("key","apply").apply();c.r.nextWrite=laterWrite;
   write.released.countDown();await(laterWrite.entered,"later apply held");check(committed.get(4,TimeUnit.SECONDS),"commit successful");
   check("apply".equals(p.getString("key",null)),"older commit does not replace later optimistic key");
   check(p.getInt("extra",0)==9,"unrelated committed key retained");
   laterWrite.released.countDown();settle(p);check("apply".equals(p.getString("key",null)),"settled order matches provider");
  }finally{write.released.countDown();laterWrite.released.countDown();shutdown(p);}
 }
 static void deniedApplyRestoresAuthoritativeSnapshot()throws Exception{
  Ctx c=new Ctx();c.r.db.put("key","before");PreferenceBridgeClient p=new PreferenceBridgeClient(c,null);
  c.r.deny=true;p.edit().putString("key","after").apply();settle(p);
  check("before".equals(p.getString("key",null)),"denied apply reconciles cache");
  check(!p.edit().putString("other","x").commit(),"failed commit reports false");
  check(!p.contains("other"),"failed commit has no optimistic value");shutdown(p);
 }
 static void reuseEditorDoesNotChangeQueuedClear()throws Exception{
  Ctx c=new Ctx();c.r.db.put("keep","value");PreferenceBridgeClient p=new PreferenceBridgeClient(c,null);Gate read=new Gate();c.r.nextRead=read;
  c.r.notifyChange();await(read.entered,"occupy executor before enqueueing editor");
  try{
   SharedPreferences.Editor editor=p.edit().putString("key","one");editor.apply();
   editor.clear().putString("key","two");read.released.countDown();settle(p);
   check("value".equals(p.getString("keep",null)),"unsubmitted editor clear must not change queued write");
   check("one".equals(p.getString("key",null)),"queued changes captured");
  }finally{read.released.countDown();shutdown(p);}
 }
 static void olderReadCannotOverwriteNewerRead()throws Exception{
  Ctx c=new Ctx();c.r.db.put("key","old");PreferenceBridgeClient p=new PreferenceBridgeClient(c,null);Gate read=new Gate();c.r.nextRead=read;
  c.r.notifyChange();await(read.entered,"old snapshot held");
  try{
   synchronized(c.r.db){c.r.db.put("key","new");}
   p.syncPreferences();check("new".equals(p.getString("key",null)),"newer provider snapshot accepted");
   read.released.countDown();settle(p);check("new".equals(p.getString("key",null)),"older read cannot replace newer published read");
  }finally{read.released.countDown();shutdown(p);}
 }
 static void burstPreservesClearRemoveAndSets()throws Exception{
  Ctx c=new Ctx();PreferenceBridgeClient p=new PreferenceBridgeClient(c,null);
  for(int i=0;i<100;i++){
   p.edit().putInt("n",i).putStringSet("set",Set.of("a","b")).apply();
   if(i%10==0)p.edit().clear().putInt("n",i).apply();
  }
  check(p.edit().remove("set").putBoolean("done",true).commit(),"final commit drains queued writes");settle(p);
  check(p.getInt("n",-1)==99&&p.getBoolean("done",false)&&!p.contains("set"),"burst final state");
  synchronized(c.r.db){check(p.getAll().equals(c.r.db),"cache equals provider after settling");}
  shutdown(p);
 }
 public static void main(String[]args)throws Exception{
  int repeat=args.length==0?1:Integer.parseInt(args[0]);
  if(repeat<1||repeat>1000)throw new IllegalArgumentException("repeat");
  for(int i=0;i<repeat;i++){
   pendingRefreshCannotEraseApply();syncStartedAfterApplyCannotEraseIt();olderCommitCannotEraseNewerApply();
   deniedApplyRestoresAuthoritativeSnapshot();reuseEditorDoesNotChangeQueuedClear();olderReadCannotOverwriteNewerRead();burstPreservesClearRemoveAndSets();
  }
  System.out.println("PASS: "+checks.get()+" checks; "+repeat+" repeats of 7 deterministic interleaving scenarios, actual bridge with explicit provider/Handler doubles; no Android persistence claim.");
 }
}
