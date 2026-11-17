// Liquid glass (refraction lens + blur + specular rim) for WaEnhancerX.
// Lens/refraction shader adapted from Kyant0/AndroidLiquidGlass (Apache 2.0), the same
// technique used by the KernelSU manager's floating bottom bar. This version is dependency-free
// (Compose GraphicsLayer + RenderEffect/AGSL) so it does not need the miuix-blur library.
package com.waenhancer.ui.glass

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** True while the liquid glass UI is enabled (pref `app_liquid_glass`). */
val LocalWaexLiquidGlass = staticCompositionLocalOf { false }

/** App-level on/off preference (`app_liquid_glass`, default on) and its writer. */
val LocalLiquidGlassPref = staticCompositionLocalOf { true }
val LocalLiquidGlassPrefUpdater = staticCompositionLocalOf<(Boolean) -> Unit> { {} }

/** The backdrop that glass surfaces sample. Null outside of [WaexLiquidGlassHost]. */
val LocalWaexBackdrop = staticCompositionLocalOf<WaexBackdrop?> { null }

/** One recorded layer (e.g. the aurora wallpaper or the scrolling page content). */
@Stable
class BackdropSource(val layer: GraphicsLayer) {
    /** Position of the source in root coordinates, used to align it under a glass surface. */
    var origin by mutableStateOf(Offset.Zero)
}

@Stable
class WaexBackdrop(val sources: List<BackdropSource>) {
    fun plus(extra: BackdropSource) = WaexBackdrop(sources + extra)
}

/** Records this node into [source] and still draws it normally. */
fun Modifier.waexBackdropSource(source: BackdropSource): Modifier =
    this
        .onGloballyPositioned { source.origin = it.positionInRoot() }
        .drawWithContent {
            source.layer.record { this@drawWithContent.drawContent() }
            drawLayer(source.layer)
        }

/**
 * Root host: paints a soft aurora wallpaper (the thing the glass refracts) and exposes it as the
 * default backdrop. When [enabled] is false it is a no-op.
 */
@Composable
fun WaexLiquidGlassHost(
    enabled: Boolean,
    baseColor: Color,
    accentA: Color,
    accentB: Color,
    isDark: Boolean,
    content: @Composable () -> Unit
) {
    if (!enabled) {
        content()
        return
    }
    val layer = rememberGraphicsLayer()
    val source = remember(layer) { BackdropSource(layer) }
    val backdrop = remember(source) { WaexBackdrop(listOf(source)) }
    CompositionLocalProvider(
        LocalWaexLiquidGlass provides true,
        LocalWaexBackdrop provides backdrop
    ) {
        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .waexBackdropSource(source)
                    .drawBehind {
                        val a = if (isDark) 0.40f else 0.30f
                        drawRect(baseColor)
                        val maxDim = maxOf(size.width, size.height)
                        drawRect(
                            Brush.radialGradient(
                                listOf(accentA.copy(alpha = a), Color.Transparent),
                                center = Offset(size.width * 0.10f, size.height * 0.08f),
                                radius = maxDim * 0.65f
                            )
                        )
                        drawRect(
                            Brush.radialGradient(
                                listOf(accentB.copy(alpha = a), Color.Transparent),
                                center = Offset(size.width * 0.95f, size.height * 0.45f),
                                radius = maxDim * 0.6f
                            )
                        )
                        drawRect(
                            Brush.radialGradient(
                                listOf(accentA.copy(alpha = a * 0.8f), Color.Transparent),
                                center = Offset(size.width * 0.25f, size.height * 0.98f),
                                radius = maxDim * 0.55f
                            )
                        )
                    }
            )
            content()
        }
    }
}

/**
 * A backdrop that also includes the page content, for bars that overlay scrolling content
 * (top bar / bottom bar). Apply [contentModifier] to the content that scrolls beneath the bars.
 */
@Stable
class ContentBackdrop(val source: BackdropSource, val backdrop: WaexBackdrop) {
    val contentModifier: Modifier get() = Modifier.waexBackdropSource(source)
}

@Composable
fun rememberContentBackdrop(): ContentBackdrop? {
    val base = LocalWaexBackdrop.current ?: return null
    val layer = rememberGraphicsLayer()
    return remember(base, layer) {
        val src = BackdropSource(layer)
        ContentBackdrop(src, base.plus(src))
    }
}

/**
 * Liquid glass surface.
 *
 * @param refract true = sample [backdrop] with blur + lens refraction (use for bars and small
 * floating controls). false = cheap glass (translucent tint + specular rim), for large lists of cards.
 */
fun Modifier.waexGlass(
    shape: Shape,
    tint: Color,
    refract: Boolean = false,
    blur: Dp = 4.dp,
    lensHeight: Dp = 20.dp,
    lensAmount: Dp = 20.dp,
    saturation: Float = 1.5f,
    rimAlpha: Float = 0.55f,
    backdrop: WaexBackdrop? = null
): Modifier = composed {
    if (!LocalWaexLiquidGlass.current) return@composed this
    val bd = backdrop ?: LocalWaexBackdrop.current
    val density = LocalDensity.current
    val glassLayer = rememberGraphicsLayer()
    val cache = remember { EffectCache() }
    var pos by remember { mutableStateOf(Offset.Zero) }
    val blurPx = with(density) { blur.toPx() }
    val lensHeightPx = with(density) { lensHeight.toPx() }
    val lensAmountPx = with(density) { lensAmount.toPx() }
    val satFilter = remember(saturation) {
        ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(saturation) })
    }

    this
        .onGloballyPositioned { pos = it.positionInRoot() }
        .drawWithContent {
            val w = size.width
            val h = size.height
            if (w < 1f || h < 1f) {
                drawContent()
                return@drawWithContent
            }
            val outline = shape.createOutline(size, layoutDirection, this)
            val clip = Path().apply { addOutline(outline) }
            val cornerRadius = (outline as? Outline.Rounded)?.roundRect?.topLeftCornerRadius?.x ?: 0f

            clipPath(clip) {
                if (refract && bd != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    glassLayer.record(size = IntSize(w.roundToInt(), h.roundToInt())) {
                        bd.sources.forEach { s ->
                            translate(left = s.origin.x - pos.x, top = s.origin.y - pos.y) {
                                drawLayer(s.layer)
                            }
                        }
                    }
                    glassLayer.renderEffect = cache.get(
                        w, h, cornerRadius, blurPx, lensHeightPx, lensAmountPx
                    )
                    glassLayer.colorFilter = satFilter
                    drawLayer(glassLayer)
                }
                // Tint, then a soft top-lit sheen for the "wet glass" look.
                drawRect(tint)
                drawRect(
                    Brush.verticalGradient(
                        listOf(Color.White.copy(alpha = 0.10f), Color.Transparent),
                        startY = 0f,
                        endY = h * 0.6f
                    )
                )
            }
            drawContent()
            // Specular rim: bright on the top-left, fading, with a faint bounce on the bottom-right.
            drawPath(
                path = clip,
                brush = Brush.linearGradient(
                    colorStops = arrayOf(
                        0f to Color.White.copy(alpha = rimAlpha),
                        0.45f to Color.White.copy(alpha = rimAlpha * 0.12f),
                        1f to Color.White.copy(alpha = rimAlpha * 0.4f)
                    ),
                    start = Offset.Zero,
                    end = Offset(w, h)
                ),
                style = Stroke(width = 1.dp.toPx())
            )
        }
}

/** Caches the RenderEffect/AGSL shader so it is only rebuilt when geometry changes. */
private class EffectCache {
    private var key: List<Float>? = null
    private var effect: androidx.compose.ui.graphics.RenderEffect? = null

    fun get(
        w: Float, h: Float, radius: Float, blurPx: Float, lensHeight: Float, lensAmount: Float
    ): androidx.compose.ui.graphics.RenderEffect? {
        val k = listOf(w, h, radius, blurPx, lensHeight, lensAmount)
        if (k != key) {
            key = k
            effect = build(w, h, radius, blurPx, lensHeight, lensAmount)
        }
        return effect
    }

    private fun build(
        w: Float, h: Float, radius: Float, blurPx: Float, lensHeight: Float, lensAmount: Float
    ): androidx.compose.ui.graphics.RenderEffect? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        var result: RenderEffect? = null
        if (blurPx > 0f) {
            result = RenderEffect.createBlurEffect(blurPx, blurPx, Shader.TileMode.MIRROR)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            lensHeight > 0f && lensAmount > 0f && radius > 0f
        ) {
            val lens = lensEffect(w, h, radius, lensHeight, lensAmount)
            result = if (result == null) lens else RenderEffect.createChainEffect(lens, result)
        }
        return result?.asComposeRenderEffect()
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun lensEffect(
        w: Float, h: Float, radius: Float, lensHeight: Float, lensAmount: Float
    ): RenderEffect {
        val shader = RuntimeShader(LENS_SHADER)
        val r = radius.coerceAtMost(minOf(w, h) / 2f)
        shader.setFloatUniform("size", w, h)
        shader.setFloatUniform("cornerRadius", r)
        shader.setFloatUniform("refractionHeight", lensHeight.coerceAtMost(minOf(w, h) / 2f))
        shader.setFloatUniform("refractionAmount", -lensAmount)
        return RenderEffect.createRuntimeShaderEffect(shader, "content")
    }
}

// Single-radius rounded-rect version of Kyant0's refraction shader.
private const val LENS_SHADER = """
uniform shader content;
uniform float2 size;
uniform float cornerRadius;
uniform float refractionHeight;
uniform float refractionAmount;

float sdRoundedRect(float2 coord, float2 halfSize, float radius) {
    float2 cornerCoord = abs(coord) - (halfSize - float2(radius));
    float outside = length(max(cornerCoord, 0.0)) - radius;
    float inside = min(max(cornerCoord.x, cornerCoord.y), 0.0);
    return outside + inside;
}

float2 gradSdRoundedRect(float2 coord, float2 halfSize, float radius) {
    float2 cornerCoord = abs(coord) - (halfSize - float2(radius));
    if (cornerCoord.x >= 0.0 || cornerCoord.y >= 0.0) {
        return sign(coord) * normalize(max(cornerCoord, 0.0));
    } else {
        float gradX = step(cornerCoord.y, cornerCoord.x);
        return sign(coord) * float2(gradX, 1.0 - gradX);
    }
}

float circleMap(float x) {
    return 1.0 - sqrt(1.0 - x * x);
}

half4 main(float2 coord) {
    float2 halfSize = size * 0.5;
    float2 centeredCoord = coord - halfSize;
    float sd = sdRoundedRect(centeredCoord, halfSize, cornerRadius);
    if (-sd >= refractionHeight) {
        return content.eval(coord);
    }
    sd = min(sd, 0.0);
    float d = circleMap(1.0 - -sd / refractionHeight) * refractionAmount;
    float gradRadius = min(cornerRadius * 1.5, min(halfSize.x, halfSize.y));
    float2 grad = normalize(gradSdRoundedRect(centeredCoord, halfSize, gradRadius));
    return content.eval(coord + d * grad);
}
"""
