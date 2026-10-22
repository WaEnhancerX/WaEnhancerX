package androidx.fragment.app;
/** Fixture for the host Fragment boundary; AndroidX internals are not simulated. */
public class Fragment {
    private android.app.Activity activity;
    public void setActivity(android.app.Activity activity) { this.activity = activity; }
    public android.app.Activity getActivity() { return activity; }
}
