package com.waenhancer.xposed.views;

import android.content.Context;
import android.content.res.Configuration;
import android.database.DataSetObserver;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ListAdapter;

import java.util.ArrayList;

/**
 * Adapter-backed horizontal row implemented on top of Android's native scroller.
 * WhatsApp can host this view in either a RecyclerView sibling or a ListView
 * header, so child attachment and drawing must not depend on a later touch-driven
 * layout pass.
 */
public class HorizontalListView extends HorizontalScrollView {

    private final LinearLayout content;
    private final int touchSlop;
    private ListAdapter adapter;
    private boolean attached;
    private boolean observerRegistered;
    private boolean refreshPosted;
    private boolean discardChildren;
    private final ArrayList<Integer> childTypes = new ArrayList<>();
    private Configuration boundConfiguration;
    private boolean restoreScrollAfterLayout;
    private int scrollBeforeRefresh;
    private float downX;
    private float downY;
    private int reconcileAttempts;
    private final Runnable reconcileChildren = new Runnable() {
        @Override
        public void run() {
            if (!isAttachedToWindow()) return;

            int adapterCount = adapter != null ? adapter.getCount() : 0;
            if (content.getChildCount() != adapterCount) {
                refreshData();
            }

            // WhatsApp can publish the status snapshot shortly after this row
            // attaches without dispatching another adapter notification.
            if (++reconcileAttempts < 50 && adapterCount <= 1) {
                postDelayed(this, 100L);
            }
        }
    };

    private final Runnable refreshChildren = () -> {
        synchronized (HorizontalListView.this) {
            refreshPosted = false;
            if (!attached) return;
        }
        rebuildChildren();
    };

    private final DataSetObserver dataObserver = new DataSetObserver() {
        @Override
        public void onChanged() {
            refreshData();
        }

        @Override
        public void onInvalidated() {
            synchronized (HorizontalListView.this) {
                discardChildren = true;
            }
            refreshData();
        }
    };

    public HorizontalListView(Context context) {
        this(context, null);
    }

    public HorizontalListView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setFillViewport(true);
        setHorizontalScrollBarEnabled(false);
        setOverScrollMode(OVER_SCROLL_NEVER);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();

        content = new LinearLayout(context);
        content.setOrientation(LinearLayout.HORIZONTAL);
        content.setGravity(android.view.Gravity.TOP);
        int topPadding = Math.round(6f * context.getResources().getDisplayMetrics().density);
        content.setPadding(0, topPadding, 0, 0);
        content.setClipToPadding(false);
        addView(content, new LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT));
    }

    public ListAdapter getAdapter() {
        return adapter;
    }

    public void setAdapter(ListAdapter newAdapter) {
        if (adapter == newAdapter) {
            refreshData();
            return;
        }
        if (adapter != null && observerRegistered) {
            adapter.unregisterDataSetObserver(dataObserver);
            observerRegistered = false;
        }
        adapter = newAdapter;
        synchronized (this) {
            discardChildren = true;
        }
        if (adapter != null && attached) {
            adapter.registerDataSetObserver(dataObserver);
            observerRegistered = true;
        }
        refreshData();
    }

    public synchronized void refreshData() {
        // Notifications can arrive in bursts or while a header is detached.
        // Attachment always refreshes the latest data, without retaining one
        // runnable and rebuilding every row for each obsolete notification.
        if (!attached || refreshPosted) return;
        refreshPosted = true;
        if (!post(refreshChildren)) refreshPosted = false;
    }

    private void rebuildChildren() {
        ListAdapter currentAdapter = adapter;
        int previousCount = content.getChildCount();
        View[] previousChildren = new View[previousCount];
        int[] previousTypes = new int[previousCount];
        boolean mayReuse;
        synchronized (this) {
            mayReuse = !discardChildren;
            discardChildren = false;
        }
        Configuration configuration = getResources().getConfiguration();
        mayReuse &= boundConfiguration != null && boundConfiguration.equals(configuration);
        boundConfiguration = new Configuration(configuration);
        for (int index = 0; index < previousCount; index++) {
            previousChildren[index] = content.getChildAt(index);
            previousTypes[index] = index < childTypes.size() ? childTypes.get(index) : -1;
        }
        if (!restoreScrollAfterLayout) scrollBeforeRefresh = getScrollX();
        restoreScrollAfterLayout = true;
        content.removeAllViews();
        childTypes.clear();

        if (currentAdapter != null) {
            int count = currentAdapter.getCount();
            for (int position = 0; position < count; position++) {
                if (adapter != currentAdapter) {
                    refreshData();
                    break;
                }
                int viewType = currentAdapter.getItemViewType(position);
                View reusable = mayReuse && position < previousCount && viewType >= 0
                        && previousTypes[position] == viewType ? previousChildren[position] : null;
                View child = currentAdapter.getView(position, reusable, content);
                ViewGroup.LayoutParams params = child.getLayoutParams();
                if (params == null) {
                    params = new LinearLayout.LayoutParams(
                            LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT);
                }
                content.addView(child, params);
                childTypes.add(viewType);
            }
        }

        content.requestLayout();
        requestLayout();
        invalidate();
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        if (restoreScrollAfterLayout) {
            restoreScrollAfterLayout = false;
            int viewport = getWidth() - getPaddingLeft() - getPaddingRight();
            scrollTo(Math.max(0, Math.min(scrollBeforeRefresh,
                    Math.max(0, content.getWidth() - viewport))), 0);
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        synchronized (this) {
            attached = true;
        }
        if (adapter != null && !observerRegistered) {
            adapter.registerDataSetObserver(dataObserver);
            observerRegistered = true;
        }
        refreshData();
        reconcileAttempts = 0;
        removeCallbacks(reconcileChildren);
        post(reconcileChildren);
    }

    @Override
    protected void onDetachedFromWindow() {
        synchronized (this) {
            attached = false;
            refreshPosted = false;
        }
        removeCallbacks(refreshChildren);
        // Do not hold the refresh lock while calling into the adapter: an
        // observer notification may arrive from another thread at this point.
        if (adapter != null && observerRegistered) {
            adapter.unregisterDataSetObserver(dataObserver);
            observerRegistered = false;
        }
        restoreScrollAfterLayout = false;
        removeCallbacks(reconcileChildren);
        super.onDetachedFromWindow();
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = event.getX();
                downY = event.getY();
                // Keep WhatsApp's vertical RecyclerView from taking the stream
                // before HorizontalScrollView can classify the drag.
                requestParentIntercept(false);
                break;
            case MotionEvent.ACTION_MOVE:
                float dx = Math.abs(event.getX() - downX);
                float dy = Math.abs(event.getY() - downY);
                if (dx > touchSlop && dx > dy) {
                    requestParentIntercept(false);
                } else if (dy > touchSlop && dy > dx) {
                    // Once the gesture is clearly vertical, return it to
                    // WhatsApp's chat list so dragging on the stories area
                    // scrolls the main homepage normally.
                    requestParentIntercept(true);
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                requestParentIntercept(true);
                break;
        }
        return super.dispatchTouchEvent(event);
    }

    private void requestParentIntercept(boolean allowIntercept) {
        ViewParent parent = getParent();
        if (parent != null) {
            parent.requestDisallowInterceptTouchEvent(!allowIntercept);
        }
    }
}
