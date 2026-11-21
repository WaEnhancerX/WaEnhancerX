package com.waenhancer.xposed.features.media;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Custom vector drawable for the Closed Caption (CC) transcription icon based on
 * close-caption-svgrepo-com.svg (viewBox 0 0 48 48).
 * Renders dynamically with high fidelity vector paths, requiring no external resource lookups.
 */
final class CaptionDrawable extends Drawable {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path originalPath = new Path();
    private final Path scaledPath = new Path();
    private final Matrix scaleMatrix = new Matrix();
    private final int intrinsicSize;

    CaptionDrawable(int sizePx, @ColorInt int tintColor) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(tintColor);
        intrinsicSize = Math.max(16, sizePx);

        buildVectorPath();
        setBounds(0, 0, intrinsicSize, intrinsicSize);
    }

    private void buildVectorPath() {
        // Paths from close-caption-svgrepo-com.svg viewBox 0 0 48 48:
        // Left 'C':
        // M14,31h6a2.9,2.9,0,0,0,3-3V26H19v1H15V21h4v1h4V20a2.9,2.9,0,0,0-3-3H14a2.9,2.9,0,0,0-3,3v8A2.9,2.9,0,0,0,14,31Z
        Path leftC = new Path();
        leftC.moveTo(14f, 31f);
        leftC.lineTo(20f, 31f);
        leftC.cubicTo(21.6f, 31f, 23f, 29.6f, 23f, 28f);
        leftC.lineTo(23f, 26f);
        leftC.lineTo(19f, 26f);
        leftC.lineTo(19f, 27f);
        leftC.lineTo(15f, 27f);
        leftC.lineTo(15f, 21f);
        leftC.lineTo(19f, 21f);
        leftC.lineTo(19f, 22f);
        leftC.lineTo(23f, 22f);
        leftC.lineTo(23f, 20f);
        leftC.cubicTo(23f, 18.4f, 21.6f, 17f, 20f, 17f);
        leftC.lineTo(14f, 17f);
        leftC.cubicTo(12.4f, 17f, 11f, 18.4f, 11f, 20f);
        leftC.lineTo(11f, 28f);
        leftC.cubicTo(11f, 29.6f, 12.4f, 31f, 14f, 31f);
        leftC.close();

        // Right 'C':
        // M28,31h6a2.9,2.9,0,0,0,3-3V26H33v1H29V21h4v1h4V20a2.9,2.9,0,0,0-3-3H28a2.9,2.9,0,0,0-3,3v8A2.9,2.9,0,0,0,28,31Z
        Path rightC = new Path();
        rightC.moveTo(28f, 31f);
        rightC.lineTo(34f, 31f);
        rightC.cubicTo(35.6f, 31f, 37f, 29.6f, 37f, 28f);
        rightC.lineTo(37f, 26f);
        rightC.lineTo(33f, 26f);
        rightC.lineTo(33f, 27f);
        rightC.lineTo(29f, 27f);
        rightC.lineTo(29f, 21f);
        rightC.lineTo(33f, 21f);
        rightC.lineTo(33f, 22f);
        rightC.lineTo(37f, 22f);
        rightC.lineTo(37f, 20f);
        rightC.cubicTo(37f, 18.4f, 35.6f, 17f, 34f, 17f);
        rightC.lineTo(28f, 17f);
        rightC.cubicTo(26.4f, 17f, 25f, 18.4f, 25f, 20f);
        rightC.lineTo(25f, 28f);
        rightC.cubicTo(25f, 29.6f, 26.4f, 31f, 28f, 31f);
        rightC.close();

        // Outer rounded screen frame with inner cutout:
        // M44,9H4a2,2,0,0,0-2,2V37a2,2,0,0,0,2,2H44a2,2,0,0,0,2-2V11A2,2,0,0,0,44,9ZM42,35H6V13H42Z
        Path frame = new Path();
        frame.setFillType(Path.FillType.EVEN_ODD);
        // Outer rect with rounded corners (r=2dp in SVG)
        frame.addRoundRect(new RectF(2f, 9f, 46f, 39f), 2f, 2f, Path.Direction.CW);
        // Inner cutout
        frame.addRect(new RectF(6f, 13f, 42f, 35f), Path.Direction.CCW);

        originalPath.reset();
        originalPath.setFillType(Path.FillType.EVEN_ODD);
        originalPath.addPath(frame);
        originalPath.addPath(leftC);
        originalPath.addPath(rightC);
    }

    @Override
    protected void onBoundsChange(Rect bounds) {
        super.onBoundsChange(bounds);
        scaleMatrix.reset();
        // The original vector viewport is 48x48
        RectF src = new RectF(0f, 0f, 48f, 48f);
        RectF dst = new RectF(bounds);
        scaleMatrix.setRectToRect(src, dst, Matrix.ScaleToFit.CENTER);
        scaledPath.reset();
        scaledPath.setFillType(Path.FillType.EVEN_ODD);
        originalPath.transform(scaleMatrix, scaledPath);
    }

    public void setTintColor(@ColorInt int color) {
        paint.setColor(color);
        invalidateSelf();
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        canvas.drawPath(scaledPath, paint);
    }

    @Override public int getIntrinsicWidth() { return intrinsicSize; }
    @Override public int getIntrinsicHeight() { return intrinsicSize; }
    @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
    @Override public void setColorFilter(@Nullable ColorFilter filter) { paint.setColorFilter(filter); }

    @Override
    @SuppressWarnings("deprecation")
    public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
