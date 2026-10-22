package com.waenhancer.xposed.features.privacy;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Toast;
import com.waenhancer.app.R;
import com.waenhancer.utils.ContactIdentityPolicy;
import com.waenhancer.utils.ContactNameResolver;
import com.waenhancer.utils.UiText;
import com.waenhancer.xposed.core.BaseFeature;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Select phone dialer or the original WhatsApp operation after its native call menu. */
public final class CallTypeControllerHook extends BaseFeature {
    static final String ENABLED = "call_type_controller";
    static final long LOOKUP_TIMEOUT_MS = 1500L;
    private static final String TAG = "[WAEX:CallType]";
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean ready = new AtomicBoolean();
    private final ContractResolver resolver;
    private final PhoneLookup lookup;
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(0, 1, 30L,
            TimeUnit.SECONDS, new ArrayBlockingQueue<>(1), runnable -> {
                Thread thread = new Thread(runnable, "WAEX-CallType");
                thread.setDaemon(true);
                return thread;
            });
    // UI-thread owned. At most one lookup/selector belongs to this installation.
    private Request pending;
    private boolean installationAttempted;

    public CallTypeControllerHook(Context context, ClassLoader loader, SharedPreferences prefs) {
        this(context, loader, prefs, () -> CallTypeHostContract.discover(context, loader),
                (app, jid) -> ContactNameResolver.INSTANCE.resolveLidToPhone(app, jid));
    }

    CallTypeControllerHook(Context context, ClassLoader loader, SharedPreferences prefs,
                           ContractResolver resolver, PhoneLookup lookup) {
        super(context, loader, prefs);
        this.resolver = resolver;
        this.lookup = lookup;
    }

    @Override public String getName() { return "Call Type Selection"; }
    @Override public String getCategory() { return "Privacy"; }

    private final SharedPreferences.OnSharedPreferenceChangeListener preferenceListener = (p, key) -> {
        if (ENABLED.equals(key) && !enabled()) onMain(() -> finish(pending));
    };

    private final Application.ActivityLifecycleCallbacks lifecycle = new Application.ActivityLifecycleCallbacks() {
        private void cancel(Activity activity) {
            onMain(() -> {
                Request request = pending;
                if (request != null && request.activity.get() == activity) finish(request);
            });
        }
        @Override public void onActivityPaused(Activity activity) { cancel(activity); }
        @Override public void onActivityStopped(Activity activity) { cancel(activity); }
        @Override public void onActivityDestroyed(Activity activity) { cancel(activity); }
        @Override public void onActivityCreated(Activity activity, Bundle state) {}
        @Override public void onActivityStarted(Activity activity) {}
        @Override public void onActivityResumed(Activity activity) {}
        @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) {}
    };

    @Override public synchronized void hook() throws Throwable {
        if (ready.get()) return;
        // The existing restart tracker covers this public setting. An installation
        // that starts disabled needs no DexKit work or hooks until the next restart.
        if (!enabled()) return;
        if (installationAttempted) {
            throw new IllegalStateException("Call selector installation already failed; restart to retry");
        }
        Context appContext = context.getApplicationContext();
        if (!(appContext instanceof Application)) {
            throw new IllegalStateException("Call selector requires the host Application");
        }
        Application application = (Application) appContext;
        CallTypeHostContract contract = resolver.resolve();
        if (contract == null || contract.sites.size() != 4) {
            throw new IllegalStateException("Call selector requires all conversation operations");
        }
        List<XC_MethodHook.Unhook> installed = new ArrayList<>();
        installationAttempted = true;
        try {
            for (CallTypeHostContract.Site site : contract.sites) {
                installed.add(XposedBridge.hookMethod(site.operation, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        if (!ready.get() || !enabled() || Looper.myLooper() != Looper.getMainLooper()) return;
                        try {
                            if (intercept(site, param.thisObject, param.args, application)) param.setResult(null);
                        } catch (RuntimeException | ReflectiveOperationException failure) {
                            // A selector that cannot take ownership leaves the original call intact.
                            finish(pending);
                            log("selector unavailable", failure);
                        }
                    }
                }));
            }
            application.registerActivityLifecycleCallbacks(lifecycle);
            prefs.registerOnSharedPreferenceChangeListener(preferenceListener);
            ready.set(true);
        } catch (Throwable failure) {
            // Retained callbacks are inert even if a framework refuses an unhook.
            ready.set(false);
            for (int index = installed.size() - 1; index >= 0; index--) {
                try { installed.get(index).unhook(); }
                catch (Throwable cleanup) { log("hook cleanup failed", cleanup); }
            }
            try { application.unregisterActivityLifecycleCallbacks(lifecycle); }
            catch (RuntimeException cleanup) { log("lifecycle cleanup failed", cleanup); }
            try { prefs.unregisterOnSharedPreferenceChangeListener(preferenceListener); }
            catch (RuntimeException cleanup) { log("preference cleanup failed", cleanup); }
            throw failure;
        }
    }

    private boolean enabled() {
        try { return prefs.getBoolean(ENABLED, false); }
        catch (RuntimeException failure) { return false; }
    }

    private boolean intercept(CallTypeHostContract.Site site, Object owner, Object[] arguments,
                              Application application) throws ReflectiveOperationException {
        if (arguments == null || arguments.length != 3) return false;
        for (Object argument : arguments) if (!(argument instanceof Boolean)) return false;
        CallTypeHostContract.Snapshot snapshot = site.read(owner);
        if (snapshot == null) return false;
        if (pending != null) {
            // Repeated events cannot queue a second call behind the same dialog.
            if (pending.owner.get() == owner && pending.peer.equals(snapshot.peer)) return true;
            finish(pending);
        }
        Request request = new Request(site, owner, snapshot, arguments.clone());
        String phone = phoneDigits(ContactIdentityPolicy.phoneUser(snapshot.peer));
        if (phone != null) {
            own(request);
            if (show(request, phone)) return true;
            finish(request);
            return false;
        }
        if (!ContactIdentityPolicy.isLid(snapshot.peer)) return false;
        own(request);
        request.timeout = () -> continueWhatsApp(request);
        try {
            request.future = worker.submit(() -> {
                String mapped = null;
                try {
                    if (!Thread.currentThread().isInterrupted()) mapped = phoneDigits(lookup.resolve(application, request.peer));
                } catch (RuntimeException failure) {
                    log("phone lookup unavailable", failure);
                }
                String result = mapped;
                main.post(() -> {
                    if (pending != request) return;
                    main.removeCallbacks(request.timeout);
                    if (!current(request)) { finish(request); return; }
                    if (result == null || !show(request, result)) continueWhatsApp(request);
                });
            });
            main.postDelayed(request.timeout, LOOKUP_TIMEOUT_MS);
            return true;
        } catch (RejectedExecutionException failure) {
            finish(request);
            return false;
        }
    }

    private boolean show(Request request, String phone) {
        if (!current(request)) return false;
        Activity activity = request.activity.get();
        try {
            AlertDialog dialog = new AlertDialog.Builder(activity)
                    .setTitle(UiText.get(R.string.call_type_dialog_title, "Choose call type"))
                    .setMessage(UiText.get(R.string.call_type_dialog_message, "Call %1$s using:", "+" + phone))
                    .setPositiveButton(UiText.get(R.string.call_type_phone, "Phone"), null)
                    .setNegativeButton("WhatsApp", (d, which) -> continueWhatsApp(request))
                    .setNeutralButton(android.R.string.cancel, (d, which) -> finish(request))
                    .create();
            request.dialog = dialog;
            dialog.setOnCancelListener(d -> finish(request));
            dialog.setOnDismissListener(d -> finish(request));
            dialog.show();
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> dial(request, phone));
            return dialog.isShowing();
        } catch (RuntimeException failure) {
            log("dialog unavailable", failure);
            return false;
        }
    }

    private void own(Request request) {
        pending = request;
        Object owner = request.owner.get();
        if (owner instanceof View) {
            request.detach = new View.OnAttachStateChangeListener() {
                @Override public void onViewAttachedToWindow(View view) {}
                @Override public void onViewDetachedFromWindow(View view) { finish(request); }
            };
            ((View) owner).addOnAttachStateChangeListener(request.detach);
        }
    }

    private void dial(Request request, String phone) {
        if (!current(request)) { finish(request); return; }
        Activity activity = request.activity.get();
        try {
            // ACTION_DIAL never starts a phone call: the user confirms it in the dialer.
            activity.startActivity(new Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", "+" + phone, null)));
            finish(request);
        } catch (RuntimeException failure) {
            log("dialer unavailable", failure);
            // Choosing Phone must not turn into an automatic WhatsApp call on failure.
            AlertDialog dialog = request.dialog;
            if (pending == request && dialog != null && dialog.isShowing()) {
                dialog.setMessage(UiText.get(R.string.call_type_dialer_unavailable,
                        "A phone app could not be opened. You can choose WhatsApp or cancel."));
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
            }
        }
    }

    private void continueWhatsApp(Request request) {
        if (!current(request)) { finish(request); return; }
        Object owner = request.owner.get();
        finish(request);
        try {
            XposedBridge.invokeOriginalMethod(request.site.operation, owner, request.arguments);
        } catch (Throwable failure) {
            // The original operation is attempted exactly once, even when it throws.
            log("original call failed", failure);
            Activity activity = request.activity.get();
            if (activity != null && !activity.isFinishing() && !activity.isDestroyed()) {
                Toast.makeText(activity, UiText.get(R.string.call_type_whatsapp_failed,
                        "The WhatsApp call could not be started."), Toast.LENGTH_SHORT).show();
            }
        }
    }

    private boolean current(Request request) {
        if (request == null || pending != request || !ready.get() || !enabled()) return false;
        try {
            CallTypeHostContract.Snapshot now = request.site.read(request.owner.get());
            return now != null && now.activity == request.activity.get() && now.peer.equals(request.peer);
        } catch (RuntimeException | ReflectiveOperationException failure) {
            return false;
        }
    }

    private void finish(Request request) {
        if (request == null || pending != request) return;
        pending = null;
        Object owner = request.owner.get();
        if (owner instanceof View && request.detach != null) {
            ((View) owner).removeOnAttachStateChangeListener(request.detach);
            request.detach = null;
        }
        if (request.timeout != null) main.removeCallbacks(request.timeout);
        if (request.future != null) request.future.cancel(true);
        // At most one queued task; canceled lookups never accumulate behind a slow database.
        worker.purge();
        AlertDialog dialog = request.dialog;
        request.dialog = null;
        if (dialog != null) {
            try { dialog.dismiss(); }
            catch (RuntimeException failure) { log("dialog cleanup failed", failure); }
        }
    }

    private void onMain(Runnable runnable) {
        if (Looper.myLooper() == Looper.getMainLooper()) runnable.run();
        else main.post(runnable);
    }

    private static String phoneDigits(String value) {
        if (value == null || value.length() < 5 || value.length() > 15 || value.charAt(0) == '0') return null;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character < '0' || character > '9') return null;
        }
        return value;
    }

    private static void log(String action, Throwable failure) {
        XposedBridge.log(TAG + " " + action + ": " + failure.getClass().getSimpleName());
    }

    @FunctionalInterface interface ContractResolver { CallTypeHostContract resolve() throws Throwable; }
    @FunctionalInterface interface PhoneLookup { String resolve(Context context, String lid); }

    private static final class Request {
        final CallTypeHostContract.Site site;
        final WeakReference<Object> owner;
        final WeakReference<Activity> activity;
        final String peer;
        final Object[] arguments;
        Future<?> future;
        Runnable timeout;
        AlertDialog dialog;
        View.OnAttachStateChangeListener detach;

        Request(CallTypeHostContract.Site site, Object owner, CallTypeHostContract.Snapshot snapshot, Object[] arguments) {
            this.site = site;
            this.owner = new WeakReference<>(owner);
            this.activity = new WeakReference<>(snapshot.activity);
            this.peer = snapshot.peer;
            this.arguments = arguments;
        }
    }
}
