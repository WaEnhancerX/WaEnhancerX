package qa;

import android.content.FixturePreferences;
import android.content.Intent;
import android.os.Binder;
import android.os.Bundle;
import com.waenhancer.app.WaexApp;
import com.waenhancer.config.PreferenceSchema;
import com.waenhancer.config.PreferenceSyncPolicy;
import com.waenhancer.licensing.LicenseManager;
import com.waenhancer.licensing.ProFeatureGate;
import com.waenhancer.utils.WhatsAppPackageDetector;
import com.waenhancer.xposed.bridge.HookProvider;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/** Full app/provider callbacks with explicit platform boundaries, no external effects. */
public final class PreferenceSyncRuntimeChecks {
    static final String WA = "com.whatsapp", BUSINESS = "com.whatsapp.w4b";
    static final String URI = "content://com.waenhancer.hookprovider/";
    static final String ACTION = "com.waenhancer.action.PREFERENCES_CHANGED";
    static final int MODULE_UID = 10100, WA_UID = 10200, WORK_UID = 1_010_200, UNKNOWN_UID = 20200;
    static WaexApp app;
    static FixturePreferences preferences;
    static HookProvider provider;
    static int checks, scenarios;
    static final List<String> failures = new ArrayList<>();

    @FunctionalInterface interface Scenario { void run() throws Throwable; }

    static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    static void prepare(boolean deferred) {
        app = new WaexApp();
        app.pm.owners.put(MODULE_UID, new String[]{"com.waenhancer"});
        app.pm.owners.put(WA_UID, new String[]{WA});
        app.pm.owners.put(WORK_UID, new String[]{WA});
        app.pm.owners.put(UNKNOWN_UID, new String[]{"fixture.untrusted"});
        Binder.uid = WA_UID; Binder.pid = 77;
        preferences = (FixturePreferences) app.getSharedPreferences("waex_prefs", 0);
        preferences.deferred = deferred;
        app.onCreate(); preferences.drain();
        provider = new HookProvider(); provider.fixtureAttach(app);
        if (!provider.onCreate()) throw new AssertionError("Provider did not attach");
        ProFeatureGate.entitled.clear(); LicenseManager.status = "INACTIVE";
        resetObservations();
    }

    static void resetObservations() {
        app.resolver.notifications.clear(); app.broadcasts.clear(); preferences.changed.clear();
        preferences.edits = 0; preferences.commits = 0;
        app.pm.queries.clear(); Binder.clearCount = 0; Binder.restoreCount = 0;
    }

    static void scenario(boolean deferred, String name, Scenario action) {
        scenarios++;
        try { prepare(deferred); action.run(); }
        catch (Throwable error) {
            failures.add((deferred ? "deferred/" : "synchronous/") + name + ": "
                    + error.getClass().getSimpleName() + ": " + error.getMessage());
        }
    }

    static int notifications(String suffix) {
        int count = 0;
        for (String uri : app.resolver.notifications) if ((URI + suffix).equals(uri)) count++;
        return count;
    }

    static int broadcasts(String key) {
        int count = 0;
        for (Intent intent : app.broadcasts) {
            if (ACTION.equals(intent.action) && java.util.Objects.equals(key, intent.extras.get("changed_key"))) count++;
        }
        return count;
    }

    static Bundle report(String method, String pkg) {
        Bundle extras = new Bundle(); extras.putString("package", pkg);
        extras.putInt("boot_count", 7); extras.putLong("timestamp", 1234567L);
        if ("report_runtime_health".equals(method)) {
            extras.putString("runtime_status", "READY"); extras.putString("bridge_status", "PROVIDER");
            extras.putString("error", "  fixture\ntrace\rmessage  ");
        }
        if ("register_injected_hooks".equals(method)) {
            extras.putString("hooks_json", "[{\"name\":\"fixture\",\"active\":true}]");
        }
        return extras;
    }

    static Bundle invoke(String method, String pkg) {
        Bundle result = provider.call(method, pkg, report(method, pkg));
        preferences.drain();
        return result;
    }

    static void noConfigurationWakeup() {
        check(notifications("preferences") == 0,
                "runtime metadata notified hook configuration " + notifications("preferences") + " time(s)");
        check(app.broadcasts.isEmpty(), "runtime metadata emitted configuration broadcasts");
        check(!preferences.contains("need_restart") && !preferences.contains("pending_restart_changes"),
                "runtime metadata created a restart requirement");
    }

    static Bundle booleanWrite(String key, boolean value) {
        Bundle operation = new Bundle(); operation.putString("key", key);
        operation.putString("type", "boolean"); operation.putBoolean("value", value);
        return operation;
    }

    static void expectIdentityRestored(int uid, int pid) {
        check(Binder.uid == uid && Binder.pid == pid, "Binder calling identity not restored");
        check(Binder.clearCount == Binder.restoreCount, "Binder clear/restore imbalance");
    }

    public static void main(String[] configuredScope) {
        WhatsAppPackageDetector.supported.addAll(Arrays.asList(configuredScope));
        check(WhatsAppPackageDetector.supported.contains(WA) && WhatsAppPackageDetector.supported.contains(BUSINESS),
                "Expected test targets are absent from configured hook scope");

        // Exercise the actual classifier against every real public setting, including
        // operational switches whose schema sensitivity is RUNTIME.
        for (PreferenceSchema.Entry entry : PreferenceSchema.all().values()) {
            if (entry.store == PreferenceSchema.Store.PUBLIC
                    && entry.sensitivity == PreferenceSchema.Sensitivity.PUBLIC_SETTING) {
                check(PreferenceSyncPolicy.shouldNotifyHooks(entry.key), "public setting filtered: " + entry.key);
            }
        }
        for (String key : new String[]{"ghostmode_active", "freeze_last_seen_active", "css_safe_mode",
                "css_last_valid", "waex_pro_active", "pro_entitlement_audio_to_voice_status",
                "pro_entitlement_status_splitter", "pro_entitlement_file_size_spoofer", "future_feature_option", ""}) {
            check(PreferenceSyncPolicy.shouldNotifyHooks(key), "operational/configuration key filtered: " + key);
        }
        check(PreferenceSyncPolicy.shouldNotifyHooks(null), "whole-store clear filtered");

        for (boolean deferred : new boolean[]{false, true}) {
            for (String method : new String[]{"register_hooked_package", "report_runtime_health", "register_injected_hooks"}) {
                scenario(deferred, method + "/telemetry", () -> {
                    Bundle result = invoke(method, WA);
                    check(result != null && result.getBoolean("success"), "valid telemetry was rejected");
                    check(preferences.getStringSet("hooked_whatsapp_packages", Set.of()).contains(WA), "registered target missing");
                    check(preferences.getInt("runtime_pid_" + WA, 0) == 77, "module PID substituted for Binder caller PID");
                    check(notifications("runtime_health") == 1, "runtime UI was not notified exactly once");
                    if (method.equals("register_injected_hooks")) {
                        check(notifications("injected_hooks") == 1 && preferences.contains("injected_hooks_" + WA),
                                "injected hook diagnostics not persisted/notified");
                    }
                    if (method.equals("report_runtime_health")) {
                        check("fixture trace message".equals(preferences.getString("runtime_error_" + WA, "")),
                                "runtime error normalization changed");
                    }
                    System.out.println("METRIC " + (deferred ? "deferred/" : "synchronous/") + method
                            + " changed_keys=" + preferences.changed.size() + " config_observers=" + notifications("preferences")
                            + " config_broadcasts=" + app.broadcasts.size() + " runtime_observers=" + notifications("runtime_health"));
                    noConfigurationWakeup();
                    expectIdentityRestored(WA_UID, 77);
                });
                scenario(deferred, method + "/disk-failure", () -> {
                    preferences.nextCommit = false;
                    Bundle result = invoke(method, WA);
                    check(preferences.contains("runtime_pid_" + WA), "fixture did not model memory update before failed disk persistence");
                    check(result != null && !result.getBoolean("success"), "failed telemetry commit reported success");
                    check(notifications("runtime_health") == 0 && notifications("injected_hooks") == 0,
                            "failed persistence advertised refreshed diagnostics");
                    noConfigurationWakeup();
                    expectIdentityRestored(WA_UID, 77);
                });
                scenario(deferred, method + "/wrong-package-owner", () -> {
                    Bundle result = invoke(method, BUSINESS);
                    check(result == null || !result.getBoolean("success"), "trusted target claimed another package");
                    check(preferences.edits == 0 && app.resolver.notifications.isEmpty() && app.broadcasts.isEmpty(),
                            "wrong package owner caused a write/notification");
                    expectIdentityRestored(WA_UID, 77);
                });
                scenario(deferred, method + "/work-profile-uid", () -> {
                    Binder.uid = WORK_UID; Binder.pid = 99;
                    Bundle result = invoke(method, WA);
                    check(result != null && result.getBoolean("success"), "full work-profile UID owner rejected");
                    check(preferences.getInt("runtime_pid_" + WA, 0) == 99, "work-profile caller PID lost");
                    check(app.pm.queries.contains(WORK_UID), "UID was reduced to appId for authorization");
                    noConfigurationWakeup();
                    expectIdentityRestored(WORK_UID, 99);
                });
            }
            for (String method : new String[]{"register_hooked_package", "report_runtime_health", "register_injected_hooks",
                    "get_all_preferences", "put_preference", "apply_preferences"}) {
                scenario(deferred, method + "/untrusted-binder", () -> {
                    Binder.uid = UNKNOWN_UID; Binder.pid = 123;
                    boolean denied = false;
                    try { provider.call(method, WA, report(method, WA)); }
                    catch (SecurityException expected) { denied = true; }
                    preferences.drain();
                    check(denied, "untrusted Binder caller was accepted");
                    check(Binder.clearCount == 0 && Binder.restoreCount == 0, "identity cleared before authorization");
                    check(preferences.edits == 0 && app.resolver.notifications.isEmpty() && app.broadcasts.isEmpty(),
                            "untrusted caller reached persistence or notification");
                    check(Binder.uid == UNKNOWN_UID && Binder.pid == 123, "denied caller identity changed");
                });
            }
            for (String metadata : new String[]{"hooked_whatsapp_packages", "active_xposed_api_version", "active_xposed_framework",
                    "injected_hooks_" + WA, "injected_hooks_time_" + WA, "last_active_" + WA,
                    "runtime_boot_" + WA, "runtime_pid_" + WA, "runtime_status_" + WA,
                    "runtime_error_" + WA, "bridge_status_" + WA}) {
                scenario(deferred, "listener-metadata/" + metadata, () -> {
                    // Listener receives the same key for an update and removal; test both.
                    preferences.notifyKey(metadata); preferences.drain();
                    noConfigurationWakeup();
                    preferences.notifyKey(metadata); preferences.drain();
                    noConfigurationWakeup();
                });
            }
            scenario(deferred, "repeated-heartbeats", () -> {
                for (int i = 0; i < 100; i++) {
                    Bundle extras = report("report_runtime_health", WA);
                    extras.putLong("timestamp", 1234567L + i);
                    check(provider.call("report_runtime_health", WA, extras).getBoolean("success"), "heartbeat rejected");
                }
                preferences.drain();
                check(notifications("runtime_health") == 100, "runtime updates lost");
                noConfigurationWakeup();
                expectIdentityRestored(WA_UID, 77);
            });
            for (String key : new String[]{"hide_read_receipts", "ghostmode_active", "waex_pro_active",
                    "pro_entitlement_audio_to_voice_status", "css_safe_mode", "future_feature_option"}) {
                scenario(deferred, "listener-configuration/" + key, () -> {
                    preferences.edit().putBoolean(key, true).commit(); preferences.drain();
                    check(notifications("preferences") >= 1, "configuration observer not notified");
                    check(broadcasts(key) == WhatsAppPackageDetector.supported.size(), "configuration not sent once to each supported target");
                    for (Intent intent : app.broadcasts) {
                        check(intent.target != null && ACTION.equals(intent.action), "configuration broadcast was not package-scoped");
                    }
                });
            }
            scenario(deferred, "generic-valid-preference-write", () -> {
                Bundle result = provider.call("put_preference", null, booleanWrite("hide_read_receipts", true));
                preferences.drain();
                check(result.getBoolean("success") && preferences.getBoolean("hide_read_receipts", false), "valid preference write failed");
                check(notifications("preferences") >= 1 && broadcasts("hide_read_receipts") > 0, "valid preference refresh missing");
                check(preferences.getBoolean("need_restart", false), "real restart tracker no longer runs for configuration");
                expectIdentityRestored(WA_UID, 77);
            });
            scenario(deferred, "generic-write-disk-failure-keeps-memory-signal", () -> {
                preferences.nextCommit = false;
                Bundle result = provider.call("put_preference", null, booleanWrite("fixture_future_flag", true));
                preferences.drain();
                check(!result.getBoolean("success"), "generic failed commit reported success");
                check(preferences.getBoolean("fixture_future_flag", false), "failed commit fixture did not preserve memory update");
                check(notifications("preferences") == 1, "provider explicitly advertised failed commit or memory listener disappeared");
                expectIdentityRestored(WA_UID, 77);
            });
            scenario(deferred, "null-clear-keeps-configuration-refresh", () -> {
                preferences.edit().clear().commit(); preferences.drain();
                check(notifications("preferences") == 1, "whole-store clear did not notify configuration");
                check(broadcasts(null) == WhatsAppPackageDetector.supported.size(), "whole-store clear lost target refresh");
            });
            scenario(deferred, "privileged-clear-policy", () -> {
                Bundle clear = new Bundle(); clear.putBoolean("clear", true);
                clear.putParcelableArrayList("operations", new ArrayList<>());
                Bundle denied = provider.call("apply_preferences", null, clear); preferences.drain();
                check(!denied.getBoolean("success") && preferences.edits == 0, "scoped host cleared module preferences");
                Binder.uid = MODULE_UID;
                Bundle accepted = provider.call("apply_preferences", null, clear); preferences.drain();
                check(accepted.getBoolean("success") && notifications("preferences") >= 1, "module clear lost configuration refresh");
                expectIdentityRestored(MODULE_UID, 77);
            });
            scenario(deferred, "derived-key-and-schema-write-guards", () -> {
                for (String key : new String[]{"waex_pro_active", "pro_entitlement_audio_to_voice_status", "runtime_status_" + WA}) {
                    Bundle result = provider.call("put_preference", null, booleanWrite(key, true));
                    check(!result.getBoolean("success"), "generic write accepted provider-derived key");
                }
                Bundle wrongType = new Bundle(); wrongType.putString("key", "hide_read_receipts");
                wrongType.putString("type", "string"); wrongType.putString("value", "true");
                check(!provider.call("put_preference", null, wrongType).getBoolean("success"), "known boolean accepted string write");
                preferences.drain();
                check(preferences.edits == 0 && app.resolver.notifications.isEmpty(), "invalid writes reached editor/notifications");
            });
            scenario(deferred, "entitlement-enforcement-and-refresh", () -> {
                Bundle denied = provider.call("put_preference", null, booleanWrite(ProFeatureGate.AUDIO_TO_VOICE_STATUS_PREF, true));
                check(!denied.getBoolean("success") && preferences.edits == 0, "unentitled feature write accepted");
                ProFeatureGate.entitled.add(ProFeatureGate.AUDIO_TO_VOICE_STATUS);
                Bundle accepted = provider.call("put_preference", null, booleanWrite(ProFeatureGate.AUDIO_TO_VOICE_STATUS_PREF, true));
                preferences.drain();
                check(accepted.getBoolean("success") && broadcasts(ProFeatureGate.AUDIO_TO_VOICE_STATUS_PREF) > 0,
                        "entitled setting change lost refresh");
                ProFeatureGate.entitled.clear();
                Bundle values = provider.call("get_all_preferences", null, null).getBundle(HookProvider.KEY_VALUES);
                check(!values.getBoolean(ProFeatureGate.AUDIO_TO_VOICE_STATUS_ENTITLEMENT)
                        && !values.getBoolean(ProFeatureGate.AUDIO_TO_VOICE_STATUS_PREF), "provider stopped enforcing live entitlement");
                check(!values.getBoolean("waex_pro_active"), "provider invented active license status");
            });
            scenario(deferred, "invalid-runtime-status-rejected", () -> {
                Bundle extras = report("report_runtime_health", WA); extras.putString("runtime_status", "FAKE_READY");
                check(provider.call("report_runtime_health", WA, extras) == null, "invalid runtime status accepted");
                check(preferences.edits == 0 && app.resolver.notifications.isEmpty(), "invalid status caused side effects");
                expectIdentityRestored(WA_UID, 77);
            });
        }
        for (String failure : failures) System.out.println("FAIL " + failure);
        System.out.println("Preference sync runtime: " + scenarios + " scenarios, " + checks
                + " checks, " + failures.size() + " failed scenarios; complete WaexApp/HookProvider and real configuration/authorization policies.");
        if (!failures.isEmpty()) throw new AssertionError("Preference sync invariant failures: " + failures.size());
    }
}
