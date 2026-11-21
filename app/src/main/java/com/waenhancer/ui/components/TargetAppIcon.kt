package com.waenhancer.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.waenhancer.ui.designsystem.WaexTheme
import com.waenhancer.utils.WhatsAppPackageDetector

/**
 * The real launcher icon of an installed target app. When the app is not installed (or its icon
 * cannot be read) [fallback] is shown instead, so callers keep their previous placeholder.
 */
@Composable
fun TargetAppIcon(
    packageName: String,
    modifier: Modifier = Modifier,
    size: Dp = 36.dp,
    fallback: @Composable () -> Unit = {}
) {
    val context = LocalContext.current
    val icon = remember(packageName) { WhatsAppPackageDetector.getAppIcon(context, packageName) }
    if (icon != null) {
        Image(
            bitmap = icon,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = modifier
                .size(size)
                .clip(WaexTheme.radius.smShape)
        )
    } else {
        fallback()
    }
}
