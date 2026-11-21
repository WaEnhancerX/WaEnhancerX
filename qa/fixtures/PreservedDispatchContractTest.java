package com.waenhancer.audit;

import java.util.Arrays;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.os.Bundle;
import com.waenhancer.xposed.bridge.HookProvider;
import com.waenhancer.xposed.core.db.PreservedMessageStore;
import com.waenhancer.xposed.core.devkit.DexCacheManager;
import com.waenhancer.xposed.core.devkit.DexSearchEngine;
import com.waenhancer.xposed.features.conversation.PreserveDeleteForMeHook;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import org.robolectric.shadows.ShadowSystemClock;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowBinder;
import org.robolectric.shadows.ShadowContentResolver;
import org.robolectric.shadows.ShadowLog;
import static org.junit.Assert.*;

/** Complete production sender/provider/store contracts with explicit host and service boundaries. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {24, 28, 34}, manifest = Config.NONE)
public class PreservedDispatchContractTest {
    private static final String DATABASE = "waex_preserved_messages.db";
    private static final String MSGSTORE = "msgstore.db";
    private static final String ID = "audit-selected-key";
    private static final String TEXT = "synthetic local-delete text";
    private static final String GROUP = "12345@g.us";
    private static final String PRIVATE = "SYNTHETIC-PRIVATE-PAYLOAD-6719";
    private static final String WARNING = "Message preservation was not confirmed by the manager";
    private static final Uri URI = Uri.parse("content://" + HookProvider.AUTHORITY);
    private Context context;
    private PreserveDeleteForMeHook hook;
    private PreservedMessageStore store;

    @Before public void before() throws Exception {
        context = RuntimeEnvironment.getApplication();
        resetStore(); resetHookDiagnostics();
        for (Class<?> type : new Class<?>[]{DexCacheManager.class, DexSearchEngine.class}) {
            Field field = type.getDeclaredField("sInstance"); field.setAccessible(true); field.set(null, null);
        }
        context.deleteDatabase(DATABASE); context.deleteDatabase(MSGSTORE);
        context.getSharedPreferences("waex_dex_cache", Context.MODE_PRIVATE).edit().clear().commit();
        SharedPreferences prefs = context.getSharedPreferences("capture-observation", Context.MODE_PRIVATE);
        prefs.edit().clear().putBoolean("preserve_delete_for_me", true).commit();
        hook = new PreserveDeleteForMeHook(context, getClass().getClassLoader(), prefs);
        XposedBridge.reset(); ShadowContentResolver.reset(); ShadowLog.clear();
        ShadowBinder.setCallingUid(context.getApplicationInfo().uid);
        createMessage();
    }

    @After public void after() throws Exception {
        // The producer is nonblocking. Drain its FIFO worker before deleting fixture
        // databases to avoid mixing writes from different Robolectric tests.
        awaitPreservationQueue(); resetStore(); resetHookDiagnostics();
        context.deleteDatabase(DATABASE); context.deleteDatabase(MSGSTORE);
        ShadowBinder.reset(); ShadowContentResolver.reset(); XposedBridge.reset();
    }

    private void resetHookDiagnostics() throws Exception {
        Field scope = PreserveDeleteForMeHook.class.getDeclaredField("deleteForMeScope");
        scope.setAccessible(true); ((ThreadLocal<?>) scope.get(null)).remove();
        try {
            Field warning = PreserveDeleteForMeHook.class.getDeclaredField("lastPreservationWarningAtElapsed");
            warning.setAccessible(true); warning.setLong(null, -1L);
        } catch (NoSuchFieldException earlierMain) { /* Baseline has no bounded warning. */ }
    }

    private void resetStore() throws Exception {
        Field field = PreservedMessageStore.class.getDeclaredField("instance"); field.setAccessible(true);
        PreservedMessageStore previous = (PreservedMessageStore) field.get(null);
        if (previous != null) previous.close();
        field.set(null, null); store = null;
    }

    private PreservedMessageStore store() {
        if (store == null) store = PreservedMessageStore.getInstance(context);
        return store;
    }

    private void registerProvider() { registerProvider(HookProvider.class); }

    private void registerProvider(Class<? extends HookProvider> type) {
        HookProvider provider = Robolectric.buildContentProvider(type).create().get();
        assertNotNull(provider.getContext());
        ShadowContentResolver.registerProviderInternal(HookProvider.AUTHORITY, provider);
    }

    private static Bundle message(String id) {
        Bundle extras = new Bundle();
        extras.putString("jid", GROUP); extras.putString("name", "Synthetic group");
        extras.putString("senderName", "Synthetic sender"); extras.putString("msgId", id);
        extras.putString("text", TEXT); extras.putLong("timestamp", 10000L);
        extras.putBoolean("fromMe", false); extras.putBoolean("isGroup", true);
        return extras;
    }

    private Bundle endpoint(String id) {
        return context.getContentResolver().call(URI, "record_preserved_message", null, message(id));
    }

    private void abortInsert() {
        store().getWritableDatabase().execSQL("CREATE TRIGGER audit_abort_insert BEFORE INSERT ON preserved_messages " +
                "BEGIN SELECT RAISE(ABORT, 'audit-only forced write failure'); END");
    }

    private int vaultRows() { return store().getAllPreservedMessages().size(); }

    private int sourceRows() {
        try (SQLiteDatabase db = context.openOrCreateDatabase(MSGSTORE, Context.MODE_PRIVATE, null);
             Cursor cursor = db.rawQuery("SELECT COUNT(*) FROM message", null)) {
            assertTrue(cursor.moveToFirst()); return cursor.getInt(0);
        }
    }

    private void createMessage() {
        try (SQLiteDatabase db = context.openOrCreateDatabase(MSGSTORE, Context.MODE_PRIVATE, null)) {
            db.execSQL("CREATE TABLE jid(_id INTEGER PRIMARY KEY,raw_string TEXT,user TEXT)");
            db.execSQL("CREATE TABLE chat(_id INTEGER PRIMARY KEY,jid_row_id INTEGER,subject TEXT)");
            db.execSQL("CREATE TABLE message(_id INTEGER PRIMARY KEY,key_id TEXT,chat_row_id INTEGER," +
                    "from_me INTEGER,message_type INTEGER,text_data TEXT,timestamp INTEGER,sender_jid_row_id INTEGER)");
            db.execSQL("INSERT INTO jid VALUES(1,?,'12345')", new Object[]{GROUP});
            db.execSQL("INSERT INTO chat VALUES(1,1,'Synthetic group')");
            db.execSQL("INSERT INTO message VALUES(1,?,1,0,0,?,10000,0)", new Object[]{ID,TEXT});
        }
    }

    public static final class FixtureJid {
        @Override public String toString() { return GROUP; }
    }
    public static final class FixtureKey {
        public String id = ID;
        public boolean fromMe;
        public FixtureJid jid = new FixtureJid();
    }
    public static final class FixtureMessage {
        public FixtureKey key = new FixtureKey();
        public FixtureKey Atl() { return key; }
    }
    public static final class FixtureRunnable implements Runnable {
        public Set<FixtureMessage> selected = new LinkedHashSet<>();
        private final Context context;
        public boolean originalExecuted;
        public FixtureRunnable(Context context) { this.context = context; selected.add(new FixtureMessage()); }
        @Override public void run() {
            originalExecuted = true;
            try (SQLiteDatabase db = context.openOrCreateDatabase(MSGSTORE, Context.MODE_PRIVATE, null)) {
                assertEquals(1, db.delete("message", "key_id = ?", new String[]{ID}));
            }
        }
    }

    private boolean capture() throws Exception {
        Method method = PreserveDeleteForMeHook.class.getDeclaredMethod("captureRunnableMessages", Object.class);
        method.setAccessible(true);
        return (Boolean) method.invoke(hook, new FixtureRunnable(context));
    }

    private void denyCaller() {
        int outside = context.getApplicationInfo().uid + 34001;
        ShadowBinder.setCallingUid(outside);
        Shadows.shadowOf(context.getPackageManager()).setPackagesForUid(outside, "outside.capture.audit");
    }


    private int warningCount() { return (int) XposedBridge.logs.stream().filter(line -> line.contains(WARNING)).count(); }

    private void assertAck(Bundle response, boolean expected) {
        assertNotNull("The receiver must return an explicit acknowledgement", response);
        assertTrue("The receiver must distinguish known success from a legacy empty response", response.containsKey("success"));
        assertEquals(expected, response.getBoolean("success", !expected));
    }

    private void awaitPreservationQueue() throws Exception {
        Field field=PreserveDeleteForMeHook.class.getDeclaredField("preservationExecutor");
        field.setAccessible(true);
        java.util.concurrent.ExecutorService worker=(java.util.concurrent.ExecutorService)field.get(null);
        // A no-op submitted after a capture is a FIFO completion barrier, not a
        // production main-thread wait or permission to treat enqueue as persistence.
        worker.submit(() -> {}).get(15,java.util.concurrent.TimeUnit.SECONDS);
    }

    private boolean captureAndAwait() throws Exception {
        boolean recognized=capture();
        awaitPreservationQueue();
        return recognized;
    }

    private Object dispatch() throws Exception {
        Method method = PreserveDeleteForMeHook.class.getDeclaredMethod("dispatchToManagerApp",
                String.class,String.class,String.class,String.class,String.class,long.class,boolean.class,boolean.class);
        method.setAccessible(true);
        return method.invoke(hook, GROUP, "Synthetic group", "Synthetic sender", ID, TEXT,10000L,false,true);
    }

    private Method resultMethod() {
        Method method = Arrays.stream(PreservedMessageStore.class.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals("tryInsertPreservedMessage")).findFirst().orElse(null);
        assertNotNull("A Boolean result API must coexist with the legacy void method", method);
        assertEquals(boolean.class, method.getReturnType());
        return method;
    }

    private boolean insertWithResult() throws Exception {
        return (Boolean) resultMethod().invoke(store(), GROUP, "Synthetic group", "Synthetic sender", ID, TEXT,10000L,false,true);
    }

    private boolean capture(FixtureRunnable owner) throws Exception {
        Method method = PreserveDeleteForMeHook.class.getDeclaredMethod("captureRunnableMessages", Object.class);
        method.setAccessible(true); return (Boolean) method.invoke(hook, owner);
    }

    private boolean captureAndAwait(FixtureRunnable owner) throws Exception {
        boolean recognized=capture(owner);
        awaitPreservationQueue();
        return recognized;
    }

    private XC_MethodHook.MethodHookParam beginRun(FixtureRunnable owner) throws Throwable {
        Method run = FixtureRunnable.class.getDeclaredMethod("run");
        DexCacheManager.getInstance(context).getMethod(getClass().getClassLoader(), "waex_delete_for_me_method_v7", () -> run);
        hook.hook(); return XposedBridge.fireBefore(run, owner, new Object[0]);
    }

    public static final class LegacyResponseProvider extends HookProvider {
        @Override public Bundle call(String method,String arg,Bundle extras) {
            super.call(method,arg,extras); return Bundle.EMPTY;
        }
    }

    public static final class PayloadFailureProvider extends HookProvider {
        @Override public Bundle call(String method,String arg,Bundle extras) {
            super.call(method,arg,extras); throw new IllegalStateException(PRIVATE);
        }
    }

    public static final class DirectDelete {
        public void deleteSelected(int token) { }
    }

    private XC_MethodHook.MethodHookParam beginDirectDelete() throws Throwable {
        Method method = DirectDelete.class.getDeclaredMethod("deleteSelected",int.class);
        DexCacheManager.getInstance(context).getMethod(getClass().getClassLoader(), "waex_delete_for_me_method_v7", () -> method);
        hook.hook(); return XposedBridge.fireBefore(method,new DirectDelete(),new Object[]{1});
    }

    @Test public void endpointAcknowledgesAnInsertThatSurvivesReopening() throws Exception {
        registerProvider(); assertAck(endpoint(ID), true); assertEquals(1,vaultRows()); resetStore();
        List<PreservedMessageStore.StoredMessage> rows=store().getAllPreservedMessages();
        assertEquals(1,rows.size()); assertEquals(TEXT,rows.get(0).getText()); assertEquals(ID,rows.get(0).getId());
        assertEquals(GROUP,rows.get(0).getJid()); assertEquals(10000L,rows.get(0).getTimestamp()); assertEquals(1,sourceRows());
    }

    @Test public void endpointExplicitlyRejectsABlankIdWithoutInserting() {
        registerProvider(); assertAck(endpoint(""),false); assertEquals(0,vaultRows()); assertEquals(1,sourceRows());
    }

    @Test public void endpointExplicitlyRejectsAnAbortedSQLiteInsert() {
        registerProvider(); abortInsert(); assertAck(endpoint(ID),false); assertEquals(0,vaultRows());
        assertTrue(ShadowLog.getLogsForTag("WAEX:PreservedStore").stream().anyMatch(e->e.msg.contains("insertPreservedMessage failed")));
        assertEquals(1,sourceRows());
    }

    @Test public void optionalNameRefreshFailureDoesNotDenyAnAlreadyInsertedCopy() {
        registerProvider(); store().getWritableDatabase().execSQL("CREATE TRIGGER audit_abort_update BEFORE UPDATE ON preserved_messages BEGIN SELECT RAISE(ABORT,'audit-only metadata failure'); END");
        assertAck(endpoint(ID),true); assertEquals(1,vaultRows()); assertEquals(TEXT,store().getAllPreservedMessages().get(0).getText());
        assertTrue(ShadowLog.getLogsForTag("WAEX:PreservedStore").stream().anyMatch(e->e.msg.contains("contact name refresh failed")));
    }

    @Test public void legacyPublicInsertRetainsItsVoidDescriptorAndStillWrites() throws Exception {
        Method legacy=PreservedMessageStore.class.getDeclaredMethod("insertPreservedMessage",String.class,String.class,String.class,String.class,String.class,long.class,boolean.class,boolean.class);
        assertEquals(void.class,legacy.getReturnType());
        assertNull(legacy.invoke(store(),GROUP,"Synthetic group","Synthetic sender",ID,TEXT,10000L,false,true));
        assertEquals(1,vaultRows());
    }

    @Test public void resultApiConfirmsSuccessfulInsert() throws Exception {
        assertTrue(insertWithResult()); assertEquals(1,vaultRows());
    }

    @Test public void resultApiRejectsFailedInsert() throws Exception {
        abortInsert(); assertFalse(insertWithResult()); assertEquals(0,vaultRows());
    }

    @Test public void senderPropagatesPositiveAcknowledgement() throws Exception {
        registerProvider(); assertEquals(Boolean.TRUE,dispatch());
        awaitPreservationQueue(); assertEquals(1,vaultRows()); assertEquals(0,warningCount());
    }

    @Test public void senderQueuesButReportsNegativePersistenceAcknowledgement() throws Exception {
        registerProvider(); abortInsert();
        assertEquals(Boolean.TRUE,dispatch()); // This only confirms enqueueing.
        awaitPreservationQueue();
        assertEquals(0,vaultRows()); assertEquals(1,warningCount());
    }

    @Test public void senderTreatsLegacyEmptyResponseAsUnconfirmedEvenWhenCopyExists() throws Exception {
        registerProvider(LegacyResponseProvider.class);
        assertEquals(Boolean.TRUE,dispatch()); // The queue accepted the request, not the insert.
        awaitPreservationQueue();
        assertEquals(1,vaultRows()); assertEquals(1,warningCount());
    }

    @Test public void successfulCapturePreservesDataWithoutFailureWarning() throws Exception {
        registerProvider(); assertTrue(captureAndAwait()); assertEquals(1,vaultRows()); assertEquals(0,warningCount());
        resetStore(); assertEquals(TEXT,store().getAllPreservedMessages().get(0).getText()); assertEquals(1,sourceRows());
    }

    @Test public void failedCaptureRemainsRecognizedAndWarnsWithoutClaimingSuccess() throws Exception {
        registerProvider(); abortInsert(); assertTrue(captureAndAwait()); assertEquals(0,vaultRows());
        assertEquals(1,warningCount()); assertEquals(1,sourceRows());
    }

    @Test public void missingProviderRemainsRecognizedAndProducesAnUnconfirmedWarning() throws Exception {
        assertNull(ShadowContentResolver.getProvider(URI)); assertTrue(captureAndAwait()); assertEquals(0,vaultRows());
        assertEquals(1,warningCount()); assertEquals(1,sourceRows());
    }

    @Test public void untrustedUidIsStillRejectedByTheCompleteProvider() {
        registerProvider(); denyCaller();
        try { endpoint(ID); fail("Caller boundary was not enforced"); }
        catch(SecurityException expected){assertTrue(expected.getMessage().contains("configured hook scope"));}
        assertEquals(0,vaultRows()); assertEquals(1,sourceRows());
    }

    @Test public void rejectedCallerCaptureRemainsRecognizedAndWarns() throws Exception {
        registerProvider(); denyCaller(); assertTrue(captureAndAwait()); assertEquals(0,vaultRows());
        assertEquals(1,warningCount()); assertEquals(1,sourceRows());
    }

    @Test public void legacyProviderCaptureWarnsAboutAcknowledgementWithoutClaimingDataLoss() throws Exception {
        registerProvider(LegacyResponseProvider.class); assertTrue(captureAndAwait()); assertEquals(1,vaultRows());
        assertEquals(1,warningCount()); assertTrue(XposedBridge.logs.stream().anyMatch(line->line.equals("[WAEX:PreserveDeleteForMe] "+WARNING)));
    }

    @Test public void warningsAreBoundedAcrossRepeatedCallsAndHookInstances() throws Exception {
        registerProvider(); abortInsert();
        for(int i=0;i<4;i++) assertTrue(captureAndAwait());
        hook=new PreserveDeleteForMeHook(context,getClass().getClassLoader(),context.getSharedPreferences("capture-observation",Context.MODE_PRIVATE));
        assertTrue(captureAndAwait()); assertEquals(1,warningCount()); assertEquals(0,vaultRows());
    }

    @Test public void warningCanRecurAfterThirtyElapsedSeconds() throws Exception {
        registerProvider(); abortInsert(); assertTrue(captureAndAwait()); assertEquals(1,warningCount());
        ShadowSystemClock.advanceBy(Duration.ofSeconds(29)); assertTrue(captureAndAwait()); assertEquals(1,warningCount());
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1)); assertTrue(captureAndAwait()); assertEquals(2,warningCount());
    }

    @Test public void concurrentCapturesCannotUseAStaleTimestampToBypassTheWarningLimit() throws Exception {
        registerProvider(); abortInsert();
        Field field=PreserveDeleteForMeHook.class.getDeclaredField("preservationWarningLock");
        field.setAccessible(true);Object monitor=field.get(null);
        synchronized(monitor) {
            // Hold the warning lock while the asynchronous worker reaches it.
            // Enqueueing must NOT wait for the provider or the warning monitor.
            assertTrue(capture());
            long deadline=System.nanoTime()+5_000_000_000L;
            boolean blocked=false;
            while(System.nanoTime()<deadline && !blocked) {
                for(Thread worker:Thread.getAllStackTraces().keySet()) {
                    if(!worker.getName().equals("WAEX-PreserveDeleteForMe") ||
                       worker.getState()!=Thread.State.BLOCKED)continue;
                    blocked=Arrays.stream(worker.getStackTrace()).anyMatch(frame ->
                        frame.getClassName().equals(PreserveDeleteForMeHook.class.getName())
                        && frame.getMethodName().equals("warnPreservationUnconfirmed"));
                    if(blocked)break;
                }
                if(!blocked)Thread.sleep(1);
            }
            assertTrue("Async worker must block on the actual warning monitor",blocked);
            // A stale timestamp read BEFORE acquiring the monitor would now be
            // 31 seconds earlier than the next worker's timestamp and log twice.
            ShadowSystemClock.advanceBy(Duration.ofSeconds(31));
            assertTrue(capture());
        }
        awaitPreservationQueue();
        assertEquals("Both failed saves within one lock interval warn once",1,warningCount());
        assertEquals(0,vaultRows());assertEquals(1,sourceRows());
    }

    @Test public void transportFailurePayloadNeverAppearsInSenderDiagnostics() throws Exception {
        registerProvider(PayloadFailureProvider.class); assertTrue(captureAndAwait()); assertEquals(1,vaultRows()); assertEquals(1,warningCount());
        for(String line:XposedBridge.logs){assertFalse(line.contains(PRIVATE));assertFalse(line.contains(TEXT));assertFalse(line.contains(ID));assertFalse(line.contains(GROUP));}
    }

    @Test public void aMixedBatchKeepsItsConfirmedCopyAndWarnsOnce() throws Exception {
        registerProvider(); FixtureRunnable owner=new FixtureRunnable(context); FixtureMessage other=new FixtureMessage();other.key.id="second-audit-key";owner.selected.add(other);
        try(SQLiteDatabase db=context.openOrCreateDatabase(MSGSTORE,Context.MODE_PRIVATE,null)){
            db.execSQL("INSERT INTO message VALUES(2,'second-audit-key',1,0,0,'second synthetic text',10001,0)");
        }
        store().getWritableDatabase().execSQL("CREATE TRIGGER audit_partial_insert BEFORE INSERT ON preserved_messages WHEN NEW.msg_id='second-audit-key' BEGIN SELECT RAISE(ABORT,'audit-only partial failure'); END");
        assertTrue(captureAndAwait(owner));assertEquals(1,vaultRows());assertEquals(ID,store().getAllPreservedMessages().get(0).getId());assertEquals(1,warningCount());assertEquals(2,sourceRows());
    }

    @Test public void emptySelectionIsNotRecognizedAndDoesNotEmitStorageWarning() throws Exception {
        FixtureRunnable owner=new FixtureRunnable(context);owner.selected.clear();assertFalse(capture(owner));assertEquals(0,warningCount());assertEquals(0,vaultRows());
    }

    @Test public void unsupportedMessageTypeIsNotRecognizedAndDoesNotEmitStorageWarning() throws Exception {
        try(SQLiteDatabase db=context.openOrCreateDatabase(MSGSTORE,Context.MODE_PRIVATE,null)){db.execSQL("UPDATE message SET message_type=15");}
        assertFalse(capture());assertEquals(0,warningCount());assertEquals(0,vaultRows());
    }

    @Test public void failedCopyKeepsScopeOriginalDeleteAndAfterCleanupUnchanged() throws Throwable {
        registerProvider();abortInsert();FixtureRunnable owner=new FixtureRunnable(context);XC_MethodHook.MethodHookParam param=beginRun(owner);
        awaitPreservationQueue();
        assertTrue(PreserveDeleteForMeHook.isDeleteForMeInProgress());assertFalse(param.resultWasSet);assertEquals(0,vaultRows());assertEquals(1,warningCount());
        owner.run();XposedBridge.fireAfter(param);
        assertTrue(owner.originalExecuted);assertFalse(PreserveDeleteForMeHook.isDeleteForMeInProgress());assertEquals(0,sourceRows());assertEquals(0,vaultRows());
    }

    @Test public void SQLiteFallbackWarnsWithoutCancellingTheScopedOriginalDelete() throws Throwable {
        registerProvider();abortInsert();XC_MethodHook.MethodHookParam outer=beginDirectDelete();
        assertTrue(PreserveDeleteForMeHook.isDeleteForMeInProgress());
        Method method=SQLiteDatabase.class.getDeclaredMethod("delete",String.class,String.class,String[].class);
        try(SQLiteDatabase db=context.openOrCreateDatabase(MSGSTORE,Context.MODE_PRIVATE,null)){
            XC_MethodHook.MethodHookParam before=XposedBridge.fireBefore(method,db,new Object[]{"message","key_id = ?",new String[]{ID}});
            awaitPreservationQueue();
            assertFalse(before.resultWasSet);assertEquals(1,warningCount());assertEquals(0,vaultRows());assertEquals(1,sourceRows());
            assertEquals(1,db.delete("message","key_id = ?",new String[]{ID}));
        }
        XposedBridge.fireAfter(outer);assertFalse(PreserveDeleteForMeHook.isDeleteForMeInProgress());assertEquals(0,sourceRows());
    }

    @Test public void SQLiteFallbackConfirmsCopyWithoutWarning() throws Throwable {
        registerProvider();XC_MethodHook.MethodHookParam outer=beginDirectDelete();
        Method method=SQLiteDatabase.class.getDeclaredMethod("delete",String.class,String.class,String[].class);
        try(SQLiteDatabase db=context.openOrCreateDatabase(MSGSTORE,Context.MODE_PRIVATE,null)){
            XC_MethodHook.MethodHookParam before=XposedBridge.fireBefore(method,db,new Object[]{"message","key_id = ?",new String[]{ID}});
            awaitPreservationQueue();
            assertFalse(before.resultWasSet);assertEquals(0,warningCount());assertEquals(1,vaultRows());assertEquals(TEXT,store().getAllPreservedMessages().get(0).getText());
        }
        XposedBridge.fireAfter(outer);assertFalse(PreserveDeleteForMeHook.isDeleteForMeInProgress());assertEquals(1,sourceRows());
    }

    @Test public void disabledOptionDoesNotCaptureWarnOrCancelOriginal() throws Throwable {
        registerProvider();context.getSharedPreferences("capture-observation",Context.MODE_PRIVATE).edit().putBoolean("preserve_delete_for_me",false).commit();
        FixtureRunnable owner=new FixtureRunnable(context);XC_MethodHook.MethodHookParam param=beginRun(owner);
        assertFalse(PreserveDeleteForMeHook.isDeleteForMeInProgress());assertFalse(param.resultWasSet);assertEquals(0,vaultRows());assertEquals(0,warningCount());
        owner.run();XposedBridge.fireAfter(param);assertEquals(0,sourceRows());assertFalse(PreserveDeleteForMeHook.isDeleteForMeInProgress());
    }

    @Test public void futureVaultSchemaCannotBeAcknowledgedOrRelabelled() throws Exception {
        registerProvider();store().getWritableDatabase().setVersion(5);resetStore();
        assertAck(endpoint(ID),false);
        try(SQLiteDatabase db=context.openOrCreateDatabase(DATABASE,Context.MODE_PRIVATE,null);Cursor rows=db.rawQuery("SELECT COUNT(*) FROM preserved_messages",null)){
            assertEquals(5,db.getVersion());assertTrue(rows.moveToFirst());assertEquals(0,rows.getInt(0));
        }
    }
    public static class BlockingProvider extends HookProvider {
        static final java.util.concurrent.CountDownLatch entered=new java.util.concurrent.CountDownLatch(1);
        static final java.util.concurrent.CountDownLatch release=new java.util.concurrent.CountDownLatch(1);
        @Override public Bundle call(String method,String arg,Bundle extras) {
            if (!"record_preserved_message".equals(method)) return super.call(method,arg,extras);
            entered.countDown();
            try {if(!release.await(10,java.util.concurrent.TimeUnit.SECONDS))throw new AssertionError("Provider barrier not released");}
            catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}
            return super.call(method,arg,extras);
        }
    }

    @Test public void originalDeleteReturnsWhileManagerPersistenceIsBlocked() throws Throwable {
        registerProvider(BlockingProvider.class);
        FixtureRunnable owner=new FixtureRunnable(context);
        try {
            XC_MethodHook.MethodHookParam param=beginRun(owner);
            assertTrue("Worker must enter provider",BlockingProvider.entered.await(5,java.util.concurrent.TimeUnit.SECONDS));
            assertFalse(owner.originalExecuted);assertFalse(param.resultWasSet);
            owner.run();XposedBridge.fireAfter(param);
            assertTrue(owner.originalExecuted);assertEquals(0,sourceRows());
            assertEquals("Provider still held while original already completed",1L,BlockingProvider.release.getCount());
        } finally {BlockingProvider.release.countDown();awaitPreservationQueue();}
        assertEquals(1,vaultRows());assertEquals(0,warningCount());
    }

    @Test public void throwableLoggerExposesPayloadToThePrivacyOracle() {
        XposedBridge.log(new IllegalStateException(PRIVATE));
        assertTrue("Fixture must not sanitize a leak before asserting",XposedBridge.logs.stream().anyMatch(v->v.contains(PRIVATE)));
    }

    @Test public void afterCallbacksUseInvocationSnapshotAfterSelfUnhook() throws Throwable {
        Method method=FixtureRunnable.class.getDeclaredMethod("run");
        final XC_MethodHook.Unhook[] handle=new XC_MethodHook.Unhook[1];
        final int[] events={0};
        handle[0]=XposedBridge.hookMethod(method,new XC_MethodHook(){
            @Override protected void beforeHookedMethod(MethodHookParam p){events[0]++;handle[0].unhook();}
            @Override protected void afterHookedMethod(MethodHookParam p){events[0]++;}
        });
        XC_MethodHook.MethodHookParam invocation=XposedBridge.fireBefore(method,new FixtureRunnable(context),new Object[0]);
        XposedBridge.fireAfter(invocation);assertEquals(2,events[0]);assertFalse(XposedBridge.hooks.containsKey(method));
    }

    @Test public void afterCallbacksRunInReverseInvocationOrder() throws Throwable {
        Method method=FixtureRunnable.class.getDeclaredMethod("run");StringBuilder order=new StringBuilder();
        for(int i=1;i<=2;i++){final int id=i;XposedBridge.hookMethod(method,new XC_MethodHook(){
            @Override protected void beforeHookedMethod(MethodHookParam p){order.append("b").append(id);}
            @Override protected void afterHookedMethod(MethodHookParam p){order.append("a").append(id);}
        });}
        XposedBridge.fireAfter(XposedBridge.fireBefore(method,new FixtureRunnable(context),new Object[0]));
        assertEquals("b1b2a2a1",order.toString());
    }

    @Test public void originalThrowableSurvivesScopeCleanup() throws Throwable {
        registerProvider();FixtureRunnable owner=new FixtureRunnable(context);XC_MethodHook.MethodHookParam param=beginRun(owner);
        IllegalStateException original=new IllegalStateException("synthetic original error");param.setThrowable(original);
        XposedBridge.fireAfter(param);assertSame(original,param.getThrowable());assertFalse(PreserveDeleteForMeHook.isDeleteForMeInProgress());
    }

}
