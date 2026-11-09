package com.waenhancer.xposed.features.homescreen;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import androidx.annotation.NonNull;
import com.waenhancer.xposed.core.BaseFeature;
import com.waenhancer.xposed.core.devkit.DexSearchEngine;
import com.waenhancer.xposed.utils.ActivityTracker;
import de.robv.android.xposed.XC_MethodHook;
import java.util.ArrayDeque;
import java.util.Locale;
import org.luckypray.dexkit.query.FindClass;
import org.luckypray.dexkit.query.enums.StringMatchType;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.result.ClassData;

/** Applies chat-list presentation controls, including complete assistant UI removal. */
public class ChatListCustomizationsHook extends BaseFeature {

    private static final String TAG = "[WAEX][ChatList]";
    private static final String HOST_PREFERENCE = "bonsai_meta_ai_button_setting_enabled";

    public ChatListCustomizationsHook(@NonNull Context context, @NonNull ClassLoader classLoader,
                                      @NonNull SharedPreferences prefs) {
        super(context, classLoader, prefs);
    }

    @Override
    public void hook() {
        if (isMetaAiDisabled()) {
            hookHostPreference();
            hookAttachedViews();
            hookActivityRefresh();
        }
        hookConversationsRow();
    }

    private void hookHostPreference() {
        try {
            Class<?> implementation = XposedHelpers.findClassIfExists(
                    "android.app.SharedPreferencesImpl", classLoader);
            if (implementation == null) return;
            XposedBridge.hookAllMethods(implementation, "getBoolean", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args != null && param.args.length > 0
                            && HOST_PREFERENCE.equals(param.args[0])
                            && shouldDisableHostConfiguration()) {
                        param.setResult(false);
                    }
                }
            });
        } catch (Throwable error) {
            XposedBridge.log(TAG + " Meta AI preference hook failed: "
                    + error.getClass().getSimpleName());
        }
    }

    private void hookAttachedViews() {
        try {
            XposedHelpers.findAndHookMethod(View.class, "onAttachedToWindow", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Activity activity = ActivityTracker.getCurrentActivity();
                    if (activity == null || !activity.getClass().getSimpleName().contains("Home")) return;
                    View view = (View) param.thisObject;
                    if (shouldRemoveView(view)) removeView(view);
                }
            });
        } catch (Throwable error) {
            XposedBridge.log(TAG + " Meta AI attachment hook failed: "
                    + error.getClass().getSimpleName());
        }
    }

    private void hookActivityRefresh() {
        try {
            XposedHelpers.findAndHookMethod(Activity.class, "onPostResume", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Activity activity = (Activity) param.thisObject;
                    if (shouldBlockAssistantChat() && isAssistantConversation(activity.getIntent())) {
                        activity.finish();
                        return;
                    }
                    View decor = activity.getWindow() != null
                            ? activity.getWindow().getDecorView() : null;
                    if (decor != null) decor.post(() -> removeAssistantViews(decor));
                }
            });

            XposedHelpers.findAndHookMethod(Activity.class, "onNewIntent", Intent.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (param.args != null && param.args.length == 1
                                    && param.args[0] instanceof Intent
                                    && shouldBlockAssistantChat()
                                    && isAssistantConversation((Intent) param.args[0])) {
                                ((Activity) param.thisObject).finish();
                            }
                        }
                    });
        } catch (Throwable error) {
            XposedBridge.log(TAG + " Meta AI activity hook failed: "
                    + error.getClass().getSimpleName());
        }
    }

    private static boolean isAssistantConversation(Intent intent) {
        if (intent == null) return false;
        Bundle extras = intent.getExtras();
        if (extras != null && (extras.containsKey("bot_metrics_entrypoint")
                || extras.containsKey("extra_presentation_source"))) return true;
        String jid = intent.getStringExtra("jid");
        if (jid == null) return false;
        String normalized = jid.toLowerCase(Locale.ROOT);
        return normalized.contains("1313555") || normalized.contains("meta_ai");
    }

    private void removeAssistantViews(View root) {
        ArrayDeque<View> pending = new ArrayDeque<>();
        pending.add(root);
        while (!pending.isEmpty()) {
            View view = pending.removeFirst();
            if (shouldRemoveView(view)) {
                removeView(view);
                continue;
            }
            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                for (int index = 0; index < group.getChildCount(); index++) {
                    pending.addLast(group.getChildAt(index));
                }
            }
        }
    }

    private boolean shouldRemoveView(View view) {
        boolean fab = isAssistantFab(view);
        return (shouldRemoveHomeFab() && fab)
                || (shouldRemoveEntryPoints() && !fab && isAssistantView(view));
    }

    private static boolean isAssistantFab(View view) {
        if (view == null || view.getId() == View.NO_ID) return false;
        try {
            String entry = view.getResources().getResourceEntryName(view.getId());
            return "fab_second".equals(entry) || "meta_ai_fab".equals(entry)
                    || "meta_ai_button".equals(entry);
        } catch (Resources.NotFoundException ignored) {
            return false;
        }
    }

    private static boolean isAssistantView(View view) {
        if (view == null) return false;
        int id = view.getId();
        if (id != View.NO_ID) {
            try {
                String entry = view.getResources().getResourceEntryName(id);
                if (isAssistantViewId(entry)) return true;
            } catch (Resources.NotFoundException ignored) {
            }
        }
        CharSequence description = view.getContentDescription();
        if (description == null) return false;
        String text = description.toString().trim().toLowerCase(Locale.ROOT);
        return text.contains("meta ai") || text.contains("ask meta")
                || text.contains("message your assistant");
    }

    private static boolean isAssistantViewId(String entry) {
        if (entry == null) return false;
        switch (entry) {
            case "fab_second":
            case "meta_ai_button":
            case "meta_ai_fab":
            case "meta_ai_entry_point":
            case "meta_ai_search_button":
                return true;
            default:
                return entry.contains("meta_ai");
        }
    }

    private static void removeView(View view) {
        view.setVisibility(View.GONE);
        ViewGroup.LayoutParams params = view.getLayoutParams();
        if (params != null) {
            params.width = 0;
            params.height = 0;
            if (params instanceof ViewGroup.MarginLayoutParams) {
                ((ViewGroup.MarginLayoutParams) params).setMargins(0, 0, 0, 0);
            }
            view.setLayoutParams(params);
        }
        view.post(() -> {
            ViewParent parent = view.getParent();
            if (parent instanceof ViewGroup) {
                try {
                    ((ViewGroup) parent).removeView(view);
                } catch (Throwable ignored) {
                }
            }
        });
    }

    private void hookConversationsRow() {
        if (!isOnlineDotEnabled() && !isOnlineTextEnabled()) return;
        try {
            Class<?> rowClass = DexSearchEngine.getInstance().findClassWithCache(
                    context,
                    classLoader,
                    "waex_conversations_row_class",
                    (bridge, loader) -> {
                        ClassData data = bridge.findClass(FindClass.create().matcher(
                                ClassMatcher.create().className(
                                        "ConversationsRow", StringMatchType.EndsWith)))
                                .firstOrNull();
                        return data != null ? data.getInstance(loader) : null;
                    });
            if (rowClass == null) return;
            XposedBridge.hookAllConstructors(rowClass, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (param.thisObject instanceof ViewGroup) {
                        ((ViewGroup) param.thisObject).setTag("waex_customized_row");
                    }
                }
            });
        } catch (Throwable error) {
            XposedBridge.log(TAG + " conversation row hook failed: "
                    + error.getClass().getSimpleName());
        }
    }

    private boolean isMetaAiDisabled() {
        return isEnabled("metaai", false);
    }

    private boolean shouldRemoveHomeFab() {
        return prefs.getBoolean("metaai_remove_home_fab", true);
    }

    private boolean shouldRemoveEntryPoints() {
        return prefs.getBoolean("metaai_remove_entry_points", true);
    }

    private boolean shouldBlockAssistantChat() {
        return prefs.getBoolean("metaai_block_chat", true);
    }

    private boolean shouldDisableHostConfiguration() {
        return shouldRemoveEntryPoints() && shouldBlockAssistantChat();
    }

    private boolean isOnlineDotEnabled() {
        return isEnabled("dotonline", false);
    }

    private boolean isOnlineTextEnabled() {
        return isEnabled("showonlinetext", false);
    }

    @NonNull
    @Override
    public String getName() {
        return "Chat List Customizations";
    }
}
