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

/** Shared Material 3 Expressive shell for every Compose bottom sheet in WAEX. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WaexModalBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(),
    shape: Shape = WaexTheme.radius.bottomSheetShape,
    containerColor: Color = WaexTheme.colors.surfaceContainerLow,
    contentColor: Color = WaexTheme.colors.onSurface,
    tonalElevation: Dp = WaexTheme.elevation.modal,
    scrimColor: Color = BottomSheetDefaults.ScrimColor,
    @Suppress("UNUSED_PARAMETER")
    dragHandle: @Composable (() -> Unit)? = null,
    contentWindowInsets: @Composable () -> WindowInsets = { BottomSheetDefaults.windowInsets },
    content: @Composable ColumnScope.() -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        sheetState = sheetState,
        shape = shape,
        containerColor = containerColor,
        contentColor = contentColor,
        tonalElevation = tonalElevation,
        scrimColor = scrimColor,
        dragHandle = { WaexSheetHandle() },
        contentWindowInsets = contentWindowInsets,
        content = content
    )
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
