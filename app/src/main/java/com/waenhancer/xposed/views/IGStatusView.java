package com.waenhancer.xposed.views;

import android.content.Context;
import android.widget.FrameLayout;
import android.widget.ListAdapter;

public class IGStatusView extends FrameLayout {

    public HorizontalListView mStatusListView;
    public ListAdapter mStatusAdapter;
    public int fragmentId = 0;
    private boolean attached;
    private boolean updatePosted;
    private final Runnable updateAdapter = () -> {
        synchronized (IGStatusView.this) {
            updatePosted = false;
            if (!attached) return;
        }
        if (mStatusAdapter instanceof android.widget.BaseAdapter) {
            ((android.widget.BaseAdapter) mStatusAdapter).notifyDataSetChanged();
        } else if (mStatusListView != null) {
            mStatusListView.refreshData();
        }
        requestLayout();
        invalidate();
    };

    public IGStatusView(Context context) {
        super(context);
        HorizontalListView statusListView = new HorizontalListView(context);
        statusListView.setLayoutParams(new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        mStatusListView = statusListView;
        addView(statusListView);
    }

    public ListAdapter getAdapter() {
        return mStatusAdapter;
    }

    public void setAdapter(ListAdapter adapter) {
        this.mStatusAdapter = adapter;
        if (mStatusListView != null) {
            mStatusListView.setAdapter(adapter);
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        synchronized (this) {
            attached = true;
        }
        updateList();
    }

    @Override
    protected void onDetachedFromWindow() {
        synchronized (this) {
            attached = false;
            removeCallbacks(updateAdapter);
            updatePosted = false;
        }
        super.onDetachedFromWindow();
    }

    @Override
    public void setTranslationY(float translationY) {
        if (getHeight() > 0) {
            int visibility = (translationY > (float) getHeight()) ? GONE : VISIBLE;
            if (visibility == VISIBLE) {
                super.setTranslationY(translationY);
            }
            setVisibility(visibility);
        }
    }

    public synchronized void updateList() {
        if (!attached || updatePosted) return;
        updatePosted = true;
        if (!post(updateAdapter)) updatePosted = false;
    }
}
