package com.waenhancer.xposed.features.homescreen;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.WindowManager;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.Switch;
import android.widget.TextView;
import androidx.core.widget.NestedScrollView;
import de.robv.android.xposed.XposedBridge;

/**
 * Native WhatsApp WDS-style quick-action sheet for WAEX Separate menu mode.
 *
 * Layout:
 *   ┌──────────────────────────────────┐
 *   │  ╌╌╌  (drag handle)             │
 *   │  WAEX                            │
 *   │  ──────────────────────────────  │
 *   │  Ghost Mode              [  ○  ] │   ← WDSSwitch row (live toggle)
 *   │  Freeze Last Seen        [  ○  ] │
 *   │  DND Mode                [  ○  ] │
 *   │  ──────────────────────────────  │
 *   │  Restart WhatsApp                │   ← plain ripple tile
 *   │  WA Enhancer Settings            │
 *   └──────────────────────────────────┘
 */
final class WaexQuickActionsSheet {

    private WaexQuickActionsSheet() {}

    /**
     * Builds and shows the sheet.
     *
     * @param activity      WhatsApp's HomeActivity
     * @param prefs         Shared preferences (PreferenceBridgeClient)
     * @param onRestart     Runnable called when "Restart WhatsApp" is tapped
     * @param onSettings    Runnable called when "WA Enhancer Settings" is tapped
     * @param onMenuInvalidate  Runnable called after any toggle so the options menu refreshes
     */
    static void show(Activity activity,
                     SharedPreferences prefs,
                     Runnable onRestart,
                     Runnable onSettings,
                     Runnable onMenuInvalidate) {

        Dialog dialog = new Dialog(activity, android.R.style.Theme_Translucent_NoTitleBar);

        float density = activity.getResources().getDisplayMetrics().density;
        int screenHeight = activity.getResources().getDisplayMetrics().heightPixels;

        // ── Colors (matches AlertDialogWpp / WaexBottomSheet exact palette) ──
        boolean isDark = isDarkTheme(activity);
        int bgColor          = isDark ? 0xFF12181C : 0xFFFFFFFF;
        int primaryText      = isDark ? 0xFFE9EDEF : 0xFF111B21;
        int secondaryText    = isDark ? 0xFF8696A0 : 0xFF667781;
        int dividerColor     = isDark ? 0xFF222D34 : 0xFFE9EDEF;
        int accentColor      = isDark ? 0xFF21C063 : 0xFF008069;

        // Resolve from theme when possible
        try {
            TypedValue tv = new TypedValue();
            if (activity.getTheme().resolveAttribute(android.R.attr.textColorPrimary, tv, true))
                primaryText = tv.data;
            if (activity.getTheme().resolveAttribute(android.R.attr.textColorSecondary, tv, true))
                secondaryText = tv.data;
            if (activity.getTheme().resolveAttribute(android.R.attr.colorAccent, tv, true))
                accentColor = tv.data;
        } catch (Throwable ignored) {}

        final int finalPrimaryText   = primaryText;
        final int finalSecondaryText = secondaryText;
        final int finalAccentColor   = accentColor;

        // ── Root container ───────────────────────────────────────────────────
        RelativeLayout container = new RelativeLayout(activity);
        container.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        container.setBackgroundColor(Color.TRANSPARENT);

        // ── Sheet card ───────────────────────────────────────────────────────
        LinearLayout sheet = new LinearLayout(activity);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setPadding(dp(activity, 0), dp(activity, 14), dp(activity, 0), dp(activity, 28));

        RelativeLayout.LayoutParams sheetLp = new RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.MATCH_PARENT, RelativeLayout.LayoutParams.WRAP_CONTENT);
        sheetLp.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM);
        sheet.setLayoutParams(sheetLp);

        GradientDrawable sheetBg = new GradientDrawable();
        sheetBg.setColor(bgColor);
        float r = 24 * density;
        sheetBg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        sheet.setBackground(sheetBg);
        sheet.setOutlineProvider(ViewOutlineProvider.BACKGROUND);
        sheet.setClipToOutline(true);
        sheet.setElevation(8 * density);

        // ── Drag handle ──────────────────────────────────────────────────────
        View handle = new View(activity);
        LinearLayout.LayoutParams handleLp = new LinearLayout.LayoutParams(
                dp(activity, 40), dp(activity, 4));
        handleLp.gravity = Gravity.CENTER_HORIZONTAL;
        handleLp.bottomMargin = dp(activity, 14);
        handle.setLayoutParams(handleLp);
        GradientDrawable handleBg = new GradientDrawable();
        handleBg.setColor(secondaryText & 0x33FFFFFF | 0x33000000);
        handleBg.setCornerRadius(2 * density);
        handle.setBackground(handleBg);
        sheet.addView(handle);

        // Dismiss-on-drag helper
        Runnable dismissAnim = () -> sheet.animate()
                .translationY(screenHeight)
                .setDuration(220)
                .withEndAction(dialog::dismiss)
                .start();

        View.OnTouchListener dragListener = new View.OnTouchListener() {
            private float startY;
            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        startY = e.getRawY();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dy = e.getRawY() - startY;
                        sheet.setTranslationY(Math.max(0f, dy));
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        float ty = sheet.getTranslationY();
                        if (ty > dp(activity, 100) || ty > sheet.getHeight() / 4f) {
                            dismissAnim.run();
                        } else {
                            sheet.animate().translationY(0).setDuration(200).start();
                        }
                        return true;
                }
                return false;
            }
        };
        handle.setOnTouchListener(dragListener);
        sheet.setOnTouchListener(dragListener);

        // ── Title ────────────────────────────────────────────────────────────
        TextView title = createWdsTextView(activity);
        title.setText("WAEX");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(finalPrimaryText);
        title.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        titleLp.leftMargin  = dp(activity, 20);
        titleLp.rightMargin = dp(activity, 20);
        titleLp.bottomMargin = dp(activity, 8);
        title.setLayoutParams(titleLp);
        sheet.addView(title);

        // ── Scrollable content ───────────────────────────────────────────────
        NestedScrollView scrollView = new NestedScrollView(activity);
        scrollView.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // ── Toggle section ───────────────────────────────────────────────────
        boolean hasToggles = prefs.getBoolean("ghostmode", false)
                || prefs.getBoolean("freezelastseen", false)
                || prefs.getBoolean("show_dndmode", false);

        if (hasToggles) {
            addDivider(content, activity, dividerColor);

            if (prefs.getBoolean("ghostmode", false)) {
                boolean ghostActive = prefs.getBoolean("ghostmode_active", false);
                addSwitchRow(content, activity, dialog,
                        "Ghost Mode",
                        "Hide online status, typing & read receipts",
                        ghostActive,
                        false,
                        finalPrimaryText, finalSecondaryText,
                        checked -> {
                            prefs.edit().putBoolean("ghostmode_active", checked).apply();
                            // When activating Ghost Mode, also force-enable its sub-features
                            if (checked) {
                                prefs.edit()
                                        .putBoolean("freeze_last_seen_active", true)
                                        .putBoolean("dnd_mode", true)
                                        .apply();
                            }
                            if (onMenuInvalidate != null) onMenuInvalidate.run();
                        });
            }

            // Read ghostActive once for dependent rows
            boolean ghostActive = prefs.getBoolean("ghostmode", false)
                    && prefs.getBoolean("ghostmode_active", false);

            if (prefs.getBoolean("freezelastseen", false)) {
                boolean freezeActive = ghostActive || prefs.getBoolean("freeze_last_seen_active", false);
                addSwitchRow(content, activity, dialog,
                        "Freeze Last Seen",
                        ghostActive ? "Managed by Ghost Mode" : "Lock your last seen timestamp",
                        freezeActive,
                        ghostActive,
                        finalPrimaryText, finalSecondaryText,
                        checked -> {
                            prefs.edit().putBoolean("freeze_last_seen_active", checked).apply();
                            if (onMenuInvalidate != null) onMenuInvalidate.run();
                        });
            }

            if (prefs.getBoolean("show_dndmode", false)) {
                boolean dndActive = ghostActive || prefs.getBoolean("dnd_mode", false);
                addSwitchRow(content, activity, dialog,
                        "DND Mode",
                        ghostActive ? "Managed by Ghost Mode" : "Mute all incoming notifications",
                        dndActive,
                        ghostActive,
                        finalPrimaryText, finalSecondaryText,
                        checked -> {
                            prefs.edit().putBoolean("dnd_mode", checked).apply();
                            if (onMenuInvalidate != null) onMenuInvalidate.run();
                        });
            }
        }

        // ── Action tiles section ─────────────────────────────────────────────
        boolean hasActions = prefs.getBoolean("restartbutton", false)
                || prefs.getBoolean("open_wae", false);

        if (hasActions) {
            addDivider(content, activity, dividerColor);

            if (prefs.getBoolean("restartbutton", false)) {
                addTileRow(content, activity,
                        "Restart WhatsApp",
                        "Force close and relaunch WhatsApp",
                        finalPrimaryText, finalSecondaryText,
                        () -> {
                            dialog.dismiss();
                            if (onRestart != null) onRestart.run();
                        });
            }

            if (prefs.getBoolean("open_wae", false)) {
                addTileRow(content, activity,
                        "WA Enhancer Settings",
                        "Open WAEX settings and preferences",
                        finalPrimaryText, finalSecondaryText,
                        () -> {
                            dialog.dismiss();
                            if (onSettings != null) onSettings.run();
                        });
            }
        }

        scrollView.addView(content);
        sheet.addView(scrollView);

        container.addView(sheet);
        container.setOnClickListener(v -> dismissAnim.run());
        sheet.setOnClickListener(v -> {});

        dialog.setContentView(container);

        android.view.Window window = dialog.getWindow();
        if (window != null) {
            window.setGravity(Gravity.BOTTOM);
            window.getDecorView().setPadding(0, 0, 0, 0);
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setDimAmount(0.5f);
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        }

        if (!activity.isFinishing() && !activity.isDestroyed()) {
            try {
                dialog.show();
            } catch (Throwable t) {
                XposedBridge.log("[WAEX] WaexQuickActionsSheet.show() failed: " + t.getMessage());
            }
        }
    }

    // ── Row builders ─────────────────────────────────────────────────────────

    /**
     * Adds a label+subtitle + WDSSwitch row.
     *
     * @param disabled  When true the row is locked: switch stays checked, alpha dimmed,
     *                  subtitle shows override text, and click does nothing.
     */
    private static void addSwitchRow(LinearLayout parent, Context ctx, Dialog dialog,
                                     String label, String subtitle, boolean initialState,
                                     boolean disabled,
                                     int primaryText, int secondaryText,
                                     SwitchCallback callback) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(ctx, 20), dp(ctx, 12), dp(ctx, 20), dp(ctx, 12));
        row.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // Dim the whole row when disabled
        if (disabled) {
            row.setAlpha(0.45f);
        }

        // Left: vertical title + subtitle stack
        LinearLayout textStack = new LinearLayout(ctx);
        textStack.setOrientation(LinearLayout.VERTICAL);
        textStack.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));

        TextView titleTv = createWdsTextView(ctx);
        titleTv.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        titleTv.setText(label);
        titleTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        titleTv.setTextColor(primaryText);
        titleTv.setGravity(Gravity.START);
        textStack.addView(titleTv);

        TextView subtitleTv = createWdsTextView(ctx);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = dp(ctx, 2);
        subtitleTv.setLayoutParams(subLp);
        subtitleTv.setText(subtitle);
        subtitleTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        subtitleTv.setTextColor(secondaryText);
        subtitleTv.setGravity(Gravity.START);
        textStack.addView(subtitleTv);

        row.addView(textStack);

        // Right: WDSSwitch — native WhatsApp toggle, fallback to MaterialSwitch → Switch
        CompoundButton sw = createWdsSwitch(ctx, initialState);
        LinearLayout.LayoutParams swLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        swLp.leftMargin = dp(ctx, 12);
        sw.setLayoutParams(swLp);
        sw.setClickable(false);
        sw.setFocusable(false);
        row.addView(sw);

        if (disabled) {
            // No ripple, no interaction when locked
            row.setBackground(new ColorDrawable(Color.TRANSPARENT));
            row.setClickable(false);
        } else {
            row.setBackground(new RippleDrawable(
                    ColorStateList.valueOf(secondaryText & 0x15FFFFFF | 0x15000000),
                    new ColorDrawable(Color.TRANSPARENT), null));
            row.setOnClickListener(v -> {
                boolean next = !sw.isChecked();
                sw.setChecked(next);
                callback.onToggled(next);
            });
        }

        parent.addView(row);
    }

    /**
     * Adds a plain title+subtitle tile (no trailing widget) — used for Restart / Settings.
     */
    private static void addTileRow(LinearLayout parent, Context ctx,
                                   String label, String subtitle,
                                   int primaryText, int secondaryText,
                                   Runnable action) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(ctx, 20), dp(ctx, 12), dp(ctx, 20), dp(ctx, 12));
        row.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // Left: vertical title + subtitle stack
        LinearLayout textStack = new LinearLayout(ctx);
        textStack.setOrientation(LinearLayout.VERTICAL);
        textStack.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView titleTv = createWdsTextView(ctx);
        titleTv.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        titleTv.setText(label);
        titleTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        titleTv.setTextColor(primaryText);
        titleTv.setGravity(Gravity.START);
        textStack.addView(titleTv);

        TextView subtitleTv = createWdsTextView(ctx);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = dp(ctx, 2);
        subtitleTv.setLayoutParams(subLp);
        subtitleTv.setText(subtitle);
        subtitleTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        subtitleTv.setTextColor(secondaryText);
        subtitleTv.setGravity(Gravity.START);
        textStack.addView(subtitleTv);

        row.addView(textStack);

        row.setBackground(new RippleDrawable(
                ColorStateList.valueOf(secondaryText & 0x15FFFFFF | 0x15000000),
                new ColorDrawable(Color.TRANSPARENT), null));

        row.setOnClickListener(v -> action.run());
        parent.addView(row);
    }

    /** Horizontal hairline divider matching WhatsApp's list separator. */
    private static void addDivider(LinearLayout parent, Context ctx, int color) {
        View divider = new View(ctx);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1);
        lp.leftMargin  = dp(ctx, 0);
        lp.rightMargin = dp(ctx, 0);
        divider.setLayoutParams(lp);
        divider.setBackgroundColor(color);
        parent.addView(divider);
    }

    // ── WDS native view factories ─────────────────────────────────────────────

    private static TextView createWdsTextView(Context ctx) {
        try {
            Class<?> cls = ctx.getClassLoader()
                    .loadClass("com.whatsapp.ui.wds.components.textview.WDSTextView");
            return (TextView) cls.getConstructor(Context.class, AttributeSet.class)
                    .newInstance(ctx, null);
        } catch (Throwable t) {
            return new TextView(ctx);
        }
    }

    private static CompoundButton createWdsSwitch(Context ctx, boolean checked) {
        CompoundButton sw;
        try {
            Class<?> cls = ctx.getClassLoader()
                    .loadClass("com.whatsapp.ui.wds.components.toggle.WDSSwitch");
            sw = (CompoundButton) cls.getConstructor(Context.class, AttributeSet.class)
                    .newInstance(ctx, null);
        } catch (Throwable t1) {
            try {
                Class<?> cls = ctx.getClassLoader()
                        .loadClass("com.google.android.material.materialswitch.MaterialSwitch");
                sw = (CompoundButton) cls.getConstructor(Context.class).newInstance(ctx);
            } catch (Throwable t2) {
                sw = new Switch(ctx);
            }
        }
        sw.setChecked(checked);
        return sw;
    }

    // ── Utility ───────────────────────────────────────────────────────────────

    private static int dp(Context ctx, int value) {
        return (int) (value * ctx.getResources().getDisplayMetrics().density);
    }

    private static boolean isDarkTheme(Context ctx) {
        try {
            int flags = ctx.getResources().getConfiguration().uiMode
                    & Configuration.UI_MODE_NIGHT_MASK;
            return flags == Configuration.UI_MODE_NIGHT_YES;
        } catch (Throwable ignored) {
            return false;
        }
    }

    // ── Callback interface ────────────────────────────────────────────────────

    interface SwitchCallback {
        void onToggled(boolean checked);
    }
}
