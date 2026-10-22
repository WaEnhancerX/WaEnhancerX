package com.waenhancer.utils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

/** Edits one target's package-visibility rules without changing shared templates. */
public final class HmaConfigPolicy {
    private HmaConfigPolicy() {}

    public static boolean isManagerPackage(String name) {
        return "com.tsng.hidemyapplist".equals(name)
                || "org.frknkrc44.hma_oss".equals(name)
                || "com.google.android.hmal".equals(name);
    }

    private static boolean contains(JSONArray array, String value) {
        if (array == null) return false;
        for (int i = 0; i < array.length(); i++) {
            if (value.equals(array.optString(i))) return true;
        }
        return false;
    }

    private static JSONArray without(JSONArray array, String value) {
        JSONArray result = new JSONArray();
        if (array != null) for (int i = 0; i < array.length(); i++) {
            Object item = array.opt(i);
            if (!value.equals(item)) result.put(item);
        }
        return result;
    }

    private static JSONArray including(JSONArray array, String value) {
        JSONArray result = without(array, value);
        result.put(value);
        return result;
    }

    private static void collectTemplateReferences(JSONObject scope, Set<String> names) {
        if (scope == null) return;
        JSONArray applied = scope.optJSONArray("applyTemplates");
        if (applied != null) for (int i = 0; i < applied.length(); i++) names.add(applied.optString(i));
    }

    /** Rule inspection only; the HMA service's in-memory state is not observed here. */
    public static String blockingReason(JSONObject root, String target, String module) {
        JSONObject scopes = root.optJSONObject("scope");
        JSONObject scope = scopes == null ? null : scopes.optJSONObject(target);
        if (scope == null) return null;
        JSONObject templates = root.optJSONObject("templates");
        if (templates == null) templates = new JSONObject();
        boolean whitelist = scope.optBoolean("useWhitelist", false);
        // Both public HMA implementations give extraAppList first precedence.
        if (contains(scope.optJSONArray("extraAppList"), module)) {
            return whitelist ? null : "Hidden via Blacklist mode (in additional apps to hide)";
        }
        if (contains(scope.optJSONArray("extraOppositeAppList"), module)) {
            return whitelist ? "Hidden via Whitelist mode (in invisible apps list)" : null;
        }
        JSONArray applied = scope.optJSONArray("applyTemplates");
        if (applied != null) for (int i = 0; i < applied.length(); i++) {
            String name = applied.optString(i);
            JSONObject template = templates.optJSONObject(name);
            // isWhitelist is an editor label; the runtime uses the target's mode.
            if (template != null && contains(template.optJSONArray("appList"), module)) {
                return whitelist ? null : "Hidden by blacklist template \"" + name + "\"";
            }
        }
        JSONArray presets = scope.optJSONArray("applyPresets");
        if (presets != null && !contains(root.optJSONArray("ignoredPackagesForPresets"), module))
            for (int i = 0; i < presets.length(); i++) {
            if ("xposed".equalsIgnoreCase(presets.optString(i))) {
                return "Hidden by HMA \"xposed\" preset";
            }
        }
        return whitelist
                ? "Hidden via Whitelist mode (module not included in visible apps)" : null;
    }

    /** Returns a new document, or null when no supported target rule needs changing. */
    public static JSONObject repair(JSONObject original, String target, String module)
            throws JSONException {
        if (!DeclaredWhatsAppTargets.contains(target) || module == null || module.isEmpty()) return null;
        JSONObject root = new JSONObject(original.toString());
        JSONObject scopes = root.optJSONObject("scope");
        JSONObject scope = scopes == null ? null : scopes.optJSONObject(target);
        if (scope == null) return null;
        JSONObject templates = root.optJSONObject("templates");
        if (templates == null) templates = new JSONObject();
        if (blockingReason(root, target, module) == null) return null;

        if (scope.optBoolean("useWhitelist", false)) {
            scope.put("extraAppList", including(scope.optJSONArray("extraAppList"), module));
            if (scope.has("extraOppositeAppList")) {
                scope.put("extraOppositeAppList",
                        without(scope.optJSONArray("extraOppositeAppList"), module));
            }
        } else if (scope.has("extraOppositeAppList")) {
            // HMA-OSS supports a target-local visibility exception before templates/presets.
            scope.put("extraAppList", without(scope.optJSONArray("extraAppList"), module));
            scope.put("extraOppositeAppList",
                    including(scope.optJSONArray("extraOppositeAppList"), module));
        } else {
            // Legacy HMA has no opposite list. Clone only a blocking applied template,
            // preserving its other members and every existing global template.
            scope.put("extraAppList", without(scope.optJSONArray("extraAppList"), module));
            // A missing template name can still be referenced by another scope
            // or OSS defaultConfig. Creating that name would activate its rule.
            Set<String> referenced = new HashSet<>();
            for (Iterator<String> keys = scopes.keys(); keys.hasNext();) {
                collectTemplateReferences(scopes.optJSONObject(keys.next()), referenced);
            }
            collectTemplateReferences(root.optJSONObject("defaultConfig"), referenced);
            JSONArray applied = scope.optJSONArray("applyTemplates");
            JSONArray replacements = new JSONArray();
            if (applied != null) for (int i = 0; i < applied.length(); i++) {
                String name = applied.getString(i);
                JSONObject template = templates.optJSONObject(name);
                if (template == null || !contains(template.optJSONArray("appList"), module)) {
                    replacements.put(name);
                    continue;
                }
                JSONObject copy = new JSONObject(template.toString());
                copy.put("appList", without(copy.optJSONArray("appList"), module));
                String stem = name + " (WAEX " + target + ")";
                String key = stem;
                int suffix = 1;
                while (templates.has(key) || referenced.contains(key)) {
                    if (suffix > 128) throw new JSONException("Target template namespace exhausted");
                    key = stem + " " + suffix++;
                }
                templates.put(key, copy);
                replacements.put(key);
            }
            if (applied != null) scope.put("applyTemplates", replacements);
            if (root.has("templates")) root.put("templates", templates);
            JSONArray presets = scope.optJSONArray("applyPresets");
            if (presets != null) {
                JSONArray kept = new JSONArray();
                for (int i = 0; i < presets.length(); i++) {
                    Object value = presets.get(i);
                    if (!(value instanceof String) || !"xposed".equalsIgnoreCase((String) value)) {
                        kept.put(value);
                    }
                }
                scope.put("applyPresets", kept);
            }
        }
        if (blockingReason(root, target, module) != null) {
            throw new JSONException("Target remains blocked by the inspected rules");
        }
        return root;
    }
}
