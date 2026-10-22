import android.content.Context;
import android.content.SharedPreferences;
import android.media.MediaRecorder;
import com.waenhancer.xposed.features.media.CallRecordingHook;
import com.whatsapp.calling.infra.voipcalling.CallInfo;
import com.whatsapp.calling.infra.voipcalling.CallLogInfo;
import com.whatsapp.calling.infra.voipcalling.CallState;
import com.whatsapp.calling.service.VoiceServiceEventCallback;
import com.whatsapp.fieldstats.events.WamCall;
import com.whatsapp.infra.core.jid.UserJid;
import de.robv.android.xposed.XposedBridge;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Controlled events exercise the real installed production callbacks and recorder methods. */
public class CallRecordingLifecycleChecks {
    private static int checks;
    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }

    private static final class Preferences implements SharedPreferences {
        final Map<String, Object> values = new ConcurrentHashMap<>();
        final Set<OnSharedPreferenceChangeListener> listeners = ConcurrentHashMap.newKeySet();
        boolean failRegistration;
        Preferences() { values.put("call_recording_enabled", true); }
        public boolean getBoolean(String key, boolean fallback) {
            Object value = values.get(key); return value == null ? fallback : (Boolean)value;
        }
        public String getString(String key, String fallback) {
            Object value = values.get(key); return value == null ? fallback : (String)value;
        }
        public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener value) {
            listeners.add(value);
            if(failRegistration) throw new IllegalStateException("private listener failure detail");
        }
        public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener value) { listeners.remove(value); }
        void enabled(boolean value) {
            values.put("call_recording_enabled", value);
            for (var listener : listeners) listener.onSharedPreferenceChanged(this, "call_recording_enabled");
        }
        void clear() { values.clear(); for (var listener : listeners) listener.onSharedPreferenceChanged(this, null); }
    }

    /** Virtual delays and serialized tasks; forced execution represents an already-entered cancelled task. */
    private static final class Scheduler extends ScheduledThreadPoolExecutor {
        long time, sequence;
        final PriorityQueue<Ticket> tasks = new PriorityQueue<>();
        Scheduler() { super(1); setRemoveOnCancelPolicy(true); }
        final class Ticket extends FutureTask<Object> implements RunnableScheduledFuture<Object> {
            final Runnable original;
            final long due, order;
            Ticket(Runnable work, long due) { super(work, null); original = work; this.due = due; order = sequence++; }
            public long getDelay(TimeUnit unit) { return unit.convert(due-time, TimeUnit.NANOSECONDS); }
            public int compareTo(Delayed other) {
                Ticket ticket=(Ticket)other;int value=Long.compare(due,ticket.due);
                return value == 0 ? Long.compare(order,ticket.order) : value;
            }
            public boolean isPeriodic() { return false; }
            public boolean cancel(boolean interrupt) { boolean result=super.cancel(interrupt);tasks.remove(this);return result; }
            void forceEnteredTask() { original.run(); }
        }
        public ScheduledFuture<?> schedule(Runnable work, long delay, TimeUnit unit) {
            Ticket ticket=new Ticket(work,time+unit.toNanos(delay));tasks.add(ticket);return ticket;
        }
        public void execute(Runnable work) { schedule(work,0,TimeUnit.NANOSECONDS); }
        Ticket next() { Ticket ticket=tasks.remove();time=Math.max(time,ticket.due);return ticket; }
        void drain() throws Exception {
            int count=0;
            while(!tasks.isEmpty()) { Ticket ticket=next();ticket.run();if(!ticket.isCancelled())ticket.get();
                if(++count>100)throw new AssertionError("Unbounded recorder task chain"); }
        }
    }

    private static final class Fixture implements AutoCloseable {
        final Context context=new Context();
        final Preferences preferences=new Preferences();
        final VoiceServiceEventCallback host=new VoiceServiceEventCallback();
        final CallRecordingHook hook;
        final Scheduler scheduler=new Scheduler();
        Fixture() throws Throwable { this(true); }
        Fixture(boolean install) throws Throwable {
            XposedBridge.reset();MediaRecorder.resetFixtures();
            hook=new CallRecordingHook(context,Fixture.class.getClassLoader(),preferences);
            Field field=CallRecordingHook.class.getDeclaredField("worker");field.setAccessible(true);
            ScheduledExecutorService old=(ScheduledExecutorService)field.get(hook);field.set(hook,scheduler);old.shutdownNow();
            if(install) hook.hook();
        }
        boolean recording() throws Exception {
            Field field=CallRecordingHook.class.getDeclaredField("recording");field.setAccessible(true);
            return ((AtomicBoolean)field.get(hook)).get();
        }
        void state(CallState old,CallState current,String id) throws Throwable {
            host.current=new CallInfo(id,current);stateOnly(host,old,host.current);
        }
        void stateOnly(VoiceServiceEventCallback owner,CallState old,CallInfo info) throws Throwable {
            XposedBridge.fire(owner,"callStateChanged",new Class<?>[]{CallState.class,CallInfo.class},old,info);
        }
        void sound() throws Throwable { XposedBridge.fire(host,"soundPortCreated",new Class<?>[]{int.class},1); }
        void active(String id) throws Throwable { state(CallState.CALLING,CallState.ACTIVE,id);sound();scheduler.drain();check(recording(),"Fixture did not start active call"); }
        void stats(boolean last,boolean realtime) throws Throwable {
            XposedBridge.fire(host,"fieldstatsReady",new Class<?>[]{WamCall.class,UserJid.class,boolean.class,boolean.class},new WamCall(),new UserJid(),last,realtime);
        }
        void ending() throws Throwable {
            Method event = null;
            for (var entry : XposedBridge.hooks.entrySet()) {
                if (!"callEnding".equals(entry.getKey().getName()) || entry.getValue().isEmpty()) continue;
                if (event != null) throw new AssertionError("Multiple ending events were installed");
                event = entry.getKey();
            }
            if (event == null) throw new AssertionError("No ending event was installed");
            Class<?>[] types = event.getParameterTypes();
            Object[] arguments = new Object[types.length];
            for (int index = 0; index < types.length; index++) {
                if (types[index] == CallLogInfo.class) arguments[index] = new CallLogInfo();
                else if (types[index] == int.class) arguments[index] = 0;
                else if (types[index] == long.class) arguments[index] = 0L;
                else if (types[index] == boolean.class) arguments[index] = true;
                else if (types[index] == String.class) arguments[index] = "private event detail";
                else throw new AssertionError("Ending fixture has an unspecified argument type");
            }
            XposedBridge.fire(host,"callEnding",types,arguments);
        }
        public void close() { scheduler.shutdownNow();MediaRecorder.prepareRelease=null;MediaRecorder.prepareEntered=null; }
    }

    private static void installation() throws Throwable {
        for(int failureAt=1;failureAt<=3;failureAt++) {
            try(Fixture f=new Fixture(false)) {
                XposedBridge.failRegistration=failureAt;
                XposedBridge.afterRegistration=()->{
                    try { f.state(CallState.CALLING,CallState.ACTIVE,"during-install");f.scheduler.drain(); }
                    catch(Throwable e){throw new AssertionError(e);}
                };
                try { f.hook.hook();throw new AssertionError("Installation error was swallowed"); }
                catch(IllegalStateException expected){}
                check(XposedBridge.hooks.values().stream().allMatch(List::isEmpty),"Partial registration left callbacks installed");
                check(f.preferences.listeners.isEmpty(),"Partial registration retained a preference listener");
                check(f.context.resolver.creates==0 && f.scheduler.tasks.isEmpty(),"A partial installation started or scheduled capture");
            }
        }
        try(Fixture f=new Fixture(false)) {
            f.preferences.failRegistration=true;
            try { f.hook.hook();throw new AssertionError("Listener error was swallowed"); }
            catch(IllegalStateException expected){}
            check(XposedBridge.hooks.values().stream().allMatch(List::isEmpty),"Listener failure left callbacks installed");
            check(f.preferences.listeners.isEmpty(),"Listener registered before throwing was not removed");
        }
        try(Fixture f=new Fixture(false)) {
            XposedBridge.failRegistration=2;XposedBridge.failUnhook=true;
            try { f.hook.hook();throw new AssertionError("Installation error was swallowed"); }
            catch(IllegalStateException expected){}
            f.state(CallState.CALLING,CallState.ACTIVE,"failed-attempt");f.scheduler.drain();
            check(f.context.resolver.creates==0,"A callback that could not be removed remained active");
            XposedBridge.failRegistration=0;XposedBridge.failUnhook=false;
            f.hook.hook();f.active("retry");
            check(f.context.resolver.creates==1,"Failed callbacks became active after retry");
            int count=XposedBridge.registrations;f.hook.hook();
            check(XposedBridge.registrations==count && f.preferences.listeners.size()==1,"Successful installation was repeated");
            check(XposedBridge.logs.stream().noneMatch(s->s.contains("private")),"Installation cleanup logged private failure details");
            f.ending();f.scheduler.drain();check(!f.recording(),"Retry failed to retain terminal cleanup");
        }
    }

    private static void intermediate() throws Throwable {
        try(Fixture f=new Fixture()) {
            f.active("call-A");MediaRecorder active=MediaRecorder.instances.get(0);
            f.stats(false,false);f.scheduler.drain();
            check(f.recording(),"An intermediate statistics report stopped the active recording");
            f.stats(false,true);f.scheduler.drain();
            check(f.recording(),"A realtime statistics report stopped the active recording");
            check(active.stops==0 && active.releases==0,"Statistics closed an active audio resource");
            // A final report also lacks a stable call identity; the actual lifetime signals own cleanup.
            f.stats(true,false);f.scheduler.drain();check(f.recording(),"Unidentified final statistics stopped a live session");
        }
    }

    private static void terminal() throws Throwable {
        try(Fixture f=new Fixture()) {
            f.active("call-A");MediaRecorder active=MediaRecorder.instances.get(0);
            f.state(CallState.ACTIVE,CallState.NONE,"call-A");f.scheduler.drain();
            check(!f.recording(),"The real callStateChanged NONE transition did not stop recording");
            check(active.stops==1 && active.releases==1,"Terminal state must stop/release once");
            check(f.context.resolver.files.get(0).closes==1,"Terminal state did not close the descriptor");
            check(f.context.resolver.deletes==0,"A successful recording was deleted");
            f.state(CallState.ACTIVE,CallState.NONE,"call-A");f.scheduler.drain();
            check(active.stops==1 && f.scheduler.tasks.isEmpty(),"Duplicate terminal work");
        }
        try(Fixture f=new Fixture()) {
            f.active("call-B");MediaRecorder active=MediaRecorder.instances.get(0);
            f.ending();f.scheduler.drain();
            check(f.host.current==null,"Ending fixture did not clear current host info");
            check(!f.recording() && active.stops==1,"callEnding lost the pre-return identity snapshot");
        }
    }

    private static void transitions() throws Throwable {
        try(Fixture f=new Fixture()) {
            f.state(CallState.NONE,CallState.RECEIVED_CALL,"ringing");f.sound();f.scheduler.drain();
            check(!f.recording() && f.context.resolver.creates==0,"Audio port creation during ringing started recording");
            f.state(CallState.NONE,CallState.ACTIVE,"A");f.scheduler.drain();
            check(f.recording(),"Used old NONE argument instead of new ACTIVE state");
            MediaRecorder first=MediaRecorder.instances.get(0);
            XposedBridge.fire(new com.whatsapp.voipcalling.VoipActivityV2(),"onDestroy",new Class<?>[]{});
            f.scheduler.drain();check(f.recording(),"Activity destruction was treated as the end of a call");
            f.state(CallState.CALLING,CallState.ACTIVE,"B");f.scheduler.drain();
            check(f.recording() && MediaRecorder.instances.size()==2,"A replacement active call did not get its own recording");
            check(first.stops==1 && first.releases==1,"Previous call not released before replacement");
            MediaRecorder second=MediaRecorder.instances.get(1);
            f.stateOnly(f.host,CallState.ACTIVE,new CallInfo("A",CallState.NONE));f.scheduler.drain();
            check(f.recording() && second.stops==0,"A stale end from A stopped B");
            VoiceServiceEventCallback other=new VoiceServiceEventCallback();other.current=new CallInfo("B",CallState.NONE);
            f.stateOnly(other,CallState.ACTIVE,other.current);f.scheduler.drain();
            check(f.recording() && second.stops==0,"Another callback owner stopped this session");
            f.stateOnly(f.host,CallState.ACTIVE,null);f.scheduler.drain();
            check(f.recording(),"An unidentifiable event changed the recording");
            f.stateOnly(f.host,CallState.ACTIVE,new CallInfo("",CallState.NONE));f.scheduler.drain();
            check(f.recording(),"An empty call ID authorized terminal cleanup");
        }
    }

    private static void failures() throws Throwable {
        try(Fixture f=new Fixture()) {
            f.host.current=new CallInfo("A",CallState.ACTIVE);f.host.failNext=true;
            try{f.sound();throw new AssertionError("Original host error missing");}catch(IllegalStateException expected){}
            f.scheduler.drain();check(f.context.resolver.creates==0,"A throwing audio callback started recording");
            f.active("A");f.host.failNext=true;
            try{f.ending();throw new AssertionError("Original host error missing");}catch(IllegalStateException expected){}
            f.scheduler.drain();check(f.recording(),"A throwing ending callback stopped recording");
            f.host.failNext=true;
            try{f.stateOnly(f.host,CallState.ACTIVE,new CallInfo("A",CallState.NONE));throw new AssertionError("Original host error missing");}
            catch(IllegalStateException expected){}
            f.scheduler.drain();check(f.recording(),"A throwing state callback stopped recording");
        }
        try(Fixture f=new Fixture()) {
            MediaRecorder.rejectedSources.addAll(List.of(1,4,7));
            f.state(CallState.CALLING,CallState.ACTIVE,"A");f.sound();f.scheduler.drain();
            check(!f.recording(),"All failed audio sources left recording marked active");
            check(MediaRecorder.instances.size()==3,"Supported-source fallback order was changed");
            check(MediaRecorder.instances.stream().allMatch(x->x.releases==1),"A failed audio source leaked its recorder");
            check(f.context.resolver.files.get(0).closes==1 && f.context.resolver.deletes==1,"Failed output was not closed/removed");
            check(XposedBridge.logs.stream().noneMatch(s->s.contains("private")),"Audio failure diagnostics expose exception details");
        }
        try(Fixture f=new Fixture()) {
            f.preferences.values.put("call_recording_use_root",true);MediaRecorder.rejectedSources.add(4);
            f.active("A");
            check(MediaRecorder.instances.get(0).source==4 && MediaRecorder.instances.get(1).source==7,
                    "The existing preferred-call-source to microphone fallback changed");
        }
        try(Fixture f=new Fixture()) {
            f.context.resolver.fail=true;f.state(CallState.CALLING,CallState.ACTIVE,"A");f.sound();f.scheduler.drain();
            check(!f.recording() && MediaRecorder.instances.isEmpty(),"Missing storage bridge attempted media capture");
        }
    }

    private static void cancellation() throws Throwable {
        try(Fixture f=new Fixture()) {
            f.state(CallState.CALLING,CallState.ACTIVE,"A");f.sound();Scheduler.Ticket stale=f.scheduler.tasks.peek();
            f.state(CallState.ACTIVE,CallState.NONE,"A");
            f.state(CallState.CALLING,CallState.ACTIVE,"B");f.sound();
            check(stale.isCancelled(),"Ending call did not cancel delayed work");
            stale.forceEnteredTask();check(MediaRecorder.instances.isEmpty(),"An already-entered stale start recorded the next call");
            f.scheduler.drain();check(f.recording() && f.context.resolver.creates==1,"Replacement call failed after stale cancellation");
        }
        try(Fixture f=new Fixture()) {
            f.state(CallState.CALLING,CallState.ACTIVE,"A");f.sound();
            f.preferences.enabled(false);f.scheduler.drain();check(f.context.resolver.creates==0,"Disabled pending recorder created output");
            f.preferences.enabled(true);f.active("A");MediaRecorder active=MediaRecorder.instances.get(0);
            f.preferences.enabled(false);f.scheduler.drain();
            check(!f.recording() && active.stops==1,"Disabling the preference did not stop the current recorder");
            f.preferences.enabled(true);f.active("B");f.preferences.clear();f.scheduler.drain();
            check(!f.recording(),"Clearing preferences did not stop recording");
        }
    }

    private static void blockedPreparation() throws Throwable {
        try(Fixture f=new Fixture()) {
            f.state(CallState.CALLING,CallState.ACTIVE,"A");f.sound();Scheduler.Ticket start=f.scheduler.next();
            MediaRecorder.prepareEntered=new CountDownLatch(1);MediaRecorder.prepareRelease=new CountDownLatch(1);
            AtomicReference<Throwable> failure=new AtomicReference<>();
            Thread recorderThread=new Thread(()->{try{start.run();start.get();}catch(Throwable t){failure.set(t);}},"controlled-media-worker");
            recorderThread.start();check(MediaRecorder.prepareEntered.await(2,TimeUnit.SECONDS),"No blocked prepare reached");
            CountDownLatch callbackReturned=new CountDownLatch(1);
            Thread callbackThread=new Thread(()->{try{f.ending();}catch(Throwable t){failure.set(t);}finally{callbackReturned.countDown();}},"controlled-host-callback");
            callbackThread.start();boolean responsive=callbackReturned.await(1,TimeUnit.SECONDS);
            MediaRecorder.prepareRelease.countDown();recorderThread.join(3000);callbackThread.join(3000);f.scheduler.drain();
            check(responsive,"Host callback waited for MediaRecorder.prepare");
            check(failure.get()==null,"Concurrent fixture failed: "+failure.get());
            check(MediaRecorder.instances.stream().allMatch(x->x.starts==0),"Recording began after cancellation during prepare");
            check(MediaRecorder.instances.size()==1 && MediaRecorder.instances.get(0).releases==1,"Cancelled prepare retried or leaked a source");
            check(!f.recording() && f.context.resolver.files.get(0).closes==1 && f.context.resolver.deletes==1,"Cancelled prepare output not cleaned up");
        }
    }

    private static void boundedWork() throws Throwable {
        try(Fixture f=new Fixture()) {
            for(int i=0;i<10000;i++)f.state(CallState.CALLING,CallState.ACTIVE,"A");
            check(f.scheduler.tasks.size()==1,"Repeated active events accumulated or postponed work");
            for(int i=0;i<10000;i++)f.sound();
            check(f.scheduler.tasks.size()==1,"Repeated sound-port events accumulated starts");
            f.scheduler.drain();check(f.context.resolver.creates==1,"Repeated events allocated more than one output");
            for(int i=0;i<10000;i++)f.state(CallState.CALLING,CallState.ACTIVE,"A");
            check(f.scheduler.tasks.isEmpty(),"An active recorder queued redundant starts");
            for(int i=0;i<10000;i++)f.stateOnly(f.host,CallState.ACTIVE,new CallInfo("A",CallState.NONE));
            check(f.scheduler.tasks.size()==1,"Terminal callbacks accumulated cleanup work");
            f.scheduler.drain();check(!f.recording(),"Bounded cleanup did not stop recording");
        }
        try(Fixture f=new Fixture()) {
            for(int i=0;i<10000;i++)f.state(CallState.CALLING,CallState.ACTIVE,"C"+i);
            check(f.scheduler.tasks.size()==1,"Successive pending sessions retained cancelled starts");
            f.scheduler.drain();check(f.context.resolver.creates==1,"Superseded pending calls allocated outputs");
        }
        try(Fixture f=new Fixture()) {
            f.active("original");
            for(int i=0;i<10000;i++)f.state(CallState.CALLING,CallState.ACTIVE,"replacement"+i);
            check(f.scheduler.tasks.size()==2,"Replacement calls accumulated more than one stop and one start");
            f.scheduler.drain();check(f.context.resolver.creates==2,"Superseded replacements allocated outputs");
        }
        Context context=new Context();Preferences preferences=new Preferences();
        CallRecordingHook hook=new CallRecordingHook(context,Fixture.class.getClassLoader(),preferences);
        Field field=CallRecordingHook.class.getDeclaredField("worker");field.setAccessible(true);
        Object value=field.get(hook);
        check(value instanceof ScheduledThreadPoolExecutor && ((ScheduledThreadPoolExecutor)value).getRemoveOnCancelPolicy(),
                "Production executor does not remove cancelled delayed tasks");
        ((ScheduledExecutorService)value).shutdownNow();
    }

    public static void main(String[] args) throws Throwable {
        String scenario=args.length==0?"all":args[0];
        switch(scenario) {
            case "rejected-contract":
                try(Fixture ignored=new Fixture()) { throw new AssertionError("Incompatible host contract was accepted"); }
                catch(IllegalStateException expected) {
                    check(expected.getCause() instanceof ReflectiveOperationException,"Unexpected contract rejection");
                    check(XposedBridge.hooks.isEmpty(),"Rejected contract left partial hooks installed");
                }
                break;
            case "intermediate":intermediate();break;
            case "terminal":terminal();break;
            case "transitions":transitions();break;
            case "failures":failures();break;
            case "cancellation":cancellation();break;
            case "blocked-prepare":blockedPreparation();break;
            case "bounds":boundedWork();break;
            case "installation":installation();break;
            case "all":installation();intermediate();terminal();transitions();failures();cancellation();blockedPreparation();boundedWork();break;
            default:throw new IllegalArgumentException(scenario);
        }
        System.out.println("PASS: "+checks+" complete-hook checks; scenario="+scenario);
        System.out.println("Explicit host/media/IPC/Xposed/scheduler doubles; no Android audio, native DexKit, LSPosed, root or device performance claim.");
    }
}
