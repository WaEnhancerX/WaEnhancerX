package com.waenhancer.xposed.core.components;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import java.util.ArrayDeque;
import java.util.LinkedHashSet;

/** Host-safe programmatic HSV picker for dialogs shown inside WhatsApp. */
public final class NativeColorPickerDialog {
    public interface Listener { void onColorSelected(int color); }

    private static final int[] PRESETS = {
            0xffef4444, 0xfff97316, 0xfff59e0b, 0xffeab308,
            0xff10b981, 0xff14b8a6, 0xff06b6d4, 0xff3b82f6,
            0xff6366f1, 0xff8b5cf6, 0xffa855f7, 0xffec4899,
            0xffffffff, 0xff9ca3af, 0xff374151, 0xff000000
    };
    private static final ArrayDeque<Integer> RECENTS = new ArrayDeque<>();

    private final Activity activity;
    private final Listener listener;
    private final float density;
    private final float[] hsv = new float[3];
    private boolean updating;
    private int selectedColor;
    private View preview;
    private EditText hexInput;
    private SeekBar hue;
    private SeekBar saturation;
    private SeekBar brightness;
    private TextView hueLabel;
    private TextView saturationLabel;
    private TextView brightnessLabel;

    private NativeColorPickerDialog(Activity activity, int initialColor, Listener listener) {
        this.activity = activity;
        this.listener = listener;
        this.density = activity.getResources().getDisplayMetrics().density;
        setSelectedColor(initialColor | 0xff000000, false);
    }

    public static void show(Activity activity, int initialColor, Listener listener) {
        new NativeColorPickerDialog(activity, initialColor, listener).show();
    }

    private void show() {
        LinearLayout content = column();
        content.setPadding(dp(20), dp(8), dp(20), dp(12));

        preview = new View(activity);
        LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(64));
        previewParams.setMargins(0, 0, 0, dp(12));
        preview.setLayoutParams(previewParams);
        content.addView(preview);

        hexInput = new EditText(activity);
        hexInput.setSingleLine(true);
        hexInput.setHint("#RRGGBB");
        hexInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        hexInput.setFilters(new InputFilter[]{new InputFilter.LengthFilter(7)});
        content.addView(hexInput, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        content.addView(sectionLabel("Palette"));
        content.addView(createPalette());

        hue = new SeekBar(activity);
        hue.setMax(360);
        saturation = new SeekBar(activity);
        saturation.setMax(100);
        brightness = new SeekBar(activity);
        brightness.setMax(100);
        hueLabel = addSlider(content, "Hue", hue);
        saturationLabel = addSlider(content, "Saturation", saturation);
        brightnessLabel = addSlider(content, "Brightness", brightness);

        SeekBar.OnSeekBarChangeListener sliderListener = new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!updating && fromUser) {
                    hsv[0] = hue.getProgress();
                    hsv[1] = saturation.getProgress() / 100f;
                    hsv[2] = brightness.getProgress() / 100f;
                    setSelectedColor(Color.HSVToColor(hsv), true);
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        };
        hue.setOnSeekBarChangeListener(sliderListener);
        saturation.setOnSeekBarChangeListener(sliderListener);
        brightness.setOnSeekBarChangeListener(sliderListener);

        hexInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (updating || s == null || s.length() != 7 || s.charAt(0) != '#') return;
                try { setSelectedColor(Color.parseColor(s.toString()), false); }
                catch (IllegalArgumentException ignored) {}
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        // Populate controls only after all dependent views exist.
        setSelectedColor(selectedColor, false);

        ScrollView scroll = new ScrollView(activity);
        scroll.addView(content);
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("Choose color")
                .setView(scroll)
                .setPositiveButton(android.R.string.ok, null)
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    remember(selectedColor);
                    listener.onColorSelected(selectedColor);
                    dialog.dismiss();
                }));
        dialog.show();
    }

    private GridLayout createPalette() {
        GridLayout grid = new GridLayout(activity);
        grid.setColumnCount(8);
        grid.setUseDefaultMargins(false);
        LinkedHashSet<Integer> colors = new LinkedHashSet<>();
        synchronized (RECENTS) { colors.addAll(RECENTS); }
        for (int preset : PRESETS) colors.add(preset);
        for (int color : colors) {
            View swatch = new View(activity);
            GridLayout.LayoutParams params = new GridLayout.LayoutParams();
            params.width = dp(34);
            params.height = dp(34);
            params.setMargins(dp(3), dp(3), dp(3), dp(3));
            swatch.setLayoutParams(params);
            swatch.setBackground(swatchDrawable(color));
            swatch.setContentDescription(String.format("#%06X", color & 0x00ffffff));
            swatch.setOnClickListener(v -> setSelectedColor(color, false));
            grid.addView(swatch);
        }
        return grid;
    }

    private TextView addSlider(LinearLayout content, String title, SeekBar seekBar) {
        TextView label = sectionLabel(title);
        content.addView(label);
        content.addView(seekBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return label;
    }

    private void setSelectedColor(int color, boolean fromSlider) {
        selectedColor = color | 0xff000000;
        Color.colorToHSV(selectedColor, hsv);
        if (preview == null) return;
        updating = true;
        preview.setBackground(previewDrawable(selectedColor));
        if (!fromSlider) {
            hue.setProgress(Math.round(hsv[0]));
            saturation.setProgress(Math.round(hsv[1] * 100));
            brightness.setProgress(Math.round(hsv[2] * 100));
        }
        hue.setProgressTintList(ColorStateList.valueOf(Color.HSVToColor(
                new float[]{hsv[0], 1f, 1f})));
        saturation.setProgressTintList(ColorStateList.valueOf(Color.HSVToColor(
                new float[]{hsv[0], hsv[1], 1f})));
        brightness.setProgressTintList(ColorStateList.valueOf(selectedColor));
        hueLabel.setText("Hue  " + Math.round(hsv[0]) + "°");
        saturationLabel.setText("Saturation  " + Math.round(hsv[1] * 100) + "%");
        brightnessLabel.setText("Brightness  " + Math.round(hsv[2] * 100) + "%");
        String hex = String.format("#%06X", selectedColor & 0x00ffffff);
        if (!hex.equalsIgnoreCase(hexInput.getText().toString())) {
            hexInput.setText(hex);
            hexInput.setSelection(hex.length());
        }
        updating = false;
    }

    private void remember(int color) {
        synchronized (RECENTS) {
            RECENTS.remove(color);
            RECENTS.addFirst(color);
            while (RECENTS.size() > 8) RECENTS.removeLast();
        }
    }

    private GradientDrawable previewDrawable(int color) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(12));
        drawable.setStroke(dp(1), contrast(color));
        return drawable;
    }

    private GradientDrawable swatchDrawable(int color) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.OVAL);
        drawable.setColor(color);
        drawable.setStroke(dp(1), contrast(color));
        return drawable;
    }

    private int contrast(int color) {
        double luminance = (0.2126 * Color.red(color)
                + 0.7152 * Color.green(color)
                + 0.0722 * Color.blue(color)) / 255.0;
        return luminance > 0.55 ? 0xff555555 : 0xffdddddd;
    }

    private TextView sectionLabel(String text) {
        TextView label = new TextView(activity);
        label.setText(text);
        label.setTextSize(13);
        label.setGravity(Gravity.START);
        label.setPadding(0, dp(10), 0, 0);
        return label;
    }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private int dp(int value) { return Math.round(value * density); }
}
