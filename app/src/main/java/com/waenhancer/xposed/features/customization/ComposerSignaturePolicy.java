package com.waenhancer.xposed.features.customization;

final class ComposerSignaturePolicy {
    private ComposerSignaturePolicy() {}

    static boolean accepts(String[] types) {
        String view = "android.view.View";
        String bundle = "android.os.Bundle";
        return types != null && types.length == 2
                && ((view.equals(types[0]) && bundle.equals(types[1]))
                || (bundle.equals(types[0]) && view.equals(types[1])));
    }
}
