package com.waenhancer.ui.screens.settings

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.waenhancer.ui.designsystem.darkColors
import com.waenhancer.ui.designsystem.lightColors
import kotlin.math.pow
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BubblePreviewContrastTest {
    private val darkInk = Color(0xFF111B21)
    private val lightInk = Color(0xFFE9EDEF)

    @Test fun midToneUsesReadableExistingInk() {
        val colors = bubblePreviewColors(Color(0xFF999999), Color.Black)
        assertEquals(darkInk, colors.body)
        assertEquals(darkInk, colors.footer)
    }

    @Test fun fallsBackOnlyWhenBothExistingInksAreInsufficient() {
        assertEquals(Color.Black, bubblePreviewColors(Color(0xFF808080), Color.Black).body)
        assertEquals(darkInk, bubblePreviewColors(Color.White, Color.Black).body)
        assertEquals(lightInk, bubblePreviewColors(Color.Black, Color.White).body)
    }

    @Test fun transparentRgbDoesNotChooseTheInk() {
        for (backdrop in listOf(lightColors.surfaceDim, darkColors.surfaceDim)) {
            val expected = bubblePreviewColors(backdrop, backdrop)
            for (fill in listOf(Color(0x00FFFFFF), Color(0x00000001), Color(0x0000FF00))) {
                assertEquals(expected, bubblePreviewColors(fill, backdrop))
            }
        }
    }

    @Test fun partialAlphaUsesTheVisibleColor() {
        // Half-transparent black over white is mid-gray, not a black bubble.
        val colors = bubblePreviewColors(Color(0x80000000), Color.White)
        assertEquals(Color.Black, colors.body)
        assertEquals(Color.Black, colors.footer)
    }

    @Test fun keepsMutedFooterOnlyWithEnoughContrast() {
        assertEquals(lightInk.copy(alpha = 0.6f), bubblePreviewColors(Color.Black, Color.White).footer)
        assertEquals(darkInk, bubblePreviewColors(Color(0xFF999999), Color.White).footer)
    }

    @Test fun defaultBubbleBodiesKeepTheirExistingInks() {
        val defaults = listOf(
            Triple(Color(0xFFFFFFFF), lightColors.surfaceDim, darkInk),
            Triple(Color(0xFFD9FDD3), lightColors.surfaceDim, darkInk),
            Triple(Color(0xFF202C33), darkColors.surfaceDim, lightInk),
            Triple(Color(0xFF005C4B), darkColors.surfaceDim, lightInk)
        )
        for ((fill, backdrop, expected) in defaults) {
            assertEquals(expected, bubblePreviewColors(fill, backdrop).body)
        }
    }

    @Test fun opaqueThemesAndAlphaColorGridMeetTextContrast() {
        // These are the real preview surfaces, plus neutral opaque extremes.
        val backdrops = listOf(lightColors.surfaceDim, darkColors.surfaceDim, Color.White, Color.Black)
        val samples = listOf(0, 64, 112, 128, 153, 192, 255)
        for (backdrop in backdrops) {
            assertEquals(1f, backdrop.alpha, 0f)
            for (alpha in listOf(0, 1, 64, 128, 192, 255)) {
                for (r in samples) for (g in samples) for (b in samples) {
                    val fill = Color((alpha shl 24) or (r shl 16) or (g shl 8) or b)
                    val colors = bubblePreviewColors(fill, backdrop)
                    val visible = over(fill, rgb(backdrop))
                    for (ink in listOf(colors.body, colors.footer)) {
                        val ratio = ratio(over(ink, visible), visible)
                        assertTrue("contrast=$ratio fill=${fill.toArgb()} backdrop=${backdrop.toArgb()}", ratio >= 4.5)
                    }
                }
            }
        }
    }

    // Independent 8-bit sRGB oracle; no Compose compositeOver or luminance calls.
    private fun rgb(color: Color) = doubleArrayOf(color.red.toDouble(), color.green.toDouble(), color.blue.toDouble())
    private fun over(front: Color, back: DoubleArray): DoubleArray {
        val alpha = front.alpha.toDouble()
        return rgb(front).mapIndexed { i, channel ->
            ((channel * alpha + back[i] * (1 - alpha)) * 255).roundToInt() / 255.0
        }.toDoubleArray()
    }
    private fun luminance(rgb: DoubleArray): Double {
        val linear = rgb.map { if (it <= 0.04045) it / 12.92 else ((it + 0.055) / 1.055).pow(2.4) }
        return linear[0] * 0.2126 + linear[1] * 0.7152 + linear[2] * 0.0722
    }
    private fun ratio(a: DoubleArray, b: DoubleArray): Double {
        val first = luminance(a)
        val second = luminance(b)
        return (maxOf(first, second) + 0.05) / (minOf(first, second) + 0.05)
    }
}
