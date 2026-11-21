package com.waenhancer.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.waenhancer.ui.designsystem.WaexTheme

/** The green every sheet uses for its main action and for positive states. */
@Composable
fun waexSheetAccent(): Color = if (WaexTheme.colors.isLight) Color(0xFF1DAA61) else Color(0xFF21C063)

/** Text and icons on the accent, matching the primary sheet button. */
@Composable
fun waexSheetOnAccent(): Color = if (WaexTheme.colors.isLight) Color.White else Color(0xFF0A1014)

/** Horizontal content margin shared by every bottom sheet. */
val WaexSheetHorizontalMargin = 22.dp

private val SheetShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
private val SheetHandleColor = Color(0xFF8D9599)

/**
 * Shared bottom sheet shell for every Compose sheet in WAEX.
 *
 * Fill, scrim, 28 dp top-only corners, handle and top offset are fixed here on purpose so
 * individual sheets cannot drift from the design.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WaexModalBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(),
    contentWindowInsets: @Composable () -> WindowInsets = { BottomSheetDefaults.windowInsets },
    content: @Composable ColumnScope.() -> Unit
) {
    val colors = WaexTheme.colors
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        // The status bar stays visible above the sheet.
        modifier = modifier.statusBarsPadding(),
        sheetState = sheetState,
        shape = SheetShape,
        // Light: white. Dark: the #12181C menu-card token.
        containerColor = if (colors.isLight) Color(0xFFFFFFFF) else Color(0xFF12181C),
        contentColor = colors.onSurface,
        tonalElevation = 0.dp,
        scrimColor = Color.Black.copy(alpha = if (colors.isLight) 0.32f else 0.30f),
        dragHandle = { WaexSheetHandle() },
        contentWindowInsets = contentWindowInsets,
        content = content
    )
}

/**
 * The one title style for every bottom sheet: `headlineMd`, bold. Use this instead of styling a
 * sheet heading by hand so sheet titles cannot drift in size.
 */
@Composable
fun WaexSheetTitle(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = WaexTheme.colors.onSurface
) {
    Text(
        text = text,
        modifier = modifier,
        style = WaexTheme.typography.headlineMd,
        fontWeight = FontWeight.Bold,
        color = color
    )
}

/** Full-width pill action: 40 dp tall, bold label, no outline. */
@Composable
fun WaexSheetPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    containerColor: Color = if (WaexTheme.colors.isLight) Color(0xFF1DAA61) else Color(0xFF21C063),
    contentColor: Color = if (WaexTheme.colors.isLight) Color.White else Color(0xFF0A1014),
    content: (@Composable () -> Unit)? = null
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().height(40.dp),
        shape = RoundedCornerShape(20.dp),
        elevation = null,
        border = null,
        colors = ButtonDefaults.buttonColors(
            containerColor = containerColor,
            contentColor = contentColor
        )
    ) {
        if (content != null) content() else Text(text, fontWeight = FontWeight.Bold)
    }
}

/** Borderless secondary action, centered under the primary button. */
@Composable
fun WaexSheetTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    color: Color = if (WaexTheme.colors.isLight) Color(0xFF1B8755) else Color(0xFF21C063)
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().height(40.dp),
        colors = ButtonDefaults.textButtonColors(contentColor = color)
    ) {
        Text(text, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun WaexSheetHandle() {
    Box(
        modifier = Modifier
            .padding(top = 10.dp, bottom = 10.dp)
            .size(width = 32.dp, height = 4.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(SheetHandleColor)
    )
}
