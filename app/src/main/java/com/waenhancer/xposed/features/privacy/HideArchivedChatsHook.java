package com.waenhancer.xposed.features.privacy;

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
import java.lang.reflect.Field;

/**
 * Hide Archived Chats Hook:
 * Completely hides the Archived Chats section and header banner from the main conversation list.
 */
public class HideArchivedChatsHook extends BaseFeature {

    private static final String TAG = "[WAEX][HideArchivedChats]";
    private static final String PREF_KEY = "typearchive";

    public HideArchivedChatsHook(@NonNull Context context,
                                 @NonNull ClassLoader classLoader,
                                 @NonNull SharedPreferences prefs) {
        super(context, classLoader, prefs);
    }

    @NonNull
    @Override
    public String getName() {
        return "Hide Archived Chats";
    }

    private boolean isHideArchivedEnabled() {
        try {
            if (isEnabled(PREF_KEY, false)) return true;
            String val = prefs.getString(PREF_KEY, "0");
            return val != null && !val.equals("0") && !val.equalsIgnoreCase("false");
        } catch (Throwable ignored) {
            return false;
        }
    }

    @Override
    public void hook() throws Throwable {
        try {
            hookArchivePreviewView();
        } catch (Throwable t) {
            XposedBridge.log(TAG + " Error hooking ArchivePreviewView: " + t.getMessage());
        }
    }

    private void hookArchivePreviewView() {
        DexSearchEngine engine = DexSearchEngine.getInstance();

        Class<?> archiveClass = engine.findClassWithCache(
                context,
                classLoader,
                "wpp_archive_chat_view_class",
                (bridge, loader) -> {
                    ClassData cd = bridge.findClass(FindClass.create()
                            .matcher(ClassMatcher.create()
                                    .addUsingString("archive/set-content-indicator-to-empty", StringMatchType.Contains)
                            )
                    ).firstOrNull();

                    if (cd == null) {
                        cd = bridge.findClass(FindClass.create()
                                .matcher(ClassMatcher.create()
                                        .addUsingString("archive/Unsupported mode in ArchivePreviewView:", StringMatchType.Contains)
                                )
                        ).firstOrNull();
                    }

                    if (cd == null) {
                        cd = bridge.findClass(FindClass.create()
                                .matcher(ClassMatcher.create()
                                        .className("ArchivePreviewView", StringMatchType.Contains)
                                )
                        ).firstOrNull();
                    }

                    return cd != null ? cd.getInstance(loader) : null;
                }
        );

        if (archiveClass != null) {
            // Find View field within the archive container class
            Field viewField = null;
            for (Field f : archiveClass.getDeclaredFields()) {
                if (View.class.isAssignableFrom(f.getType())) {
                    viewField = f;
                    viewField.setAccessible(true);
                    break;
                }
            }

            final Field targetViewField = viewField;

            XposedBridge.hookAllConstructors(archiveClass, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (!isHideArchivedEnabled()) return;
                    try {
                        if (param.thisObject instanceof View) {
                            View view = (View) param.thisObject;
                            view.setVisibility(View.GONE);
                            if (view.getLayoutParams() != null) {
                                view.getLayoutParams().height = 0;
                            }
                        } else if (targetViewField != null) {
                            Object viewObj = targetViewField.get(param.thisObject);
                            if (viewObj instanceof View) {
                                View view = (View) viewObj;
                                view.setVisibility(View.GONE);
                                if (view.getLayoutParams() != null) {
                                    view.getLayoutParams().height = 0;
                                }
                            }
                        }
                    } catch (Throwable ignored) {}
                }
            });

            // Also hook setVisibility or onMeasure if applicable
            XposedBridge.hookAllMethods(archiveClass, "setVisibility", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (isHideArchivedEnabled()) {
                        param.args[0] = View.GONE;
                    }
                }
            });

            XposedBridge.log(TAG + " Hooked Archive banner class: " + archiveClass.getName());
        }
    }
}
