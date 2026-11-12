package com.waenhancer.xposed.features.customization;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import androidx.annotation.NonNull;
import com.waenhancer.xposed.core.BaseFeature;
import com.waenhancer.xposed.core.ViewHookDispatcher;
import com.waenhancer.xposed.utils.HookErrors;
import com.waenhancer.xposed.utils.HostResourceIds;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Colors WhatsApp's own floating bottom navigation bar.
 *
 * The bar itself is enabled by the MobileConfig override that the feature registry applies
 * before WhatsApp reads its flags ({@link #FLOATING_NAVIGATION_PROP}). This hook only installs
 * the optional background and selection colors, and only when at least one is customized.
 */
public final class NativeFloatingNavigationHook extends BaseFeature {
    public static final String PREF_KEY = "native_floating_navigation";
    public static final String PREF_BACKGROUND_COLOR = "native_floating_navigation_bg_color";
    public static final String PREF_SELECTION_COLOR = "native_floating_navigation_selection_color";
    /** WhatsApp's MobileConfig flag that rolls out the native floating bottom bar. */
    public static final int FLOATING_NAVIGATION_PROP = 36795;

    private static final String CONTAINER_ID = "nav_phoenix_rounded_background_container";
    private static final String NAVIGATION_ID = "bottom_nav";

    private final Set<View> colored = Collections.newSetFromMap(new WeakHashMap<>());
    private volatile Method indicatorColorSetter;
    private volatile Method indicatorColorGetter;

    public NativeFloatingNavigationHook(@NonNull Context context, @NonNull ClassLoader classLoader,
                                        @NonNull SharedPreferences prefs) {
        super(context, classLoader, prefs);
    }

    /** The native bar replaces the module's own floating bar; never run both. */
    public static boolean isActive(@NonNull SharedPreferences prefs) {
        return prefs.getBoolean(PREF_KEY, false) && !prefs.getBoolean("floating_bottom_bar", false);
    }

    @NonNull
    @Override
    public String getName() {
        return "Native Floating Navigation";
    }

    @Override
    public void hook() throws Throwable {
        if (!isActive(prefs)) return;
        int backgroundColor = prefs.getInt(PREF_BACKGROUND_COLOR, 0);
        int selectionColor = prefs.getInt(PREF_SELECTION_COLOR, 0);
        if (backgroundColor == 0 && selectionColor == 0) return;

        int containerId = HostResourceIds.id(context, CONTAINER_ID);
        int navigationId = HostResourceIds.id(context, NAVIGATION_ID);
        ViewHookDispatcher.onAttached(this, view -> {
            int id = view.getId();
            // HostResourceIds returns 0 for ids this WhatsApp build does not define.
            if (id == View.NO_ID || id == 0) return;
            if (id == containerId && backgroundColor != 0) {
                keepApplied(view, () -> applyBackground(view, backgroundColor));
            } else if (id == navigationId && selectionColor != 0) {
                keepApplied(view, () -> applySelection(view, selectionColor));
            }
        });
    }

    private void keepApplied(View view, Runnable apply) {
        apply.run();
        synchronized (colored) {
            if (!colored.add(view)) return;
        }
        // WhatsApp re-themes the bar on tab and configuration changes.
        view.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> apply.run());
    }

    private static void applyBackground(View container, int color) {
        Drawable background = container.getBackground();
        if (!(background instanceof GradientDrawable)) return;
        GradientDrawable shape = (GradientDrawable) background;
        ColorStateList current = shape.getColor();
        if (current == null || current.getDefaultColor() != color) shape.setColor(color);
    }

    private void applySelection(View navigation, int color) {
        try {
            Method setter = indicatorColorSetter;
            if (setter == null) {
                setter = navigation.getClass().getMethod("setItemActiveIndicatorColor", ColorStateList.class);
                indicatorColorSetter = setter;
            }
            Method getter = indicatorColorGetter;
            if (getter == null) {
                getter = navigation.getClass().getMethod("getItemActiveIndicatorColor");
                indicatorColorGetter = getter;
            }
            Object current = getter.invoke(navigation);
            if (current instanceof ColorStateList && ((ColorStateList) current).getDefaultColor() == color) return;
            setter.invoke(navigation, ColorStateList.valueOf(color));
        } catch (Throwable error) {
            HookErrors.swallowed("NativeFloatingNavigationHook", error);
        }
    }
}
