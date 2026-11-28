package com.waenhancer.ui.screens.settings

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance

private val PREVIEW_DARK_INK = Color(0xFF111B21)
private val PREVIEW_LIGHT_INK = Color(0xFFE9EDEF)
private const val MIN_PREVIEW_CONTRAST = 4.5f

internal data class BubblePreviewColors(val body: Color, val footer: Color)

/** Chooses sample text colors over the preview's opaque backdrop, including fill alpha. */
internal fun bubblePreviewColors(fill: Color, backdrop: Color): BubblePreviewColors {
    val background = fill.compositeOver(backdrop)
    val backgroundLuminance = background.luminance()
    fun contrast(ink: Color): Float {
        val inkLuminance = ink.compositeOver(background).luminance()
        return (maxOf(inkLuminance, backgroundLuminance) + 0.05f) /
            (minOf(inkLuminance, backgroundLuminance) + 0.05f)
    }

    val darkContrast = contrast(PREVIEW_DARK_INK)
    val lightContrast = contrast(PREVIEW_LIGHT_INK)
    val body = if (maxOf(darkContrast, lightContrast) >= MIN_PREVIEW_CONTRAST) {
        if (darkContrast >= lightContrast) PREVIEW_DARK_INK else PREVIEW_LIGHT_INK
    } else {
        // Mid-tone fills can leave both existing inks below the text contrast minimum.
        if (contrast(Color.Black) >= contrast(Color.White)) Color.Black else Color.White
    }
    val mutedFooter = body.copy(alpha = 0.6f)
    val footer = if (contrast(mutedFooter) >= MIN_PREVIEW_CONTRAST) mutedFooter else body
    return BubblePreviewColors(body, footer)
}
