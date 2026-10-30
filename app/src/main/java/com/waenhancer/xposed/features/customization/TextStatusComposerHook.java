package com.waenhancer.xposed.features.customization;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import androidx.annotation.NonNull;
import com.waenhancer.xposed.core.BaseFeature;
import com.waenhancer.xposed.core.components.NativeColorPickerDialog;
import com.waenhancer.xposed.core.devkit.DexSearchEngine;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.LinkedHashSet;
import java.util.Set;
import org.luckypray.dexkit.query.FindClass;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.enums.StringMatchType;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.ClassData;
import org.luckypray.dexkit.result.MethodData;
import org.luckypray.dexkit.result.MethodDataList;

/** Adds long-press custom colors to both legacy and V2 text-status composers. */
public final class TextStatusComposerHook extends BaseFeature {
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
            int mod = method.getModifiers();
            if (Modifier.isAbstract(mod) || Modifier.isNative(mod)) continue;
            Class<?>[] types = method.getParameterTypes();
            if (!isComposerSignature(types) && !isCreateViewSignature(method, types)) continue;
            method.setAccessible(true);
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    View root = param.getResult() instanceof View ? (View) param.getResult() : null;
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

    private static boolean isCreateViewSignature(Method method, Class<?>[] types) {
        String[] names = new String[types.length];
        for (int i = 0; i < types.length; i++) names[i] = types[i].getName();
        return ComposerSignaturePolicy.acceptsCreateView(
                method.getName(), method.getReturnType().getName(), names);
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
            NativeColorPickerDialog.show(activity,
                    customBackgroundColor != null ? customBackgroundColor : 0xff00a884, color -> {
                customBackgroundColor = color;
                activity.getWindow().setBackgroundDrawable(new ColorDrawable(color));
                setBackground(root, "background", color);
                setBackground(root, "controls", color);
            });
            return true;
        });
        if (textButton != null) textButton.setOnLongClickListener(v -> {
            NativeColorPickerDialog.show(activity,
                    customTextColor != null ? customTextColor : 0xffffffff, color -> {
                customTextColor = color;
                if (entry != null) entry.setTextColor(color);
            });
            return true;
        });
    }

    private void hookTextDataConstruction() {
        Class<?> textData = DexSearchEngine.getInstance().findClassWithCache(
                context, classLoader, "waex_text_status_data_class_v3", (bridge, loader) -> {
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

        Method[] consumers = DexSearchEngine.getInstance().findMethodsWithCache(
                context, classLoader, "waex_text_status_data_consumers_v3", (bridge, loader) -> {
                    MethodDataList matches = bridge.findMethod(FindMethod.create().matcher(
                            MethodMatcher.create().addParamType(textData.getName())));
                    java.util.ArrayList<Method> methods = new java.util.ArrayList<>();
                    for (MethodData match : matches) {
                        if (match.isMethod()) {
                            Method m = match.getMethodInstance(loader);
                            int mod = m.getModifiers();
                            if (!Modifier.isAbstract(mod) && !Modifier.isNative(mod)) {
                                methods.add(m);
                            }
                        }
                    }
                    return methods.toArray(new Method[0]);
                });
        if (consumers != null) {
            for (Method consumer : consumers) {
                int mod = consumer.getModifiers();
                if (Modifier.isAbstract(mod) || Modifier.isNative(mod)) continue;
                XposedBridge.hookMethod(consumer, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        for (Object argument : param.args) {
                            if (textData.isInstance(argument)) applyColors(argument);
                        }
                    }
                });
            }
        }

        Class<?>[] constructorOwners = DexSearchEngine.getInstance().findClassesWithCache(
                context, classLoader, "waex_text_status_data_constructor_owners_v3", (bridge, loader) -> {
                    MethodDataList matches = bridge.findMethod(FindMethod.create().matcher(
                            MethodMatcher.create().name("<init>").addParamType(textData.getName())));
                    LinkedHashSet<Class<?>> owners = new LinkedHashSet<>();
                    for (MethodData match : matches) {
                        if (match.isConstructor()) {
                            owners.add(match.getConstructorInstance(loader).getDeclaringClass());
                        }
                    }
                    return owners.toArray(new Class<?>[0]);
                });
        if (constructorOwners != null) {
            for (Class<?> owner : constructorOwners) {
                for (Constructor<?> constructor : owner.getDeclaredConstructors()) {
                    boolean acceptsTextData = false;
                    for (Class<?> parameter : constructor.getParameterTypes()) {
                        if (parameter == textData) {
                            acceptsTextData = true;
                            break;
                        }
                    }
                    if (!acceptsTextData) continue;
                    XposedBridge.hookMethod(constructor, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            for (Object argument : param.args) {
                                if (textData.isInstance(argument)) applyColors(argument);
                            }
                        }
                    });
                }
            }
        }

        Method[] producers = DexSearchEngine.getInstance().findMethodsWithCache(
                context, classLoader, "waex_text_status_data_producers_v3", (bridge, loader) -> {
                    MethodDataList matches = bridge.findMethod(FindMethod.create().matcher(
                            MethodMatcher.create().returnType(textData.getName())));
                    java.util.ArrayList<Method> methods = new java.util.ArrayList<>();
                    for (MethodData match : matches) {
                        if (match.isMethod()) {
                            Method m = match.getMethodInstance(loader);
                            int mod = m.getModifiers();
                            if (!Modifier.isAbstract(mod) && !Modifier.isNative(mod)) {
                                methods.add(m);
                            }
                        }
                    }
                    return methods.toArray(new Method[0]);
                });
        if (producers != null) {
            for (Method producer : producers) {
                int mod = producer.getModifiers();
                if (Modifier.isAbstract(mod) || Modifier.isNative(mod)) continue;
                XposedBridge.hookMethod(producer, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        if (textData.isInstance(param.getResult())) applyColors(param.getResult());
                    }
                });
            }
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

    private static Activity activity(Context context) {
        while (context instanceof ContextWrapper) {
            if (context instanceof Activity) return (Activity) context;
            context = ((ContextWrapper) context).getBaseContext();
        }
        return context instanceof Activity ? (Activity) context : null;
    }
}
