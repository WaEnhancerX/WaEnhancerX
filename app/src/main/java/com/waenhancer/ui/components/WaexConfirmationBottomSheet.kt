package com.waenhancer.ui.components

import com.waenhancer.app.R
import com.waenhancer.utils.UiText

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.waenhancer.ui.designsystem.WaexTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WaexConfirmationBottomSheet(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
    confirmEnabled: Boolean = true,
    confirmContent: (@Composable () -> Unit)? = null,
    supportingContent: (@Composable () -> Unit)? = null
) {
    val colors = WaexTheme.colors
    val typography = WaexTheme.typography
    val radius = WaexTheme.radius
    val actionColor = if (destructive) colors.error else colors.primary
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    WaexModalBottomSheet(
        onDismissRequest = { if (confirmEnabled) onDismiss() },
        sheetState = sheetState,
        containerColor = colors.surface,
        shape = radius.bottomSheetShape
    ) {
        val sheetDensity = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides sheetDensity) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .imePadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(title, style = typography.headlineMd, fontWeight = FontWeight.Bold, color = colors.onSurface)
                Text(message, style = typography.bodyMd, color = colors.onSurfaceVariant)
                supportingContent?.invoke()
                Spacer(Modifier.height(4.dp))
                Button(
                    onClick = onConfirm,
                    enabled = confirmEnabled,
                    modifier = Modifier.fillMaxWidth().height(42.dp),
                    shape = radius.buttonShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = actionColor,
                        contentColor = if (destructive) Color.White else colors.onPrimary
                    )
                ) {
                    if (confirmContent != null) confirmContent() else Text(confirmLabel, fontWeight = FontWeight.Bold)
                }
                OutlinedButton(
                    onClick = onDismiss,
                    enabled = confirmEnabled,
                    modifier = Modifier.fillMaxWidth().height(42.dp),
                    shape = radius.buttonShape,
                    border = BorderStroke(1.dp, colors.outlineVariant)
                ) {
                    Text(UiText.get(R.string.action_cancel, "Cancel"), color = colors.onSurface)
                }
            }
        }
    }
}
