package com.waenhancer.xposed.features.media;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Draws a short string (an emoji) as a compound drawable. Module drawables are not reliable
 * inside WhatsApp's resources, and a glyph needs no layout changes to the host's views.
 */
final class GlyphDrawable extends Drawable {
    private final String glyph;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int size;

    GlyphDrawable(@NonNull String glyph, float textSizePx) {
        this.glyph = glyph;
        paint.setTextSize(textSizePx);
        paint.setTextAlign(Paint.Align.CENTER);
        size = Math.round(textSizePx * 1.25f);
        setBounds(0, 0, size, size);
    }

    String glyph() { return glyph; }

    @Override
    public void draw(@NonNull Canvas canvas) {
        Rect bounds = getBounds();
        Paint.FontMetrics metrics = paint.getFontMetrics();
        float baseline = bounds.exactCenterY() - (metrics.ascent + metrics.descent) / 2f;
        canvas.drawText(glyph, bounds.exactCenterX(), baseline, paint);
    }

    @Override public int getIntrinsicWidth() { return size; }
    @Override public int getIntrinsicHeight() { return size; }
    @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
    @Override public void setColorFilter(@Nullable ColorFilter filter) { paint.setColorFilter(filter); }

    @Override
    @SuppressWarnings("deprecation") // Required override; the value is not used by modern Android.
    public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
