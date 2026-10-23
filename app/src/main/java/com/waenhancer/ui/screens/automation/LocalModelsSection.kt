package com.waenhancer.ui.screens.automation

import com.waenhancer.app.R
import com.waenhancer.utils.UiText

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.waenhancer.core.transcription.local.LocalModelStore
import com.waenhancer.core.transcription.local.LocalModels
import com.waenhancer.core.transcription.local.WhisperNative
import com.waenhancer.ui.designsystem.WaexTheme
import com.waenhancer.ui.navigation.LocalWaexPreferenceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * On-device whisper models. The suggested model for this phone and language is listed first;
 * tapping any model chooses it (like a radio button), and a chosen model that is not downloaded
 * yet is used as soon as its download finishes. Downloads continue if the screen is left.
 */
@Composable
fun LocalModelsSection(language: String) {
    val context = LocalContext.current
    val preferenceManager = LocalWaexPreferenceManager.current
    val colors = WaexTheme.colors
    val typography = WaexTheme.typography
    val radius = WaexTheme.radius
    val scope = rememberCoroutineScope()

    var supported by remember { mutableStateOf<Boolean?>(null) }
    var chosen by remember { mutableStateOf(preferenceManager.getString("local_model", "")) }
    var tick by remember { mutableIntStateOf(0) }
    val totalRam = remember { LocalModelStore.totalRam(context) }
    val ramGb = remember { Math.ceil(totalRam / (1024.0 * 1024 * 1024)) }
    val cores = remember { Runtime.getRuntime().availableProcessors() }
    val suggestion = remember(language) { LocalModels.recommend(totalRam, cores, language) }
    // Nothing chosen yet means the suggested model is the one in use.
    val effectiveId = LocalModels.byId(chosen)?.id ?: suggestion.model.id
    val ordered = remember(suggestion) {
        listOf(suggestion.model) + LocalModels.ALL.filter { it.id != suggestion.model.id }
    }

    fun choose(model: LocalModels.Model) {
        chosen = model.id
        preferenceManager.putString("local_model", model.id)
    }

    // Checking CPU support loads the native library: do it off the UI thread.
    LaunchedEffect(Unit) {
        supported = withContext(Dispatchers.IO) { WhisperNative.isSupported() }
    }
    // Download progress lives outside Compose; refresh twice a second while visible.
    LaunchedEffect(Unit) {
        while (true) {
            delay(500)
            tick++
        }
    }
    @Suppress("UNUSED_VARIABLE") val refresh = tick // Re-read download state on every tick.

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = UiText.get(R.string.waex_ui_on_device_models, "On-device models"),
            style = typography.labelSm,
            color = colors.onSurfaceVariant
        )
        Text(
            text = UiText.get(R.string.waex_ui_runs_on_your_phone_nothing_is_uploaded,
                "Runs on your phone, nothing is uploaded. Larger models are more accurate but slower."),
            style = typography.labelSm,
            color = colors.onSurfaceVariant
        )
        if (supported == false) {
            Text(
                text = UiText.get(R.string.waex_ui_on_device_transcription_isn_t_availabl,
                    "On-device transcription isn't available on this phone") +
                        ": " + (WhisperNative.unsupportedReason() ?: ""),
                style = typography.bodyMd,
                color = colors.error
            )
        }
        Text(
            text = UiText.get(R.string.waex_ui_this_phone, "This phone") + ": " +
                    ramGb.toInt() + " GB RAM · " + cores + " cores" +
                    (WhisperNative.backendName()?.let { " · $it" } ?: ""),
            style = typography.bodyMd,
            color = colors.onSurface
        )

        // One-tap path for people who do not want to compare models.
        val suggestedProgress = LocalModelStore.progress(suggestion.model.id)
        if (!LocalModelStore.isDownloaded(context, suggestion.model) && suggestedProgress?.running != true) {
            Button(
                onClick = {
                    choose(suggestion.model)
                    LocalModelStore.download(context, suggestion.model)
                },
                shape = radius.mdShape,
                colors = ButtonDefaults.buttonColors(containerColor = colors.primary, contentColor = colors.onPrimary),
                modifier = Modifier.fillMaxWidth().height(44.dp)
            ) {
                Text(
                    UiText.get(R.string.waex_ui_download_suggested_model, "Download suggested model") +
                            " · " + suggestion.model.label + " (" + suggestion.model.sizeLabel() + ")"
                )
            }
        }
        Text(
            text = UiText.get(R.string.waex_ui_tap_a_model_to_choose_it_the_suggested,
                "Tap a model to choose it. The suggested one is used until you pick another."),
            style = typography.labelSm,
            color = colors.onSurfaceVariant
        )

        ordered.forEach { model ->
            val downloaded = LocalModelStore.isDownloaded(context, model)
            val progress = LocalModelStore.progress(model.id)
            val isChosen = model.id == effectiveId
            val isSuggested = model.id == suggestion.model.id
            val tooHeavy = ramGb < model.minRamGb
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        if (isChosen) 2.dp else 1.dp,
                        if (isChosen) colors.primary else colors.outlineVariant,
                        RoundedCornerShape(12.dp)
                    )
                    .clickable { choose(model) }
                    .padding(start = 4.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = isChosen, onClick = { choose(model) })
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = (if (isSuggested) "★ " else "") + model.label + " · " + model.sizeLabel(),
                            style = typography.bodyMd,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.onSurface
                        )
                        Text(text = hint(model), style = typography.labelSm, color = colors.onSurfaceVariant)
                        val tags = buildList {
                            if (isSuggested) {
                                add(UiText.get(R.string.waex_ui_suggested, "Suggested") + ": " +
                                        suggestion.reason.substringAfter(": "))
                            }
                            if (isChosen && downloaded) add(UiText.get(R.string.waex_ui_in_use, "In use"))
                        }
                        if (tags.isNotEmpty()) {
                            Text(text = tags.joinToString(" · "), style = typography.labelSm, color = colors.primary)
                        }
                        if (isChosen && !downloaded && progress?.running != true) {
                            Text(
                                text = UiText.get(R.string.waex_ui_selected_download_it_to_start_using_it,
                                    "Selected: download it to start using it."),
                                style = typography.labelSm,
                                color = colors.primary
                            )
                        }
                        if (tooHeavy) {
                            Text(
                                text = UiText.get(R.string.waex_ui_may_be_too_heavy_for_this_phone,
                                    "May be too heavy for this phone"),
                                style = typography.labelSm,
                                color = colors.error
                            )
                        }
                        progress?.error?.let { Text(text = it, style = typography.labelSm, color = colors.error) }
                    }
                    when {
                        progress?.running == true -> TextButton(onClick = { LocalModelStore.cancel(model.id) }) {
                            Text(UiText.get(R.string.waex_ui_cancel, "Cancel"))
                        }
                        !downloaded -> TextButton(onClick = {
                            choose(model)
                            LocalModelStore.download(context, model)
                        }) {
                            Text(UiText.get(R.string.waex_ui_download, "Download"))
                        }
                        else -> TextButton(onClick = {
                            // Deleting waits for a running transcription to finish: not on the UI thread.
                            scope.launch { withContext(Dispatchers.IO) { LocalModelStore.delete(context, model) } }
                        }) {
                            Text(UiText.get(R.string.waex_ui_delete, "Delete"), color = colors.error)
                        }
                    }
                }
                if (progress?.running == true) {
                    val fraction = if (progress.totalBytes > 0)
                        progress.downloadedBytes.toFloat() / progress.totalBytes else 0f
                    LinearProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
                    )
                    Text(
                        text = "${(fraction * 100).toInt()} %",
                        style = typography.labelSm,
                        color = colors.onSurfaceVariant,
                        modifier = Modifier.padding(start = 12.dp)
                    )
                }
            }
        }
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
