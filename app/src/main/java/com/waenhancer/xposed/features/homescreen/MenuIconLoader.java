package com.waenhancer.xposed.features.homescreen;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import androidx.core.content.ContextCompat;
import de.robv.android.xposed.XposedBridge;

/**
 * Loads vector drawables from the WAEX module resources into the target app process.
 * Uses createPackageContext + getIdentifier() to resolve drawable IDs without
 * a direct R class reference (safe for Xposed cross-process loading).
 */
final class MenuIconLoader {

    private static final String MODULE_PACKAGE = "com.waenhancer";

    private MenuIconLoader() {}

    /**
     * Loads a drawable from the WAEX module resources by name.
     *
     * @param whatsappContext WhatsApp's application context
     * @param drawableName    Name of the drawable (e.g. "ic_waex_ghost_on")
     * @return Drawable or null on failure
     */
    static Drawable load(Context whatsappContext, String drawableName) {
        try {
            Context moduleCtx = whatsappContext.createPackageContext(
                    MODULE_PACKAGE,
                    Context.CONTEXT_IGNORE_SECURITY | Context.CONTEXT_INCLUDE_CODE
            );
            int resId = moduleCtx.getResources().getIdentifier(
                    drawableName, "drawable", MODULE_PACKAGE
            );
            if (resId == 0) {
                XposedBridge.log("[WAEX] MenuIconLoader: drawable not found – " + drawableName);
                return null;
            }
            return ContextCompat.getDrawable(moduleCtx, resId);
        } catch (PackageManager.NameNotFoundException e) {
            XposedBridge.log("[WAEX] MenuIconLoader: module package not found: " + e.getMessage());
            return null;
        } catch (Exception e) {
            XposedBridge.log("[WAEX] MenuIconLoader: error loading " + drawableName + ": " + e.getMessage());
            return null;
        }
    }

    // ── Convenience accessors ────────────────────────────────────────────────

    static Drawable ghostIcon(Context ctx, boolean active) {
        return load(ctx, active ? "ic_waex_ghost_on" : "ic_waex_ghost_off");
    }

    static Drawable freezeIcon(Context ctx, boolean active) {
        return load(ctx, active ? "ic_waex_freeze_on" : "ic_waex_freeze_off");
    }

    static Drawable dndIcon(Context ctx, boolean active) {
        return load(ctx, active ? "ic_waex_dnd_on" : "ic_waex_dnd_off");
    }

    static Drawable restartIcon(Context ctx) {
        return load(ctx, "ic_waex_restart");
    }

    static Drawable settingsIcon(Context ctx) {
        return load(ctx, "ic_waex_settings");
    }

    static Drawable logoIcon(Context ctx) {
        return load(ctx, "ic_waex_logo");
    }
}
