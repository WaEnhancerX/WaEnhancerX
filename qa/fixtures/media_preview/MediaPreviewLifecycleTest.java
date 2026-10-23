package com.waenhancer.xposed.features.media;

import static org.junit.Assert.*;
import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.media.MediaPlayer;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.VideoView;
import android.widget.ImageButton;
import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import okhttp3.FixtureNetwork;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.LooperMode;
import org.robolectric.annotation.RealObject;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowToast;
import org.robolectric.shadows.ShadowVideoView;

@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk={24,28,34},shadows={MediaPreviewLifecycleTest.CountingVideoShadow.class,MediaPreviewLifecycleTest.PreviewPlayerShadow.class})
@LooperMode(LooperMode.Mode.PAUSED)
public final class MediaPreviewLifecycleTest {
    private Activity activity;
    private ActivityController<Activity> controller;
    private MediaPreviewHook hook;
    private Constructor<?> infoConstructor;
    private Method start;
    private int sequence;

    @Implements(VideoView.class)
    public static class CountingVideoShadow extends ShadowVideoView {
        @RealObject private VideoView view;
        static final Map<VideoView,Integer> reads=new IdentityHashMap<>();
        @Implementation protected boolean isPlaying(){reads.merge(view,1,Integer::sum);return super.isPlaying();}
        static int reads(VideoView view){return reads.getOrDefault(view,0);}
    }

    // Decoder/audio routing is outside this lifecycle probe. A minimal player
    // fixture makes an intentionally late prepared callback deterministic even
    // on SDKs whose Robolectric audio shadow references newer Bluetooth types.
    @Implements(MediaPlayer.class)
    public static class PreviewPlayerShadow {
        @Implementation protected void __constructor__(){}
        @Implementation protected void __constructor__(Context context,int sessionId){}
        @Implementation protected void setLooping(boolean value){}
        @Implementation protected void setVolume(float left,float right){}
        @Implementation protected int getDuration(){return 12000;}
        @Implementation protected void release(){}
    }

    @Before public void setup() throws Exception {
        FixtureNetwork.reset();
        CountingVideoShadow.reads.clear();
        controller=Robolectric.buildActivity(Activity.class).setup();activity=controller.get();
        hook=new MediaPreviewHook(activity,getClass().getClassLoader(),activity.getSharedPreferences("preview_test",Context.MODE_PRIVATE));
        Class<?> info=Class.forName(MediaPreviewHook.class.getName()+"$MediaInfo");
        infoConstructor=info.getDeclaredConstructor(String.class,String.class,String.class,String.class,long.class,File.class);
        infoConstructor.setAccessible(true);
        start=MediaPreviewHook.class.getDeclaredMethod("startPlayer",Long.class,Context.class,boolean.class,View.class,info);
        start.setAccessible(true);
    }

    @After public void cleanup() throws Exception {
        for(FixtureNetwork.Scenario s:FixtureNetwork.scenarios.values()){s.release.countDown();s.releaseCreation.countDown();}
        for(Dialog dialog:new ArrayList<>(ShadowDialog.getShownDialogs())){try{dialog.dismiss();}catch(RuntimeException ignored){}}
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        ThreadPoolExecutor executor=worker();
        executor.shutdownNow();executor.awaitTermination(3,TimeUnit.SECONDS);
        FixtureNetwork.reset();
        controller.pause().stop().destroy();Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    private ThreadPoolExecutor worker() throws Exception {
        Object companion=MediaPreviewHook.class.getDeclaredField("Companion").get(null);
        Method get=companion.getClass().getDeclaredMethod("getPreviewExecutor");get.setAccessible(true);
        Object executor=get.invoke(companion);
        while(!(executor instanceof ThreadPoolExecutor)){
            Class<?> type=executor.getClass();Field delegate=null;
            while(type!=null && delegate==null){try{delegate=type.getDeclaredField("e");}catch(NoSuchFieldException ignored){type=type.getSuperclass();}}
            assertNotNull("Unknown executor delegate",delegate);delegate.setAccessible(true);executor=delegate.get(executor);
        }
        return (ThreadPoolExecutor)executor;
    }

    private void await(BooleanSupplier condition) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);
        boolean ready=condition.getAsBoolean();
        while(!ready && System.nanoTime()<deadline){Thread.sleep(2);ready=condition.getAsBoolean();}
        // Conditions may drain the main looper and schedule the next worker
        // phase. Do not evaluate one again after it has already returned true.
        if(!ready){
            for(Map.Entry<Thread,StackTraceElement[]> entry:Thread.getAllStackTraces().entrySet()){
                if(!entry.getKey().getName().startsWith("WAEX-MediaPreview"))continue;
                System.err.println("WORKER_TIMEOUT "+entry.getKey().getState());
                for(StackTraceElement frame:entry.getValue())System.err.println("  at "+frame);
            }
        }
        assertTrue("Timed out waiting for controlled worker",ready);
    }

    private void idleWorker() throws Exception {
        ThreadPoolExecutor executor=worker();
        FutureTask<Void> barrier=new FutureTask<>(()->null);
        await(()->{
            try{executor.execute(barrier);return true;}
            catch(RejectedExecutionException busy){return false;}
        });
        barrier.get(4,TimeUnit.SECONDS);
    }

    private void settle() throws Exception {
        for(int i=0;i<4;i++){
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            idleWorker();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
        }
    }

    private File local(String suffix) throws Exception {
        File file=new File(activity.getCacheDir(),"host_fixture_"+(sequence++)+suffix);
        Files.write(file.toPath(),FixtureNetwork.PNG);return file;
    }

    private void open(String url,String mime,File local) throws Exception {
        Object info=infoConstructor.newInstance(url,mime,"A".repeat(64),null,0L,local);
        start.invoke(hook,null,activity,true,null,info);
    }

    private Dialog latest(){return ShadowDialog.getLatestDialog();}

    private void openBlocking(String url,FixtureNetwork.Scenario scenario) throws Exception {
        FixtureNetwork.scenarios.put(url,scenario);open(url,"image/png",null);
        await(()->{Shadows.shadowOf(Looper.getMainLooper()).idle();return scenario.entered.getCount()==0;});
    }

    private Set<File> previews(){
        File[] files=activity.getCacheDir().listFiles((directory,name)->name.startsWith("mediapreview_"));
        return new HashSet<>(Arrays.asList(files==null?new File[0]:files));
    }

    private static VideoView video(View view){
        if(view instanceof VideoView)return (VideoView)view;
        if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++){VideoView found=video(group.getChildAt(i));if(found!=null)return found;}}
        return null;
    }

    private static ImageButton closeButton(View view){
        if(view instanceof ImageButton)return (ImageButton)view;
        if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++){ImageButton found=closeButton(group.getChildAt(i));if(found!=null)return found;}}
        return null;
    }

    @Test public void rapidRequestsKeepAtMostOneQueuedTask() throws Exception {
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        worker().execute(()->{entered.countDown();try{release.await(4,TimeUnit.SECONDS);}catch(InterruptedException ignored){}});
        assertTrue(entered.await(3,TimeUnit.SECONDS));
        boolean bounded=false;
        try{
            for(int i=0;i<100;i++){
                String url="https://example.invalid/rapid-"+i;
                FixtureNetwork.scenarios.put(url,new FixtureNetwork.Scenario(false,false));open(url,"image/png",null);
            }
            bounded=worker().getQueue().size()<=1;
            assertTrue("100 rapid opens must retain at most one pending preview, not "+worker().getQueue().size(),worker().getQueue().size()<=1);
        }finally{if(!bounded)worker().getQueue().clear();release.countDown();}
        settle();
        assertEquals("Only the newest queued request should download",1,FixtureNetwork.calls.size());
        assertTrue(FixtureNetwork.calls.get(0).request.url.endsWith("rapid-99"));
        assertTrue(latest().isShowing());
    }

    @Test public void rapidRequestsOnlyShowTheLatestDialog() throws Exception {
        open(null,"image/png",local(".png"));open(null,"image/png",local(".png"));settle();
        long showing=ShadowDialog.getShownDialogs().stream().filter(Dialog::isShowing).count();
        assertEquals("Superseded preparation must not display another dialog",1L,showing);
    }

    @Test public void dismissCancelsTheActiveCallAndRemovesOwnedFile() throws Exception {
        FixtureNetwork.Scenario scenario=new FixtureNetwork.Scenario(true,false);
        openBlocking("https://example.invalid/cancel",scenario);
        Dialog dialog=latest();assertTrue(dialog.isShowing());
        dialog.dismiss();Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertTrue("Dismiss must cancel the active HTTP Call",FixtureNetwork.calls.get(0).cancelled);
        idleWorker();Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertTrue("Cancelled download must not leave an owned temp file",previews().isEmpty());
        assertFalse(dialog.isShowing());
    }

    @Test public void cancellationDuringCallCreationCancelsBeforeExecute() throws Exception {
        FixtureNetwork.Scenario scenario=new FixtureNetwork.Scenario(false,false);scenario.blockCreation=true;
        FixtureNetwork.scenarios.put("https://example.invalid/create-race",scenario);
        open("https://example.invalid/create-race","image/png",null);
        await(()->{Shadows.shadowOf(Looper.getMainLooper()).idle();return scenario.created.getCount()==0;});
        latest().dismiss();Shadows.shadowOf(Looper.getMainLooper()).idle();
        scenario.releaseCreation.countDown();settle();
        assertTrue("A call created concurrently with dismiss must be cancelled",FixtureNetwork.calls.get(0).cancelled);
        assertEquals("Cancelled call must not execute",1L,scenario.entered.getCount());
        assertTrue(previews().isEmpty());
    }

    @Test public void detachedWindowCancelsTheDownload() throws Exception {
        FixtureNetwork.Scenario scenario=new FixtureNetwork.Scenario(true,false);
        openBlocking("https://example.invalid/detach",scenario);
        Dialog dialog=latest();View decor=dialog.getWindow().getDecorView();
        assertTrue("The test dialog must have an attached Android window",decor.isAttachedToWindow());
        dialog.getWindow().getWindowManager().removeViewImmediate(decor);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertTrue("Window teardown must cancel its owned HTTP call",FixtureNetwork.calls.get(0).cancelled);
        settle();assertTrue(previews().isEmpty());
    }

    @Test public void closedPreviewDoesNotAttachAnAlreadyQueuedVideoResult() throws Exception {
        Dialog before=latest();
        open(null,"video/mp4",local(".mp4"));idleWorker();
        for(int i=0;latest()==before && i<30;i++)Shadows.shadowOf(Looper.getMainLooper()).runOneTask();
        assertNotSame("Dialog creation task must have executed",before,latest());idleWorker();
        Dialog dialog=latest();View decor=dialog.getWindow().getDecorView();
        assertNull("The local result must still be queued",video(decor));
        ImageButton close=closeButton(decor);assertNotNull(close);close.performClick();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertFalse(dialog.isShowing());
        assertNull("Queued work must not attach a video after closing",video(decor));
    }

    @Test public void delayedFailureFromCancelledSessionDoesNotToastOrCloseReplacement() throws Exception {
        FixtureNetwork.Scenario scenario=new FixtureNetwork.Scenario(true,true);scenario.ignoreCancellation=true;
        openBlocking("https://example.invalid/late",scenario);
        Dialog previous=latest();previous.dismiss();Shadows.shadowOf(Looper.getMainLooper()).idle();
        open(null,"image/png",local(".png"));
        scenario.release.countDown();settle();
        assertTrue("Replacement should remain visible",latest().isShowing());
        assertNull("Cancelled work must not emit a delayed error toast",ShadowToast.getTextOfLatestToast());
    }

    @Test public void supersededDownloadErrorCannotDismissAnAlreadyPreparedReplacement() throws Exception {
        FixtureNetwork.scenarios.put("https://example.invalid/old-failure",new FixtureNetwork.Scenario(false,true));
        FixtureNetwork.scenarios.put("https://example.invalid/new-success",new FixtureNetwork.Scenario(false,false));
        // Complete both metadata tasks before allowing their UI callbacks. On
        // the baseline both dialogs exist before the first download fails.
        open("https://example.invalid/old-failure","image/png",null);idleWorker();
        open("https://example.invalid/new-success","image/png",null);idleWorker();
        settle();
        assertTrue("An old download error must not dismiss the replacement dialog",latest().isShowing());
        assertNull("A superseded download must not show an error toast",ShadowToast.getTextOfLatestToast());
    }

    @Test public void oldDialogCannotDeleteTheReplacementDownload() throws Exception {
        FixtureNetwork.scenarios.put("https://example.invalid/one",new FixtureNetwork.Scenario(false,false));
        open("https://example.invalid/one","image/png",null);settle();Dialog previous=latest();
        Set<File> before=previews();
        FixtureNetwork.scenarios.put("https://example.invalid/two",new FixtureNetwork.Scenario(false,false));
        open("https://example.invalid/two","image/png",null);settle();Dialog replacement=latest();
        Set<File> created=previews();created.removeAll(before);
        assertEquals("Replacement needs its own temporary file",1,created.size());File owned=created.iterator().next();
        previous.dismiss();Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertTrue("Dismissing the old dialog must preserve the replacement file",owned.isFile());
        assertTrue(replacement.isShowing());
    }

    @Test public void localHostFilesAreNeverDeletedOnDismiss() throws Exception {
        File file=local(".png");open(null,"image/png",file);settle();
        latest().dismiss();Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertTrue("Original host-owned media must survive preview cleanup",file.isFile());
    }

    @Test public void replacementStopsTheOldVideoUpdater() throws Exception {
        open(null,"video/mp4",local(".mp4"));settle();
        Dialog oldDialog=latest();VideoView previous=video(oldDialog.getWindow().getDecorView());assertNotNull(previous);
        open(null,"video/mp4",local(".mp4"));settle();
        assertFalse("Replacement must close the previous dialog",oldDialog.isShowing());
        int before=CountingVideoShadow.reads(previous);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3));
        assertEquals("Old updater must not continue because another dialog is showing",before,CountingVideoShadow.reads(previous));
        assertEquals("Old VideoView playback must be stopped",ShadowVideoView.STOP,Shadows.shadowOf(previous).getCurrentVideoState());
    }

    @Test public void latePreparedCallbackCannotRestartDismissedVideo() throws Exception {
        open(null,"video/mp4",local(".mp4"));settle();
        Dialog dialog=latest();VideoView view=video(dialog.getWindow().getDecorView());assertNotNull(view);
        MediaPlayer.OnPreparedListener callback=Shadows.shadowOf(view).getOnPreparedListener();assertNotNull(callback);
        dialog.dismiss();Shadows.shadowOf(Looper.getMainLooper()).idle();
        MediaPlayer player=new MediaPlayer();
        try{callback.onPrepared(player);assertEquals("A late prepare callback must not restart cancelled playback",ShadowVideoView.STOP,Shadows.shadowOf(view).getCurrentVideoState());}
        finally{player.release();}
    }
}
