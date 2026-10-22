package com.waenhancer.xposed.utils;

import android.view.View;

/** A string anchor alone must not authorize hiding an arbitrary host object. */
public final class ViewClassSelector {
    private ViewClassSelector() {}

    public static Class<?> uniqueView(Iterable<Class<?>> candidates) {
        Class<?> target = null;
        for (Class<?> candidate : candidates) {
            if (candidate == null || !View.class.isAssignableFrom(candidate)) continue;
            if (target != null && target != candidate) {
                throw new IllegalStateException("View selector matched multiple distinct Views");
            }
            target = candidate;
        }
        return target;
    }
}
