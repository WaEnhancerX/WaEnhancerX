package com.waenhancer.ui.designsystem

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.waenhancer.app.R
import androidx.compose.material3.Typography

val OutfitFontFamily = FontFamily(
    Font(R.font.outfit_regular, FontWeight.Normal),
    Font(R.font.outfit_regular, FontWeight.Medium),
    Font(R.font.outfit_bold, FontWeight.SemiBold),
    Font(R.font.outfit_bold, FontWeight.Bold)
)

@Immutable
data class WaexTypography(
    val scale: Float = 1f,
    val displayLg: TextStyle = TextStyle(
        fontFamily = OutfitFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = (26f * scale).sp,
        lineHeight = (32f * scale).sp,
        letterSpacing = (-0.03).sp
    ),
    val displayLgMobile: TextStyle = TextStyle(
        fontFamily = OutfitFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = (22f * scale).sp,
        lineHeight = (28f * scale).sp,
        letterSpacing = (-0.03).sp
    ),
    val headlineMd: TextStyle = TextStyle(
        fontFamily = OutfitFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = (20f * scale).sp,
        lineHeight = (26f * scale).sp,
        letterSpacing = (-0.02).sp
    ),
    val titleLg: TextStyle = TextStyle(
        fontFamily = OutfitFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = (18f * scale).sp,
        lineHeight = (24f * scale).sp,
        letterSpacing = (-0.01).sp
    ),
    val titleMd: TextStyle = TextStyle(
        fontFamily = OutfitFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = (16f * scale).sp,
        lineHeight = (22f * scale).sp,
        letterSpacing = 0.sp
    ),
    val bodyLg: TextStyle = TextStyle(
        fontFamily = OutfitFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = (16f * scale).sp,
        lineHeight = (22f * scale).sp,
        letterSpacing = 0.sp
    ),
    val bodyMd: TextStyle = TextStyle(
        fontFamily = OutfitFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = (14f * scale).sp,
        lineHeight = (20f * scale).sp,
        letterSpacing = 0.sp
    ),
    val bodySm: TextStyle = TextStyle(
        fontFamily = OutfitFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = (12f * scale).sp,
        lineHeight = (17f * scale).sp,
        letterSpacing = 0.sp
    ),
    val labelLg: TextStyle = TextStyle(
        fontFamily = OutfitFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = (14f * scale).sp,
        lineHeight = (19f * scale).sp,
        letterSpacing = 0.01.sp
    ),
    val labelSm: TextStyle = TextStyle(
        fontFamily = OutfitFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = (12f * scale).sp,
        lineHeight = (16f * scale).sp,
        letterSpacing = 0.02.sp
    )
)

fun materialTypography(scale: Float = 1f) = Typography(
    displayLarge = TextStyle(
        fontFamily = OutfitFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = (57f * scale).sp,
        lineHeight = (64f * scale).sp,
        letterSpacing = (-0.25).sp
    ),
    displayMedium = TextStyle(
        fontFamily = OutfitFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = (45f * scale).sp,
        lineHeight = (52f * scale).sp,
        letterSpacing = 0.sp
    ),
    displaySmall = TextStyle(
        fontFamily = OutfitFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = (36f * scale).sp,
        lineHeight = (44f * scale).sp,
        letterSpacing = 0.sp
    ),
    headlineLarge = TextStyle(
        fontFamily = OutfitFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = (32f * scale).sp,
        lineHeight = (40f * scale).sp,
        letterSpacing = 0.sp
    ),
    headlineMedium = TextStyle(
        fontFamily = OutfitFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = (28f * scale).sp,
        lineHeight = (36f * scale).sp,
        letterSpacing = 0.sp
    ),
    headlineSmall = TextStyle(
        fontFamily = OutfitFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = (24f * scale).sp,
        lineHeight = (32f * scale).sp,
        letterSpacing = 0.sp
    ),
    titleLarge = TextStyle(
        fontFamily = OutfitFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = (22f * scale).sp,
        lineHeight = (28f * scale).sp,
        letterSpacing = 0.sp
    ),
    titleMedium = TextStyle(
        fontFamily = OutfitFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = (16f * scale).sp,
        lineHeight = (24f * scale).sp,
        letterSpacing = 0.15.sp
    ),
    titleSmall = TextStyle(
        fontFamily = OutfitFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = (14f * scale).sp,
        lineHeight = (20f * scale).sp,
        letterSpacing = 0.1.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = OutfitFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = (16f * scale).sp,
        lineHeight = (24f * scale).sp,
        letterSpacing = 0.5.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = OutfitFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = (14f * scale).sp,
        lineHeight = (20f * scale).sp,
        letterSpacing = 0.25.sp
    ),
    bodySmall = TextStyle(
        fontFamily = OutfitFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = (12f * scale).sp,
        lineHeight = (16f * scale).sp,
        letterSpacing = 0.4.sp
    ),
    labelLarge = TextStyle(
        fontFamily = OutfitFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = (14f * scale).sp,
        lineHeight = (20f * scale).sp,
        letterSpacing = 0.1.sp
    ),
    labelMedium = TextStyle(
        fontFamily = OutfitFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = (12f * scale).sp,
        lineHeight = (16f * scale).sp,
        letterSpacing = 0.5.sp
    ),
    labelSmall = TextStyle(
        fontFamily = OutfitFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = (11f * scale).sp,
        lineHeight = (16f * scale).sp,
        letterSpacing = 0.5.sp
    )
)

val MaterialTypography = materialTypography(1f)

val LocalWaexTypography = staticCompositionLocalOf { WaexTypography() }
