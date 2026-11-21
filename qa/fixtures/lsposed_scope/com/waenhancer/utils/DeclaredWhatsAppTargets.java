package com.waenhancer.utils;

import java.util.Set;

public final class DeclaredWhatsAppTargets {
    private DeclaredWhatsAppTargets() {}
    public static Set<String> packages() {
        return Set.of("com.whatsapp", "com.whatsapp.w4b");
    }
    public static boolean contains(String packageName) {
        return packages().contains(packageName);
    }
}
