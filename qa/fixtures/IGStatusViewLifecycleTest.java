package com.waenhancer.xposed.views;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.database.DataSetObserver;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.ListView;
import org.junit.Test;
import org.junit.Before;
import android.content.res.Configuration;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Collections;
import org.robolectric.android.controller.ActivityController;
import com.waenhancer.xposed.core.devkit.DexCacheManager;
import com.waenhancer.xposed.core.devkit.DexSearchEngine;
import com.whatsapp.conversationslist.ConversationsFragment;
import de.robv.android.xposed.XposedBridge;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Robolectric;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import java.time.Duration;
import com.waenhancer.xposed.features.customization.IGStatusHook;

import static org.junit.Assert.*;

/** Full current production Views and adapter; host data/services are explicit fixtures. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {24, 28, 34}, manifest = Config.NONE, application = Application.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class IGStatusViewLifecycleTest {
    private boolean hookInstalled;

    @Before public void resetHookState() throws Exception {
        registry().clear();
        IGStatusHook.items.clear();
        XposedBridge.reset();
        for (Class<?> type : new Class<?>[] {DexCacheManager.class, DexSearchEngine.class}) {
            Field field = type.getDeclaredField("sInstance");
            field.setAccessible(true); field.set(null, null);
        }
        RuntimeEnvironment.getApplication().getSharedPreferences("waex_dex_cache", Context.MODE_PRIVATE)
                .edit().clear().commit();
    }

    @SuppressWarnings("unchecked") private static List<Object> registry() throws Exception {
        Field field = IGStatusHook.class.getDeclaredField("statusContainers");
        field.setAccessible(true); return (List<Object>) field.get(null);
    }

    private void installHook() throws Throwable {
        if (hookInstalled) return;
        Context context = RuntimeEnvironment.getApplication();
        ClassLoader loader = getClass().getClassLoader();
        android.content.SharedPreferences prefs = context.getSharedPreferences("ig-fixture", Context.MODE_PRIVATE);
        prefs.edit().putBoolean("igstatus", true).commit();
        DexCacheManager.getInstance(context).getClass(loader, "ig_status_list_updates_class", () -> StatusUpdates.class);
        new IGStatusHook(context, loader, prefs).hook();
        IGStatusHook.items.add(null);
        hookInstalled = true;
    }

    public static final class StatusUpdates {
        public final List<?> first, second;
        public StatusUpdates(List<?> first, List<?> second) { this.first = first; this.second = second; }
    }

    private static void publishFixtureStatus() throws Throwable {
        XposedBridge.fire(StatusUpdates.class.getDeclaredConstructor(List.class, List.class), null,
                new Object[] {Collections.emptyList(), Collections.emptyList()}, null, true);
    }

    private class HookScreen {
        final ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup();
        final Activity activity = controller.get();
        final FrameLayout root = new FrameLayout(activity);
        final ConversationsFragment fragment = new ConversationsFragment();
        final ConversationsFragment.FixtureRecyclerView recycler;
        final IGStatusView header;
        HookScreen(boolean nativeRecycler, boolean attach) throws Throwable {
            installHook();
            fragment.setActivity(activity);
            if (nativeRecycler) {
                recycler = new ConversationsFragment.FixtureRecyclerView(activity);
                recycler.setId(android.R.id.list); root.addView(recycler);
            } else {
                recycler = null;
                ListView chats = new ListView(activity); chats.setId(android.R.id.list);
                chats.setAdapter(new Rows(activity)); root.addView(chats);
            }
            Method create = ConversationsFragment.class.getDeclaredMethod("onCreateView",
                    android.view.LayoutInflater.class, ViewGroup.class, android.os.Bundle.class);
            XposedBridge.fire(create, fragment, new Object[] {null, null, null}, root, false);
            assertEquals("The actual hook must register its newly created header", 1, registry().size());
            Object entry = registry().get(0);
            header = (IGStatusView) (entry instanceof WeakReference ? ((WeakReference<?>) entry).get() : entry);
            assertNotNull(header);
            if (attach) { activity.setContentView(root); drain(); }
        }
    }

    private static void drain() {
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100));
    }

    private static void layout(View view) {
        view.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(84, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, 400, 84);
    }

    private static ViewGroup cells(HorizontalListView row) {
        return (ViewGroup) row.getChildAt(0);
    }

    private static class Screen {
        final Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        final FrameLayout parent = new FrameLayout(activity);
        final CountingStatus status = new CountingStatus(activity);
        final Rows rows = new Rows(activity);
        Screen() {
            status.setLayoutParams(new FrameLayout.LayoutParams(400, 84));
            status.setAdapter(rows);
            parent.addView(status);
            activity.setContentView(parent);
            drain(); layout(status); drain();
        }
        void assertLatest(int version) {
            assertEquals(rows.count, cells(status.mStatusListView).getChildCount());
            for (int index = 0; index < rows.count; index++) {
                assertEquals(version, cells(status.mStatusListView).getChildAt(index).getTag());
            }
        }
    }

    @Test public void detachedRefreshBurstsDoNotRetainAQueueOfCallbacks() {
        CountingHorizontal row = new CountingHorizontal(RuntimeEnvironment.getApplication());
        for (int index = 0; index < 10000; index++) row.refreshData();
        assertEquals("unattached headers refresh when they attach", 0, row.posts);
    }

    @Test public void attachedRefreshBurstRebindsTheLatestDataOnlyOnce() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        CountingHorizontal row = new CountingHorizontal(activity);
        Rows rows = new Rows(activity);
        row.setAdapter(rows);
        activity.setContentView(row);
        drain(); layout(row); drain();
        row.posts = 0; rows.binds = 0;
        for (int index = 0; index < 10000; index++) {
            rows.version = index;
            row.refreshData();
        }
        assertEquals("one queued refresh covers the burst", 1, row.posts);
        drain();
        assertEquals(rows.count, rows.binds);
        assertEquals(9999, cells(row).getChildAt(0).getTag());
    }

    @Test public void statusUpdateBurstNotifiesTheAdapterOnlyOnce() {
        Screen screen = new Screen();
        screen.status.posts = 0; screen.rows.notifications = 0;
        for (int index = 0; index < 10000; index++) {
            screen.rows.version = index;
            screen.status.updateList();
        }
        assertEquals(1, screen.status.posts);
        drain();
        assertEquals(1, screen.rows.notifications);
        screen.assertLatest(9999);
    }

    @Test public void aReentrantUpdateDuringNotificationIsNotLost() {
        Screen screen = new Screen();
        screen.rows.notifications = 0;
        screen.rows.onNotify = () -> {
            screen.rows.onNotify = null;
            screen.rows.version = 19;
            screen.status.updateList();
        };
        screen.status.updateList(); drain();
        assertEquals(2, screen.rows.notifications);
        screen.assertLatest(19);
    }

    @Test public void rejectedStatusPostDoesNotDisableFutureUpdates() {
        Screen screen = new Screen();
        screen.status.rejectNext = true;
        screen.status.updateList();
        screen.rows.version = 24;
        screen.status.updateList(); drain();
        screen.assertLatest(24);
    }

    @Test public void rejectedHorizontalPostDoesNotDisableFutureRefreshes() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        CountingHorizontal row = new CountingHorizontal(activity);
        Rows rows = new Rows(activity);
        row.setAdapter(rows); activity.setContentView(row); drain();
        row.rejectNext = true; row.refreshData();
        rows.version = 25; row.refreshData(); drain();
        assertEquals(25, cells(row).getChildAt(0).getTag());
    }

    @Test public void detachedStatusCancelsPendingAdapterNotification() {
        Screen screen = new Screen();
        screen.rows.notifications = 0;
        screen.status.updateList();
        screen.parent.removeView(screen.status); drain();
        assertEquals("removed screens must not receive queued notifications", 0, screen.rows.notifications);
    }

    @Test public void detachedHorizontalCancelsPendingRebuild() {
        Screen screen = new Screen();
        screen.rows.binds = 0;
        screen.status.mStatusListView.refreshData();
        screen.parent.removeView(screen.status); drain();
        assertEquals("removed rows must not bind new Views", 0, screen.rows.binds);
    }

    @Test public void adapterObserverFollowsAttachmentAndRegistersOnlyOnce() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout parent = new FrameLayout(activity);
        HorizontalListView row = new HorizontalListView(activity);
        Rows rows = new Rows(activity);
        row.setAdapter(rows);
        assertEquals(0, rows.observers);
        parent.addView(row); activity.setContentView(parent); drain();
        assertEquals(1, rows.observers);
        row.setAdapter(rows); drain(); assertEquals(1, rows.observers);
        parent.removeView(row); assertEquals(0, rows.observers);
        parent.addView(row); drain(); assertEquals(1, rows.observers);
    }

    @Test public void clearingStatusAdapterAlsoClearsTheVisibleRows() {
        Screen screen = new Screen();
        assertTrue(cells(screen.status.mStatusListView).getChildCount() > 0);
        screen.status.setAdapter(null); drain();
        assertNull(screen.status.mStatusListView.getAdapter());
        assertEquals(0, screen.rows.observers);
        assertEquals(0, cells(screen.status.mStatusListView).getChildCount());
    }

    @Test public void replacementAdapterDoesNotReceiveAnIncompatibleOldView() {
        Screen screen = new Screen();
        Rows replacement = new Rows(screen.activity);
        replacement.version = 31;
        screen.status.setAdapter(replacement); drain();
        assertEquals(0, screen.rows.observers);
        assertEquals(1, replacement.observers);
        assertEquals(replacement.count, replacement.created);
        assertEquals(31, cells(screen.status.mStatusListView).getChildAt(0).getTag());
    }

    @Test public void unchangedViewTypesReuseExistingRows() {
        Screen screen = new Screen();
        View first = cells(screen.status.mStatusListView).getChildAt(0);
        screen.rows.created = 0; screen.rows.binds = 0; screen.rows.version = 32;
        screen.status.updateList(); drain();
        assertEquals(screen.rows.count, screen.rows.binds);
        assertEquals("a refresh must not allocate every unchanged cell again", 0, screen.rows.created);
        assertSame(first, cells(screen.status.mStatusListView).getChildAt(0));
        screen.assertLatest(32);
    }

    @Test public void invalidationDiscardsRecycledRows() {
        Screen screen = new Screen();
        View first = cells(screen.status.mStatusListView).getChildAt(0);
        screen.rows.created = 0;
        screen.rows.notifyDataSetInvalidated(); drain();
        assertEquals(screen.rows.count, screen.rows.created);
        assertNotSame(first, cells(screen.status.mStatusListView).getChildAt(0));
    }

    @Test public void changedViewTypeOnlyReplacesTheAffectedCell() {
        Screen screen = new Screen();
        View first = cells(screen.status.mStatusListView).getChildAt(0);
        View second = cells(screen.status.mStatusListView).getChildAt(1);
        screen.rows.created = 0; screen.rows.specialType = 1;
        screen.status.updateList(); drain();
        assertEquals(1, screen.rows.created);
        assertSame(first, cells(screen.status.mStatusListView).getChildAt(0));
        assertNotSame(second, cells(screen.status.mStatusListView).getChildAt(1));
    }

    @Test public void scrollRestorationUsesTheNewMeasuredWidth() {
        Screen screen = new Screen();
        HorizontalListView row = screen.status.mStatusListView;
        layout(screen.status);
        row.scrollTo(150, 0);
        assertEquals(150, row.getScrollX());
        screen.rows.count = 12;
        screen.status.updateList(); drain(); layout(screen.status);
        assertEquals("growing the row preserves its previous position", 150, row.getScrollX());
        screen.rows.count = 2;
        screen.status.updateList(); drain(); layout(screen.status);
        assertEquals("shrinking below the viewport clamps to its new end", 0, row.getScrollX());
    }

    @Test public void listViewHeaderBindsWithoutAnInitialHorizontalGesture() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        IGStatusView status = new IGStatusView(activity);
        status.setLayoutParams(new ListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 84));
        Rows rows = new Rows(activity);
        status.setAdapter(rows);
        ListView chats = new ListView(activity);
        chats.addHeaderView(status); chats.setAdapter(new Rows(activity));
        activity.setContentView(chats); drain();
        assertEquals(rows.count, cells(status.mStatusListView).getChildCount());
        rows.version = 8; status.updateList(); drain();
        assertEquals(8, cells(status.mStatusListView).getChildAt(0).getTag());
    }

    @Test public void completeProductionIgAdapterStillRendersItsOwnStatusCell() {
        IGStatusHook.items.clear(); IGStatusHook.items.add(null);
        try {
            Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
            IGStatusView status = new IGStatusView(activity);
            status.setAdapter(new IGStatusAdapter(activity, Object.class, null));
            activity.setContentView(status); drain(); layout(status);
            ViewGroup children = cells(status.mStatusListView);
            assertEquals(1, children.getChildCount());
            assertTrue(children.getChildAt(0).getMeasuredHeight() > 0);
            assertTrue(children.getChildAt(0).hasOnClickListeners());
            View first = children.getChildAt(0);
            status.updateList(); drain(); layout(status);
            assertSame(first, children.getChildAt(0));
        } finally { IGStatusHook.items.clear(); }
    }

    @Test public void horizontalAndVerticalGesturesRetainTheCurrentParentContract() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        InterceptParent parent = new InterceptParent(activity);
        HorizontalListView row = new HorizontalListView(activity);
        row.setAdapter(new Rows(activity)); parent.addView(row);
        activity.setContentView(parent); drain(); layout(row);
        touch(row, MotionEvent.ACTION_DOWN, 5, 5);
        assertTrue(parent.disallowed);
        touch(row, MotionEvent.ACTION_MOVE, 150, 6);
        assertTrue(parent.disallowed);
        touch(row, MotionEvent.ACTION_CANCEL, 150, 6);
        assertFalse(parent.disallowed);
        touch(row, MotionEvent.ACTION_DOWN, 5, 5);
        touch(row, MotionEvent.ACTION_MOVE, 6, 150);
        assertFalse(parent.disallowed);
        touch(row, MotionEvent.ACTION_UP, 6, 150);
        assertFalse(parent.disallowed);
    }

    @Test public void reattachmentRendersChangesPublishedWhileDetached() {
        Screen screen = new Screen();
        screen.parent.removeView(screen.status);
        screen.rows.version = 37; screen.status.updateList();
        screen.rows.notifyDataSetChanged(); drain();
        screen.parent.addView(screen.status); drain();
        screen.assertLatest(37);
        assertEquals(1, screen.rows.observers);
    }

    @Test public void replacingTheAdapterDuringBindingEventuallyShowsOnlyItsRows() {
        Screen screen = new Screen();
        Rows replacement = new Rows(screen.activity); replacement.version = 51;
        screen.rows.onBind = () -> {
            screen.rows.onBind = null;
            screen.status.setAdapter(replacement);
        };
        screen.status.updateList(); drain();
        ViewGroup children = cells(screen.status.mStatusListView);
        assertEquals(replacement.count, children.getChildCount());
        for (int index = 0; index < children.getChildCount(); index++) {
            assertEquals(51, children.getChildAt(index).getTag());
        }
    }

    @Test public void boundedReconciliationStillFindsAnUnnotifiedCountChange() {
        Screen screen = new Screen();
        screen.rows.count = 1;
        screen.parent.removeView(screen.status);
        screen.parent.addView(screen.status); drain();
        assertEquals(1, cells(screen.status.mStatusListView).getChildCount());
        screen.rows.count = 4;
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250));
        assertEquals(4, cells(screen.status.mStatusListView).getChildCount());
    }

    @Test public void changedConfigurationRecreatesRowsWithCurrentThemeResources() {
        Screen screen = new Screen();
        Configuration previous = new Configuration(screen.activity.getResources().getConfiguration());
        Configuration changed = new Configuration(previous);
        changed.uiMode ^= Configuration.UI_MODE_NIGHT_MASK;
        try {
            screen.activity.getResources().updateConfiguration(changed, screen.activity.getResources().getDisplayMetrics());
            screen.rows.created = 0;
            screen.status.updateList(); drain();
            assertEquals("old themed Views must not survive a resource configuration change", screen.rows.count, screen.rows.created);
        } finally {
            screen.activity.getResources().updateConfiguration(previous, screen.activity.getResources().getDisplayMetrics());
        }
    }

    @Test public void actualAdapterReplacesAPlaceholderWhenItsPositionAppearsAgain() {
        Context context = RuntimeEnvironment.getApplication();
        IGStatusHook.items.add(null);
        IGStatusAdapter adapter = new IGStatusAdapter(context, Object.class, null);
        FrameLayout parent = new FrameLayout(context);
        View placeholder = adapter.getView(1, null, parent);
        assertNull(placeholder.getTag());
        IGStatusHook.items.add(new Object());
        try {
            View result = adapter.getView(1, placeholder, parent);
            assertNotSame(placeholder, result);
            assertNotNull(result.getTag());
            assertTrue(result.hasOnClickListeners());
        } catch (RuntimeException invalidReuse) {
            fail("A placeholder must not be treated as a populated ViewHolder: " + invalidReuse.getClass().getSimpleName());
        }
    }

    @Test public void disabledHookDoesNotRegisterCallbacksOrHeaders() throws Throwable {
        Context context = RuntimeEnvironment.getApplication();
        android.content.SharedPreferences prefs = context.getSharedPreferences("disabled-fixture", Context.MODE_PRIVATE);
        prefs.edit().putBoolean("igstatus", false).commit();
        new IGStatusHook(context, getClass().getClassLoader(), prefs).hook();
        assertTrue(XposedBridge.hooks.isEmpty()); assertTrue(registry().isEmpty());
    }

    @Test public void realHookStoresOnlyWeakReferencesToItsHeaders() throws Throwable {
        HookScreen screen = new HookScreen(false, true);
        assertTrue(screen.header.isAttachedToWindow());
        assertTrue("the static registry must not keep an Activity alive", registry().get(0) instanceof WeakReference);
        assertSame(screen.header, ((WeakReference<?>) registry().get(0)).get());
    }

    @Test public void realHookRegistryDetachesAndReattachesWithItsView() throws Throwable {
        HookScreen screen = new HookScreen(false, true);
        Rows rows = new Rows(screen.activity);
        screen.header.setAdapter(rows); drain(); rows.notifications = 0;
        screen.activity.setContentView(new FrameLayout(screen.activity));
        assertTrue("detaching a screen removes its registry entry", registry().isEmpty());
        publishFixtureStatus(); drain(); assertEquals(0, rows.notifications);
        screen.activity.setContentView(screen.root); drain(); rows.notifications = 0;
        assertEquals(1, registry().size());
        publishFixtureStatus(); drain(); assertEquals(1, rows.notifications);
    }

    @Test public void switchingActivitiesOnlyRefreshesTheCurrentRegisteredHeader() throws Throwable {
        HookScreen oldScreen = new HookScreen(false, true);
        Rows oldRows = new Rows(oldScreen.activity);
        oldScreen.header.setAdapter(oldRows); drain();
        oldScreen.controller.pause().stop().destroy();
        assertTrue("destroyed Activity header must leave the registry", registry().isEmpty());
        HookScreen current = new HookScreen(false, true);
        Rows currentRows = new Rows(current.activity);
        current.header.setAdapter(currentRows); drain();
        oldRows.notifications = 0; currentRows.notifications = 0;
        publishFixtureStatus(); drain();
        assertEquals(0, oldRows.notifications); assertEquals(1, currentRows.notifications);
        assertEquals(1, registry().size());
    }

    @Test public void clearedWeakRegistryEntriesAreRemovedDuringActualHookUpdates() throws Throwable {
        HookScreen screen = new HookScreen(false, true);
        Object entry = registry().get(0);
        assertTrue(entry instanceof WeakReference);
        ((WeakReference<?>) entry).clear();
        publishFixtureStatus(); drain();
        assertTrue(registry().isEmpty());
        assertTrue("the test deliberately clears the weak reference without relying on nondeterministic GC", screen.header.isAttachedToWindow());
    }

    @Test public void recyclerHeaderWaitsForItsOwnerViewToAttach() throws Throwable {
        HookScreen screen = new HookScreen(true, false);
        screen.fragment.controller = new ConversationsFragment.HeaderController(screen.recycler);
        drain();
        assertEquals("do not mutate a host controller for an unattached screen", 0, screen.fragment.controller.calls);
        screen.activity.setContentView(screen.root); drain();
        assertEquals(1, screen.fragment.controller.calls);
        assertTrue(screen.header.isAttachedToWindow());
    }

    @Test public void pendingRecyclerRetryCancelsOnDetachAndResumesAfterReattachment() throws Throwable {
        HookScreen screen = new HookScreen(true, true);
        screen.root.removeView(screen.recycler);
        screen.fragment.controller = new ConversationsFragment.HeaderController(screen.recycler);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500));
        assertEquals("a detached host must not receive a stale header insertion", 0, screen.fragment.controller.calls);
        screen.root.addView(screen.recycler); drain();
        assertEquals(1, screen.fragment.controller.calls);
        screen.root.removeView(screen.recycler); screen.root.addView(screen.recycler); drain();
        assertEquals("an accepted header must not be inserted twice", 1, screen.fragment.controller.calls);
    }

    @Test public void pendingRecyclerRetryDoesNotInsertIntoDestroyedActivity() throws Throwable {
        HookScreen screen = new HookScreen(true, true);
        screen.controller.pause().stop().destroy();
        screen.fragment.controller = new ConversationsFragment.HeaderController(screen.recycler);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1000));
        assertEquals("destroyed Activity cannot receive header insertion", 0, screen.fragment.controller.calls);
    }

    @Test public void unsuccessfulHeaderDiscoveryHasABoundedRetryLifetime() throws Throwable {
        HookScreen screen = new HookScreen(true, true);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(4000));
        int posts = screen.recycler.posts;
        assertTrue("preserve the existing maximum of 31 lookup attempts", posts <= 31);
        assertEquals("finished retries release their attach listener", 0, screen.recycler.attachListeners);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(4000));
        assertEquals(posts, screen.recycler.posts);
    }

    private static void touch(HorizontalListView row, int action, float x, float y) {
        MotionEvent event = MotionEvent.obtain(0, 1, action, x, y, 0);
        try { row.dispatchTouchEvent(event); } finally { event.recycle(); }
    }

    private static class InterceptParent extends FrameLayout {
        boolean disallowed;
        InterceptParent(Context context) { super(context); }
        @Override public void requestDisallowInterceptTouchEvent(boolean disallow) {
            disallowed = disallow; super.requestDisallowInterceptTouchEvent(disallow);
        }
    }

    private static class CountingHorizontal extends HorizontalListView {
        int posts;
        boolean rejectNext;
        CountingHorizontal(Context context) { super(context); }
        @Override public boolean post(Runnable action) {
            posts++;
            if (rejectNext) { rejectNext = false; return false; }
            return super.post(action);
        }
    }

    private static class CountingStatus extends IGStatusView {
        int posts;
        boolean rejectNext;
        CountingStatus(Context context) { super(context); }
        @Override public boolean post(Runnable action) {
            posts++;
            if (rejectNext) { rejectNext = false; return false; }
            return super.post(action);
        }
    }

    private static class Cell extends View {
        final Rows owner;
        final int type;
        Cell(Context context, Rows owner, int type) {
            super(context); this.owner = owner; this.type = type;
            setLayoutParams(new ViewGroup.LayoutParams(74, 54));
        }
    }

    private static class Rows extends BaseAdapter {
        final Context context;
        int version, notifications, binds, created, observers, specialType = -1, count = 8;
        Runnable onNotify, onBind;
        Rows(Context context) { this.context = context; }
        @Override public int getCount() { return count; }
        @Override public Object getItem(int index) { return index; }
        @Override public long getItemId(int index) { return index; }
        @Override public int getViewTypeCount() { return 2; }
        @Override public int getItemViewType(int index) { return index == specialType ? 1 : 0; }
        @Override public View getView(int position, View convertView, ViewGroup parent) {
            binds++;
            if (onBind != null) onBind.run();
            Cell cell;
            if (convertView == null) {
                cell = new Cell(context, this, getItemViewType(position)); created++;
            } else {
                cell = (Cell) convertView;
                assertSame("Views must not be shared between adapters", this, cell.owner);
                assertEquals("Views must not be reused across incompatible types", getItemViewType(position), cell.type);
            }
            cell.setTag(version);
            return cell;
        }
        @Override public void notifyDataSetChanged() {
            notifications++; super.notifyDataSetChanged();
            if (onNotify != null) onNotify.run();
        }
        @Override public void registerDataSetObserver(DataSetObserver observer) {
            observers++; super.registerDataSetObserver(observer);
        }
        @Override public void unregisterDataSetObserver(DataSetObserver observer) {
            observers--; super.unregisterDataSetObserver(observer);
        }
    }
}
