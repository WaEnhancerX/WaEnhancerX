package com.whatsapp.conversationslist;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.os.Bundle;
import android.widget.FrameLayout;
public class ConversationsFragment extends androidx.fragment.app.Fragment {
    public HeaderController controller;
    public View onCreateView(LayoutInflater inflater, ViewGroup parent, Bundle state) { return null; }
    public int fragmentKey() { return 42; }
    public static final class FixtureRecyclerView extends FrameLayout {
        public int posts, attachListeners;
        public FixtureRecyclerView(Context context) { super(context); }
        @Override public void addOnAttachStateChangeListener(View.OnAttachStateChangeListener listener) {
            attachListeners++; super.addOnAttachStateChangeListener(listener);
        }
        @Override public void removeOnAttachStateChangeListener(View.OnAttachStateChangeListener listener) {
            attachListeners--; super.removeOnAttachStateChangeListener(listener);
        }
        @Override public boolean postDelayed(Runnable action, long delay) {
            posts++; return super.postDelayed(action, delay);
        }
    }
    public static final class HeaderController {
        public final FixtureRecyclerView recyclerView;
        public int calls;
        public HeaderController(FixtureRecyclerView view) { recyclerView = view; }
        public void addHeader(View header, Integer position) {
            calls++;
            recyclerView.addView(header);
        }
    }
}
