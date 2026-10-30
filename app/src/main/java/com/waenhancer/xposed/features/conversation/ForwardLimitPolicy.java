package com.waenhancer.xposed.features.conversation;

import java.util.ArrayList;
import java.util.List;

/** Selects forwarding restriction arguments without relying on obfuscated constructor positions. */
final class ForwardLimitPolicy {
    private ForwardLimitPolicy() {}

    static void removeLimits(Class<?>[] parameterTypes, Object[] arguments) {
        if (parameterTypes == null || arguments == null) return;
        int count = Math.min(parameterTypes.length, arguments.length);
        List<Integer> plausibleLimits = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Class<?> type = parameterTypes[i];
            if (type == boolean.class || type == Boolean.class) {
                arguments[i] = false;
            } else if ((type == int.class || type == Integer.class) && arguments[i] instanceof Integer) {
                int value = (Integer) arguments[i];
                // Android resource IDs occupy the high byte; forwarding limits are small values.
                if (value >= 0 && (value >>> 24) == 0) plausibleLimits.add(i);
            }
        }
        for (int index : plausibleLimits) arguments[index] = Integer.MAX_VALUE;
    }
}
