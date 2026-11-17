package com.waenhancer.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.waenhancer.ui.designsystem.WaexTheme
import com.waenhancer.ui.glass.LocalWaexLiquidGlass
import com.waenhancer.ui.glass.waexGlass
import android.os.Build
import android.view.WindowManager
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider

/** Shared Material 3 Expressive shell for every Compose bottom sheet in WAEX. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WaexModalBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(),
    shape: Shape = WaexTheme.radius.bottomSheetShape,
    containerColor: Color = Color.Unspecified,
    contentColor: Color = WaexTheme.colors.onSurface,
    tonalElevation: Dp = WaexTheme.elevation.modal,
    scrimColor: Color = BottomSheetDefaults.ScrimColor,
    @Suppress("UNUSED_PARAMETER")
    dragHandle: @Composable (() -> Unit)? = null,
    contentWindowInsets: @Composable () -> WindowInsets = { BottomSheetDefaults.windowInsets },
    content: @Composable ColumnScope.() -> Unit
) {
    val glass = LocalWaexLiquidGlass.current
    // Sheets live in their own window, so they cannot sample the in-app backdrop. Instead the glass can only draw a translucent surface plus the window's blur-behind (API 31+).
    // The in-app backdrop is not shared with this window.
    val resolvedContainer = when {
        containerColor != Color.Unspecified -> containerColor
        glass -> MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.78f)
        else -> WaexTheme.colors.surfaceContainerLow
    }
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier.then(if (glass) Modifier.waexGlass(shape, Color.Transparent, rimAlpha = 0.6f) else Modifier),
        sheetState = sheetState,
        shape = shape,
        containerColor = resolvedContainer,
        contentColor = contentColor,
        tonalElevation = tonalElevation,
        scrimColor = scrimColor,
        dragHandle = { WaexSheetHandle() },
        contentWindowInsets = contentWindowInsets,
        content = {
            if (glass) WindowBlurBehind()
            content()
        }
    )
}

@Composable
private fun WindowBlurBehind() {
    val view = LocalView.current
    SideEffect {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val window = (view.parent as? DialogWindowProvider)?.window ?: return@SideEffect
            window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            window.attributes = window.attributes.also { it.blurBehindRadius = 24 }
        }
    }
}

@Composable
private fun WaexSheetHandle() {
    Box(
        modifier = Modifier
            .padding(top = 12.dp, bottom = 10.dp)
            .size(width = 48.dp, height = 5.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(WaexTheme.colors.outline.copy(alpha = 0.45f))
    )
}
