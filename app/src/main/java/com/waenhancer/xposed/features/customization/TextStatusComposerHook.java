package com.waenhancer.xposed.features.customization;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import androidx.annotation.NonNull;
import com.waenhancer.xposed.core.BaseFeature;
import com.waenhancer.xposed.core.devkit.DexSearchEngine;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedHashSet;
import java.util.Set;
import org.luckypray.dexkit.query.FindClass;
import org.luckypray.dexkit.query.enums.StringMatchType;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.result.ClassData;

/** Adds long-press custom colors to both legacy and V2 text-status composers. */
public final class TextStatusComposerHook extends BaseFeature {
    private static final int[] COLORS = {
            Color.WHITE, Color.BLACK, 0xff00a884, 0xff1f7aec, 0xff7c4dff,
            0xffe91e63, 0xffff5722, 0xffffc107, 0xff4caf50, 0xff607d8b
    };
    private static final String[] COLOR_NAMES = {
            "White", "Black", "Teal", "Blue", "Purple", "Pink", "Orange", "Amber", "Green", "Slate"
    };
    private volatile Integer customTextColor;
    private volatile Integer customBackgroundColor;

    public TextStatusComposerHook(@NonNull Context context, @NonNull ClassLoader classLoader,
                                  @NonNull SharedPreferences prefs) {
        super(context, classLoader, prefs);
    }

    @NonNull @Override public String getName() { return "Custom colors for text status"; }
    @NonNull @Override public String getCategory() { return "Customization"; }

    @Override public void hook() {
        Set<Class<?>> fragments = new LinkedHashSet<>();
        addIfPresent(fragments, "com.whatsapp.textstatuscomposer.TextStatusComposerFragment");
        addIfPresent(fragments, "com.whatsapp.status.composer.TextStatusComposerFragment");
        Class<?> resolved = DexSearchEngine.getInstance().findClassWithCache(
                context, classLoader, "waex_text_status_composer_fragment_v2", (bridge, loader) -> {
                    ClassData data = bridge.findClass(FindClass.create().matcher(
                            ClassMatcher.create().className(
                                    "TextStatusComposerFragment", StringMatchType.EndsWith))).firstOrNull();
                    return data == null ? null : data.getInstance(loader);
                });
        if (resolved != null) fragments.add(resolved);
        for (Class<?> fragment : fragments) hookFragment(fragment);
        hookTextDataConstruction();
    }

    private void addIfPresent(Set<Class<?>> fragments, String name) {
        Class<?> fragment = XposedHelpers.findClassIfExists(name, classLoader);
        if (fragment != null) fragments.add(fragment);
    }

    private void hookFragment(Class<?> fragment) {
        for (Method method : fragment.getDeclaredMethods()) {
            Class<?>[] types = method.getParameterTypes();
            if (!isComposerSignature(types)) continue;
            method.setAccessible(true);
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    View root = null;
                    for (Object argument : param.args) if (argument instanceof View) root = (View) argument;
                    if (root != null) configureComposer(root);
                }
            });
        }
    }

    static boolean isComposerSignature(Class<?>[] types) {
        if (types == null) return false;
        String[] names = new String[types.length];
        for (int i = 0; i < types.length; i++) names[i] = types[i].getName();
        return ComposerSignaturePolicy.accepts(names);
    }

    private void configureComposer(View root) {
        customTextColor = null;
        customBackgroundColor = null;
        Activity activity = activity(root.getContext());
        if (activity == null) return;
        int backgroundButtonId = root.getResources().getIdentifier("color_picker_btn", "id", root.getContext().getPackageName());
        int textButtonId = root.getResources().getIdentifier("font_picker_btn", "id", root.getContext().getPackageName());
        int entryId = root.getResources().getIdentifier("entry", "id", root.getContext().getPackageName());
        View backgroundButton = root.findViewById(backgroundButtonId);
        View textButton = root.findViewById(textButtonId);
        EditText entry = root.findViewById(entryId);
        if (backgroundButton != null) backgroundButton.setOnLongClickListener(v -> {
            showPicker(activity, color -> {
                customBackgroundColor = color;
                activity.getWindow().setBackgroundDrawable(new ColorDrawable(color));
                setBackground(root, "background", color);
                setBackground(root, "controls", color);
            });
            return true;
        });
        if (textButton != null) textButton.setOnLongClickListener(v -> {
            showPicker(activity, color -> {
                customTextColor = color;
                if (entry != null) entry.setTextColor(color);
            });
            return true;
        });
    }

    private void hookTextDataConstruction() {
        Class<?> textData = DexSearchEngine.getInstance().findClassWithCache(
                context, classLoader, "waex_text_status_data_class_v2", (bridge, loader) -> {
                    ClassData data = bridge.findClass(FindClass.create().matcher(
                            ClassMatcher.create().addUsingString("TextData;"))).firstOrNull();
                    return data == null ? null : data.getInstance(loader);
                });
        if (textData == null) return;
        for (Constructor<?> constructor : textData.getDeclaredConstructors()) {
            XposedBridge.hookMethod(constructor, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    applyColors(param.thisObject);
                }
            });
        }
    }

    private void applyColors(Object data) {
        setField(data, "textColor", customTextColor);
        setField(data, "backgroundColor", customBackgroundColor);
        if (customBackgroundColor != null) setField(data, "backgroundColorHasChanged", true);
    }

    private static void setField(Object target, String name, Object value) {
        if (target == null || value == null) return;
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (Throwable ignored) {}
    }

    private static void setBackground(View root, String resourceName, int color) {
        int id = root.getResources().getIdentifier(resourceName, "id", root.getContext().getPackageName());
        View target = root.findViewById(id);
        if (target != null) target.setBackgroundColor(color);
    }

    private interface ColorConsumer { void accept(int color); }
    private static void showPicker(Activity activity, ColorConsumer consumer) {
        new AlertDialog.Builder(activity).setTitle("Choose color")
                .setItems(COLOR_NAMES, (dialog, which) -> consumer.accept(COLORS[which]))
                .setNegativeButton(android.R.string.cancel, null).show();
    }

    private static Activity activity(Context context) {
        while (context instanceof ContextWrapper) {
            if (context instanceof Activity) return (Activity) context;
            context = ((ContextWrapper) context).getBaseContext();
        }
        return context instanceof Activity ? (Activity) context : null;
    }
}
