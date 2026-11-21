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

    static boolean acceptsCreateView(String methodName, String returnType, String[] types) {
        return "onCreateView".equals(methodName)
                && "android.view.View".equals(returnType)
                && types != null && types.length == 3
                && "android.view.LayoutInflater".equals(types[0])
                && "android.view.ViewGroup".equals(types[1])
                && "android.os.Bundle".equals(types[2]);
    }
}
