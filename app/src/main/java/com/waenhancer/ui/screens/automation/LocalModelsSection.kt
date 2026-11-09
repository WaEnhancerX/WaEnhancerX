package com.waenhancer.ui.screens.automation

import com.waenhancer.app.R
import com.waenhancer.utils.UiText

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.WavyProgressIndicatorDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.waenhancer.core.transcription.local.LocalModelStore
import com.waenhancer.core.transcription.local.LocalModels
import com.waenhancer.core.transcription.local.WhisperNative
import com.waenhancer.ui.components.WaexConfirmationBottomSheet
import com.waenhancer.ui.designsystem.WaexTheme
import com.waenhancer.ui.navigation.LocalWaexPreferenceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.sin

/**
 * Hallmark · redesign: full · scope: component · genre: editorial · theme: WAEX brand
 * On-device whisper models with expressive card layouts, icon action buttons,
 * download progress indicators, and device capability masthead.
 */
@Composable
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
fun LocalModelsSection(language: String = "") {
    val context = LocalContext.current
    val preferenceManager = LocalWaexPreferenceManager.current
    val colors = WaexTheme.colors
    val typography = WaexTheme.typography
    val radius = WaexTheme.radius
    val scope = rememberCoroutineScope()

    var supported by remember { mutableStateOf<Boolean?>(null) }
    var chosen by remember { mutableStateOf(preferenceManager.getString("local_model", "")) }
    val currentLang = language.ifEmpty { preferenceManager.getString("transcription_language", "") }
    var tick by remember { mutableIntStateOf(0) }
    val totalRam = remember { LocalModelStore.totalRam(context) }
    val ramGb = remember { Math.ceil(totalRam / (1024.0 * 1024 * 1024)) }
    val cores = remember { Runtime.getRuntime().availableProcessors() }
    val suggestion = remember(currentLang) { LocalModels.recommend(totalRam, cores, currentLang) }
    // Nothing chosen yet means the suggested model is the one in use.
    val effectiveId = LocalModels.byId(chosen)?.id ?: suggestion.model.id
    val ordered = remember(suggestion) {
        listOf(suggestion.model) + LocalModels.ALL.filter { it.id != suggestion.model.id }
    }

    // Selection is only allowed if the model is downloaded on device.
    fun choose(model: LocalModels.Model) {
        if (!LocalModelStore.isDownloaded(context, model)) return
        chosen = model.id
        preferenceManager.putString("local_model", model.id)
    }

    // Auto-select the first downloaded model if current chosen/suggested isn't downloaded
    LaunchedEffect(tick) {
        val currentValid = LocalModels.byId(chosen)?.let { LocalModelStore.isDownloaded(context, it) } == true
        if (!currentValid) {
            val firstDownloaded = LocalModels.ALL.firstOrNull { LocalModelStore.isDownloaded(context, it) }
            if (firstDownloaded != null && chosen != firstDownloaded.id) {
                chosen = firstDownloaded.id
                preferenceManager.putString("local_model", firstDownloaded.id)
            }
        }
    }

    LaunchedEffect(Unit) {
        supported = withContext(Dispatchers.IO) { WhisperNative.isSupported() }
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(500)
            tick++
        }
    }
    @Suppress("UNUSED_VARIABLE") val refresh = tick

    var modelToDelete by remember { mutableStateOf<LocalModels.Model?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        // Hardware Device Context Chip / Banner
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = colors.surfaceContainerLow,
            border = androidx.compose.foundation.BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.5f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = UiText.get(R.string.waex_ui_on_device_models, "On-device models"),
                        style = typography.bodyMd,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.onSurface
                    )
                    Text(
                        text = "${ramGb.toInt()} GB RAM · $cores Cores" + (WhisperNative.backendName()?.let { " · $it" } ?: ""),
                        style = typography.labelSm,
                        color = colors.onSurfaceVariant
                    )
                }
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = colors.primary.copy(alpha = 0.12f)
                ) {
                    Text(
                        text = "Offline",
                        style = typography.labelSm,
                        fontWeight = FontWeight.Bold,
                        color = colors.primary,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
        }

        if (supported == false) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = colors.error.copy(alpha = 0.1f),
                border = androidx.compose.foundation.BorderStroke(1.dp, colors.error.copy(alpha = 0.3f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = UiText.get(R.string.waex_ui_on_device_transcription_isn_t_availabl,
                        "On-device transcription isn't available on this phone") +
                            ": " + (WhisperNative.unsupportedReason() ?: ""),
                    style = typography.bodySm,
                    color = colors.error,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }

        // Section header for models
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "Available Models",
                style = typography.titleMd,
                fontWeight = FontWeight.SemiBold,
                color = colors.onSurface
            )
            Text(
                text = "Download required to select",
                style = typography.labelSm,
                color = colors.onSurfaceVariant
            )
        }

        // Model List Items
        ordered.forEach { model ->
            val downloaded = LocalModelStore.isDownloaded(context, model)
            val progress = LocalModelStore.progress(model.id)
            val isDownloading = progress?.running == true
            val isChosen = model.id == effectiveId && downloaded
            val isSuggested = model.id == suggestion.model.id
            val tooHeavy = ramGb < model.minRamGb

            val borderColor by animateColorAsState(
                targetValue = when {
                    isChosen -> colors.primary
                    isDownloading -> colors.primary.copy(alpha = 0.6f)
                    else -> colors.outlineVariant.copy(alpha = 0.4f)
                },
                animationSpec = tween(200),
                label = "borderColor"
            )
            val containerBg by animateColorAsState(
                targetValue = when {
                    isChosen -> colors.primary.copy(alpha = 0.08f)
                    isDownloading -> colors.primary.copy(alpha = 0.03f)
                    !downloaded -> colors.surfaceContainerLow.copy(alpha = 0.65f)
                    else -> colors.surfaceContainerLow
                },
                animationSpec = tween(200),
                label = "containerBg"
            )

            Box(modifier = Modifier.fillMaxWidth()) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = containerBg,
                    border = androidx.compose.foundation.BorderStroke(if (isChosen) 1.5.dp else 1.dp, borderColor),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .clickable(enabled = downloaded) { choose(model) }
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Radio button: ONLY enabled if model is downloaded
                            RadioButton(
                                selected = isChosen,
                                onClick = { choose(model) },
                                enabled = downloaded,
                                colors = RadioButtonDefaults.colors(
                                    selectedColor = colors.primary,
                                    unselectedColor = colors.outline.copy(alpha = if (downloaded) 0.8f else 0.3f),
                                    disabledSelectedColor = colors.primary.copy(alpha = 0.38f),
                                    disabledUnselectedColor = colors.outline.copy(alpha = 0.25f)
                                ),
                                modifier = Modifier.size(24.dp)
                            )

                            Spacer(modifier = Modifier.width(10.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = model.label,
                                        style = typography.bodyMd,
                                        fontWeight = FontWeight.Bold,
                                        color = if (downloaded) colors.onSurface else colors.onSurface.copy(alpha = 0.72f)
                                    )
                                    Surface(
                                        shape = RoundedCornerShape(4.dp),
                                        color = colors.outlineVariant.copy(alpha = 0.3f)
                                    ) {
                                        Text(
                                            text = model.sizeLabel(),
                                            style = typography.labelSm,
                                            color = colors.onSurfaceVariant,
                                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                        )
                                    }
                                }

                                Text(
                                    text = hint(model),
                                    style = typography.bodySm,
                                    color = colors.onSurfaceVariant
                                )

                                if (tooHeavy) {
                                    Text(
                                        text = UiText.get(R.string.waex_ui_may_be_too_heavy_for_this_phone,
                                            "May be too heavy for this phone"),
                                        style = typography.labelSm,
                                        color = colors.error
                                    )
                                }
                                progress?.error?.let {
                                    Text(text = it, style = typography.labelSm, color = colors.error)
                                }
                            }

                            // Action Button (Download / Cancel / Delete)
                            when {
                                isDownloading -> {
                                    IconButton(
                                        onClick = { LocalModelStore.cancel(model.id) },
                                        modifier = Modifier
                                            .size(30.dp)
                                            .clip(CircleShape)
                                            .background(colors.surfaceContainerHigh)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.Close,
                                            contentDescription = UiText.get(R.string.waex_ui_cancel, "Cancel"),
                                            tint = colors.onSurface,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                                !downloaded -> {
                                    IconButton(
                                        onClick = {
                                            LocalModelStore.download(context, model)
                                        },
                                        modifier = Modifier
                                            .size(30.dp)
                                            .clip(CircleShape)
                                            .background(colors.primaryContainer)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.FileDownload,
                                            contentDescription = UiText.get(R.string.waex_ui_download, "Download"),
                                            tint = colors.primary,
                                            modifier = Modifier.size(17.dp)
                                        )
                                    }
                                }
                                else -> {
                                    // Downloaded: no checkmark icon, just the delete button
                                    IconButton(
                                        onClick = { modelToDelete = model },
                                        modifier = Modifier.size(30.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.Delete,
                                            contentDescription = UiText.get(R.string.waex_ui_delete, "Delete"),
                                            tint = colors.error.copy(alpha = 0.8f),
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        }

                        // Official Material 3 Expressive wavy download progress
                        if (isDownloading && progress != null) {
                            val fraction = if (progress.totalBytes > 0)
                                (progress.downloadedBytes.toFloat() / progress.totalBytes).coerceIn(0f, 1f) else 0f
                            val animatedFraction by animateFloatAsState(
                                targetValue = fraction,
                                animationSpec = ProgressIndicatorDefaults.ProgressAnimationSpec,
                                label = "model_download_progress"
                            )
                            LinearWavyProgressIndicator(
                                progress = { animatedFraction },
                                color = colors.primary,
                                trackColor = colors.surfaceContainerHighest,
                                wavelength = 24.dp,
                                waveSpeed = 32.dp,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 4.dp, bottom = 2.dp)
                            )
                        }
                    }
                }

                // Floating badge on top right of suggested model
                if (isSuggested) {
                    Surface(
                        shape = RoundedCornerShape(bottomStart = 8.dp, topEnd = 16.dp),
                        color = colors.primary,
                        shadowElevation = 2.dp,
                        modifier = Modifier.align(Alignment.TopEnd)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Star,
                                contentDescription = null,
                                tint = colors.onPrimary,
                                modifier = Modifier.size(10.dp)
                            )
                            Text(
                                text = UiText.get(R.string.waex_ui_suggested, "Recommended"),
                                style = typography.labelSm,
                                fontWeight = FontWeight.Bold,
                                color = colors.onPrimary
                            )
                        }
                    }
                }
            }
        }
    }

    // Delete Confirmation Dialog
    modelToDelete?.let { model ->
        WaexConfirmationBottomSheet(
            title = UiText.get(R.string.waex_ui_delete, "Delete"),
            message = "Are you sure you want to delete the ${model.label} model (${model.sizeLabel()})? You can download it again anytime.",
            confirmLabel = UiText.get(R.string.waex_ui_delete, "Delete"),
            destructive = true,
            onDismiss = { modelToDelete = null },
            onConfirm = {
                val target = model
                modelToDelete = null
                scope.launch {
                    withContext(Dispatchers.IO) {
                        LocalModelStore.delete(context, target)
                    }
                }
            }
        )
    }
}


/** One line about speed and accuracy for each model size. */
private fun hint(model: LocalModels.Model): String {
    val tier = when {
        model.id.startsWith("tiny") -> UiText.get(R.string.waex_ui_fastest_lowest_accuracy, "Fastest, lowest accuracy")
        model.id.startsWith("base") -> UiText.get(R.string.waex_ui_fast_fair_accuracy, "Fast, fair accuracy")
        model.id.startsWith("small") -> UiText.get(R.string.waex_ui_balanced_speed_and_accuracy, "Balanced speed and accuracy")
        else -> UiText.get(R.string.waex_ui_most_accurate_slow, "Most accurate, slow")
    }
    return if (model.englishOnly) {
        tier + " · " + UiText.get(R.string.waex_ui_english_only_more_accurate_for_english,
            "English only: more accurate for English")
    } else tier
}
