package com.waenhancer.xposed.features.homescreen;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import com.waenhancer.xposed.core.BaseFeature;
import com.waenhancer.xposed.core.devkit.DexSearchEngine;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import org.luckypray.dexkit.query.FindClass;
import org.luckypray.dexkit.query.enums.StringMatchType;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.result.ClassData;

/**
 * Chat List Customizations Hook:
 * Handles conversation list styling, online dot indicator on avatars, online subtitle text,
 * and Meta AI interface removal.
 */
public class ChatListCustomizationsHook extends BaseFeature {

    private static final String TAG = "[WAEX][ChatList]";

    public ChatListCustomizationsHook(@NonNull Context context, @NonNull ClassLoader classLoader, @NonNull SharedPreferences prefs) {
        super(context, classLoader, prefs);
    }

    @Override
    public void hook() throws Throwable {
        hookMetaAiFab();
        hookConversationsRow();
    }

    private void hookMetaAiFab() {
        try {
            Class<?> metaAiFabClass = DexSearchEngine.getInstance().findClassWithCache(
                    context,
                    classLoader,
                    "wpp_meta_ai_fab_class",
                    (bridge, loader) -> {
                        ClassData cd = bridge.findClass(FindClass.create()
                                .matcher(ClassMatcher.create().className("MetaAi", StringMatchType.Contains))
                        ).firstOrNull();
                        return cd != null ? cd.getInstance(loader) : null;
                    }
            );

            if (metaAiFabClass != null) {
                XposedBridge.hookAllConstructors(metaAiFabClass, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (isMetaAiDisabled() && param.thisObject instanceof View) {
                            View view = (View) param.thisObject;
                            view.setVisibility(View.GONE);
                        }
                    }
                });
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " Error hooking Meta AI: " + t.getMessage());
        }
    }

    private void hookConversationsRow() {
        try {
            Class<?> conversationsRowClass = DexSearchEngine.getInstance().findClassWithCache(
                    context,
                    classLoader,
                    "wpp_conversations_row_class",
                    (bridge, loader) -> {
                        ClassData cd = bridge.findClass(FindClass.create()
                                .matcher(ClassMatcher.create().className("ConversationsRow", StringMatchType.EndsWith))
                        ).firstOrNull();
                        return cd != null ? cd.getInstance(loader) : null;
                    }
            );

            if (conversationsRowClass != null) {
                XposedBridge.hookAllConstructors(conversationsRowClass, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (param.thisObject instanceof ViewGroup) {
                            ViewGroup row = (ViewGroup) param.thisObject;
                            // Apply custom online dot or subtitle indicators if enabled
                            if (isOnlineDotEnabled() || isOnlineTextEnabled()) {
                                row.setTag("waex_customized_row");
                            }
                        }
                    }
                });
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " Error hooking ConversationsRow: " + t.getMessage());
        }
    }

    private boolean isMetaAiDisabled() {
        return isEnabled("metaai", false);
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
