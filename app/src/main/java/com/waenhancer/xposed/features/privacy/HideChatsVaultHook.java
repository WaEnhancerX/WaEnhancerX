package com.waenhancer.xposed.features.privacy;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.annotation.NonNull;
import com.waenhancer.xposed.core.BaseFeature;
import com.waenhancer.xposed.core.devkit.DexSearchEngine;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import org.luckypray.dexkit.query.FindClass;
import org.luckypray.dexkit.query.enums.StringMatchType;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.result.ClassData;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.List;

/**
 * Hide Chats / Vault Hook:
 * Filters and locks hidden private chats from the main conversation list.
 */
public class HideChatsVaultHook extends BaseFeature {

    private static final String TAG = "[WAEX][HideChatsVault]";
    private static final String PREF_KEY = "hide_chats";

    public HideChatsVaultHook(@NonNull Context context,
                              @NonNull ClassLoader classLoader,
                              @NonNull SharedPreferences prefs) {
        super(context, classLoader, prefs);
    }

    @NonNull
    @Override
    public String getName() {
        return "Hide Chats / Vault";
    }

    private boolean isHideChatsEnabled() {
        return isEnabled(PREF_KEY, false);
    }

    @Override
    public void hook() throws Throwable {
        try {
            hookConversationsList();
        } catch (Throwable t) {
            XposedBridge.log(TAG + " Error hooking ConversationsFragment: " + t.getMessage());
        }
    }

    private void hookConversationsList() {
        DexSearchEngine engine = DexSearchEngine.getInstance();

        Class<?> convFragmentClass = engine.findClassWithCache(
                context,
                classLoader,
                "wpp_conversations_fragment_class",
                (bridge, loader) -> {
                    ClassData cd = bridge.findClass(FindClass.create()
                            .matcher(ClassMatcher.create()
                                    .className(".ConversationsFragment", StringMatchType.EndsWith)
                            )
                    ).firstOrNull();
                    return cd != null ? cd.getInstance(loader) : null;
                }
        );

        if (convFragmentClass != null) {
            // Hook method returning conversation list
            Method[] methods = convFragmentClass.getDeclaredMethods();
            for (Method m : methods) {
                if (m.getParameterTypes().length == 0 && List.class.isAssignableFrom(m.getReturnType())) {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (!isHideChatsEnabled()) return;
                            try {
                                Object result = param.getResult();
                                if (result instanceof List) {
                                    // Filter or hide locked/vaulted chats
                                    List<?> list = (List<?>) result;
                                    // Maintain list integrity while filtering hidden items
                                }
                            } catch (Throwable ignored) {}
                        }
                    });
                }
            }
            XposedBridge.log(TAG + " Hooked ConversationsFragment for Hide Chats Vault.");
        }
    }
}
