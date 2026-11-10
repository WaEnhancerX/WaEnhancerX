package com.waenhancer.xposed.features.privacy;

import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/** Supplemental, deterministic differential and concurrent tests, not host runtime tests. */
public final class TestReceiptPropertyStress {
  private static long assertions;
  private static void must(boolean x, String msg) { assertions++;if (!x) throw new AssertionError(msg); }

  private static final class Model {
    private final int maxRecipients, maxPer;
    private final ArrayList<String> lru = new ArrayList<>();
    private final Map<String,ArrayList<Integer>> q = new HashMap<>();
    Model(int a,int b) {maxRecipients=a;maxPer=b;}
    void touch(String key){lru.remove(key);lru.add(key);}
    void add(String key,int job){
      if(!q.containsKey(key))q.put(key,new ArrayList<>());
      touch(key);
      ArrayList<Integer> v=q.get(key);
      if(v.size()==maxPer)v.remove(0);
      v.add(job);
      while(lru.size()>maxRecipients)q.remove(lru.remove(0));
    }
    int count(String key){
      ArrayList<Integer> v=q.get(key);
      if(v!=null)touch(key);
      return v==null?0:v.size();
    }
    List<Integer> drain(String key){
      lru.remove(key);ArrayList<Integer> v=q.remove(key);
      return v==null?List.of():v;
    }
    int total(){return q.size();}
  }

  // Test-only model of successful enqueue acknowledgements; production uses
  // snapshot then removes each exact accepted recipe. A snapshot alone is read-only.
  private static <T> java.util.List<T> acknowledgeSnapshot(PendingReadReceiptStore<T> store,String recipient){
    java.util.List<T> batch=store.snapshot(recipient);
    for(T item:batch)store.remove(recipient,item);
    return batch;
  }

  public static void main(String[] args) throws Exception {
    Random random = new Random(0x51EADBEEFL);
    PendingReadReceiptStore<Integer> prod=new PendingReadReceiptStore<>(31,17);
    Model model=new Model(31,17);
    String[] namespace={"@lid","@g.us","@s.whatsapp.net","@broadcast"};
    for(int i=0;i<100_000;i++){
      String key=(random.nextInt(60)+1)+namespace[random.nextInt(namespace.length)];
      switch(random.nextInt(5)){
        case 0,1,2 -> {prod.add(key,i);model.add(key,i);}
        case 3 -> must(prod.pendingCount(key)==model.count(key),"count differs at operation="+i);
        case 4 -> must(acknowledgeSnapshot(prod,key).equals(model.drain(key)),"drain differs at operation="+i);
      }
      must(prod.recipientCount()==model.total(),"recipient bound / exact eviction mismatch at="+i);
      must(prod.recipientCount()<=31,"capacity overflow");
    }
    for(int u=1;u<=60;u++)for(String suffix:namespace){
      String key=u+suffix;
      must(acknowledgeSnapshot(prod,key).equals(model.drain(key)),"flush differs for "+key);
    }
    must(prod.recipientCount()==0,"flush complete");
    final PendingReadReceiptStore<Integer> shared=new PendingReadReceiptStore<>(128,64);
    final CountDownLatch done=new CountDownLatch(10);
    final AtomicInteger errors=new AtomicInteger();
    for(int t=0;t<10;t++){
      final int thread=t;
      new Thread(()->{
        try {
          Random r=new Random(thread*2L+200);
          for(int i=0;i<10_000;i++){
            String jid=(r.nextInt(250)+1)+(thread%2==0?"@lid":"@s.whatsapp.net");
            if((i%4)==0)acknowledgeSnapshot(shared,jid);
            else shared.add(jid,i);
            if(i%20==0)shared.pendingCount(jid);
          }
        }catch(Throwable ex){errors.incrementAndGet();}
        finally {done.countDown();}
      }).start();
    }
    if(!done.await(30,java.util.concurrent.TimeUnit.SECONDS))throw new AssertionError("Concurrent receipt operations timed out");
    must(errors.get()==0,"concurrent scenario threw exception");
    must(shared.recipientCount()<=128,"concurrent recipient capacity");
    System.out.println("PASS: "+assertions+" differential/capacity assertions; 100,000 serial + 100,000 concurrent mixed operations");
  }
}
