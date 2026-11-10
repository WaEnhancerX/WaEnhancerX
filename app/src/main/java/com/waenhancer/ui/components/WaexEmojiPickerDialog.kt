package com.waenhancer.ui.components

import com.waenhancer.app.R
import com.waenhancer.utils.UiText

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.waenhancer.ui.designsystem.WaexTheme
import com.waenhancer.utils.EmojiInputPolicy

private val PRESET_EMOJIS = listOf(
    "👍", "❤️", "😂", "😮", "😢", "🙏",
    "🔥", "👏", "🎉", "💯", "😍", "🤩",
    "🤔", "🥳", "✨", "🤝", "🚀", "👌"
)

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun WaexEmojiPickerDialog(
    initialEmoji: String,
    onEmojiSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val colors = WaexTheme.colors
    val typography = WaexTheme.typography
    val spacing = WaexTheme.spacing
    val radius = WaexTheme.radius
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var emojiInput by remember(initialEmoji) {
        mutableStateOf(EmojiInputPolicy.singleEmojiOrDefault(initialEmoji))
    }
    val hasValidSingleEmoji = EmojiInputPolicy.isSingleEmoji(emojiInput.trim())

    WaexModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.surface,
        shape = radius.bottomSheetShape
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
        ) {
                // Header
                Text(
                    text = UiText.get(R.string.feat_doubletap2like_emoji_title, "Custom Reaction Emoji"),
                    style = typography.titleMd,
                    color = colors.onSurface,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    text = UiText.get(R.string.waex_ui_select_or_type_the_default_emoji_sent, "Select or type the default emoji sent when double-tapping a message."),
                    style = typography.bodySm,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 16.dp)
                )

                // Quick Presets
                Text(
                    text = UiText.get(R.string.waex_ui_popular_reactions, "Popular Reactions"),
                    style = typography.labelSm,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    PRESET_EMOJIS.forEach { emoji ->
                        val isSelected = emojiInput == emoji
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(
                                    if (isSelected) colors.primary.copy(alpha = 0.2f)
                                    else colors.surfaceContainerHighest
                                )
                        .clickable { emojiInput = emoji },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = emoji,
                                fontSize = 22.sp,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Custom Input Field
                Text(
                    text = UiText.get(R.string.waex_ui_or_enter_custom_emoji, "Or enter custom emoji"),
                    style = typography.labelSm,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 6.dp)
                )

                OutlinedTextField(
                    value = emojiInput,
                    onValueChange = { emojiInput = it },
                    singleLine = true,
                    isError = emojiInput.isNotEmpty() && !hasValidSingleEmoji,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = colors.primary,
                        unfocusedBorderColor = colors.outlineVariant,
                        focusedTextColor = colors.onSurface,
                        unfocusedTextColor = colors.onSurface
                    ),
                    placeholder = {
                        Text(UiText.get(R.string.waex_ui_e_g, "e.g. 👍"), color = colors.onSurfaceVariant)
                    }
                )

                Spacer(modifier = Modifier.height(20.dp))

                // Actions
                Button(
                    onClick = {
                        onEmojiSelected(emojiInput.trim())
                        onDismiss()
                    },
                    enabled = hasValidSingleEmoji,
                    colors = ButtonDefaults.buttonColors(containerColor = colors.primary),
                    shape = radius.buttonShape,
                    modifier = Modifier.fillMaxWidth().height(50.dp)
                ) {
                    Text(UiText.get(R.string.action_save, "Save"), color = colors.onPrimary, fontWeight = FontWeight.Bold)
                }
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedButton(
                    onClick = onDismiss,
                    shape = radius.buttonShape,
                    modifier = Modifier.fillMaxWidth().height(50.dp)
                ) {
                    Text(UiText.get(R.string.action_cancel, "Cancel"), color = colors.onSurface)
                }
        }
    }
}
